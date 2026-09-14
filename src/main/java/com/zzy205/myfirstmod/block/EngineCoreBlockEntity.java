package com.zzy205.myfirstmod.block;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.fluids.tank.SoundPool;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.compat.cc.SensorSystemAPI;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.nbt.NBTHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发动机核心方块实体：Create 动力源骨架 + 多方块组网（P0）+ 燃烧发电（P1：流体燃烧室；P2：蒸汽动力室）。
 * <p>
 * 组网机制参考 CDG {@code ModularDieselEngineBlockEntity}（方案见 memo/engine-module.md）：
 * <ul>
 *   <li>每个核心 BE 记录 {@link #controller}（controller 坐标，自己为 controller 时 null）与
 *       {@link #length}（整条总节数，每个成员存同一值）；</li>
 *   <li>只有 controller 参与发电（{@link #getGeneratedSpeed()} 非 controller 恒 0），
 *       整条核心经 AXIS 贯通传动杆耦合成一个应力网络；</li>
 *   <li>放置/拆除/读档分别触发 {@code ConnectivityHandler.formMulti / splitMulti} 与
 *       {@code Uninitialized} 重组（触发点见 {@link EngineCoreBlock}）；</li>
 *   <li>存档：非 controller 写 {@code Controller}；所有成员写 {@code LastKnownPos}；
 *       controller 写 {@code Height}（= length）；{@code Uninitialized} 标志驱动读档后重新组网。</li>
 * </ul>
 * 燃烧发电（仅 controller tick，零流体缓存）：
 * <ul>
 *   <li>流体燃烧室：每室消耗燃料表流体的 consumption mb/s（fuelDebt 累加器 → 从源罐 drain 1mb）；
 *       容量 = 室数 × 4096 × stress 倍率；</li>
 *   <li>蒸汽动力室：水（1mb/s/室，从源罐 drain）+ 燃料（burnTick 制，1 tick 烧 1 个 burnTick，等价熔炉速率）——
 *       固体燃料从室邻居容器抽取（+物品 burnTime），流体燃料按 burn_ticks_per_bucket 从源罐抽；
 *       水不可用时暂停（不烧）；</li>
 *   <li>定距桨单杆模型（P4 定稿）：转速 = 油门 × {@link #GENERATED_SPEED}（0~256 线性，100% 油门 = 256rpm/满应力）；
 *       总容量 = 两类运行室贡献之和 × 油门，与转速同比例缩放（过载比例不随油门变）。</li>
 * </ul>
 * 参考来源：CDG {@code ModularDieselEngineBlockEntity}；Create {@code ConnectivityHandler} / {@code IMultiBlockEntityContainer}。
 */
public class EngineCoreBlockEntity extends GeneratingKineticBlockEntity implements IMultiBlockEntityContainer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 调试日志开关：定位"引擎没反应"问题时置 true，定位后改回 false */
    public static boolean DEBUG = true;

    /** 最大发电转速（RPM，油门 100% 时输出）；转速 = 油门 × GENERATED_SPEED（0~256 线性，定距桨模型） */
    public static final float GENERATED_SPEED = 256;

    /** 每个流体燃烧室的基础应力贡献（SU） */
    public static final float BASE_STRESS_PER_CHAMBER = 4096;

    /** 每个蒸汽动力室的基础应力贡献（SU） */
    public static final float STEAM_STRESS_PER_CHAMBER = 3072;

    /** 整条引擎的总节数（每个成员都存同一值，由 ConnectivityHandler.setHeight 维护） */
    protected int length = 1;
    /** controller 坐标；自己是 controller 时为 null */
    protected BlockPos controller;
    /** 存档时记录自身位置，供 chunk 重载后找回 controller */
    protected BlockPos lastKnownPos;
    /** 需要重新组网（放置 / 读档 Uninitialized / 拆解） */
    protected boolean updateConnectivity = false;

    // ---- P1：燃烧/发电状态（仅 controller 计算；Running 同步给客户端驱动音效/动画） ----
    /** 是否运行（有燃烧室 + 燃料可用 + 未过载） */
    protected boolean running = false;
    /** 模块总容量（SU，当前转速下）＝运行燃烧室数 × 4096 × 燃料 stress 倍率 */
    protected float moduleCapacity = 0;
    /** 燃料消耗累加器（0.05mb/t = 1mb/s，≥1 才从源罐 drain 1mb，零缓存） */
    protected float fuelDebt = 0f;
    /** 当前燃料流体（缓存，供 drain 使用） */
    protected FluidStack fuelFluid = FluidStack.EMPTY;
    /** 当前燃料源罐位置（缓存，失效重扫） */
    protected BlockPos fuelSourcePos;
    /** 空转重扫冷却（无燃料时避免每 tick 全量扫描源罐） */
    protected int fuelRescanCooldown = 0;

    // ---- P2：蒸汽室状态（仅 controller 计算；水/蒸汽流体燃料零缓存） ----
    /** 水源罐位置（缓存，失效重扫；蒸汽室共享） */
    protected BlockPos waterSourcePos;
    /** 水源重扫冷却 */
    protected int waterRescanCooldown = 0;
    /** 水消耗累加器（每蒸汽室 1mb/s = 0.05mb/t，≥1 才 drain 1mb） */
    protected float waterDebt = 0f;
    /** 蒸汽室当前流体燃料条目（缓存） */
    protected EngineFuels.Entry steamFuelEntry;
    /** 蒸汽室当前流体燃料（缓存，供 drain 使用） */
    protected FluidStack steamFuelFluid = FluidStack.EMPTY;
    /** 蒸汽室流体燃料源罐位置（缓存，失效重扫） */
    protected BlockPos steamFuelSourcePos;
    /** 蒸汽室流体燃料重扫冷却 */
    protected int steamFuelRescanCooldown = 0;

    // ---- P3：温度/冷却（牛顿冷却模型，方案见 memo/engine-module.md 关键机制 6） ----
    /** 过热阈值（°C）：T ≥ 此值硬停 */
    public static final float OVERHEAT_TEMP = 200f;
    /** 滞回恢复阈值（°C）：T ≤ 此值才允许重启（0.8×OVERHEAT_TEMP） */
    public static final float OVERHEAT_RESUME = 160f;
    /** 海平面环境温度（°C） */
    public static final float T_AMB_SEA = 20f;
    /** 对流层环境温度递减率（°C/m，按海平面高度） */
    public static final float T_AMB_LAPSE = 0.0065f;
    /** 环境温度下限（°C） */
    public static final float T_AMB_FLOOR = -40f;
    /** 流体室基础热（°C/s，100% 效率下每室） */
    public static final float BASE_HEAT_FLUID = 30f;
    /** 蒸汽室基础热倍率（P3 旧模型：吃水 = 天然冷却，比流体室低 20%）——P6 Plan B 已退役：
     *  蒸汽温度改为「运行中钉在 BOILER_T_OPT」的自调节模型（燃料热量用于产汽=功率而非升温），此倍率不再参与温度计算 */
    public static final float STEAM_HEAT_FACTOR = 0.8f;
    /** 蒸汽锅炉升温速率（1/s）：点火时温度向 BOILER_T_OPT 指数收敛（τ=4s；20→100°C 阈值约 4s，~20s 基本到设计点）。进游戏可调 */
    public static final float STEAM_WARMUP_RATE = 0.25f;
    /** 蒸汽机最低工作温度（°C）：低于此值锅炉压力不足，无法驱动（暖机阶段只烧不发电）；高于才「开始工作」。
     *  真实：饱和蒸汽 <100°C 无蒸汽压力（常压沸点） */
    public static final float STEAM_MIN_WORK_TEMP = 100f;
    /** 环境散热系数（°C/s/°C/室）——静态 eff25% 不过热 ⇒ K_AMB ≥ 0.25×H0/ΔT_max */
    public static final float K_AMBIENT = 0.05f;
    /** 每个整合气道散热系数（°C/s/°C）——静态 eff50% + 1风道/室 ⇒ K_AMB+K_DUCT ≥ 0.5×H0/ΔT_max */
    public static final float K_DUCT = 0.05f;
    /** 引擎核心自身散热系数（°C/s/°C/节）：停机时核心仍缓慢散热（不依赖整合气道），τ ≈ 1/0.02 = 50s */
    public static final float K_CORE = 0.02f;
    /** 冲压冷却满增益（×）：运动 ≥RAM_FULL 时总散热 ×2.0（eff100% + 1风道/室 + 冲压 ⇒ 足够） */
    public static final float RAM_MAX = 2.0f;
    /** 冲压起始速度（m/s）：低于此无增益 */
    public static final float RAM_START = 10f;
    /** 冲压满速（m/s）：达到此速度增益 = RAM_MAX */
    public static final float RAM_FULL = 30f;
    /** 热容基数（°C 变化速率分母；×length：模块越大热得越慢） */
    public static final float C_TH_BASE = 1.0f;
    /** 气压因子下限（防大气顶气压→0 导致冷却归零） */
    public static final float PRESSURE_FLOOR = 0.25f;
    /** 温度同步到客户端的阈值（°C，避免每 tick 发包） */
    public static final float SYNC_TEMP_DELTA = 1f;

    /** 模块温度（°C，controller 持有；NBT 持久化 + 客户端同步显示） */
    protected float temperature = T_AMB_SEA;
    /** 效率（=油门，同时缩出力和热量；P3 默认 0.25，P4 由 Lua 控制） */
    protected float efficiency = 0.25f;
    /** 过热锁定（滞回：T≥OVERHEAT_TEMP 置位，T≤OVERHEAT_RESUME 复位） */
    protected boolean overheated = false;
    /** 上次同步给客户端的温度（差量发包用） */
    protected float lastSyncedTemp = T_AMB_SEA;
    /** 客户端温度显示值（服务端差量采样 → 趋势外推平滑，见 tickClient） */
    @OnlyIn(Dist.CLIENT)
    protected float displayedTemperature = T_AMB_SEA;
    /** 最近两个温度采样点（服务端差量发包到达时滚动，tickClient 沿斜率外推） */
    @OnlyIn(Dist.CLIENT)
    protected float lastTempSample = T_AMB_SEA, newTempSample = T_AMB_SEA;
    @OnlyIn(Dist.CLIENT)
    protected long lastTempSampleTime, newTempSampleTime;

    // ---- P4：Lua 控制（ccpe.engine 外设，挂 controller；方案见 memo/engine-module.md 关键机制 5） ----
    /** Lua 引擎开关（默认 true 保持 P0–P3 行为；false = 整机停摆：不发电、不消耗，温度自然冷却）。NBT 持久化。 */
    protected boolean enabled = true;
    /** 是否有电脑已连接本外设（Peripheral.attach/detach 维护；仅同步客户端供 Goggle 显示，不落盘） */
    protected boolean luaConnected = false;

    /** 客户端：当前 goggle 悬停的引擎方块（核心 / 模块方块的 {@code IProxyHoveringInformation.getInformationSource}
     *  代理到本 controller 时记录——核心记录自身、蒸汽室记录蒸汽室、流体室/气道记录各自方块）。
     *  仅用于 {@link #addToGoggleTooltip} 按悬停方块分流三套 tooltip（核心 / 蒸汽动力室 / 流体燃烧室+整合气道全量）；
     *  每次 tooltip 渲染后复位（防跨帧串味）。 */
    @OnlyIn(Dist.CLIENT)
    private Block hoveredSourceBlock;

    /** 客户端：记录当前 goggle 悬停的模块方块（由模块方块的 getInformationSource 代理调用，调用方需已判 level.isClientSide） */
    void markHoveredModule(Block block) {
        hoveredSourceBlock = block;
    }

    // ---- P5：混合比（经济性/热管理杆 + 高空自动富油；方案见 memo/engine-module.md 关键机制 7）----
    /** 混合比杆范围（setMixture 越界钳制） */
    public static final float MIXTURE_MIN = 0.6f;
    public static final float MIXTURE_MAX = 1.4f;
    /** 自动富油系数（气压自变量）：1 + K×(1 − 气压)，钳制 [1, MIXTURE_ALT_MAX]。
     *  气压 = getPressureForEngine(engineAltitude)（与冷却模型同源同曲线，海平面 1.0 → 高空降，Y=320 为 0）。
     *  起步 K=0.45：Y≈200（云层）≈×1.19，Y≈260 ≈×1.25 达上限（游戏高度就 0~320，不能再按"10km"标定）。 */
    public static final float MIXTURE_PRESSURE_K = 0.45f;
    public static final float MIXTURE_ALT_MAX = 1.25f;
    /** 热因子凸曲线：稀侧 1 + A×(1−m)²（加速惩罚防"永远拉稀"驻点），浓侧 1 − B×(m−1)（平缓收敛，下限 RICH_FLOOR） */
    public static final float MIXTURE_LEAN_K = 2.0f;
    public static final float MIXTURE_RICH_K = 0.5f;
    public static final float MIXTURE_RICH_FLOOR = 0.7f;

    /** 混合比杆（0.6~1.4，默认 1.0；只影响油耗与温度，不影响应力/转速）。NBT 持久化。 */
    protected float mixture = 1.0f;
    /** 服务端每 tick 的实际混合比（杆 × 高空自动富油），NBT 同步给客户端供 Goggle 显示——客户端无法可靠获得运动体真实高度，必须以服务端值为准 */
    protected float lastEffectiveMixture = 1f;

    // ---- P6：最佳工作温度经济区（只省油耗；方案见 memo/engine-module.md 节 10） ----
    /** 经济区最大折扣（×，最佳温度处）；平底容差窗内恒此值 */
    public static final float ECO_MIN = 0.8f;
    /** 平底容差窗半径（°C）：|T−T_opt| ≤ 此值 → 系数 = ECO_MIN */
    public static final float ECO_FLAT = 10f;
    /** 经济区半径（°C）：|T−T_opt| ≥ 此值 → 系数 = 1.0 */
    public static final float ECO_OUTER = 40f;
    /** 蒸汽引擎固定最佳工作温度（°C，锅炉设计温度，不随燃料变——真实：蒸汽效率 ∝ 蒸汽温度/压力 [卡诺]） */
    public static final float BOILER_T_OPT = 155f;

    /** 当前是否蒸汽引擎（tick 仲裁后：模块燃烧室为蒸汽型；流体/蒸汽物理排斥 → 互斥）。服务端权威，NBT 同步客户端供 Goggle。 */
    protected boolean steamEngine = false;
    /** 蒸汽暖机中（tick 服务端计算：点火燃烧但温度 < STEAM_MIN_WORK_TEMP，只烧不发电）。NBT 同步客户端供 Goggle。 */
    protected boolean warmingUp = false;
    /** 是否已装整合气道（tick 扫描；P6 门控：解锁经济区/拉稀权/风门）。NBT 同步客户端。 */
    protected boolean hasAirDuct = false;
    /** 服务端每 tick 的当前经济系数（0.8~1.0；无整合气道/停机 → 1.0），NBT 同步客户端 Goggle 显示 */
    protected float lastEconomyFactor = 1f;
    /** 冷却强度（风门，0~1，默认 1.0 = 全开；只缩放 K_DUCT 风道散热分量，冲压/气压/环境不动；仅装整合气道后可调，只能降——真实 cowl flap）。NBT 持久化。 */
    protected float coolingStrength = 1f;

    // ---- P6：Lua getActiveFuel 缓存（服务端 tick 更新，供读缓存） ----
    /** 当前活动燃料类型："none" / "fluid" / "steam" */
    protected String activeFuelType = "none";
    /** 当前活动流体燃料 id（蒸汽引擎为空串） */
    protected String activeFuelId = "";
    /** 当前活动燃料的最佳工作温度（°C） */
    protected float activeFuelTopt = EngineFuels.DEFAULT_OPTIMAL_TEMP;

    /** CC:T 外设实例（懒加载），不直接在 BE 上实现 IPeripheral 以避免 getType() 与 BlockEntity.getType() 冲突 */
    @Nullable
    private IPeripheral peripheral;

    /** 客户端：流体燃烧室活塞"噗嗤"音效池（每引擎一个，音量 = Config.ENGINE_FLUID_PUFF_VOLUME） */
    @OnlyIn(Dist.CLIENT)
    protected SoundPool fluidPistonSoundPool;

    /** 客户端：蒸汽动力室活塞"噗嗤"音效池（每引擎一个，音量 = Config.ENGINE_STEAM_PUFF_VOLUME） */
    @OnlyIn(Dist.CLIENT)
    protected SoundPool steamPistonSoundPool;

    public EngineCoreBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.engine_core_entity.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (updateConnectivity)
            updateConnectivity();

        if (level.isClientSide) {
            CatnipServices.PLATFORM.executeOnClientOnly(() -> this::tickClient);
            return;
        }
        if (!isController())
            return;

        // ---- P1+P2+P3：燃烧室 → 燃料 → 发电/消耗/温度（零缓存，直接从源罐 drain） ----
        boolean prevRunning = running;
        float prevCapacity = moduleCapacity;

        boolean overstressed = isOverStressed();
        ModuleScan scan = scanModule();
        // P6 单燃料制：流体/蒸汽物理排斥 —— 模块同时挂有两类燃烧室（旧档/蓝图/命令残留）时，
        // 只让多数派类型工作，少数派忽略（不发电/不消耗/不产热/不计容量）；平局流体优先。
        List<BlockPos> fluidChambers = scan.fluidChambers();
        List<BlockPos> steamChambers = scan.steamChambers();
        if (!fluidChambers.isEmpty() && !steamChambers.isEmpty()) {
            if (steamChambers.size() > fluidChambers.size()) {
                LOGGER.warn("[EngineCore] {} 混合流体+蒸汽模块 steam={} > fluid={} → 仅蒸汽工作",
                        worldPosition, steamChambers.size(), fluidChambers.size());
                fluidChambers = List.of();
            } else {
                LOGGER.warn("[EngineCore] {} 混合流体+蒸汽模块 fluid={} >= steam={} → 仅流体工作",
                        worldPosition, fluidChambers.size(), steamChambers.size());
                steamChambers = List.of();
            }
        }
        List<BlockPos> neighbors = scan.neighbors();

        // P6 Plan B：蒸汽类型在仲裁后判定（物理排斥 → 互斥）；蒸汽 = 闭式锅炉自调节，永不过热
        steamEngine = !steamChambers.isEmpty();

        // 过热锁定（滞回）：T≥OVERHEAT_TEMP 停机锁定，T≤OVERHEAT_RESUME 解锁——仅流体引擎
        // （蒸汽引擎温度钉在 BOILER_T_OPT 设计点，永不过热，见 Plan B）
        if (steamEngine) {
            overheated = false;
        } else if (overheated) {
            if (temperature <= OVERHEAT_RESUME)
                overheated = false;
        } else if (temperature >= OVERHEAT_TEMP) {
            overheated = true;
        }
        // P4：Lua 开关 enabled 与过热同为"整机停摆"门控（不发电不消耗）
        boolean heatAllowed = enabled && !overheated;

        // P5/P6：混合比（仅流体引擎；蒸汽引擎无混合比轴——锁 1.0、无自动富油、发热不乘热因子）。
        // P6 进气=拉稀权：无整合气道 → 有效混合比钳 ≥1.0（不能拉稀省油）；装后开放 0.6~1.4（自动富油仍按物理生效）
        boolean airDuct = scan.coolingDucts > 0;
        hasAirDuct = airDuct;
        float effectiveMixture = steamEngine ? 1f
                : airDuct ? mixture * autoRichness() : Math.max(1f, mixture) * autoRichness();
        lastEffectiveMixture = effectiveMixture;
        float mixtureHeatFactor = steamEngine ? 1f : heatFactor(effectiveMixture);

        // 流体燃烧室（P1）：燃料表第一个可用流体；每室 consumption mb/s × 效率 × 混合比（蒸汽引擎无流体室 → 跳过扫描）
        int runningFluid = 0;
        float fluidCapacity = 0;
        EngineFuels.Entry fluidFuel = heatAllowed && !fluidChambers.isEmpty() ? findFuel(neighbors) : null;
        if (!fluidChambers.isEmpty() && fluidFuel != null && !overstressed) {
            runningFluid = fluidChambers.size();
            fluidCapacity = runningFluid * BASE_STRESS_PER_CHAMBER * fluidFuel.stress();
            // P6 经济区（只省油耗、绝不反哺 Q_heat）：×eco(T, 该燃料 optimal_temp)；无整合气道 → 1.0
            fuelDebt += runningFluid * fluidFuel.consumption() * efficiency * effectiveMixture
                    * economyFactor(temperature, fluidFuel.optimalTemp(), airDuct) / 20f;
            while (fuelDebt >= 1f) {
                if (drainFuel(1)) {
                    fuelDebt -= 1f;
                } else {
                    runningFluid = 0;
                    fluidCapacity = 0;
                    fuelDebt = 0;
                    break;
                }
            }
        } else {
            fuelDebt = 0;
        }

        // 蒸汽动力室（P2）：水 + 流体燃料（burnTick 制，水在才烧；水不可用整类暂停；固体燃料抽取暂缓见 tryPullSolidFuel）
        int runningSteam = 0;
        boolean waterOk = !overstressed && heatAllowed && waterAvailable(neighbors);
        EngineFuels.Entry steamFuel = heatAllowed && !steamChambers.isEmpty() ? findSteamFluidFuel(neighbors) : null;
        if (waterOk && steamFuel != null) {
            // P6 Plan B：蒸汽无经济区（温度钉在 BOILER_T_OPT 自调节 → 无折扣无惩罚，系数恒 1.0）
            for (BlockPos sp : steamChambers) {
                if (!(level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be))
                    continue;
                // 流体燃料：每 tick 每室消耗 1000/burn_ticks_per_bucket × 效率 mb（≈效率 个 burnTick/tick），
                // 累计 ≥1mb 从源罐抽；每抽 1mb → burn_ticks_per_bucket/1000 个 burnTick（熔岩 20 tick/mb）
                be.fluidFuelDebt += 1000f / steamFuel.burnTicksPerBucket() * efficiency * effectiveMixture;
                while (be.fluidFuelDebt >= 1f) {
                    if (drainSteamFluidFuel(1)) {
                        be.fluidFuelDebt -= 1f;
                        be.burnTicks += Math.max(1, Math.round(steamFuel.burnTicksPerBucket() / 1000f));
                        be.setChanged();
                    } else {
                        be.fluidFuelDebt = 0;
                        break;
                    }
                }
                if (be.burnTicks > 0) {
                    be.burnTicks -= efficiency; // 1 个 burnTick 烧 1/efficiency tick（25% 效率 = 4× 时长）
                    if (be.burnTicks <= 0)
                        be.setChanged();
                    runningSteam++;
                }
            }
            if (runningSteam > 0) {
                waterDebt += runningSteam * 0.05f * efficiency; // 1mb/s/室 × 效率 = 0.05mb/t
                while (waterDebt >= 1f) {
                    if (drainWater(1)) {
                        waterDebt -= 1f;
                    } else {
                        waterDebt = 0;
                        break;
                    }
                }
            }
        }

        int runningTotal = runningFluid + runningSteam;
        // P6 Plan B：蒸汽锅炉暖机门控——T ≥ STEAM_MIN_WORK_TEMP 才「开始工作」（低于阈值只烧不发电，热机过程）
        boolean steamReady = !steamEngine || temperature >= STEAM_MIN_WORK_TEMP;
        // P4：enabled=false 或 throttle=0（效率=0）→ 停机（不发电；throttle=0 时容量/消耗/发热全为 0，避免"空转"假象）
        running = runningTotal > 0 && steamReady && !overstressed && !overheated && enabled && efficiency > 0f;
        moduleCapacity = (fluidCapacity + (steamReady ? runningSteam : 0) * STEAM_STRESS_PER_CHAMBER) * efficiency;

        // ---- P3/P6 Plan B：温度更新 ----
        float tAmb = ambientTemp();
        if (steamEngine) {
            // 蒸汽 = 闭式锅炉：点火燃烧（runningSteam>0，含暖机阶段）→ 温度指数收敛到 BOILER_T_OPT
            // （饱和温度；燃料热量用于产汽=功率而非升温，与气压/环境/冲压无关，永不过热——
            //  缺水 = 停烧，waterOk 门控已预先防干烧；暖机 = T < STEAM_MIN_WORK_TEMP 只烧不发电）；
            // 停火 → 牛顿冷却缓慢降温（K_CORE 自散热）。
            // 注意：runningSteam 计的是「有燃料储备（burnTicks>0）的室」；油门 0（efficiency=0）时 burnTicks 冻结、
            // 不抽油，必须加 efficiency>0 才视为点火——否则「不转不耗油但温度钉住」。
            if (runningSteam > 0 && efficiency > 0f) {
                temperature += (BOILER_T_OPT - temperature) * STEAM_WARMUP_RATE / 20f;
            } else {
                float kCool = K_CORE * length + K_AMBIENT * runningTotal
                        + K_DUCT * scan.coolingDucts * (hasAirDuct ? coolingStrength : 1f);
                temperature += (-kCool * (temperature - tAmb)) / thermalCapacity() / 20f;
            }
        } else {
            // 流体引擎：牛顿冷却（发热 − 散热），气压/冲压/风道/风门全部生效
            float heat = 0;
            if (running && !overheated && runningFluid > 0 && fluidFuel != null)
                heat += runningFluid * efficiency * fluidFuel.heat() * BASE_HEAT_FLUID * mixtureHeatFactor;
            // P6 风门：冷却强度只缩放整合气道散热分量（冲压/气压/环境不动）；无气道时 D=0 无影响
            float kTotal = (K_CORE * length + K_AMBIENT * runningTotal
                    + K_DUCT * scan.coolingDucts * (hasAirDuct ? coolingStrength : 1f))
                    * ramFactor() * pressureFactor();
            temperature += (heat - kTotal * (temperature - tAmb)) / thermalCapacity() / 20f;
        }
        temperature = Mth.clamp(temperature, tAmb, OVERHEAT_TEMP * 1.2f);

        // P6 Plan B：当前经济系数（仅流体引擎；蒸汽无经济区恒 1.0；停机/油门 0 → 1.0 无意义）
        lastEconomyFactor = (!steamEngine && running && runningFluid > 0 && fluidFuel != null)
                ? economyFactor(temperature, fluidFuel.optimalTemp(), airDuct) : 1f;
        // P6 Plan B：蒸汽暖机标志（点火燃烧但未达工作温度；油门 0 不点火 → false；供 Goggle 状态行 / Lua isWarmingUp）
        warmingUp = steamEngine && runningSteam > 0 && efficiency > 0f && temperature < STEAM_MIN_WORK_TEMP;
        // P6：getActiveFuel 缓存（当前活动燃料 + 其 T_opt）
        if (runningFluid > 0 && fluidFuel != null) {
            activeFuelType = "fluid";
            activeFuelId = fluidFuel.fluid().toString();
            activeFuelTopt = fluidFuel.optimalTemp();
        } else if (runningSteam > 0) {
            activeFuelType = "steam";
            activeFuelId = "";
            activeFuelTopt = BOILER_T_OPT;
        } else {
            activeFuelType = "none";
            activeFuelId = "";
            activeFuelTopt = EngineFuels.DEFAULT_OPTIMAL_TEMP;
        }

        if (running != prevRunning || !Mth.equal(moduleCapacity, prevCapacity)
                || Math.abs(temperature - lastSyncedTemp) >= SYNC_TEMP_DELTA) {
            reActivateSource = true;
            setChanged();
            lastSyncedTemp = temperature;
            sendData();
        }

        if (DEBUG) {
            if (running != prevRunning) {
                LOGGER.info("[EngineCore] {} running {} -> {} | fluid={} steam={} fuel={} water={} T={}℃ overheated={} capacity={}",
                        worldPosition, prevRunning, running, runningFluid, runningSteam,
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterOk, temperature, overheated, moduleCapacity);
            } else if (!running && level.getGameTime() % 40 == 0) {
                LOGGER.info("[EngineCore] {} idle | fluid={} steam={} fuel={} water={} T={}℃ overheated={} ducts={} len={} speed={}",
                        worldPosition, fluidChambers.size(), steamChambers.size(),
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterOk, temperature, overheated,
                        scan.coolingDucts, length, getSpeed());
            }
        }
    }

    /** 模块扫描结果：一次遍历收集两类燃烧室 + 整合气道数 + 去重后的全部邻居（core 成员 6 邻居 ∪ 燃烧室 6 邻居） */
    protected record ModuleScan(List<BlockPos> fluidChambers, List<BlockPos> steamChambers, List<BlockPos> neighbors,
                                int coolingDucts) {}

    /**
     * 扫描本条引擎（全部成员）：统计贴附的流体/蒸汽燃烧室（仅计入背面 FACING 反方向正贴核心的，
     * 避免双计），并收集模块全部邻居（core 成员 ∪ 燃烧室，去重）——水和流体燃料的源罐查找范围。
     * 整合气道计数范围 = 贴在核心成员 ∪ 燃烧室上（blockstate 计数，经 seen 去重防重复计）。
     */
    protected ModuleScan scanModule() {
        List<BlockPos> fluidChambers = new ArrayList<>();
        List<BlockPos> steamChambers = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> neighbors = new ArrayList<>();
        int coolingDucts = 0;

        Direction.Axis axis = getMainConnectionAxis();
        for (int i = 0; i < length; i++) {
            BlockPos corePos = worldPosition.relative(axis, i);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = corePos.relative(dir);
                BlockState state = level.getBlockState(neighbor);
                if (state.is(MyModBlocks.fluid_combustion_chamber.get())
                        // 燃烧室背面（FACING 反方向）坐标 == 核心坐标，才算贴在这节核心上
                        && neighbor.relative(state.getValue(FluidCombustionChamberBlock.FACING).getOpposite()).equals(corePos)) {
                    fluidChambers.add(neighbor);
                } else if (state.is(MyModBlocks.steam_power_chamber.get())
                        && neighbor.relative(state.getValue(SteamPowerChamberBlock.FACING).getOpposite()).equals(corePos)) {
                    steamChambers.add(neighbor);
                }
                // 整合气道：贴在核心成员上即计入（blockstate 计数，无 BE；去重防两核心间重复计）
                if (state.is(MyModBlocks.integrated_air_duct.get()) && seen.add(neighbor))
                    coolingDucts++;
                addNeighbor(seen, neighbors, neighbor);
            }
        }
        // 燃烧室自己的邻居（罐/容器可能贴着燃烧室；整合气道贴在燃烧室旁同样计入冷却）
        for (BlockPos chamber : fluidChambers)
            coolingDucts += scanChamberNeighbors(seen, neighbors, chamber);
        for (BlockPos chamber : steamChambers)
            coolingDucts += scanChamberNeighbors(seen, neighbors, chamber);
        return new ModuleScan(fluidChambers, steamChambers, neighbors, coolingDucts);
    }

    private void addNeighbor(Set<BlockPos> seen, List<BlockPos> list, BlockPos pos) {
        if (seen.add(pos))
            list.add(pos);
    }

    private void addNeighbors(Set<BlockPos> seen, List<BlockPos> list, BlockPos pos) {
        for (Direction dir : Direction.values())
            addNeighbor(seen, list, pos.relative(dir));
    }

    /**
     * 扫描 pos（燃烧室）的 6 邻居：整合气道计入冷却计数（返回新计入数，经 seen 去重防核心/燃烧室间重复计），
     * 其余邻居并入模块邻居列表（水和流体燃料的源罐/容器查找范围）。
     */
    private int scanChamberNeighbors(Set<BlockPos> seen, List<BlockPos> neighbors, BlockPos pos) {
        int ducts = 0;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (level.getBlockState(neighbor).is(MyModBlocks.integrated_air_duct.get()) && seen.add(neighbor))
                ducts++;
            addNeighbor(seen, neighbors, neighbor);
        }
        return ducts;
    }

    /** 找流体燃烧室当前可用燃料：优先复用缓存的源罐；失效则按优先级全量重扫（带冷却） */
    protected EngineFuels.Entry findFuel(List<BlockPos> neighbors) {
        if (fuelSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(fuelSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty()) {
                    EngineFuels.Entry entry = EngineFuels.get(stack.getFluid());
                    if (entry != null) {
                        fuelFluid = stack.copy();
                        return entry;
                    }
                }
            }
            fuelSourcePos = null;
        }

        if (fuelRescanCooldown > 0) {
            fuelRescanCooldown--;
            return null;
        }
        fuelRescanCooldown = 10;

        for (EngineFuels.Entry entry : EngineFuels.sortedByPriority()) {
            for (BlockPos neighbor : neighbors) {
                IFluidHandler handler = fluidHandlerAt(neighbor);
                if (handler == null)
                    continue;
                FluidStack stack = handler.getFluidInTank(0);
                if (stack.isEmpty())
                    continue;
                if (BuiltInRegistries.FLUID.getKey(stack.getFluid()).equals(entry.fluid())) {
                    fuelSourcePos = neighbor;
                    fuelFluid = stack.copy();
                    return entry;
                }
            }
        }
        return null;
    }

    // ================= P2：蒸汽室（水 + 燃料） =================

    /** 水是否可用：优先复用缓存的水源罐；失效则重扫（带冷却）。源 = 模块邻居中含原版水的罐 */
    protected boolean waterAvailable(List<BlockPos> neighbors) {
        if (waterSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(waterSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty() && stack.getFluid() == Fluids.WATER)
                    return true;
            }
            waterSourcePos = null;
        }

        if (waterRescanCooldown > 0) {
            waterRescanCooldown--;
            return false;
        }
        waterRescanCooldown = 10;

        for (BlockPos pos : neighbors) {
            IFluidHandler handler = fluidHandlerAt(pos);
            if (handler == null)
                continue;
            FluidStack stack = handler.getFluidInTank(0);
            if (!stack.isEmpty() && stack.getFluid() == Fluids.WATER) {
                waterSourcePos = pos;
                return true;
            }
        }
        return false;
    }

    /** 从水源罐 drain 指定量（mb）；源罐失效返回 false */
    protected boolean drainWater(int mb) {
        if (waterSourcePos == null)
            return false;
        IFluidHandler handler = fluidHandlerAt(waterSourcePos);
        if (handler == null) {
            waterSourcePos = null;
            return false;
        }
        return handler.drain(new FluidStack(Fluids.WATER, mb), IFluidHandler.FluidAction.EXECUTE).getAmount() >= mb;
    }

    /** 找蒸汽室当前可用流体燃料（burn_ticks_per_bucket > 0）：缓存优先，失效重扫（带冷却） */
    protected EngineFuels.Entry findSteamFluidFuel(List<BlockPos> neighbors) {
        if (steamFuelSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(steamFuelSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty()) {
                    EngineFuels.Entry entry = EngineFuels.get(stack.getFluid());
                    if (entry != null && entry.burnTicksPerBucket() > 0) {
                        steamFuelEntry = entry;
                        steamFuelFluid = stack.copy();
                        return entry;
                    }
                }
            }
            steamFuelSourcePos = null;
        }

        if (steamFuelRescanCooldown > 0) {
            steamFuelRescanCooldown--;
            return null;
        }
        steamFuelRescanCooldown = 10;

        for (EngineFuels.Entry entry : EngineFuels.sortedByPriority()) {
            if (entry.burnTicksPerBucket() <= 0)
                continue;
            for (BlockPos pos : neighbors) {
                IFluidHandler handler = fluidHandlerAt(pos);
                if (handler == null)
                    continue;
                FluidStack stack = handler.getFluidInTank(0);
                if (stack.isEmpty())
                    continue;
                if (BuiltInRegistries.FLUID.getKey(stack.getFluid()).equals(entry.fluid())) {
                    steamFuelSourcePos = pos;
                    steamFuelEntry = entry;
                    steamFuelFluid = stack.copy();
                    return entry;
                }
            }
        }
        return null;
    }

    /** 从蒸汽室流体燃料源罐 drain 指定量（mb）；源罐失效返回 false */
    protected boolean drainSteamFluidFuel(int mb) {
        if (steamFuelSourcePos == null)
            return false;
        IFluidHandler handler = fluidHandlerAt(steamFuelSourcePos);
        if (handler == null) {
            steamFuelSourcePos = null;
            return false;
        }
        return handler.drain(new FluidStack(steamFuelFluid.getFluid(), mb), IFluidHandler.FluidAction.EXECUTE).getAmount() >= mb;
    }

    /**
     * 从蒸汽室 6 邻居容器抽取 1 个熔炉燃料物品（burnTime > 0），burnTicks += 物品 burnTime；抽到返回 true。
     * <p><b>暂缓（用户要求先做流体燃料熔岩）</b>：当前 tick 未调用；恢复固体燃料时在蒸汽室循环里
     * 先于流体燃料尝试即可（be.burnTicks <= 0 时先 tryPullSolidFuel 再走流体）。</p>
     */
    protected boolean tryPullSolidFuel(SteamPowerChamberBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, neighbor, null);
            if (handler == null)
                continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty())
                    continue;
                int burnTime = stack.getBurnTime(RecipeType.SMELTING);
                if (burnTime <= 0)
                    continue;
                ItemStack extracted = handler.extractItem(slot, 1, false);
                if (extracted.isEmpty())
                    continue;
                be.burnTicks += burnTime;
                be.setChanged();
                return true;
            }
        }
        return false;
    }

    // ================= P3：温度/冷却辅助 =================

    /** 冲压冷却因子：运动体速度 10→30 m/s 线性爬升 1.0→RAM_MAX（<10 无增益，≥30 满增益；静态方块 = 1.0） */
    protected float ramFactor() {
        SubLevel sub = SableCompat.getContainingSubLevel(this);
        if (sub == null)
            return 1f;
        Vec3 v = SableCompat.getWorldLinearVelocity(sub);
        if (v == null)
            return 1f;
        float speed = (float) v.length();
        if (speed <= RAM_START)
            return 1f;
        if (speed >= RAM_FULL)
            return RAM_MAX;
        float t = (speed - RAM_START) / (RAM_FULL - RAM_START);
        return 1f + (RAM_MAX - 1f) * t;
    }

    /** 引擎当前世界高度 Y：运动体上取物理体原点 Y，静态取方块自身 Y */
    protected float engineAltitude() {
        SubLevel sub = SableCompat.getContainingSubLevel(this);
        if (sub != null) {
            Vec3 pos = SableCompat.getSubLevelWorldPos(sub);
            if (pos != null)
                return (float) pos.y;
        }
        return worldPosition.getY();
    }

    /** 气压因子：pressure^0.8（钳位下限 PRESSURE_FLOOR）。高空气压低 → 换热差 → 冷却更差（物理结论见 memo） */
    protected float pressureFactor() {
        double p = SensorSystemAPI.getPressureForEngine(engineAltitude());
        return (float) Math.pow(Math.max(p, PRESSURE_FLOOR), 0.8);
    }

    /** 环境温度（°C）：海平面 20°C，对流层 −0.0065°C/m（按海平面高度），下限 T_AMB_FLOOR */
    protected float ambientTemp() {
        float aboveSea = Math.max(0, engineAltitude() - 63f);
        return Math.max(T_AMB_SEA - T_AMB_LAPSE * aboveSea, T_AMB_FLOOR);
    }

    // ---- P5：混合比辅助（方案见 memo/engine-module.md 关键机制 7）----

    /** 自动富油系数：实际混合比 = 杆 × autoRichness()。真实：化油器按进气体积配油 → 气压低（空气稀）→ 天然变浓 */
    protected float autoRichness() {
        double p = SensorSystemAPI.getPressureForEngine(engineAltitude());
        return Mth.clamp(1f + MIXTURE_PRESSURE_K * (float) (1d - p), 1f, MIXTURE_ALT_MAX);
    }

    /**
     * 混合比热因子（凸曲线，与油耗反向——核心矛盾：发热不能跟烧油量走，否则永远拉稀）：
     * 稀侧 1 + A×(1−m)² 加速惩罚（防"永远拉稀"驻点），浓侧 1 − B×(m−1) 平缓收敛（富油吸热降温，下限 RICH_FLOOR）。
     */
    protected float heatFactor(float m) {
        if (m < 1f)
            return 1f + MIXTURE_LEAN_K * (1f - m) * (1f - m);
        return Math.max(MIXTURE_RICH_FLOOR, 1f - MIXTURE_RICH_K * (m - 1f));
    }

    /**
     * P6 经济系数（只乘消耗、<b>绝不反哺 Q_heat</b>——防「追经济→温度变→系数变」自震荡）：
     * 单点最优 + 平底容差窗——|T−T_opt| ≤ {@link #ECO_FLAT} 恒 {@link #ECO_MIN}；≥ {@link #ECO_OUTER} → 1.0；
     * 之间 = ECO_MIN + 0.2×sqrt((δ−δ_flat)/(δ_outer−δ_flat))——刚出窗掉得快、远处趋平（越接近最优掉得越快）。
     * 无整合气道（未解锁经济区）→ 恒 1.0。
     */
    protected float economyFactor(float temp, float tOpt, boolean airDuctInstalled) {
        if (!airDuctInstalled)
            return 1f;
        float d = Math.abs(temp - tOpt);
        if (d <= ECO_FLAT)
            return ECO_MIN;
        if (d >= ECO_OUTER)
            return 1f;
        float u = (d - ECO_FLAT) / (ECO_OUTER - ECO_FLAT);
        return ECO_MIN + (1f - ECO_MIN) * (float) Math.sqrt(u);
    }

    /** 热容：C_TH_BASE × 节数（模块越大热得越慢） */
    protected float thermalCapacity() {
        return C_TH_BASE * Math.max(1, length);
    }

    /** 从当前源罐 drain 指定量（mb）；源罐失效返回 false */
    protected boolean drainFuel(int mb) {
        if (fuelSourcePos == null)
            return false;
        IFluidHandler handler = fluidHandlerAt(fuelSourcePos);
        if (handler == null) {
            fuelSourcePos = null;
            return false;
        }
        FluidStack drained = handler.drain(new FluidStack(fuelFluid.getFluid(), mb), IFluidHandler.FluidAction.EXECUTE);
        return drained.getAmount() >= mb;
    }

    protected IFluidHandler fluidHandlerAt(BlockPos pos) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
    }

    /**
     * 流体燃烧室"噗嗤"音效池（每引擎一个，懒创建）：音量 = {@link Config#ENGINE_FLUID_PUFF_VOLUME}
     * （播放时读取，改配置即时生效）。照 Create {@code BoilerData/SoundPool}：
     * mergeTicks 合并窗口 + maxConcurrent 并发上限（超出随机截断）+ 各自坐标发声。
     */
    @OnlyIn(Dist.CLIENT)
    public SoundPool getFluidPistonSoundPool() {
        if (fluidPistonSoundPool == null)
            fluidPistonSoundPool = new SoundPool(4, 2,
                    (level, pos) -> AllSoundEvents.STEAM.playAt(level, pos,
                            Config.ENGINE_FLUID_PUFF_VOLUME.get().floatValue(), 0.8f + level.random.nextFloat() * 0.4f, false));
        return fluidPistonSoundPool;
    }

    /** 蒸汽动力室"噗嗤"音效池（每引擎一个，懒创建）：音量 = {@link Config#ENGINE_STEAM_PUFF_VOLUME}，其余同流体室 */
    @OnlyIn(Dist.CLIENT)
    public SoundPool getSteamPistonSoundPool() {
        if (steamPistonSoundPool == null)
            steamPistonSoundPool = new SoundPool(4, 2,
                    (level, pos) -> AllSoundEvents.STEAM.playAt(level, pos,
                            Config.ENGINE_STEAM_PUFF_VOLUME.get().floatValue(), 0.8f + level.random.nextFloat() * 0.4f, false));
        return steamPistonSoundPool;
    }

    @OnlyIn(Dist.CLIENT)
    protected void tickClient() {
        // 活塞"噗嗤"音效池：本 tick 播放燃烧室/动力室入队的脉冲（空队列直接返回；对应音量=0 时不创建/不播放，省性能）
        if (isController()) {
            if (Config.ENGINE_FLUID_PUFF_VOLUME.get() > 0)
                getFluidPistonSoundPool().play(level);
            if (Config.ENGINE_STEAM_PUFF_VOLUME.get() > 0)
                getSteamPistonSoundPool().play(level);
        }

        // 温度显示：沿最近两个采样点的斜率外推（服务端差量发包低频 → 外推让显示连续无台阶/平台）
        long now = level.getGameTime();
        if (lastTempSampleTime < newTempSampleTime) {
            float dt = Math.max(1, newTempSampleTime - lastTempSampleTime);
            float slope = (newTempSample - lastTempSample) / dt;
            float extrapolated = newTempSample + slope * Math.max(0, now - newTempSampleTime);
            // 限制外推带，防温度曲线拐弯（接近平衡/停机冷却）时过冲
            float lo = Math.min(lastTempSample, newTempSample) - 10f;
            float hi = Math.max(lastTempSample, newTempSample) + 10f;
            displayedTemperature = Mth.clamp(extrapolated, lo, hi);
        } else {
            displayedTemperature = newTempSample;
        }
    }

    /** 是否运行（有燃烧室 + 燃料可用 + 未过载）；服务端 tick 计算，经 NBT 同步给客户端（音效/活塞动画用） */
    public boolean isRunning() {
        return running;
    }

    // ═══════════════ CC:T 外设（P4，Lua 控制；方案见 memo/engine-module.md 关键机制 5） ═══════════════

    /**
     * 获取此外设的 CC:T IPeripheral 实例（懒加载；非 controller 委托给整条引擎的 controller——
     * 外设"挂在 controller"上，包裹任意核心节都返回同一外设实例）。
     * <p>Capability 查询发生在主线程（CC 外设挂载路径：BlockCapabilityCache + ServerLevel.getBlockEntity），
     * 此处跨 BE 解析 controller 是安全的；controller 暂不可达（拆解重组中）返回 null = 暂时无外设。</p>
     * 注册见 {@code compat/cc/CCPeripheralCapabilities.java}。
     */
    @Nullable
    public IPeripheral getPeripheral() {
        if (!isController()) {
            EngineCoreBlockEntity controllerBE = getControllerBE();
            return controllerBE != null ? controllerBE.getPeripheral() : null;
        }
        if (peripheral == null)
            peripheral = new Peripheral();
        return peripheral;
    }

    /**
     * 内嵌外设类（同 TransmissionPeripheralBlockEntity / MyBearingBlockEntity 模式）：引擎唯一控制入口（无红石）。
     * <pre>{@code
     * local e = peripheral.wrap("front")     -- 包裹任意引擎核心节（外设挂在整条引擎的 controller 上）
     * print(e.getTemperature())              -- 温度（°C）
     * print(e.isOverheated())                -- 过热锁定
     * for _, t in ipairs(e.getFluidTanks()) do -- 所有连接储罐：fluid/amount/remaining/capacity
     *   print(t.fluid, t.amount, t.remaining, t.capacity)
     * end
     * e.setEnabled(false)                    -- Lua 开关（默认 true；false = 整机停摆：不发电不消耗）
     * e.setThrottle(0.5)                     -- 油门（0..1；应力与转速同比例：50% = 半应力 + 128rpm；0 = 停机）
     * e.setMixture(0.8)                      -- 混合比（0.6~1.4；只影响油耗与温度：稀=省油但更热，浓=费油但降温）
     * e.getEffectiveMixture()                -- 实际混合比（杆 × 高空自动富油，气压驱动；高空 > 杆值）
     * }</pre>
     * 读方法 mainThread=false 直读 controller 缓存状态（每 tick 由 controller 刷新，最多滞后 1 tick）；
     * 写方法 / 需要扫描世界的 `getFluidTanks` 为 mainThread=true 服务端权威。
     * 电脑 attach/detach 发生在主线程（CC 外设挂载路径），在此维护 {@code luaConnected} 供 Goggle 显示。
     */
    private class Peripheral implements IPeripheral {
        /** 当前连接的电脑集合（attach/detach 维护；非空 = Lua 控制已连接） */
        private final Set<IComputerAccess> attachedComputers = ConcurrentHashMap.newKeySet();

        @Override
        public String getType() {
            return "ccpe:engine";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            if (this == other) return true;
            if (other instanceof EngineCoreBlockEntity.Peripheral that) {
                return EngineCoreBlockEntity.this.worldPosition
                        .equals(EngineCoreBlockEntity.this.worldPosition);
            }
            return false;
        }

        @Override
        public void attach(IComputerAccess computer) {
            attachedComputers.add(computer);
            updateLuaConnected();
        }

        @Override
        public void detach(IComputerAccess computer) {
            attachedComputers.remove(computer);
            updateLuaConnected();
        }

        /** 把「是否有电脑连接」写入 BE 并同步客户端（供 Goggle 显示 Lua 控制连接状态） */
        private void updateLuaConnected() {
            boolean connected = !attachedComputers.isEmpty();
            if (connected == luaConnected)
                return;
            luaConnected = connected;
            setChanged();
            sendData();
        }

        // ═══════════════ Lua API ═══════════════

        /** 引擎温度（°C，服务端权威值；客户端 Goggle 显示的是趋势外推平滑值，此处为实时服务端值） */
        @LuaFunction
        public final double getTemperature() {
            return temperature;
        }

        /** 是否过热锁定（T≥OVERHEAT_TEMP 硬停，T≤OVERHEAT_RESUME 滞回解锁） */
        @LuaFunction
        public final boolean isOverheated() {
            return overheated;
        }

        /**
         * 所有连接的流体储罐内容（模块邻居中带流体能力且方块 tag 过滤通过的方块，含燃料/水源罐，经 seen 去重）：
         * 每个储罐一项：{@code fluid}（流体 id，空罐为 nil）、{@code amount}（当前量 mb）、
         * {@code remaining}（剩余可装量 mb = capacity − amount）、{@code capacity}（总量 mb）。
         * mainThread=true：需要现场扫描模块邻居并做 capability 查询。
         */
        @LuaFunction(mainThread = true)
        public final List<Map<String, Object>> getFluidTanks() {
            List<Map<String, Object>> tanks = new ArrayList<>();
            for (BlockPos neighbor : scanModule().neighbors()) {
                IFluidHandler handler = fluidHandlerAt(neighbor);
                if (handler == null)
                    continue;
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack stack = handler.getFluidInTank(tank);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("fluid", stack.isEmpty() ? null
                            : BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());
                    entry.put("amount", (double) stack.getAmount());
                    int capacity = handler.getTankCapacity(tank);
                    entry.put("remaining", (double) Math.max(0, capacity - stack.getAmount()));
                    entry.put("capacity", (double) capacity);
                    tanks.add(entry);
                }
            }
            return tanks;
        }

        /** 引擎开关（默认 true；false = 整机停摆：不发电、不消耗，温度自然冷却） */
        @LuaFunction
        public final boolean getEnabled() {
            return enabled;
        }

        /** 开关引擎（Lua 唯一控制入口，无红石）。返回是否发生变更。 */
        @LuaFunction(mainThread = true)
        public final boolean setEnabled(boolean value) {
            if (enabled == value)
                return true;
            enabled = value;
            reActivateSource = true;
            setChanged();
            sendData();
            return true;
        }

        /** 当前油门（0..1；默认 0.25——静态无风道不过热的既有定标值）。定距桨单杆模型：转速 = 油门 × 256 */
        @LuaFunction
        public final double getThrottle() {
            return efficiency;
        }

        /**
         * 设置油门（0..1，越界钳制；非法参数返回 false）。
         * 定距桨单杆模型（P4 定稿）：油门同时线性缩放<b>应力输出与转速</b>（100% = 满应力 + 256rpm，
         * 50% = 半应力 + 128rpm，0 = 停机），发热与燃料消耗 ∝ 油门；与转速控制器组合不会产生作弊
         * （预算 = 应力×转速 恒随油门缩放，外部变速无法放大）。
         */
        @LuaFunction(mainThread = true)
        public final boolean setThrottle(double value) {
            if (!Double.isFinite(value))
                return false;
            float clamped = (float) Mth.clamp(value, 0.0, 1.0);
            if (Mth.equal(efficiency, clamped))
                return true;
            efficiency = clamped;
            reActivateSource = true;
            setChanged();
            sendData();
            return true;
        }

        /** 当前混合比杆（0.6~1.4，默认 1.0；只影响油耗与温度，不影响应力/转速）。蒸汽引擎无混合比轴 → 恒 1.0 */
        @LuaFunction
        public final double getMixture() {
            return steamEngine ? 1.0 : mixture;
        }

        /**
         * 当前<b>实际</b>混合比（杆 × 自动富油，服务端每 tick 计算）：
         * 高空/低压下 > 杆值（天然变浓），可用作自动拉稀校正的反馈读数；海平面 = 杆值。
         * 蒸汽引擎无混合比轴 → 恒 1.0。
         */
        @LuaFunction
        public final double getEffectiveMixture() {
            return steamEngine ? 1.0 : lastEffectiveMixture;
        }

        /**
         * 设置混合比（0.6~1.4，越界钳制；非法参数返回 false）。
         * P5 经济性/热管理杆：只影响<b>油耗</b>（×实际混合比）与<b>温度</b>（×凸热因子，与油耗反向）——
         * 稀=省油但更热，浓=费油但降温；应力/转速完全不受影响。
         * 高空自动富油：实际混合比 = 杆 × autoRichness（气压驱动，海平面 1.0，Y≈260 ≈×1.25 上限），
         * 高空不拉稀 = 白烧油；可用 {@link #getEffectiveMixture()} 读实际值做自动校正。
         * P6：蒸汽引擎（无混合比轴）或未装整合气道（进气=拉稀权未解锁）→ 拒绝返回 false。
         */
        @LuaFunction(mainThread = true)
        public final boolean setMixture(double value) {
            if (steamEngine || !hasAirDuct)
                return false;
            if (!Double.isFinite(value))
                return false;
            float clamped = (float) Mth.clamp(value, MIXTURE_MIN, MIXTURE_MAX);
            if (Mth.equal(mixture, clamped))
                return true;
            mixture = clamped;
            setChanged();
            sendData();
            return true;
        }

        /**
         * P6：当前经济系数（0.8~1.0；无整合气道 / 停机 / 油门 0 → 1.0 无意义）。
         * 服务端每 tick 计算并同步（读缓存，≤1 tick 滞后）；温度保持在该燃料最优工作温度附近时 <1 = 省油中。
         */
        @LuaFunction
        public final double getFuelEconomyFactor() {
            return lastEconomyFactor;
        }

        /** P6：是否已装整合气道（解锁经济区 / 拉稀权 / 风门） */
        @LuaFunction
        public final boolean hasAirDuct() {
            return hasAirDuct;
        }

        /** P6 Plan B：蒸汽锅炉是否暖机中（点火燃烧但 T < STEAM_MIN_WORK_TEMP，只烧不发电；暖机完成前无输出） */
        @LuaFunction
        public final boolean isWarmingUp() {
            return warmingUp;
        }

        /**
         * P6：当前活动燃料（服务端 tick 缓存，读缓存 ≤1 tick 滞后）。
         * 流体 → {@code {type="fluid", fluid=<流体id>, optimalTemp=..}}；蒸汽 → {@code {type="steam", optimalTemp=BOILER_T_OPT}}（无混合比）；
         * 停机/无燃料 → {@code {type="none"}}。
         */
        @LuaFunction
        public final Map<String, Object> getActiveFuel() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", activeFuelType);
            if (activeFuelType.equals("fluid"))
                result.put("fluid", activeFuelId);
            result.put("optimalTemp", (double) activeFuelTopt);
            return result;
        }

        /** P6：当前冷却强度（风门 0~1，默认 1.0 = 全开）。蒸汽引擎无风门（恒温自调节）→ 恒 1.0 */
        @LuaFunction
        public final double getCooling() {
            return steamEngine ? 1.0 : coolingStrength;
        }

        /**
         * P6：设置冷却强度（风门 0~1，越界钳制；未装整合气道 / 蒸汽引擎 / 非法参数返回 false）。
         * 只缩放风道散热分量（K_DUCT×D×strength），冲压/气压/环境散热不动；只能降（想更冷 = 多装风道，真实 cowl flap）。
         * 蒸汽引擎无此轴（温度钉在 BOILER_T_OPT 自调节，散热无对象）。
         */
        @LuaFunction(mainThread = true)
        public final boolean setCooling(double value) {
            if (steamEngine || !hasAirDuct)
                return false;
            if (!Double.isFinite(value))
                return false;
            float clamped = (float) Mth.clamp(value, 0.0, 1.0);
            if (Mth.equal(coolingStrength, clamped))
                return true;
            coolingStrength = clamped;
            setChanged();
            sendData();
            return true;
        }
    }

    /** 重新组网：只有 controller 触发 formMulti（服务端执行，客户端直接跳过） */
    public void updateConnectivity() {
        updateConnectivity = false;
        if (level.isClientSide)
            return;
        if (!isController())
            return;
        ConnectivityHandler.formMulti(this);
    }

    // ================= IMultiBlockEntityContainer =================

    @Override
    public BlockPos getController() {
        return isController() ? worldPosition : controller;
    }

    @Override
    public EngineCoreBlockEntity getControllerBE() {
        if (isController() || !hasLevel())
            return this;
        BlockEntity be = level.getBlockEntity(controller);
        if (be instanceof EngineCoreBlockEntity)
            return (EngineCoreBlockEntity) be;
        return null;
    }

    /**
     * 从引擎任一模块方块位置（核心成员 / 燃烧室 / 整合气道）解析整条引擎 controller 坐标；
     * 该方块不属于任何已组网引擎（未连接核心）时返回 null。
     * <p>供模块方块（燃烧室 / 整合气道）的 goggle tooltip 代理使用（{@code IProxyHoveringInformation}）。</p>
     */
    public static BlockPos engineControllerPos(Level level, BlockPos modulePos) {
        // 方块本身是核心成员
        if (level.getBlockEntity(modulePos) instanceof EngineCoreBlockEntity)
            return controllerOfCoreAt(level, modulePos);

        // 燃烧室：背面（FACING 反方向）= 贴附的核心
        BlockState state = level.getBlockState(modulePos);
        if (state.is(MyModBlocks.fluid_combustion_chamber.get()) || state.is(MyModBlocks.steam_power_chamber.get())) {
            Direction facing = state.getValue(DirectionalBlock.FACING);
            return controllerOfCoreAt(level, modulePos.relative(facing.getOpposite()));
        }

        // 整合气道：与冷却计数同范围（核心成员 ∪ 燃烧室邻居）
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = modulePos.relative(dir);
            BlockState ns = level.getBlockState(neighbor);
            if (ns.is(MyModBlocks.engine_core.get())) {
                BlockPos controller = controllerOfCoreAt(level, neighbor);
                if (controller != null)
                    return controller;
            } else if (ns.is(MyModBlocks.fluid_combustion_chamber.get()) || ns.is(MyModBlocks.steam_power_chamber.get())) {
                Direction facing = ns.getValue(DirectionalBlock.FACING);
                BlockPos controller = controllerOfCoreAt(level, neighbor.relative(facing.getOpposite()));
                if (controller != null)
                    return controller;
            }
        }
        return null;
    }

    /** 核心成员位置 → 整条引擎 controller 坐标；该位置不是核心或 controller 不可达时返回 null */
    private static BlockPos controllerOfCoreAt(Level level, BlockPos corePos) {
        if (level.getBlockEntity(corePos) instanceof EngineCoreBlockEntity core) {
            EngineCoreBlockEntity controller = core.getControllerBE();
            return controller != null ? controller.getBlockPos() : null;
        }
        return null;
    }

    /**
     * P6 物理排斥：以 corePos 为核心成员的引擎模块是否已挂有<b>流体燃烧室</b>（供蒸汽动力室放置时拒绝）。
     * corePos 不是引擎核心成员或模块无控制器时返回 false。
     */
    public static boolean moduleHasFluidChambers(Level level, BlockPos corePos) {
        BlockPos controllerPos = controllerOfCoreAt(level, corePos);
        if (controllerPos == null)
            return false;
        if (level.getBlockEntity(controllerPos) instanceof EngineCoreBlockEntity controller)
            return !controller.scanModule().fluidChambers().isEmpty();
        return false;
    }

    /**
     * P6 物理排斥：以 corePos 为核心成员的引擎模块是否已挂有<b>蒸汽动力室</b>（供流体燃烧室放置时拒绝）。
     * corePos 不是引擎核心成员或模块无控制器时返回 false。
     */
    public static boolean moduleHasSteamChambers(Level level, BlockPos corePos) {
        BlockPos controllerPos = controllerOfCoreAt(level, corePos);
        if (controllerPos == null)
            return false;
        if (level.getBlockEntity(controllerPos) instanceof EngineCoreBlockEntity controller)
            return !controller.scanModule().steamChambers().isEmpty();
        return false;
    }

    @Override
    public boolean isController() {
        return controller == null || controller.equals(worldPosition);
    }

    @Override
    public void setController(BlockPos controller) {
        if (level.isClientSide && !isVirtual())
            return;
        if (controller.equals(this.controller))
            return;
        this.controller = controller;
        setChanged();
        sendData();
    }

    @Override
    public void removeController(boolean keepContents) {
        if (level.isClientSide)
            return;
        updateConnectivity = true;
        controller = null;
        length = 1;
        reActivateSource = true;
        setChanged();
        sendData();
    }

    @Override
    public BlockPos getLastKnownPos() {
        return lastKnownPos;
    }

    @Override
    public void preventConnectivityUpdate() {
        updateConnectivity = false;
    }

    @Override
    public void notifyMultiUpdated() {
        reActivateSource = true;
        setChanged();
    }

    @Override
    public Direction.Axis getMainConnectionAxis() {
        return getBlockState().getValue(EngineCoreBlock.AXIS);
    }

    @Override
    public int getMaxLength(Direction.Axis longAxis, int width) {
        return 21;
    }

    @Override
    public int getMaxWidth() {
        return 1;
    }

    @Override
    public int getHeight() {
        return length;
    }

    @Override
    public void setHeight(int height) {
        length = height;
    }

    @Override
    public int getWidth() {
        return 1;
    }

    @Override
    public void setWidth(int width) {
        // 宽度固定 1（1×1×N 线性柱体，沿 FACING 轴）
    }

    // ================= NBT =================

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        BlockPos controllerBefore = controller;
        int prevHeight = length;

        updateConnectivity = compound.contains("Uninitialized");
        controller = null;
        lastKnownPos = null;

        if (compound.contains("LastKnownPos"))
            lastKnownPos = NBTHelper.readBlockPos(compound, "LastKnownPos");
        if (compound.contains("Controller"))
            controller = NBTHelper.readBlockPos(compound, "Controller");

        if (isController())
            length = compound.getInt("Height");
        running = compound.getBoolean("Running");
        overheated = compound.getBoolean("Overheated");
        moduleCapacity = compound.getFloat("Capacity");
        if (compound.contains("Temperature"))
            temperature = compound.getFloat("Temperature");
        if (compound.contains("Efficiency"))
            efficiency = compound.getFloat("Efficiency");
        // P4：旧存档（P0–P3）无 Enabled 字段 → 默认开启，保持既有行为
        enabled = !compound.contains("Enabled") || compound.getBoolean("Enabled");
        // P5：旧存档无 Mixture 字段 → 默认 1.0（海平面杆 1.0 = 现状）
        mixture = compound.contains("Mixture") ? compound.getFloat("Mixture") : 1f;
        // P5：实际混合比（服务端同步；旧存档/首个 tick 前 → 1.0）
        lastEffectiveMixture = compound.contains("EffectiveMixture") ? compound.getFloat("EffectiveMixture") : 1f;
        // P6：蒸汽类型 / 整合气道 / 经济系数（服务端权威；旧存档无字段 → 默认值）
        steamEngine = compound.getBoolean("SteamEngine");
        warmingUp = compound.getBoolean("WarmingUp");
        hasAirDuct = compound.getBoolean("AirDuct");
        lastEconomyFactor = compound.contains("EconomyFactor") ? compound.getFloat("EconomyFactor") : 1f;
        coolingStrength = compound.contains("CoolingStrength") ? compound.getFloat("CoolingStrength") : 1f;
        // Lua 连接状态：磁盘加载无此字段 → false（服务端启动时无电脑挂载）；客户端包带真实值
        luaConnected = compound.getBoolean("LuaConnected");

        if (!clientPacket)
            return;

        // 温度采样滚动：新包到达时推进采样窗口（tickClient 沿最近两点斜率外推显示）
        lastTempSample = newTempSample;
        lastTempSampleTime = newTempSampleTime;
        newTempSample = temperature;
        newTempSampleTime = hasLevel() ? level.getGameTime() : 0;

        boolean changeOfController = !Objects.equals(controllerBefore, controller);
        if (changeOfController || prevHeight != length) {
            if (hasLevel())
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 16);
            invalidateRenderBoundingBox();
        }
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        if (updateConnectivity)
            compound.putBoolean("Uninitialized", true);
        if (lastKnownPos != null)
            compound.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
        if (!isController())
            compound.put("Controller", NbtUtils.writeBlockPos(controller));
        if (isController())
            compound.putInt("Height", length);
        compound.putBoolean("Running", running);
        compound.putBoolean("Overheated", overheated);
        // 总应力输出（SU，当前转速下）：客户端 Goggle「总应力输出」行——客户端拿不到燃料表 stress 倍率，必须以服务端值为准
        compound.putFloat("Capacity", moduleCapacity);
        compound.putFloat("Temperature", temperature);
        compound.putFloat("Efficiency", efficiency);
        compound.putBoolean("Enabled", enabled);
        compound.putFloat("Mixture", mixture);
        // 实际混合比（含高空自动富油）服务端权威值，同步给客户端 Goggle 显示
        compound.putFloat("EffectiveMixture", lastEffectiveMixture);
        // P6：蒸汽类型 / 整合气道 / 当前经济系数（服务端每 tick 计算，同步客户端 Goggle；客户端不重算）
        compound.putBoolean("SteamEngine", steamEngine);
        compound.putBoolean("WarmingUp", warmingUp);
        compound.putBoolean("AirDuct", hasAirDuct);
        compound.putFloat("EconomyFactor", lastEconomyFactor);
        compound.putFloat("CoolingStrength", coolingStrength);
        // Lua 连接状态是运行时瞬态（电脑挂载），只同步客户端供 Goggle 显示，不落盘
        if (clientPacket)
            compound.putBoolean("LuaConnected", luaConnected);
    }

    /**
     * Goggle 提示：非 controller 委托给 controller；按悬停方块分流四套 tooltip——引擎核心（温度 / Lua 控制 /
     * 总应力输出 / 目前转速 / 连接的模块）、蒸汽动力室（状态 / 温度 / 油门）、整合气道（温度 / 效率=风门）、
     * 流体燃烧室（全量引擎状态，行为不变）。
     * 首行标题（Create overlay 视为标题行，其后有间距）→ 内容整体下移一行。
     * <p>悬停方块由各引擎方块（核心 {@code EngineCoreBlock} / 模块方块）的 {@code IProxyHoveringInformation.getInformationSource}
     * 代理到本 controller 时记录在 {@link #hoveredSourceBlock}：模块记录自身 → 按类型分流；核心记录自身 → 核心 tooltip。
     * 每次渲染后复位防串帧。</p>
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!isController()) {
            EngineCoreBlockEntity controller = getControllerBE();
            if (controller == null)
                return false;
            return controller.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }
        try {
            Block hovered = level != null && level.isClientSide ? hoveredSourceBlock : null;
            if (hovered == MyModBlocks.steam_power_chamber.get()) {
                addSteamChamberTooltip(tooltip);
            } else if (hovered == MyModBlocks.integrated_air_duct.get()) {
                addAirDuctTooltip(tooltip);
            } else if (hovered == MyModBlocks.fluid_combustion_chamber.get()) {
                addFullEngineTooltip(tooltip, isPlayerSneaking);
            } else {
                // 引擎核心（含核心自身代理记录）→ 核心精简 tooltip
                addCoreTooltip(tooltip);
            }
        } finally {
            // 复位悬停记录：下一帧由代理重新设置
            if (level != null && level.isClientSide)
                hoveredSourceBlock = null;
        }
        return true;
    }

    /** 引擎核心 tooltip：温度 + Lua 控制状态 + 总应力输出 + 目前转速 + 连接的模块清单 */
    private void addCoreTooltip(List<Component> tooltip) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));
        // 温度（客户端趋势外推平滑值；过热锁定时红色）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", tooltipTemp()))
                        .withStyle(overheated ? ChatFormatting.RED : ChatFormatting.GOLD)));
        // Lua 控制连接状态（Peripheral.attach/detach 维护，经 NBT 同步客户端）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.lua_control").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(luaConnected ? "tooltip.ccpe.engine.lua_connected"
                        : "tooltip.ccpe.engine.lua_disconnected")
                        .withStyle(luaConnected ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)));
        // 总应力输出（服务端同步 moduleCapacity；客户端拿不到燃料表 stress 倍率，不自算）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.stress_output").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(moduleCapacity) + " SU").withStyle(ChatFormatting.AQUA)));
        // 目前转速（定距桨：油门 × 256；停机 = 0）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.speed").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(getGeneratedSpeed()) + " RPM").withStyle(ChatFormatting.AQUA)));
        // 连接的模块清单（显示用：blockstate 轻扫，与服务端 scanModule 同判定；燃烧室按 FACING 反面贴核心归属，不双计）
        ModuleScan scan = scanModule();
        int fluid = scan.fluidChambers().size();
        int steam = scan.steamChambers().size();
        int ducts = scan.coolingDucts;
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.modules").withStyle(ChatFormatting.GRAY)));
        if (fluid + steam + ducts == 0) {
            tooltip.add(Component.literal("     ")
                    .append(Component.literal("- ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("tooltip.ccpe.engine.modules_none").withStyle(ChatFormatting.DARK_GRAY)));
        } else {
            if (fluid > 0)
                addModuleLine(tooltip, MyModBlocks.fluid_combustion_chamber.get().getName(), fluid);
            if (steam > 0)
                addModuleLine(tooltip, MyModBlocks.steam_power_chamber.get().getName(), steam);
            if (ducts > 0)
                addModuleLine(tooltip, MyModBlocks.integrated_air_duct.get().getName(), ducts);
        }
    }

    /** 模块清单行：- 名称 x数量 */
    private static void addModuleLine(List<Component> tooltip, Component name, int count) {
        tooltip.add(Component.literal("     ")
                .append(Component.literal("- ").withStyle(ChatFormatting.GRAY))
                .append(name.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" x" + count).withStyle(ChatFormatting.GRAY)));
    }

    /** tooltip 温度值：客户端用趋势外推平滑值，服务端用权威值 */
    private float tooltipTemp() {
        return level != null && level.isClientSide ? displayedTemperature : temperature;
    }

    /** 蒸汽动力室 tooltip：状态（停机 / 正常 / 暖机中）+ 温度 + 油门（= 效率百分比） */
    private void addSteamChamberTooltip(List<Component> tooltip) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));
        String statusKey;
        ChatFormatting statusColor;
        if (warmingUp) {
            statusKey = "tooltip.ccpe.engine.status.warming";
            statusColor = ChatFormatting.GOLD;
        } else if (running) {
            statusKey = "tooltip.ccpe.engine.status.normal";
            statusColor = ChatFormatting.GREEN;
        } else {
            statusKey = "tooltip.ccpe.engine.status.stopped";
            statusColor = ChatFormatting.GRAY;
        }
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.status").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(statusKey).withStyle(statusColor)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", tooltipTemp()))
                        .withStyle(ChatFormatting.GOLD)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.throttle").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(efficiency * 100) + "%").withStyle(ChatFormatting.AQUA)));
    }

    /** 整合气道 tooltip：温度 + 效率（= setCooling 风门百分比） */
    private void addAirDuctTooltip(List<Component> tooltip) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", tooltipTemp()))
                        .withStyle(overheated ? ChatFormatting.RED : ChatFormatting.GOLD)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.efficiency").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(coolingStrength * 100) + "%")
                        .withStyle(coolingStrength < 1f ? ChatFormatting.AQUA : ChatFormatting.GRAY)));
    }

    /** 全量引擎 tooltip（流体燃烧室悬停，保持既有行为不变） */
    private void addFullEngineTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));

        boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);

        // 状态行：正常 / 即将过热（T ≥ 0.8×T_max）/ 过热锁定
        String statusKey;
        ChatFormatting statusColor;
        if (overheated) {
            statusKey = "tooltip.ccpe.engine.status.overheated";
            statusColor = ChatFormatting.RED;
        } else if (warmingUp) {
            // 蒸汽锅炉暖机中（点火燃烧但未达工作温度，只烧不发电）
            statusKey = "tooltip.ccpe.engine.status.warming";
            statusColor = ChatFormatting.GOLD;
        } else if (temperature >= OVERHEAT_TEMP * 0.8f) {
            statusKey = "tooltip.ccpe.engine.status.warning";
            statusColor = ChatFormatting.GOLD;
        } else {
            statusKey = "tooltip.ccpe.engine.status.normal";
            statusColor = ChatFormatting.GREEN;
        }
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.status").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(statusKey).withStyle(statusColor)));

        float shownTemp = tooltipTemp();
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", shownTemp))
                        .withStyle(overheated ? ChatFormatting.RED : ChatFormatting.GOLD)));
        if (overheated)
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.engine.overheated").withStyle(ChatFormatting.RED)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.efficiency").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(efficiency * 100) + "%").withStyle(ChatFormatting.AQUA)));
        // P6：经济区 / 风门（仅流体引擎；蒸汽 Plan B = 恒温自调节、无经济区无风门——经济区行/未装气道提示行都不显示）
        if (!steamEngine) {
            // 经济区（服务端同步系数；未装整合气道 → 提示行；系数 <1 = 在带内省油中）
            if (hasAirDuct) {
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("tooltip.ccpe.engine.economy").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("×" + String.format(Locale.ROOT, "%.2f", (double) lastEconomyFactor))
                                .withStyle(lastEconomyFactor < 1f ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
            } else {
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("tooltip.ccpe.engine.economy").withStyle(ChatFormatting.GRAY))
                        .append(Component.translatable("tooltip.ccpe.engine.no_air_duct").withStyle(ChatFormatting.GOLD)));
            }
            // 风门（整合气道解锁；未装不显示——<1 = 已关小保热）
            if (hasAirDuct) {
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("tooltip.ccpe.engine.cowling").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(Math.round(coolingStrength * 100) + "%")
                                .withStyle(coolingStrength < 1f ? ChatFormatting.AQUA : ChatFormatting.GRAY)));
            }
        }
        // P5：混合比（仅流体引擎；蒸汽引擎无混合比轴，不显示——杆值/高空实际都用服务端同步值，客户端无法可靠算运动体高度）
        if (!steamEngine) {
            float mEff = lastEffectiveMixture;
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.engine.mixture").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(Locale.ROOT, "%.2f", (double) mixture))
                            .withStyle(ChatFormatting.AQUA)));
            if (Math.abs(mEff - mixture) >= 0.005f)
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("tooltip.ccpe.engine.mixture_actual").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format(Locale.ROOT, "%.2f", (double) mEff))
                                .withStyle(ChatFormatting.GOLD)));
        }
        // Lua 控制连接状态（Peripheral.attach/detach 维护，经 NBT 同步客户端）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.lua_control").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(luaConnected ? "tooltip.ccpe.engine.lua_connected"
                        : "tooltip.ccpe.engine.lua_disconnected")
                        .withStyle(luaConnected ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)));
    }

    @Override
    public float getGeneratedSpeed() {
        if (!isController() || !running)
            return 0;
        // 定距桨模型（P4 定稿）：转速 = 油门 × 最大转速（0~256 线性；0 油门 = 停机）
        return GENERATED_SPEED * efficiency;
    }

    /**
     * 应力容量（SU per RPM，Create 语义）：总容量 = 运行燃烧室数 × 4096 × 燃料 stress 倍率，
     * 除以当前转速即为每 RPM 容量（CDG modular engine 同款公式）。
     */
    @Override
    public float calculateAddedStressCapacity() {
        float speed = Math.abs(getGeneratedSpeed());
        if (speed == 0)
            return 0;
        return moduleCapacity / speed;
    }
}
