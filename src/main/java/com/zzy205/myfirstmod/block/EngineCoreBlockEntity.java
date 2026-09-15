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
 *       流体燃料按 burn_ticks_per_bucket 从源罐抽；<b>流体燃料优先，流体不可用时</b>从模块邻居燃料箱
 *       （quick_fill_fuel_vault 等 ItemHandler 容器）自动抽取固体燃料（+物品 burnTime，缓存源坐标 + 冷却重扫，
 *       参考流体源罐模式）；水不可用时暂停（不烧）；</li>
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
    /** 蒸汽室当前流体燃料（缓存，供 drain 使用；P7 = 纯原版解析，无 datapack 条目） */
    protected FluidStack steamFuelFluid = FluidStack.EMPTY;
    /** 蒸汽室流体燃料源罐位置（缓存，失效重扫） */
    protected BlockPos steamFuelSourcePos;
    /** 蒸汽室流体燃料重扫冷却 */
    protected int steamFuelRescanCooldown = 0;
    /** 蒸汽室固体燃料源（燃料箱）位置（缓存，失效重扫；流体燃料不可用时的兜底燃料；各蒸汽室共享） */
    protected BlockPos solidFuelSourcePos;
    /** 固体燃料源重扫冷却 */
    protected int solidFuelRescanCooldown = 0;

    // ---- 蒸汽室燃料显示（Goggle 燃料行；服务端每 tick 计算，NBT 同步客户端，参考 simulated portable_engine）----
    /** 蒸汽室当前燃料类型（服务端权威）："none"（无储备）/"fluid"（最后靠流体 feed 补燃料）/"solid"（最后靠固体 pull 补燃料） */
    protected String steamFuelType = "none";
    /** 当前蒸汽燃料的显示翻译键（流体 = 流体描述 id，如 block.minecraft.lava；固体 = 物品描述 id，如 item.minecraft.coal） */
    protected String steamFuelKey = "";
    /** 蒸汽室固体燃料剩余燃烧 tick（并行燃烧各室同值 = 单个燃料剩余时长；仅固体类型显示剩余时间，流体不显示） */
    protected float steamBurnTicksRemaining = 0f;
    /** 最近一次固体 pull 成功的物品翻译键（储备来源为固体时显示用；pull 时更新） */
    protected String steamSolidFuelKey = "";

    // ---- P3：温度/冷却（牛顿冷却模型，方案见 memo/engine-module.md 关键机制 6） ----
    /** 过热阈值（°C）：T ≥ 此值硬停 */
    public static final float OVERHEAT_TEMP = 200f;
    /** 滞回恢复阈值（°C）：T ≤ 此值才允许重启（0.9×OVERHEAT_TEMP；与「即将过热」预警阈值同值 180） */
    public static final float OVERHEAT_RESUME = 180f;
    /** 海平面高度（Y，主世界默认 63）：Y≤此值环境温度恒 T_AMB_SEA */
    public static final float T_AMB_SEA_Y = 63f;
    /** 海平面环境温度（°C） */
    public static final float T_AMB_SEA = 20f;
    /** 云层高度（Y，主世界默认 200）：环境温度在此降到 0°C 结冰层 */
    public static final float T_AMB_CLOUD_Y = 200f;
    /** 云层环境温度（°C） */
    public static final float T_AMB_CLOUD_TEMP = 0f;
    /** 世界顶高度（Y，主世界默认 320）：环境温度在此恰好到下限 T_AMB_FLOOR */
    public static final float T_AMB_TOP_Y = 320f;
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

    // ---- P6/P7：最佳工作温度经济区（P7：双因素 AND 门控 + 时间解锁进度；方案见 memo/engine-module.md 节 7/10） ----
    /** 经济区最大折扣（×，P7 由 0.8 调高到 0.75 = 省 25%）；双因素持续达标、解锁进度满时达到 */
    public static final float ECO_MIN = 0.75f;
    /** 温度平底窗半径（°C）：|T−T_opt(eff)| ≤ 此值 = 温度达标 */
    public static final float ECO_FLAT = 10f;
    /** 混合比平底窗：|m_eff − 1| ≤ 此值 = 混合比达标（P7；与温度窗并列，双达标才解锁经济） */
    public static final float ECO_MIX_FLAT = 0.05f;
    /** 经济解锁速率（/s）：达标时 ecoProgress += 此值/20，15s 缓慢累积满（保持才奖励，快速掠过不奖励） */
    public static final float ECO_UNLOCK_RATE = 1f / 15f;
    /** 经济流失速率（/s）：不达标时 ecoProgress −= 此值/20，6s 归零（离开窗口快速失去折扣） */
    public static final float ECO_DECAY_RATE = 1f / 6f;
    /** P7 修订：流体引擎设计工作温度（°C，经济目标）——全部引擎固定（不随燃料、不随油门），真实 = 引擎设计点/节温器恒定 */
    public static final float ENGINE_T_OPT = 155f;
    /** 过冷阈值（°C，引擎最低工作温度；与蒸汽暖机门槛 STEAM_MIN_WORK_TEMP 同值）：T < 此值 = 过冷油耗惩罚，先小油门暖机 */
    public static final float ENGINE_MIN_WORK_TEMP = 100f;
    /** 过冷惩罚系数：cold = 1 + COLD_K × max(0, ENGINE_MIN_WORK_TEMP − T) / ENGINE_MIN_WORK_TEMP（20°C ≈×1.24） */
    public static final float COLD_K = 0.3f;
    /** 蒸汽引擎固定最佳工作温度（°C，锅炉设计温度，不随燃料变——真实：蒸汽效率 ∝ 蒸汽温度/压力 [卡诺]） */
    public static final float BOILER_T_OPT = 155f;

    /** 当前是否蒸汽引擎（tick 仲裁后：模块燃烧室为蒸汽型；流体/蒸汽物理排斥 → 互斥）。服务端权威，NBT 同步客户端供 Goggle。 */
    protected boolean steamEngine = false;
    /** 蒸汽暖机中（tick 服务端计算：点火燃烧但温度 < STEAM_MIN_WORK_TEMP，只烧不发电）。NBT 同步客户端供 Goggle。 */
    protected boolean warmingUp = false;
    /** 是否已装整合气道（tick 扫描；P6 门控：解锁经济区/拉稀权/风门）。NBT 同步客户端。 */
    protected boolean hasAirDuct = false;
    /** 服务端每 tick 的当前经济系数（0.75~1.0；无整合气道/停机 → 1.0），NBT 同步客户端 Goggle 显示 */
    protected float lastEconomyFactor = 1f;
    /** P7：经济解锁进度（0~1，服务端权威：双因素持续达标缓慢累积、离开窗口快速流失）。NBT 同步客户端 Goggle 经济行渐入显示 */
    protected float ecoProgress = 0f;
    /** P7：当前最佳工作温度目标（随油门，服务端每 tick 计算）。NBT 同步客户端 Goggle */
    protected float lastOptimalTemp = ENGINE_T_OPT;
    /** P7：当前过冷系数（cold ≥ 1；>1 = 温度低于目标、油耗惩罚中，只乘油耗）。NBT 同步客户端 Goggle */
    protected float lastColdFactor = 1f;
    /** P7：最终油耗系数（杆值 × 经济系数 × 过冷惩罚，服务端每 tick 计算；停机/蒸汽 → 1.0 无意义）。NBT 同步客户端 Goggle 经济行 */
    protected float lastFuelFactor = 1f;
    /** P7：最终发热系数（heatFactor(m_eff)，m_eff = 杆 × 高空自动富油；稀=热 >1 / 富油=凉 <1；蒸汽恒 1.0）。NBT 同步客户端 Goggle */
    protected float lastHeatFactor = 1f;
    /** 冷却强度（风门，0~1，默认 1.0 = 全开；只缩放 K_DUCT 风道散热分量，冲压/气压/环境不动；仅装整合气道后可调，只能降——真实 cowl flap）。NBT 持久化。 */
    protected float coolingStrength = 1f;

    // ---- P6：Lua getActiveFuel 缓存（服务端 tick 更新，供读缓存） ----
    /** 当前活动燃料类型："none" / "fluid" / "steam" */
    protected String activeFuelType = "none";
    /** 当前活动流体燃料 id（蒸汽引擎为空串） */
    protected String activeFuelId = "";
    /** 当前活动燃料的最佳工作温度（°C） */
    protected float activeFuelTopt = ENGINE_T_OPT;

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
        // 过热（流体）为"整机停摆"门控（不发电不消耗）；蒸汽引擎永不过热
        boolean heatAllowed = !overheated;

        // P5/P7：混合比（仅流体引擎；蒸汽引擎无混合比轴——锁 1.0、无自动富油、发热不乘热因子）。
        // P6 进气=拉稀权：无整合气道 → 杆值钳 ≥1.0（不能拉稀省油）；装后开放 0.6~1.4。
        // P7 奖励驱动：m_eff = 杆 × autoRichness 只进发热（heatFactor——高空自动富油 = 只是发热减少/富油降温）
        //   与 eco 混合比窗口（海拔补偿 = 拉到 m_eff≈1.0 吃经济折扣）；完全不进油耗——
        //   油耗只随 杆值 × 经济系数 × 过冷惩罚（高空不拉稀 = ×1.0 无惩罚）。
        boolean airDuct = scan.coolingDucts > 0;
        hasAirDuct = airDuct;
        float leverMixture = airDuct ? mixture : Math.max(1f, mixture);            // 杆值（含拉稀权钳制；油耗直接 × 杆值）
        float effectiveMixture = steamEngine ? 1f : leverMixture * autoRichness(); // 实际混合比（发热/eco 窗口/显示用）
        lastEffectiveMixture = effectiveMixture;
        float mixtureHeatFactor = steamEngine ? 1f : heatFactor(effectiveMixture);
        lastHeatFactor = mixtureHeatFactor; // P7：同步给 Goggle 发热系数行（服务端权威）

        // 流体燃烧室（P1）：燃料表第一个可用流体；每室 consumption mb/s × 效率 × 杆值 × 经济 × 过冷（P7，蒸汽引擎无流体室 → 跳过扫描）
        int runningFluid = 0;
        float fluidCapacity = 0;
        EngineFuels.Entry fluidFuel = heatAllowed && !fluidChambers.isEmpty() ? findFuel(neighbors) : null;
        if (!fluidChambers.isEmpty() && fluidFuel != null && !overstressed) {
            runningFluid = fluidChambers.size();
            fluidCapacity = runningFluid * BASE_STRESS_PER_CHAMBER * fluidFuel.stress();
            // P7 经济区（只省油耗、绝不反哺 Q_heat）：eco = 双因素 AND 门控 + 解锁进度（上 tick 值，1 tick 滞后可忽略）
            // P7 过冷惩罚：cold = 1 + COLD_K×(ENGINE_MIN_WORK_TEMP−T)/ENGINE_MIN_WORK_TEMP（刚开机低温 = 油耗惩罚，小油门暖机；只乘油耗）
            // P7 奖励驱动：油耗 = 杆值 × eco × cold（自动富油/heatFactor 不进油耗；高空不拉稀 = ×1.0 无惩罚）
            float coldPen = coldPenalty(temperature);
            fuelDebt += runningFluid * fluidFuel.consumption() * efficiency * leverMixture
                    * (1f - (1f - ECO_MIN) * ecoProgress) * coldPen / 20f;
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

        // 蒸汽动力室（P2/P2.5）：水 + 燃料（burnTick 制，水在才烧；水不可用整类暂停）。
        // 燃料来源：流体燃料优先（P7 纯原版解析：桶物品熔炉燃烧时长）；流体不可用 → 从模块邻居
        // 燃料箱（quick_fill_fuel_vault 等 ItemHandler 容器）自动抽取 1 个熔炉燃料物品（固体兜底）。
        // 燃烧倒计时独立于燃料源：燃料箱/源罐被拆时，已有储备（burnTicks）仍继续燃烧，
        // 倒计时结束才尝试再抽，抽不到才停烧（不因源消失立即停机）。
        int runningSteam = 0;
        boolean waterOk = !overstressed && heatAllowed && waterAvailable(neighbors);
        int steamFuelBurnTicks = heatAllowed && !steamChambers.isEmpty() ? findSteamFuelBurnTicks(neighbors) : 0;
        // 固体燃料可用性：仅流体不可用时才判定（流体优先）；缓存燃料箱逻辑 = 自动抽取流缓存储罐同款
        // （源坐标 + 冷却重扫，见 solidFuelAvailable）
        boolean steamSolidFuelOk = steamFuelBurnTicks <= 0 && heatAllowed && !steamChambers.isEmpty()
                && solidFuelAvailable(neighbors);
        // 门控 = 水 OK 且（流体可用 || 固体可用 || 任一室仍有储备）——储备倒计时独立于燃料源存在性
        boolean steamHasReserve = hasSteamReserve(steamChambers);
        if (waterOk && (steamFuelBurnTicks > 0 || steamSolidFuelOk || steamHasReserve)) {
            // P6 Plan B：蒸汽无经济区（温度钉在 BOILER_T_OPT 自调节 → 无折扣无惩罚，系数恒 1.0）
            // P2.5 并行燃烧：N = 蒸汽室个数，一次抽 N 个燃料（不足不抽），每室 1 个同燃同熄——
            // 燃烧时长 = 单个燃料的时长（每室独立 burnTicks 倒计时，节奏相同）。
            // 蒸汽消耗固定（不随油门缩放，memo §8："1 tick 烧 1 burnTick" + 水 1mb/s/室，无折扣）：
            // 油门只缩出力（容量/转速）；油门 0 = 停机不烧油（储备冻结，不烧不抽不耗水）。
            if (efficiency > 0f) {
                if (steamFuelBurnTicks > 0) {
                    // 流体燃料（P7 纯原版解析）：固定熔炉速率——每 tick 每室消耗 1000/原版桶燃烧时长 mb
                    // （熔岩桶 20000 → 0.05mb/t = 1mb/s，1mb = 20 burnTick）；累计 ≥1mb 从源罐抽
                    for (BlockPos sp : steamChambers) {
                        if (!(level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be))
                            continue;
                        be.fluidFuelDebt += 1000f / steamFuelBurnTicks;
                        while (be.fluidFuelDebt >= 1f) {
                            if (drainSteamFluidFuel(1)) {
                                be.fluidFuelDebt -= 1f;
                                be.burnTicks += Math.max(1, Math.round(steamFuelBurnTicks / 1000f));
                                steamFuelType = "fluid"; // 储备来源 = 流体（Goggle 燃料行）
                                be.setChanged();
                            } else {
                                be.fluidFuelDebt = 0;
                                break;
                            }
                        }
                    }
                } else if (steamAllChambersEmpty(steamChambers)) {
                    // 固体燃料并行补料（流体不可用 + 全部室同时空炉）：一次抽 N 个熔炉燃料物品，
                    // 每室 +1 个物品的 burnTime；源不足 N 个 → 不抽取（整组断供不拆零）
                    tryPullSolidFuelBatch(steamChambers);
                }
                // 燃烧：各室并行倒计时（固定 1 个 burnTick/tick = 原版熔炉速率，同燃同熄；
                // Goggle 燃料行剩余秒数 = burnTicks/20 即真实墙钟秒数）
                for (BlockPos sp : steamChambers) {
                    if (!(level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be))
                        continue;
                    if (be.burnTicks > 0) {
                        be.burnTicks -= 1f;
                        if (be.burnTicks <= 0)
                            be.setChanged();
                        runningSteam++;
                    }
                }
                if (runningSteam > 0) {
                    waterDebt += runningSteam * 0.05f; // 固定 1mb/s/室 = 0.05mb/t（不随油门缩放）
                    while (waterDebt >= 1f) {
                        if (drainWater(1)) {
                            waterDebt -= 1f;
                        } else {
                            waterDebt = 0;
                            break;
                        }
                    }
                }
            } else {
                // 油门 0：停机不烧油——储备冻结（不烧不抽不耗水），仅统计有储备的室数（状态显示用）
                for (BlockPos sp : steamChambers) {
                    if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be && be.burnTicks > 0)
                        runningSteam++;
                }
            }
        }

        // 蒸汽室燃料显示数据（服务端每 tick；同步客户端 Goggle 燃料行）
        updateSteamFuelDisplay(steamChambers);

        int runningTotal = runningFluid + runningSteam;
        // P6 Plan B：蒸汽锅炉暖机门控——T ≥ STEAM_MIN_WORK_TEMP 才「开始工作」（低于阈值只烧不发电，热机过程）
        boolean steamReady = !steamEngine || temperature >= STEAM_MIN_WORK_TEMP;
        // P2.5：蒸汽运行条件 = 油门开启且温度 > 100（不再要求有燃料储备）——燃料耗尽但锅炉仍热
        // （T ≥ 100°C）时靠余热继续发电，温度冷却到阈值以下才停机；流体引擎维持原条件（有运行燃烧室）。
        // P4：throttle=0（效率=0）→ 停机（不发电；throttle=0 时容量/消耗/发热全为 0，避免"空转"假象）
        running = !overstressed && !overheated && efficiency > 0f && steamReady
                && (runningTotal > 0 || steamEngine);
        // 蒸汽容量 = 运行状态下的全部蒸汽室（余热运转同样满出力，容量不受燃料储备限制）；暖机/停机 = 0
        float steamCapacityChambers = steamEngine && running ? steamChambers.size()
                : (steamReady ? runningSteam : 0);
        moduleCapacity = (fluidCapacity + steamCapacityChambers * STEAM_STRESS_PER_CHAMBER) * efficiency;

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

        // P7：经济系数（双因素 AND 门控 + 解锁进度；仅流体引擎；蒸汽无经济区恒 1.0；停机/油门 0 → 进度流失、1.0 无意义）
        // P7 修订：温度阈值全部引擎固定（经济 ENGINE_T_OPT=155 / 过冷 ENGINE_MIN_WORK_TEMP=100），不随燃料/油门
        if (!steamEngine && running && runningFluid > 0 && fluidFuel != null) {
            lastOptimalTemp = ENGINE_T_OPT;
            lastColdFactor = coldPenalty(temperature);
            ecoProgress = updateEcoProgress(ecoProgress, temperature, ENGINE_T_OPT, effectiveMixture, true);
        } else {
            lastColdFactor = 1f;
            ecoProgress = updateEcoProgress(ecoProgress, temperature, ENGINE_T_OPT, effectiveMixture, false);
        }
        lastEconomyFactor = 1f - (1f - ECO_MIN) * ecoProgress;
        // P7：最终油耗系数（= 杆值 × 经济 × 过冷，服务端权威同步给 Goggle 经济行；停机/蒸汽 → 1.0 无意义）
        lastFuelFactor = (!steamEngine && running && runningFluid > 0 && fluidFuel != null)
                ? leverMixture * lastEconomyFactor * lastColdFactor : 1f;
        // P6 Plan B：蒸汽暖机标志（点火燃烧但未达工作温度；油门 0 不点火 → false；供 Goggle 状态行 / Lua isWarmingUp）
        warmingUp = steamEngine && runningSteam > 0 && efficiency > 0f && temperature < STEAM_MIN_WORK_TEMP;
        // P6：getActiveFuel 缓存（当前活动燃料 + 其 T_opt）
        if (runningFluid > 0 && fluidFuel != null) {
            activeFuelType = "fluid";
            activeFuelId = fluidFuel.fluid().toString();
            activeFuelTopt = ENGINE_T_OPT;
        } else if (runningSteam > 0) {
            activeFuelType = "steam";
            activeFuelId = "";
            activeFuelTopt = BOILER_T_OPT;
        } else {
            activeFuelType = "none";
            activeFuelId = "";
            activeFuelTopt = ENGINE_T_OPT;
        }

        // P7：tooltip 数据（经济解锁进度/过冷/最佳温度/混合比实际值等）改为<b>每 tick 同步</b>（20Hz，照 simulated velocity_sensor）——
        // 差量发包优化（温度 ≥SYNC_TEMP_DELTA 才 sendData + 客户端趋势外推，原 1/20 带宽方案）列入后续计划（见 memo/WORK RECORD.md）。
        // reActivateSource 只在运行态/容量变化时置位（避免每 tick 触发传动网络重激活）；sendData 每 tick 保证 tooltip 实时
        if (running != prevRunning || !Mth.equal(moduleCapacity, prevCapacity)) {
            reActivateSource = true;
        }
        setChanged();
        lastSyncedTemp = temperature;
        sendData();

        if (DEBUG) {
            if (running != prevRunning) {
                LOGGER.info("[EngineCore] {} running {} -> {} | fluid={} steam={} fuel={} water={} steamFuel={} solid={} T={}℃ overheated={} capacity={}",
                        worldPosition, prevRunning, running, runningFluid, runningSteam,
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterOk, steamFuelBurnTicks,
                        solidFuelSourcePos != null, temperature, overheated, moduleCapacity);
            } else if (!running && level.getGameTime() % 40 == 0) {
                LOGGER.info("[EngineCore] {} idle | fluid={} steam={} fuel={} water={} steamFuel={} solid={} T={}℃ overheated={} ducts={} len={} speed={}",
                        worldPosition, fluidChambers.size(), steamChambers.size(),
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterOk, steamFuelBurnTicks,
                        solidFuelSourcePos != null, temperature, overheated,
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

    /** 找蒸汽室当前可用流体燃料（P7 纯原版解析：桶物品原版熔炉燃烧时长 > 0）：缓存优先，失效重扫（带冷却）。
     *  返回每桶燃烧 tick 数（熔岩桶 = 20000）；无可用燃料返回 0。不查 engine_fuel datapack（那是流体燃烧室的燃料表）。 */
    protected int findSteamFuelBurnTicks(List<BlockPos> neighbors) {
        if (steamFuelSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(steamFuelSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty()) {
                    int burn = EngineFuels.vanillaBucketBurnTicks(BuiltInRegistries.FLUID.getKey(stack.getFluid()));
                    if (burn > 0) {
                        steamFuelFluid = stack.copy();
                        return burn;
                    }
                }
            }
            steamFuelSourcePos = null;
        }

        if (steamFuelRescanCooldown > 0) {
            steamFuelRescanCooldown--;
            return 0;
        }
        steamFuelRescanCooldown = 10;

        // 扫模块邻居储罐：桶物品熔炉燃烧时长 > 0 的流体中，选燃烧最久者（原版燃料值权威）
        int bestBurn = 0;
        for (BlockPos pos : neighbors) {
            IFluidHandler handler = fluidHandlerAt(pos);
            if (handler == null)
                continue;
            FluidStack stack = handler.getFluidInTank(0);
            if (stack.isEmpty())
                continue;
            int burn = EngineFuels.vanillaBucketBurnTicks(BuiltInRegistries.FLUID.getKey(stack.getFluid()));
            if (burn > bestBurn) {
                bestBurn = burn;
                steamFuelSourcePos = pos;
                steamFuelFluid = stack.copy();
            }
        }
        return bestBurn;
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

    /** 从指定位置取 ItemHandler 能力（模块邻居容器：quick_fill_fuel_vault 等；只走 capability，绝不直接改容器 BE） */
    protected IItemHandler itemHandlerAt(BlockPos pos) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    /** 容器内是否存在熔炉燃料物品（原版 burnTime > 0；熔岩桶 20000、煤炭 1600 等） */
    private boolean hasBurnableFuel(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getBurnTime(RecipeType.SMELTING) > 0)
                return true;
        }
        return false;
    }

    /**
     * 蒸汽室固体燃料是否可用（仅流体燃料不可用时才调用——流体优先）：
     * 优先复用缓存的燃料箱；源失效/已空则带冷却重扫（同 {@code steamFuelSourcePos} 模式）。
     * 源 = 模块邻居中带 ItemHandler 且含熔炉燃料物品（burnTime > 0）的容器（quick_fill_fuel_vault 等）。
     */
    protected boolean solidFuelAvailable(List<BlockPos> neighbors) {
        if (solidFuelSourcePos != null) {
            IItemHandler handler = itemHandlerAt(solidFuelSourcePos);
            if (handler != null && hasBurnableFuel(handler))
                return true;
            solidFuelSourcePos = null;
        }

        if (solidFuelRescanCooldown > 0) {
            solidFuelRescanCooldown--;
            return false;
        }
        solidFuelRescanCooldown = 10;

        for (BlockPos pos : neighbors) {
            IItemHandler handler = itemHandlerAt(pos);
            if (handler == null)
                continue;
            if (hasBurnableFuel(handler)) {
                solidFuelSourcePos = pos;
                return true;
            }
        }
        return false;
    }

    /**
     * 任一蒸汽室是否仍有燃料储备（burnTicks > 0）。储备 = 燃烧倒计时，独立于燃料源存在性——
     * 燃料箱/源罐被拆或无源时，储备仍应继续燃烧；倒计时结束且抽不到燃料才停烧。
     */
    private boolean hasSteamReserve(List<BlockPos> steamChambers) {
        for (BlockPos sp : steamChambers) {
            if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be && be.burnTicks > 0)
                return true;
        }
        return false;
    }

    /**
     * 蒸汽室燃料显示数据（服务端每 tick，同步客户端 Goggle 燃料行，参考 simulated portable_engine）：
     * 储备来源 = 最后一次补燃料的方式（流体 feed / 固体批量 pull），由 {@link #steamFuelType} 标记；
     * 任何室有储备（burnTicks>0）才显示燃料，否则"无"。
     * 并行燃烧：各室 burnTicks 同值，剩余时间 = 单个燃料的时长（取最大即可，客户端换算秒数）；
     * 数量 = 蒸汽室个数（客户端 scanModule 算）。流体不显示时间（用户要求）。
     */
    private void updateSteamFuelDisplay(List<BlockPos> steamChambers) {
        float maxBurn = 0f;
        boolean anyReserve = false;
        for (BlockPos sp : steamChambers) {
            if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be) {
                maxBurn = Math.max(maxBurn, be.burnTicks);
                if (be.burnTicks > 0)
                    anyReserve = true;
            }
        }
        if (!anyReserve) {
            steamFuelType = "none";
            steamFuelKey = "";
            steamBurnTicksRemaining = 0f;
            return;
        }
        if (steamFuelType.equals("fluid") && !steamFuelFluid.isEmpty()) {
            steamFuelKey = steamFuelFluid.getFluid().getFluidType().getDescriptionId();
            steamBurnTicksRemaining = 0f;
        } else if (steamFuelType.equals("solid")) {
            steamFuelKey = steamSolidFuelKey;
            steamBurnTicksRemaining = maxBurn; // 并行燃烧：各室同值，取最大 = 单个燃料剩余时长
        } else {
            // 有储备但来源未知（旧档/首个 tick 前）→ 显示"无"（下一 tick 补燃料后立即修正）
            steamFuelKey = "";
            steamBurnTicksRemaining = 0f;
        }
    }

    /**
     * 全部蒸汽室是否同时空炉（burnTicks 全部 ≤ 0）——并行燃烧的补料时机：同燃同熄，
     * 一整个并行周期结束才批量补料（每室 1 个，数量 = 蒸汽室个数）。
     */
    private boolean steamAllChambersEmpty(List<BlockPos> steamChambers) {
        for (BlockPos sp : steamChambers) {
            if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be && be.burnTicks > 0)
                return false;
        }
        return true;
    }

    /**
     * 并行燃烧批量补固体燃料：全部蒸汽室同时空炉时，一次从燃料箱抽 N 个（N = 蒸汽室个数）
     * 熔炉燃料物品（burnTime > 0），每个室 +1 个物品的 burnTime——各室并行燃烧同一燃料，
     * 燃烧时长 = 单个燃料的时长；<b>源不足 N 个 → 不抽取</b>（整组断供不拆零，下一 tick 重试）。
     * <p>调用前提：流体燃料不可用（流体优先）且全室空炉；油门 0 不抽（停机不烧油）。
     * 只走 capability，绝不直接改容器 BE；源 = 缓存的燃料箱（{@code solidFuelSourcePos}，
     * 由 {@link #solidFuelAvailable} 维护）。源已空/已拆 → 失效缓存（下一 tick 重扫）。</p>
     */
    protected boolean tryPullSolidFuelBatch(List<BlockPos> steamChambers) {
        if (steamChambers.isEmpty())
            return false;
        if (solidFuelSourcePos == null)
            return false;
        IItemHandler handler = itemHandlerAt(solidFuelSourcePos);
        if (handler == null) {
            solidFuelSourcePos = null;
            return false;
        }
        int need = steamChambers.size();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty())
                continue;
            int burnTime = stack.getBurnTime(RecipeType.SMELTING);
            if (burnTime <= 0)
                continue;
            // 不足 N 个不抽取（模拟先验；不足 → 视为断供，不拆零）
            ItemStack simulated = handler.extractItem(slot, need, true);
            if (simulated.getCount() < need)
                return false;
            ItemStack extracted = handler.extractItem(slot, need, false);
            if (extracted.getCount() < need) { // 竞态兜底
                solidFuelSourcePos = null;
                return false;
            }
            // 每室 +1 个物品的 burnTime（并行燃烧：每室 1 个，同燃同熄）
            for (BlockPos sp : steamChambers) {
                if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be) {
                    be.burnTicks += burnTime;
                    be.setChanged();
                }
            }
            steamFuelType = "solid"; // 储备来源 = 固体（Goggle 燃料行）
            steamSolidFuelKey = extracted.getDescriptionId();
            return true;
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

    /**
     * 环境温度（°C）：按 Minecraft 尺度分段线性（不再套真实对流层 0.0065°C/m —— 那在 63~320 的
     * 世界里整段只降 ~1.7°C，高度几乎无温度差异）：
     *   Y ≤ 63（海平面）    → 恒 20°C
     *   63 → 200（云层）    → 20°C 线性降到 0°C（结冰层，云层生成高度）
     *   200 → 320（世界顶） → 0°C 线性降到 −40°C（下限）
     *   Y ≥ 320            → 恒 −40°C
     */
    protected float ambientTemp() {
        float y = engineAltitude();
        if (y <= T_AMB_SEA_Y)
            return T_AMB_SEA;
        if (y >= T_AMB_TOP_Y)
            return T_AMB_FLOOR;
        if (y <= T_AMB_CLOUD_Y) {
            float t = (y - T_AMB_SEA_Y) / (T_AMB_CLOUD_Y - T_AMB_SEA_Y);
            return T_AMB_SEA + (T_AMB_CLOUD_TEMP - T_AMB_SEA) * t;
        }
        float t = (y - T_AMB_CLOUD_Y) / (T_AMB_TOP_Y - T_AMB_CLOUD_Y);
        return T_AMB_CLOUD_TEMP + (T_AMB_FLOOR - T_AMB_CLOUD_TEMP) * t;
    }

    // ---- P5：混合比辅助（方案见 memo/engine-module.md 关键机制 7）----

    /**
     * 自动富油系数：实际混合比 = 杆 × autoRichness()。
     * 现实：化油器按进气体积配油 → 气压低（空气稀）→ 空燃比天然变浓。
     * P7 奖励驱动：autoRichness 只进发热（heatFactor → 高空富油降温 ×0.875）与 eco 混合比窗口，
     * <b>不进油耗公式</b>——油耗只随 杆值 × 经济系数 × 过冷惩罚（高空不拉稀 = ×1.0 无惩罚）。
     */
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
     * P7 经济解锁进度（双因素 AND 门控 + 时间积分）：温度与混合比<b>同时</b>持续在平底窗内
     * （|T−ENGINE_T_OPT|≤ECO_FLAT ∧ |m_eff−1|≤ECO_MIX_FLAT）→ 缓慢累积（ECO_UNLOCK_RATE，15s 满）；
     * 离开窗口/停机 → 快速流失（ECO_DECAY_RATE，6s 归零）。保持才奖励、快速掠过不奖励；
     * 混合比不对 = 无奖励也无惩罚（温度控得再好也没用）。
     * P7 修订：经济系数<b>不以整合气道为门控</b>（无气道同样生效）——整合气道只门控 setMixture/setCooling Lua。
     */
    protected float updateEcoProgress(float progress, float temp, float tOptEff, float mEff,
                                      boolean runningFluid) {
        boolean satisfied = runningFluid
                && Math.abs(temp - tOptEff) <= ECO_FLAT
                && Math.abs(mEff - 1f) <= ECO_MIX_FLAT;
        if (satisfied)
            return Math.min(1f, progress + ECO_UNLOCK_RATE / 20f);
        return Math.max(0f, progress - ECO_DECAY_RATE / 20f);
    }

    /**
     * P7 过冷惩罚（只乘油耗、绝不反哺 Q_heat）：cold = 1 + COLD_K × max(0, ENGINE_MIN_WORK_TEMP − T) / ENGINE_MIN_WORK_TEMP。
     * T ≥ 100°C（引擎最低工作温度，引擎固定）无惩罚；刚开机 20°C ≈×1.24——玩家先小油门暖机（绝对油耗小、损失轻）再推油门。
     * 阈值引擎固定（不随燃料/油门），真实 = 最低工作油温。
     */
    protected float coldPenalty(float temp) {
        float gap = Math.max(0f, ENGINE_MIN_WORK_TEMP - temp);
        return 1f + COLD_K * gap / ENGINE_MIN_WORK_TEMP;
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
         * 高空/低压下 > 杆值（空气稀 → 化油器按体积配油 → 天然变浓）。P7：自动富油只降温不进油耗——
         * 该读数是经济系数混合比窗口（m_eff≈1.0）与发热的反馈：海拔补偿 = 拉到 m_eff≈1.0 吃经济折扣。
         * 海平面 = 杆值。蒸汽引擎无混合比轴 → 恒 1.0。
         */
        @LuaFunction
        public final double getEffectiveMixture() {
            return steamEngine ? 1.0 : lastEffectiveMixture;
        }

        /**
         * 设置混合比（0.6~1.4，越界钳制；非法参数返回 false）。
         * P5/P7 经济性/热管理杆：只影响<b>油耗</b>（P7：×杆值，自动富油不进油耗）与<b>温度</b>（×凸热因子，m_eff=杆×autoRichness）——
         * 稀=省油但更热，浓=费油但降温；应力/转速完全不受影响。
         * P7 奖励驱动：杆 1.0 = 出厂标定（任何高度油耗 ×1.0 无惩罚）；高空自动富油只降温（进 heatFactor）；
         * 经济系数 = 温度+实际混合比双因素（m_eff≈1.0 才解锁）——海拔补偿 = 拉到 m_eff≈1.0 吃经济折扣。
         * 可用 {@link #getEffectiveMixture()} 读实际值做海拔补偿校正。
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
         * P7：当前经济系数（0.75~1.0；无整合气道 / 停机 / 油门 0 → 1.0 无意义）。
         * 服务端每 tick 计算并同步（读缓存，≤1 tick 滞后）；温度与<b>实际混合比</b>双因素持续达标
         * （|T−T_opt(eff)|≤10 ∧ |m_eff−1|≤0.05）→ 解锁进度缓慢累积（15s 满）渐入 0.75，离开窗口 6s 流失；
         * 混合比不对 → 无奖励无惩罚。
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
         * P7 修订：optimalTemp = 引擎固定设计点（流体 ENGINE_T_OPT=155；蒸汽 BOILER_T_OPT=155），不随燃料/油门。
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
        // 新放置的核心会以自身为锚点组网：放在原 controller 外侧时将成为新 controller，
        // 必须先继承相邻引擎 controller 的运行状态，否则温度/油门/混合比等全部重置（见 adoptAdjacentEngineState）
        adoptAdjacentEngineState();
        ConnectivityHandler.formMulti(this);
    }

    /**
     * 延长引擎时，若本核心（新放置）将取代相邻引擎成为 controller，先继承其 controller 的运行状态。
     * <p>Create {@code ConnectivityHandler.formMulti} 以调用它的方块为锚点、沿轴正方向扫描组网：
     * 新核心放在原 controller 外侧（负方向端）时，其正方向扫描覆盖整条旧引擎 → 新核心成为新 controller；
     * 而引擎运行状态只存在 controller BE 内存字段里、无迁移机制（未实现 getExtraData/setExtraData），
     * 不继承就会全部重置。仅在 {@link #updateConnectivity()}（新放置核心路径）中调用；
     * 两侧都有引擎（合并场景）时取更长的作为捐赠者。</p>
     */
    private void adoptAdjacentEngineState() {
        if (level == null || level.isClientSide)
            return;
        EngineCoreBlockEntity donor = null;
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() != getMainConnectionAxis())
                continue;
            if (!(level.getBlockEntity(worldPosition.relative(dir)) instanceof EngineCoreBlockEntity core))
                continue;
            EngineCoreBlockEntity controller = core.getControllerBE();
            if (controller == null || controller == this)
                continue;
            if (donor == null || controller.length > donor.length)
                donor = controller;
        }
        if (donor != null)
            adoptStateFrom(donor);
    }

    /** 从捐赠者（旧 controller）拷贝整条引擎的运行状态到本核心（新 controller，刚放置、全默认值）。
     *  不拷贝：running/moduleCapacity（本 tick 立即重算）、length（formMulti 设置）、
     *  luaConnected（外设挂载是逐 BE 的运行时瞬态，新 controller 未挂电脑）、客户端温度外推字段。 */
    private void adoptStateFrom(EngineCoreBlockEntity donor) {
        temperature = donor.temperature;
        efficiency = donor.efficiency;
        mixture = donor.mixture;
        coolingStrength = donor.coolingStrength;
        ecoProgress = donor.ecoProgress;
        lastEffectiveMixture = donor.lastEffectiveMixture;
        lastEconomyFactor = donor.lastEconomyFactor;
        lastColdFactor = donor.lastColdFactor;
        lastFuelFactor = donor.lastFuelFactor;
        lastHeatFactor = donor.lastHeatFactor;
        lastOptimalTemp = donor.lastOptimalTemp;
        overheated = donor.overheated;
        warmingUp = donor.warmingUp;
        steamEngine = donor.steamEngine;
        hasAirDuct = donor.hasAirDuct;
        fuelDebt = donor.fuelDebt;
        waterDebt = donor.waterDebt;
        fuelFluid = donor.fuelFluid.copy();
        steamFuelFluid = donor.steamFuelFluid.copy();
        fuelSourcePos = donor.fuelSourcePos;
        waterSourcePos = donor.waterSourcePos;
        steamFuelSourcePos = donor.steamFuelSourcePos;
        solidFuelSourcePos = donor.solidFuelSourcePos;
        steamFuelType = donor.steamFuelType;
        steamFuelKey = donor.steamFuelKey;
        steamBurnTicksRemaining = donor.steamBurnTicksRemaining;
        steamSolidFuelKey = donor.steamSolidFuelKey;
        activeFuelType = donor.activeFuelType;
        activeFuelId = donor.activeFuelId;
        activeFuelTopt = donor.activeFuelTopt;
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
        // P7：经济解锁进度 / 最佳温度 / 过冷系数（旧存档无字段 → 默认值）
        ecoProgress = compound.contains("EcoProgress") ? compound.getFloat("EcoProgress") : 0f;
        lastOptimalTemp = compound.contains("OptimalTemp") ? compound.getFloat("OptimalTemp") : ENGINE_T_OPT;
        lastColdFactor = compound.contains("ColdFactor") ? compound.getFloat("ColdFactor") : 1f;
        lastFuelFactor = compound.contains("FuelFactor") ? compound.getFloat("FuelFactor") : 1f;
        lastHeatFactor = compound.contains("HeatFactor") ? compound.getFloat("HeatFactor") : 1f;
        // 蒸汽室燃料显示（服务端每 tick 计算；旧存档无字段 → 默认值）
        steamFuelType = compound.contains("SteamFuelType") ? compound.getString("SteamFuelType") : "none";
        steamFuelKey = compound.contains("SteamFuelKey") ? compound.getString("SteamFuelKey") : "";
        steamBurnTicksRemaining = compound.contains("SteamBurnTicks") ? compound.getFloat("SteamBurnTicks") : 0f;
        steamSolidFuelKey = compound.contains("SteamSolidFuelKey") ? compound.getString("SteamSolidFuelKey") : "";
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
        compound.putFloat("Mixture", mixture);
        // 实际混合比（含高空自动富油）服务端权威值，同步给客户端 Goggle 显示
        compound.putFloat("EffectiveMixture", lastEffectiveMixture);
        // P6：蒸汽类型 / 整合气道 / 当前经济系数（服务端每 tick 计算，同步客户端 Goggle；客户端不重算）
        compound.putBoolean("SteamEngine", steamEngine);
        compound.putBoolean("WarmingUp", warmingUp);
        compound.putBoolean("AirDuct", hasAirDuct);
        compound.putFloat("EconomyFactor", lastEconomyFactor);
        compound.putFloat("CoolingStrength", coolingStrength);
        // P7：经济解锁进度 / 最佳温度（随油门）/ 过冷系数（服务端每 tick 计算，同步客户端 Goggle；客户端不重算）
        compound.putFloat("EcoProgress", ecoProgress);
        compound.putFloat("OptimalTemp", lastOptimalTemp);
        compound.putFloat("ColdFactor", lastColdFactor);
        compound.putFloat("FuelFactor", lastFuelFactor);
        compound.putFloat("HeatFactor", lastHeatFactor);
        // 蒸汽室燃料显示（服务端每 tick 计算，同步客户端 Goggle 燃料行；客户端不重算）
        compound.putString("SteamFuelType", steamFuelType);
        compound.putString("SteamFuelKey", steamFuelKey);
        compound.putFloat("SteamBurnTicks", steamBurnTicksRemaining);
        compound.putString("SteamSolidFuelKey", steamSolidFuelKey);
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

    /** 引擎核心 tooltip：温度 + 总应力输出 + 目前转速 + 连接的模块清单 */
    private void addCoreTooltip(List<Component> tooltip) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));
        // 温度（客户端趋势外推平滑值；过热锁定时红色）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", tooltipTemp()))
                        .withStyle(overheated ? ChatFormatting.RED : ChatFormatting.GOLD)));
        // 总应力输出（用户要求显示产生的总应力；服务端同步 moduleCapacity——客户端拿不到燃料表 stress 倍率，不自算）
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
        // 燃料（正在使用的燃料；固体显示剩余燃烧时间，流体不显示——参考 simulated portable_engine）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.fuel").withStyle(ChatFormatting.GRAY))
                .append(steamFuelValueComponent()));
    }

    /** 蒸汽室燃料行值：无储备 → 红"无"；流体 → 绿燃料名（不显示时间/数量）；固体 → 绿"燃料名 xN" + 青" (时间)"
     *  （并行燃烧：数量 = 蒸汽室个数，时间 = 单个燃料剩余时长 burnTicks/20，各室同值）。
     *  燃料名/时间来自服务端同步（客户端不重算），数量用客户端 scanModule 轻扫（同核心 tooltip 模块清单）。 */
    private Component steamFuelValueComponent() {
        if (steamFuelType.equals("fluid") && !steamFuelKey.isEmpty())
            return Component.translatable(steamFuelKey).withStyle(ChatFormatting.GREEN);
        if (steamFuelType.equals("solid") && !steamFuelKey.isEmpty()) {
            int chambers = scanModule().steamChambers().size();
            Component name = Component.translatable(steamFuelKey)
                    .append(Component.literal(" x" + chambers))
                    .withStyle(ChatFormatting.GREEN);
            if (steamBurnTicksRemaining > 0) {
                // 剩余时间 = 单个燃料燃烧 tick / 20（原版熔炉速率秒，参考 portable_engine.getTime；与效率无关）
                int seconds = Math.max(0, Math.round(steamBurnTicksRemaining / 20f));
                name = name.copy().append(Component.literal(" (" + formatBurnTime(seconds) + ")")
                        .withStyle(ChatFormatting.AQUA));
            }
            return name;
        }
        return Component.translatable("tooltip.ccpe.engine.fuel_none").withStyle(ChatFormatting.RED);
    }

    /** 秒数格式化（照抄 simulated portable_engine 的 getTime）：Xh Ym Zs（小时/分钟省略前导零规则一致） */
    private static String formatBurnTime(int sec) {
        String s = "";
        int min = sec / 60;
        int hour = min / 60;
        sec = Math.floorMod(sec, 60);
        min = Math.floorMod(min, 60);
        if (hour > 0)
            s += hour + "h ";
        if (min < 10 && hour > 0)
            s += 0;
        if (min > 0 || hour > 0)
            s += min + "m ";
        if (sec < 10 && min > 0)
            s += 0;
        s += sec + "s";
        return s;
    }

    /** 整合气道 tooltip：温度 + 冷却（= setCooling 风门百分比） */
    private void addAirDuctTooltip(List<Component> tooltip) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", tooltipTemp()))
                        .withStyle(overheated ? ChatFormatting.RED : ChatFormatting.GOLD)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.cooling").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(coolingStrength * 100) + "%")
                        .withStyle(coolingStrength < 1f ? ChatFormatting.AQUA : ChatFormatting.GRAY)));
    }

    /** 全量引擎 tooltip（流体燃烧室悬停，保持既有行为不变） */
    private void addFullEngineTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));

        // 不调用 super.addToGoggleTooltip：Create 动力方默认 goggle 会加「容量提供 / 应力影响」行，
        // 用户要求精简 tooltip —— 全部应力条目移除（核心 tooltip 的应力行也已移除）。
        // 状态行档位（仅流体引擎；蒸汽走暖机/正常）：停机（!running：油门 0/缺燃料/缺水/过载）/
        // 过冷(<~97) / 正常(97~145) / 高效(145~165 经济带) / 正常(165~180) / 即将过热(180~200) / 过热锁定(≥200，滞回 180 解锁)
        String statusKey;
        ChatFormatting statusColor;
        if (overheated) {
            statusKey = "tooltip.ccpe.engine.status.overheated";
            statusColor = ChatFormatting.RED;
        } else if (warmingUp) {
            // 蒸汽锅炉暖机中（点火燃烧但未达工作温度，只烧不发电）
            statusKey = "tooltip.ccpe.engine.status.warming";
            statusColor = ChatFormatting.GOLD;
        } else if (!running) {
            // 停机：油门 0 / 缺燃料 / 缺水 / 过载等（不发电不消耗；温度档位无意义，优先显示停机）
            statusKey = "tooltip.ccpe.engine.status.stopped";
            statusColor = ChatFormatting.GRAY;
        } else if (temperature >= OVERHEAT_TEMP * 0.9f) {
            // 即将过热：T ≥ 180（0.9×200），与过热滞回解锁阈值同值
            statusKey = "tooltip.ccpe.engine.status.warning";
            statusColor = ChatFormatting.GOLD;
        } else if (!steamEngine && temperature >= ENGINE_T_OPT - ECO_FLAT
                && temperature <= ENGINE_T_OPT + ECO_FLAT) {
            // P7：高效 = 经济带 |T−155|≤10（145~165），仅流体引擎
            statusKey = "tooltip.ccpe.engine.status.efficient";
            statusColor = ChatFormatting.AQUA;
        } else if (!steamEngine && lastColdFactor > 1.01f) {
            // P7：过冷状态（温度低于最低工作温度、油耗惩罚中——先小油门暖机；仅流体引擎）
            statusKey = "tooltip.ccpe.engine.status.cold";
            statusColor = ChatFormatting.BLUE;
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
                .append(Component.translatable("tooltip.ccpe.engine.throttle").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(efficiency * 100) + "%").withStyle(ChatFormatting.AQUA)));
        // 油耗 / 发热系数（仅流体引擎；蒸汽 Plan B = 恒温自调节、无这两行）
        if (!steamEngine) {
            // 油耗（服务端同步「最终油耗系数」= 杆值 × 经济系数 × 过冷惩罚，即除油门/自动富油外的全部油耗因子；<1 = 省油中）
            // P7 修订：经济系数/油耗不以整合气道为门控（无气道同样生效，过冷/经济区无气道可达）——气道只门控 setMixture/setCooling Lua
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.engine.economy").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("×" + String.format(Locale.ROOT, "%.2f", (double) lastFuelFactor))
                            .withStyle(lastFuelFactor < 1f ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
            // P7：发热系数（heatFactor(m_eff)，m_eff = 杆 × 高空自动富油；稀=热 >1，富油=凉 <1）
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.engine.heat_factor").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("×" + String.format(Locale.ROOT, "%.2f", (double) lastHeatFactor))
                            .withStyle(lastHeatFactor > 1f ? ChatFormatting.GOLD : ChatFormatting.AQUA)));
            // 过冷状态已由状态行（status.cold）显示，不再单独列数值行；风门/最佳温度/混合比行均移除（用户要求精简 tooltip）
        }
        // Lua 控制行已移除（用户要求精简 tooltip；luaConnected 字段/NBT 同步保留备用）
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
