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
 *   <li>流体燃烧室：每室消耗燃料表流体的 consumption mb/s（流体燃料储备 → 批量补料，批次 = 室数×K mb）；</li>
 *   <li>蒸汽动力室：水（1mb/s/室 × 油门，水储备 → 批量补料）+ 燃料（burnTick 制，每秒烧 油门 个 burnTick，
 *       25% 油门燃料耐用 4 倍——真实蒸汽车节流阀语义：消耗 ∝ 蒸汽流量）——
 *       流体燃料按 burn_ticks_per_bucket 批量抽；<b>流体燃料优先，流体不可用时</b>从模块邻居燃料箱
 *       （quick_fill_fuel_vault 等 ItemHandler 容器）批量抽固体燃料（+物品 burnTime）；</li>
 *   <li>源抽取（P2.5）：事件化重建源列表（onLoad / 方块 neighborChanged / 连接性变化，去抖 10 tick；
 *       全源失败延迟 20 tick 重扫），从列表头一直抽、失败即时切下一个；批次倍数 K 配置进游戏缓存。</li>
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
    public static final float BASE_STRESS_PER_CHAMBER = 8192;

    /** 每个蒸汽动力室的基础应力贡献（SU） */
    public static final float STEAM_STRESS_PER_CHAMBER = 4096;

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
    /** 模块总容量（SU，当前转速下）＝运行燃烧室数 × 8192 × 燃料 stress 倍率 */
    protected float moduleCapacity = 0;

    // ---- P2.5：源列表 + 储备（transient，事件化重建；方案见 memo/engine-module.md 关键机制 7.3） ----
    /** 引擎源抽取批次倍数（K，进游戏缓存一次）：批次 = 引擎数量 × K（水/流体燃料 mb、固体燃料 个）。
     *  配置见 {@code Config.ENGINE_SOURCE_BATCH_MULTIPLIER}，由 {@code CCPeripheralExtender#onServerStarting} 写入。 */
    public static float SOURCE_BATCH_MULTIPLIER = 1f;

    /** 水源罐列表（含原版水的流体罐；查找序 = scanModule 邻居枚举序） */
    protected List<BlockPos> waterSources = List.of();
    /** 当前水源列表游标（drain 失败即时切下一个） */
    protected int waterSourceIdx = 0;
    /** 流体室燃料源列表（pos + datapack 条目；查找序） */
    protected List<FuelSource> fluidFuelSources = List.of();
    /** 当前流体室燃料源游标 */
    protected int fluidFuelSourceIdx = 0;
    /** 蒸汽室流体燃料源列表（pos + 流体 + 每桶原版燃烧 tick；查找序） */
    protected List<SteamFuelSource> steamFuelSources = List.of();
    /** 当前蒸汽室流体燃料源游标 */
    protected int steamFuelSourceIdx = 0;
    /** 蒸汽室固体燃料源（燃料箱）列表（查找序） */
    protected List<BlockPos> solidFuelSources = List.of();
    /** 当前固体燃料箱游标 */
    protected int solidFuelSourceIdx = 0;
    /** 水储备（mb，蒸汽室共享；批次补料，消耗 ∝ 油门；>0 = 有水可烧） */
    protected float waterReserve = 0f;
    /** 流体室燃料储备（mb；批次补料） */
    protected float fluidFuelReserve = 0f;
    /** 当前活动流体室燃料条目（最近一次成功补料的源决定；储备耗尽后由下次补料重选） */
    protected EngineFuels.Entry fluidFuel = null;
    /** 源列表需要重建（事件：onLoad / 邻居变化去抖到期 / 连接性变化 / 全失败延迟重扫） */
    protected boolean sourcesDirty = false;
    /** 全源失败等待重扫中：置位期间不尝试补料（避免每 tick 扫空表）；重建/新事件后复位 */
    protected boolean sourcesAllFailed = false;
    /** 源列表重建倒计时（邻居变化去抖 / 全失败延迟重扫用） */
    protected int sourcesRescanCooldown = 0;
    /** 邻居变化去抖 tick（方块 neighborChanged 去抖后重建源列表） */
    private static final int SOURCES_DEBOUNCE_TICKS = 10;
    /** 全部源不可用后的延迟重扫 tick（避免频繁空扫） */
    private static final int SOURCES_RESCAN_TICKS = 20;

    /** 流体室燃料源：位置 + 对应 datapack 燃料条目 */
    protected record FuelSource(BlockPos pos, EngineFuels.Entry entry) {}

    /** 蒸汽室流体燃料源：位置 + 流体 + 每桶原版熔炉燃烧 tick（P7 纯原版解析，如熔岩 20000） */
    protected record SteamFuelSource(BlockPos pos, FluidStack fluid, int burnTicksPerBucket) {}

    /** 蒸汽室当前流体燃料（缓存，供 drain 使用；P7 = 纯原版解析，无 datapack 条目） */
    protected FluidStack steamFuelFluid = FluidStack.EMPTY;

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
    public static final float OVERHEAT_TEMP = 220f;
    /** 滞回恢复阈值（°C）：T ≤ 此值才允许重启（≈0.9×OVERHEAT_TEMP；与「即将过热」预警阈值同值 200） */
    public static final float OVERHEAT_RESUME = 200f;
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
    /** 地狱维度（minecraft:the_nether）环境温度（°C）：全高度恒定（地狱 = 热环境，与高度无关）；与引擎经济目标同值 155 */
    public static final float T_AMB_NETHER = 155f;
    /** 末地维度（minecraft:the_end）环境温度（°C）：全高度恒定（末地 = 冷寂环境，与高度无关） */
    public static final float T_AMB_END = 0f;
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
    /** 每个冷却气道散热系数（°C/s/°C）——静态 eff50% + 1风道/室 ⇒ K_AMB+K_DUCT ≥ 0.5×H0/ΔT_max */
    public static final float K_DUCT = 0.05f;
    /** 引擎核心自身散热系数（°C/s/°C/节）：停机时核心仍缓慢散热（不依赖冷却气道），τ ≈ 1/0.02 = 50s */
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
    /** 慢字段心跳间隔（tick）：经济进度/实际混合比/发热系数/蒸汽倒计时等低频字段每 20 tick（1Hz）无条件补发一次（校准 + 防漏） */
    public static final int SYNC_HEARTBEAT_INTERVAL = 20;
    /** 上次 sendData 时各离散 tooltip 字段快照（P7+ 方案 A 差量门控；温度用 {@link #lastSyncedTemp} 单独门控） */
    protected boolean lastSyncedRunning, lastSyncedOverheated, lastSyncedWarmingUp, lastSyncedSteamEngine, lastSyncedAirDuct;
    protected String lastSyncedSteamFuelType = "none", lastSyncedSteamFuelKey = "", lastSyncedSteamSolidFuelKey = "";
    /** 慢字段心跳计数器：每次 sendData 归零，≥SYNC_HEARTBEAT_INTERVAL 强制补发一次 */
    protected int syncHeartbeat;
    /** 客户端：蒸汽倒计时外推基线时刻（最近一次 SteamBurnTicks 包到达时的 gameTime，方案 D：burnTicks 每 tick −效率 精确线性） */
    @OnlyIn(Dist.CLIENT)
    protected long steamBurnTicksSyncTime;

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
     *  仅用于 {@link #addToGoggleTooltip} 按悬停方块分流四套 tooltip（核心 / 蒸汽动力室 / 流体燃烧室全量 / 冷却气道精简）；
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
    /** 服务端每 tick 的自动富油系数（自然高度混合比 = 1 + 0.45×(1−气压)，钳 [1.0, MIXTURE_ALT_MAX]，不含杆值）；Lua getAutoRichness 读缓存，不随 NBT 同步（无客户端消费者） */
    protected float lastAutoRichness = 1f;

    // ---- P6/P7：最佳工作温度经济区（P7：双因素 AND 门控 + 时间解锁进度；方案见 memo/engine-module.md 节 7/10） ----
    /** 经济区最大折扣（×，P7 由 0.8 调高到 0.75 = 省 25%）；双因素持续达标、解锁进度满时达到 */
    public static final float ECO_MIN = 0.75f;
    /** 温度平底窗半径（°C）：|T−T_opt(eff)| ≤ 此值 = 温度达标 */
    public static final float ECO_FLAT = 10f;
    /** 混合比平底窗下限：m_eff ≥ 此值 = 混合比达标（P7；与温度窗并列，双达标才解锁经济；0.8 = 过稀失火阈值边界） */
    public static final float ECO_MIX_MIN = 0.8f;
    /** 混合比平底窗上限：m_eff ≤ 此值 = 混合比达标（P7；与温度窗并列，双达标才解锁经济） */
    public static final float ECO_MIX_MAX = 1.1f;
    /** 经济解锁速率（/s）：达标时 ecoProgress += 此值/20，15s 缓慢累积满（保持才奖励，快速掠过不奖励） */
    public static final float ECO_UNLOCK_RATE = 1f / 15f;
    /** 经济流失速率（/s）：不达标时 ecoProgress −= 此值/20，6s 归零（离开窗口快速失去折扣） */
    public static final float ECO_DECAY_RATE = 1f / 6f;
    /** P7 修订：流体引擎设计工作温度（°C，经济目标）——全部引擎固定（不随燃料、不随油门），真实 = 引擎设计点/节温器恒定 */
    public static final float ENGINE_T_OPT = 155f;
    /** 过冷阈值（°C，引擎最低工作温度；与蒸汽暖机门槛 STEAM_MIN_WORK_TEMP 同值）：T < 此值 = 过冷油耗惩罚，先小油门暖机 */
    public static final float ENGINE_MIN_WORK_TEMP = 100f;
    /** 过冷惩罚系数：cold = 1 + COLD_K × max(0, ENGINE_MIN_WORK_TEMP − T) / ENGINE_MIN_WORK_TEMP（20°C ≈×1.8，0°C ≈×2.0） */
    public static final float COLD_K = 1.0f;
    /** 蒸汽引擎固定最佳工作温度（°C，锅炉设计温度，不随燃料变——真实：蒸汽效率 ∝ 蒸汽温度/压力 [卡诺]） */
    public static final float BOILER_T_OPT = 155f;

    /** 当前是否蒸汽引擎（tick 仲裁后：模块燃烧室为蒸汽型；流体/蒸汽物理排斥 → 互斥）。服务端权威，NBT 同步客户端供 Goggle。 */
    protected boolean steamEngine = false;
    /** 蒸汽暖机中（tick 服务端计算：点火燃烧但温度 < STEAM_MIN_WORK_TEMP，只烧不发电）。NBT 同步客户端供 Goggle。 */
    protected boolean warmingUp = false;
    /** 是否已装冷却气道（tick 扫描；信息用——风道散热分量消费 coolingStrength 的载体，setMixture/setCooling 均无门控）。NBT 同步客户端。 */
    protected boolean hasAirDuct = false;
    /** 服务端当前经济系数（0.75~1.0；停机 → 1.0；Lua getFuelEconomyFactor 直读）。P7+ 方案 B 起不再随包同步——客户端由 ecoProgress 现算 */
    protected float lastEconomyFactor = 1f;
    /** P7：经济解锁进度（0~1，服务端权威：双因素持续达标缓慢累积、离开窗口快速流失）。NBT 同步客户端，Goggle 经济行经它现算 */
    protected float ecoProgress = 0f;
    /** P7：当前最佳工作温度目标（P7 定稿后恒 = ENGINE_T_OPT/BOILER_T_OPT，无消费者，仅保留服务端字段） */
    protected float lastOptimalTemp = ENGINE_T_OPT;
    /** P7：服务端当前过冷系数（cold ≥ 1；>1 = 温度低于目标、油耗惩罚中，只乘油耗）。P7+ 方案 B 起不再随包同步——客户端由 coldPenalty(同步温度) 现算 */
    protected float lastColdFactor = 1f;
    /** P7：服务端最终油耗系数（杆值 × 经济系数 × 过冷惩罚，服务端每 tick 计算；停机/蒸汽 → 1.0 无意义）。P7+ 方案 B 起不再随包同步——客户端现算 */
    protected float lastFuelFactor = 1f;
    /** P7：最终发热系数（heatFactor(m_eff)，m_eff = 杆 × 高空自动富油；稀=热 >1 / 富油=凉 <1；蒸汽恒 1.0）。依赖运动体高度 → 必须随包同步（踩坑 10） */
    protected float lastHeatFactor = 1f;
    /** 冷却强度（风门，0~1，默认 1.0 = 全开；只缩放 K_DUCT 风道散热分量，冲压/气压/环境不动；仅装冷却气道后可调，只能降——真实 cowl flap）。NBT 持久化。 */
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

        // P5/P7：混合比（仅流体引擎消费；蒸汽引擎无混合比轴——effectiveMixture 锁 1.0、无自动富油、发热不乘热因子）。
        // P7 修订：混合比纯存值、无门控（setMixture/getMixture 直存取值，不按引擎类型拒绝）——
        //   非流体引擎（蒸汽）因 effectiveMixture 强制 1.0、且无流体室不耗油，存值不生效。
        // P7 奖励驱动：m_eff = 杆 × autoRichness 只进发热（heatFactor——高空自动富油 = 只是发热减少/富油降温）
        //   与 eco 混合比窗口（海拔补偿 = 拉到 m_eff≈1.0 吃经济折扣）；完全不进油耗——
        //   油耗只随 杆值 × 经济系数 × 过冷惩罚（高空不拉稀 = ×1.0 无惩罚）。
        boolean airDuct = scan.coolingDucts > 0;
        hasAirDuct = airDuct;
        // P7 修订：混合比纯存值、无门控——杆值 = mixture 直取；非流体引擎（蒸汽）因 effectiveMixture 强制 1.0
        // 且无流体室不耗油，存值不生效（见 steamEngine 分支）。
        float leverMixture = mixture;                                             // 杆值（油耗直接 × 杆值）
        float autoRich = steamEngine ? 1f : autoRichness();                        // 自然高度混合比（自动富油，不含杆值；蒸汽无此轴恒 1.0）
        lastAutoRichness = autoRich;
        float effectiveMixture = steamEngine ? 1f : leverMixture * autoRich;       // 实际混合比（发热/eco 窗口/显示用）
        lastEffectiveMixture = effectiveMixture;
        float mixtureHeatFactor = steamEngine ? 1f : heatFactor(effectiveMixture);
        lastHeatFactor = mixtureHeatFactor; // P7：同步给 Goggle 发热系数行（服务端权威）

        // 源列表重建调度（方案 A：scanModule 每 tick 枚举邻居当安全网；重建事件驱动——
        // onLoad / 方块 neighborChanged / 连接性变化 → markSourcesDirty；全源失败 → scheduleSourcesRescan）
        if (sourcesDirty && --sourcesRescanCooldown <= 0) {
            sourcesDirty = false;
            rebuildSources(neighbors);
        }
        // 流体燃烧室（P1/P2.5 批量）：储备空才补料（批次 = 室数×K mb），running = 储备>0 且燃料条目已知；
        // 每室 consumption mb/s × 效率 × 杆值 × 经济 × 过冷（P7，蒸汽引擎无流体室 → 跳过）。
        // !sourcesAllFailed = 全源失败等待重扫期间不补料（避免每 tick 扫空表）
        int runningFluid = 0;
        float fluidCapacity = 0;
        // P3 方案 C：过载不再停烧（对齐 Create）——过载时照常燃烧/发电，网络红字提示；消除「过载→容量归零→解除过载」自激振荡
        if (heatAllowed && !fluidChambers.isEmpty()) {
            if (fluidFuelReserve <= 0f && efficiency > 0f && !sourcesAllFailed)
                fluidFuelReserve += drainFluidFuelBatch(fluidChambers.size());
            if (fluidFuel != null && fluidFuelReserve > 0f) {
                runningFluid = fluidChambers.size();
                fluidCapacity = runningFluid * BASE_STRESS_PER_CHAMBER * fluidFuel.stress();
                // P7 经济区（只省油耗、绝不反哺 Q_heat）：eco = 双因素 AND 门控 + 解锁进度（上 tick 值，1 tick 滞后可忽略）
                // P7 过冷惩罚：cold = 1 + COLD_K×(ENGINE_MIN_WORK_TEMP−T)/ENGINE_MIN_WORK_TEMP（刚开机低温 = 油耗惩罚，小油门暖机；只乘油耗）
                // P7 奖励驱动：油耗 = 杆值 × eco × cold（自动富油/heatFactor 不进油耗；高空不拉稀 = ×1.0 无惩罚）
                float coldPen = coldPenalty(temperature);
                fluidFuelReserve -= runningFluid * fluidFuel.consumption() * efficiency * leverMixture
                        * (1f - (1f - ECO_MIN) * ecoProgress) * coldPen / 20f;
            } else if (fluidFuelReserve <= 0f) {
                fluidFuel = null; // 储备耗尽且补不到 → 无活动燃料
            }
        }

        // 蒸汽动力室（P2/P2.5 批量）：水储备 + 燃料储备（burnTick 制）。
        // 燃料来源：流体燃料优先（P7 纯原版解析：桶物品熔炉燃烧时长）；流体不可用 → 固体燃料箱兜底。
        // 储备倒计时独立于燃料源：燃料箱/源罐被拆时，已有储备（burnTicks / 水储备）仍继续燃烧/运转，
        // 补不到才停烧/停机（不因源消失立即停机）。
        int runningSteam = 0;
        boolean waterOk = false;
        // P3 方案 C：同流体室——过载不再停烧（对齐 Create），照常烧水/烧燃料，网络红字提示
        if (heatAllowed && !steamChambers.isEmpty()) {
            // 水储备：空才补一批（批次 = 室数×K mb，接受部分量；头罐失败即时切下一个）；油门 0 不抽。
            // !sourcesAllFailed = 全源失败等待重扫期间不补料（避免每 tick 扫空表）
            if (waterReserve <= 0f && efficiency > 0f && !sourcesAllFailed)
                waterReserve += drainWaterBatch(steamChambers.size());
            waterOk = waterReserve > 0f;
            if (waterOk) {
                // 燃料：并行燃烧，全室空炉才补（流体燃料优先 → 固体兜底；油门 0 不抽；
                // 全源失败等待重扫期间不补料）
                if (steamAllChambersEmpty(steamChambers) && efficiency > 0f && !sourcesAllFailed) {
                    if (!refillSteamFluidFuel(steamChambers))
                        tryPullSolidFuelBatch(steamChambers);
                }
                // 燃烧：各室并行倒计时（每秒烧 油门 个 burnTick → 25% 油门燃料耐用 4 倍，同燃同熄；
                // Goggle 燃料行显示墙钟剩余秒 = burnTicks/(油门×20)）
                for (BlockPos sp : steamChambers) {
                    if (!(level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be))
                        continue;
                    if (be.burnTicks > 0) {
                        be.burnTicks -= efficiency;
                        if (be.burnTicks <= 0)
                            be.setChanged();
                        runningSteam++;
                    }
                }
                // 耗水：1mb/s/室 × 油门（从水储备扣）。门控 = 引擎在工作（running）或正烧燃料暖机（runningSteam>0）——
                // 水是工质（蒸汽流量 ∝ 油门），燃料只影响加热不影响发电（memo §7.7/§15）：无燃料余热运转 / 地狱环境热运转
                // （T≥100 只靠环境）同样正常耗水（踩坑 30：原门控 runningSteam>0 → 无燃料永不耗水）。
                // 耗水室数：工作中 = 全部蒸汽室（容量 = 全部室满出力）；暖机 = 正在烧的室。
                if (running || runningSteam > 0) {
                    int waterChambers = running ? steamChambers.size() : runningSteam;
                    waterReserve -= waterChambers * 0.05f * efficiency;
                    // 储备刚好耗尽 → 立即补一批（同一 tick 内补回，避免 running 判定出现 1 tick 空档
                    // → 应力网络不闪断；补不到（全源失败）才真停机）
                    if (waterReserve <= 0f && efficiency > 0f)
                        waterReserve += drainWaterBatch(steamChambers.size());
                }
            }
        }

        // 蒸汽室燃料显示数据（服务端每 tick；同步客户端 Goggle 燃料行）
        updateSteamFuelDisplay(steamChambers);

        int runningTotal = runningFluid + runningSteam;
        // P6 Plan B：蒸汽锅炉暖机门控——T ≥ STEAM_MIN_WORK_TEMP 才「开始工作」（低于阈值只烧不发电，热机过程）
        boolean steamReady = !steamEngine || temperature >= STEAM_MIN_WORK_TEMP;
        // P2.5：蒸汽运行条件 = 油门开启且温度 > 100 且有水储备（有水储备才运行；余热运转同样需要水在）——
        // 燃料耗尽但锅炉仍热（T ≥ 100°C）且水储备 > 0 时靠余热继续发电；缺水/温度冷却到阈值以下才停机；
        // 流体引擎维持原条件（有运行燃烧室，不要求水）。
        boolean steamWaterReady = !steamEngine || waterReserve > 0f;
        // P4：throttle=0（效率=0）→ 停机（不发电；throttle=0 时容量/消耗/发热全为 0，避免"空转"假象）
        // P3 方案 C：running 不再要求 !overstressed——过载是网络状态不是引擎状态（对齐 Create：过载照常运行烧油，
        // 网络红字提示；容量不随过载归零 → 无「停机→解除过载→重启」自激振荡）
        running = !overheated && efficiency > 0f && steamReady && steamWaterReady
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
                // 修正：K_AMBIENT 只计「运行中」的室——停机（油门 0）时 runningSteam 仍计有储备的室，
                // 而储备≠在消耗，不加门控会导致「有剩燃料冷得更快」（踩坑 13 原则；冷却按运行中室数计，memo §15）
                float kCool = K_CORE * length + K_AMBIENT * (running ? runningTotal : 0)
                        + K_DUCT * scan.coolingDucts * (hasAirDuct ? coolingStrength : 1f);
                temperature += (-kCool * (temperature - tAmb)) / thermalCapacity() / 20f;
            }
        } else {
            // 流体引擎：牛顿冷却（发热 − 散热），气压/冲压/风道/风门全部生效
            float heat = 0;
            if (running && !overheated && runningFluid > 0 && fluidFuel != null)
                heat += runningFluid * efficiency * fluidFuel.heat() * BASE_HEAT_FLUID * mixtureHeatFactor;
            // P6 风门：冷却强度只缩放冷却气道散热分量（冲压/气压/环境不动）；无气道时 D=0 无影响
            // P3 修正：K_AMBIENT 只计「运行中」的室——停机（油门 0）时 runningFluid 仍计有储备的室，
            // 储备≠在消耗，不加门控会导致「有剩燃料冷得更快」（踩坑 13 原则；冷却按运行中室数计，memo §15）
            float kTotal = (K_CORE * length + K_AMBIENT * (running ? runningTotal : 0)
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

        // P7+ 同步优化（方案 A+B+D）：tooltip 数据从「每 tick 20Hz 全量广播」改为「事件差量 + 温度 ≥1°C 门控
        // + 慢字段 1Hz 心跳」——稳态包量降到 1/20 以下；可推导字段（经济/过冷/油耗系数）客户端按公式现算（方案 B），
        // 蒸汽倒计时客户端线性外推（方案 D：burnTicks 每 tick −效率 精确线性，事件/心跳到来自动校准）。
        // reActivateSource 只在运行态/容量变化时置位（避免每 tick 触发传动网络重激活，Create 基类 tick 消费）；
        // setChanged 每 tick 保留（温度存 controller NBT 需持久化，与发包解耦）。
        boolean capacityChanged = !Mth.equal(moduleCapacity, prevCapacity);
        if (running != prevRunning || capacityChanged) {
            reActivateSource = true;
        }
        boolean discreteChanged = running != lastSyncedRunning || overheated != lastSyncedOverheated
                || warmingUp != lastSyncedWarmingUp || steamEngine != lastSyncedSteamEngine
                || hasAirDuct != lastSyncedAirDuct || capacityChanged
                || !Objects.equals(steamFuelType, lastSyncedSteamFuelType)
                || !Objects.equals(steamFuelKey, lastSyncedSteamFuelKey)
                || !Objects.equals(steamSolidFuelKey, lastSyncedSteamSolidFuelKey);
        boolean tempChanged = Math.abs(temperature - lastSyncedTemp) >= SYNC_TEMP_DELTA;
        boolean heartbeat = ++syncHeartbeat >= SYNC_HEARTBEAT_INTERVAL;
        setChanged();
        if (discreteChanged || tempChanged || heartbeat) {
            syncHeartbeat = 0;
            lastSyncedTemp = temperature;
            lastSyncedRunning = running;
            lastSyncedOverheated = overheated;
            lastSyncedWarmingUp = warmingUp;
            lastSyncedSteamEngine = steamEngine;
            lastSyncedAirDuct = hasAirDuct;
            lastSyncedSteamFuelType = steamFuelType;
            lastSyncedSteamFuelKey = steamFuelKey;
            lastSyncedSteamSolidFuelKey = steamSolidFuelKey;
            sendData();
        }

        if (DEBUG) {
            if (running != prevRunning) {
                LOGGER.info("[EngineCore] {} running {} -> {} | fluid={} steam={} fuel={} water={} steamFuel={} solid={} T={}℃ overheated={} capacity={}",
                        worldPosition, prevRunning, running, runningFluid, runningSteam,
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterReserve, steamFuelType,
                        solidFuelSources.size(), temperature, overheated, moduleCapacity);
            } else if (!running && level.getGameTime() % 40 == 0) {
                LOGGER.info("[EngineCore] {} idle | fluid={} steam={} fuel={} water={} steamFuel={} solid={} T={}℃ overheated={} ducts={} len={} speed={}",
                        worldPosition, fluidChambers.size(), steamChambers.size(),
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterReserve, steamFuelType,
                        solidFuelSources.size(), temperature, overheated,
                        scan.coolingDucts, length, getSpeed());
            }
        }
    }

    /** 模块扫描结果：一次遍历收集两类燃烧室 + 冷却气道数 + 去重后的全部邻居（core 成员 6 邻居 ∪ 燃烧室 6 邻居） */
    protected record ModuleScan(List<BlockPos> fluidChambers, List<BlockPos> steamChambers, List<BlockPos> neighbors,
                                int coolingDucts) {}

    /**
     * 扫描本条引擎（全部成员）：统计贴附的流体/蒸汽燃烧室（仅计入背面 FACING 反方向正贴核心的，
     * 避免双计），并收集模块全部邻居（core 成员 ∪ 燃烧室，去重）——水和流体燃料的源罐查找范围。
     * 冷却气道计数范围 = 贴在核心成员 ∪ 燃烧室上（blockstate 计数，经 seen 去重防重复计）。
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
                // 冷却气道：贴在核心成员上即计入（blockstate 计数，无 BE；去重防两核心间重复计）
                if (state.is(MyModBlocks.cooling_air_duct.get()) && seen.add(neighbor))
                    coolingDucts++;
                addNeighbor(seen, neighbors, neighbor);
            }
        }
        // 燃烧室自己的邻居（罐/容器可能贴着燃烧室；冷却气道贴在燃烧室旁同样计入冷却）
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
     * 扫描 pos（燃烧室）的 6 邻居：冷却气道计入冷却计数（返回新计入数，经 seen 去重防核心/燃烧室间重复计），
     * 其余邻居并入模块邻居列表（水和流体燃料的源罐/容器查找范围）。
     */
    private int scanChamberNeighbors(Set<BlockPos> seen, List<BlockPos> neighbors, BlockPos pos) {
        int ducts = 0;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (level.getBlockState(neighbor).is(MyModBlocks.cooling_air_duct.get()) && seen.add(neighbor))
                ducts++;
            addNeighbor(seen, neighbors, neighbor);
        }
        return ducts;
    }

    // ================= P2.5：源列表（事件化重建 + 批量补料） =================

    /**
     * 重建四类源列表（事件驱动：onLoad / 方块 neighborChanged / 连接性变化 → {@link #markSourcesDirty()}；
     * 全源失败 → {@link #scheduleSourcesRescan()}）。按 scanModule 邻居枚举顺序（查找序）收集：
     * <ul>
     *   <li>水源：罐内为原版水的流体罐；</li>
     *   <li>流体室燃料：罐内流体在 engine_fuel datapack 表中（P2.5 起不再按 priority，就按查找顺序）；</li>
     *   <li>蒸汽室流体燃料：桶物品原版熔炉燃烧时长 &gt; 0（P7 纯原版解析）；</li>
     *   <li>蒸汽室固体燃料：带 ItemHandler 且含可烧物品的容器（quick_fill_fuel_vault 等）。</li>
     * </ul>
     * 只走 capability，绝不直接改罐/容器 BE。列表 transient 不落盘。
     */
    protected void rebuildSources(List<BlockPos> neighbors) {
        List<BlockPos> water = new ArrayList<>();
        List<FuelSource> fluidFuelList = new ArrayList<>();
        List<SteamFuelSource> steamFuelList = new ArrayList<>();
        List<BlockPos> solid = new ArrayList<>();
        for (BlockPos pos : neighbors) {
            IFluidHandler fh = fluidHandlerAt(pos);
            if (fh != null) {
                FluidStack stack = fh.getFluidInTank(0);
                if (!stack.isEmpty()) {
                    if (stack.getFluid() == Fluids.WATER)
                        water.add(pos);
                    int burn = EngineFuels.vanillaBucketBurnTicks(BuiltInRegistries.FLUID.getKey(stack.getFluid()));
                    if (burn > 0)
                        steamFuelList.add(new SteamFuelSource(pos, stack.copy(), burn));
                    EngineFuels.Entry entry = EngineFuels.get(stack.getFluid());
                    if (entry != null)
                        fluidFuelList.add(new FuelSource(pos, entry));
                }
            }
            IItemHandler ih = itemHandlerAt(pos);
            if (ih != null && hasBurnableFuel(ih))
                solid.add(pos);
        }
        waterSources = water;
        fluidFuelSources = fluidFuelList;
        steamFuelSources = steamFuelList;
        solidFuelSources = solid;
        waterSourceIdx = fluidFuelSourceIdx = steamFuelSourceIdx = solidFuelSourceIdx = 0;
        sourcesAllFailed = false; // 重建完成 → 解除「全源失败」等待（下 tick 即可重试补料）
    }

    /** 标记源列表需要重建（方块 neighborChanged）。去抖语义：已有排程则不再重置——
     *  最后一次事件起 {@link #SOURCES_DEBOUNCE_TICKS} tick 重建（合并同一瞬间的连续事件，防邻居噪声饿死重建）。
     *  新事件也复位「全源失败」等待（可能有新源出现，立即允许补料）。 */
    protected void markSourcesDirty() {
        sourcesAllFailed = false;
        if (sourcesDirty && sourcesRescanCooldown > 0)
            return;
        sourcesDirty = true;
        sourcesRescanCooldown = SOURCES_DEBOUNCE_TICKS;
    }

    /** 立即重建源列表（onLoad / 连接性变化：下 tick 就扫，不等去抖） */
    protected void markSourcesDirtyNow() {
        sourcesAllFailed = false;
        sourcesDirty = true;
        sourcesRescanCooldown = 1;
    }

    /** 全部源不可用 → 延迟重扫（{@link #SOURCES_RESCAN_TICKS} tick 后重建源列表再试，避免频繁空扫）。
     *  已有排程则不再重置（每 tick 的失败 drain 不会反复推迟重扫——否则永远不会执行）。 */
    protected void scheduleSourcesRescan() {
        if (sourcesAllFailed || (sourcesDirty && sourcesRescanCooldown > 0))
            return;
        sourcesAllFailed = true;
        sourcesDirty = true;
        sourcesRescanCooldown = SOURCES_RESCAN_TICKS;
    }

    /** 批次流体量（mb）：引擎数量 × 配置倍数 K（进游戏缓存） */
    private int batchMb(int chamberCount) {
        return Math.max(1, Math.round(chamberCount * SOURCE_BATCH_MULTIPLIER));
    }

    /** 批次固体物品数（个）：引擎数量 × 配置倍数 K */
    private int batchItems(int chamberCount) {
        return Math.max(1, Math.round(chamberCount * SOURCE_BATCH_MULTIPLIER));
    }

    /**
     * 从水源列表头 drain 一批水（批次 = 室数×K mb，接受部分量）；头罐失败/空/被拆 → 即时切下一个；
     * 全部失败 → {@link #scheduleSourcesRescan()} 并返回 0。只走 capability。
     */
    protected float drainWaterBatch(int chamberCount) {
        if (waterSources.isEmpty()) {
            // 列表空（罐未探测到 / 空罐未入列）→ 纳入"全源失败延迟重扫"自愈循环：每 ~1s 重建一次，
            // 罐被灌满/放好即被探测（踩坑 29：内容变化无事件，仅靠事件重建会永久卡死）
            scheduleSourcesRescan();
            return 0f;
        }
        int batch = batchMb(chamberCount);
        int n = waterSources.size();
        for (int tries = 0; tries < n; tries++) {
            BlockPos pos = waterSources.get(waterSourceIdx);
            IFluidHandler handler = fluidHandlerAt(pos);
            if (handler == null) {
                advanceWaterSource();
                continue;
            }
            FluidStack drained = handler.drain(new FluidStack(Fluids.WATER, batch), IFluidHandler.FluidAction.EXECUTE);
            if (drained.getAmount() > 0)
                return drained.getAmount(); // 部分接受：罐剩多少收多少
            advanceWaterSource(); // 空 → 切下一个
        }
        scheduleSourcesRescan();
        return 0f;
    }

    private void advanceWaterSource() {
        if (!waterSources.isEmpty())
            waterSourceIdx = (waterSourceIdx + 1) % waterSources.size();
    }

    /**
     * 从流体室燃料源列表头 drain 一批（批次 = 室数×K mb，接受部分量）；头罐失败/空/被拆 → 即时切下一个；
     * 成功时把 {@link #fluidFuel} 设为该源的燃料条目（P2.5：按查找顺序，不再按 priority）；
     * 全部失败 → {@link #scheduleSourcesRescan()} 并返回 0。只走 capability。
     */
    protected float drainFluidFuelBatch(int chamberCount) {
        if (fluidFuelSources.isEmpty()) {
            scheduleSourcesRescan(); // 同 drainWaterBatch（踩坑 29）：空列表也要周期重扫自愈
            return 0f;
        }
        int batch = batchMb(chamberCount);
        int n = fluidFuelSources.size();
        for (int tries = 0; tries < n; tries++) {
            FuelSource src = fluidFuelSources.get(fluidFuelSourceIdx);
            IFluidHandler handler = fluidHandlerAt(src.pos());
            if (handler == null) {
                advanceFluidFuelSource();
                continue;
            }
            net.minecraft.world.level.material.Fluid fuel = BuiltInRegistries.FLUID.get(src.entry().fluid());
            if (fuel == null) {
                advanceFluidFuelSource();
                continue;
            }
            FluidStack drained = handler.drain(new FluidStack(fuel, batch), IFluidHandler.FluidAction.EXECUTE);            if (drained.getAmount() > 0) {
                fluidFuel = src.entry(); // 活动燃料 = 当前源条目
                return drained.getAmount();
            }
            advanceFluidFuelSource();
        }
        scheduleSourcesRescan();
        return 0f;
    }

    private void advanceFluidFuelSource() {
        if (!fluidFuelSources.isEmpty())
            fluidFuelSourceIdx = (fluidFuelSourceIdx + 1) % fluidFuelSources.size();
    }

    /**
     * 蒸汽室流体燃料批量补料（并行燃烧同燃同熄）：从蒸汽流体燃料源列表头一次 drain 批次 mb，
     * 每室 + 批次/N × burnTicksPerBucket/1000 burnTick（N = 蒸汽室数；1mb 熔岩 = 20 burnTick）；
     * 头罐失败/空/被拆 → 即时切下一个；全部失败 → {@link #scheduleSourcesRescan()} 并返回 false。
     * 只走 capability，绝不直接改罐 BE。调用前提：全室空炉 + 油门 > 0。
     */
    protected boolean refillSteamFluidFuel(List<BlockPos> steamChambers) {
        if (steamChambers.isEmpty())
            return false;
        if (steamFuelSources.isEmpty()) {
            scheduleSourcesRescan(); // 同 drainWaterBatch（踩坑 29）：空列表也要周期重扫自愈
            return false;
        }
        int batch = batchMb(steamChambers.size());
        int n = steamFuelSources.size();
        for (int tries = 0; tries < n; tries++) {
            SteamFuelSource src = steamFuelSources.get(steamFuelSourceIdx);
            IFluidHandler handler = fluidHandlerAt(src.pos());
            if (handler == null) {
                advanceSteamFuelSource();
                continue;
            }
            FluidStack drained = handler.drain(new FluidStack(src.fluid().getFluid(), batch), IFluidHandler.FluidAction.EXECUTE);
            if (drained.getAmount() > 0) {
                // 每室 + 实际抽到的 mb/N × burnTicksPerBucket/1000（部分接受；并行燃烧各室同值）
                float addBurn = drained.getAmount() / (float) steamChambers.size() * src.burnTicksPerBucket() / 1000f;
                for (BlockPos sp : steamChambers) {
                    if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be) {
                        be.burnTicks += Math.max(1, addBurn);
                        be.setChanged();
                    }
                }
                steamFuelType = "fluid"; // 储备来源 = 流体（Goggle 燃料行）
                steamFuelFluid = drained.copy();
                return true;
            }
            advanceSteamFuelSource();
        }
        scheduleSourcesRescan();
        return false;
    }

    private void advanceSteamFuelSource() {
        if (!steamFuelSources.isEmpty())
            steamFuelSourceIdx = (steamFuelSourceIdx + 1) % steamFuelSources.size();
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
     * 并行燃烧批量补固体燃料：全部蒸汽室同时空炉时，从固体燃料源列表头依次找箱，一次抽
     * N×K 个（N = 蒸汽室个数，K = 配置批次倍数）熔炉燃料物品（burnTime > 0），每室 +
     * 抽到数量/N × burnTime——各室并行燃烧同一燃料，同燃同熄；<b>当前箱不足 → 切下一箱</b>；
     * 全部箱不足/空 → {@link #scheduleSourcesRescan()} 并返回 false。
     * <p>调用前提：流体燃料不可用（流体优先）且全室空炉；油门 0 不抽（停机不烧油）。
     * 只走 capability，绝不直接改容器 BE。</p>
     */
    protected boolean tryPullSolidFuelBatch(List<BlockPos> steamChambers) {
        if (steamChambers.isEmpty())
            return false;
        if (solidFuelSources.isEmpty()) {
            scheduleSourcesRescan(); // 同 drainWaterBatch（踩坑 29）：空列表也要周期重扫自愈
            return false;
        }
        int need = batchItems(steamChambers.size());
        int n = solidFuelSources.size();
        for (int tries = 0; tries < n; tries++) {
            BlockPos pos = solidFuelSources.get(solidFuelSourceIdx);
            IItemHandler handler = itemHandlerAt(pos);
            if (handler == null) {
                advanceSolidSource();
                continue;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty())
                    continue;
                int burnTime = stack.getBurnTime(RecipeType.SMELTING);
                if (burnTime <= 0)
                    continue;
                // 不足 N×K 个不抽取（模拟先验；不足 → 该箱断供，切下一箱）
                ItemStack simulated = handler.extractItem(slot, need, true);
                if (simulated.getCount() < need)
                    break; // 本箱不足 → 切下一箱
                ItemStack extracted = handler.extractItem(slot, need, false);
                if (extracted.getCount() < need) { // 竞态兜底 → 切下一箱
                    advanceSolidSource();
                    return false;
                }
                // 每室 + 抽到数量/N × burnTime（并行燃烧：每室同燃同熄）
                int perChamberBurn = Math.max(1, Math.round(extracted.getCount() / (float) steamChambers.size() * burnTime));
                for (BlockPos sp : steamChambers) {
                    if (level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be) {
                        be.burnTicks += perChamberBurn;
                        be.setChanged();
                    }
                }
                steamFuelType = "solid"; // 储备来源 = 固体（Goggle 燃料行）
                steamSolidFuelKey = extracted.getDescriptionId();
                return true;
            }
            advanceSolidSource(); // 本箱无合适燃料 → 切下一箱
        }
        scheduleSourcesRescan();
        return false;
    }

    private void advanceSolidSource() {
        if (!solidFuelSources.isEmpty())
            solidFuelSourceIdx = (solidFuelSourceIdx + 1) % solidFuelSources.size();
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
     * 环境温度（°C）：地狱维度（the_nether）全高度恒 {@link #T_AMB_NETHER}=155°C（热环境）、
     * 末地维度（the_end）全高度恒 {@link #T_AMB_END}=0°C（冷寂环境），均与高度无关；
     * 其余维度按 Minecraft 尺度分段线性（不再套真实对流层 0.0065°C/m —— 那在 63~320 的
     * 世界里整段只降 ~1.7°C，高度几乎无温度差异）：
     *   Y ≤ 63（海平面）    → 恒 20°C
     *   63 → 200（云层）    → 20°C 线性降到 0°C（结冰层，云层生成高度）
     *   200 → 320（世界顶） → 0°C 线性降到 −40°C（下限）
     *   Y ≥ 320            → 恒 −40°C
     */
    protected float ambientTemp() {
        if (hasLevel()) {
            if (level.dimension() == Level.NETHER)
                return T_AMB_NETHER;
            if (level.dimension() == Level.END)
                return T_AMB_END;
        }
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
     * （|T−ENGINE_T_OPT|≤ECO_FLAT ∧ ECO_MIX_MIN≤m_eff≤ECO_MIX_MAX）→ 缓慢累积（ECO_UNLOCK_RATE，15s 满）；
     * 离开窗口/停机 → 快速流失（ECO_DECAY_RATE，6s 归零）。保持才奖励、快速掠过不奖励；
     * 混合比不对 = 无奖励也无惩罚（温度控得再好也没用）。
     * P7 修订：经济系数<b>不以冷却气道为门控</b>（无气道同样生效）——setMixture/setCooling 均无门控（纯存值），冷却气道只是风门数值的消费载体。
     */
    protected float updateEcoProgress(float progress, float temp, float tOptEff, float mEff,
                                      boolean runningFluid) {
        boolean satisfied = runningFluid
                && Math.abs(temp - tOptEff) <= ECO_FLAT
                && mEff >= ECO_MIX_MIN && mEff <= ECO_MIX_MAX;
        if (satisfied)
            return Math.min(1f, progress + ECO_UNLOCK_RATE / 20f);
        return Math.max(0f, progress - ECO_DECAY_RATE / 20f);
    }

    /**
     * P7 过冷惩罚（只乘油耗、绝不反哺 Q_heat）：cold = 1 + COLD_K × max(0, ENGINE_MIN_WORK_TEMP − T) / ENGINE_MIN_WORK_TEMP。
     * T ≥ 100°C（引擎最低工作温度，引擎固定）无惩罚；刚开机 20°C ≈×1.8——玩家先小油门暖机（绝对油耗小、损失轻）再推油门。
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

        /** 当前混合比杆（0.6~1.4，默认 1.0；只影响油耗与温度，不影响应力/转速）。纯存值无门控——非流体引擎存了也不生效 */
        @LuaFunction
        public final double getMixture() {
            return mixture;
        }

        /**
         * 当前<b>实际</b>混合比（杆 × 自动富油，服务端每 tick 计算）：
         * 高空/低压下 > 杆值（空气稀 → 化油器按体积配油 → 天然变浓）。P7：自动富油只降温不进油耗——
         * 该读数是经济系数混合比窗口（m_eff≈1.0）与发热的反馈：海拔补偿 = 拉到 m_eff≈1.0 吃经济折扣。
         * 海平面 = 杆值。蒸汽引擎无混合比轴（tick 计算时已强制 1.0）→ 恒 1.0。
         */
        @LuaFunction
        public final double getEffectiveMixture() {
            return lastEffectiveMixture;
        }

        /**
         * 当前<b>自然高度混合比</b>（高空自动富油系数，<b>不含玩家设定的杆值</b>）：
         * 海平面 = 1.0；气压随高度下降 → 化油器按进气体积配油 → 空气稀天然变浓，最高 ×{@code MIXTURE_ALT_MAX}（Y≈260）。
         * P7：自动富油只降温不进油耗——该读数是「海拔补偿该拉多少杆」的参考：
         * 目标是 m_eff = 杆 × autoRichness ≈ 1.0 → 杆 ≈ 1 / getAutoRichness()。
         * 蒸汽引擎无混合比轴 → 恒 1.0。
         */
        @LuaFunction
        public final double getAutoRichness() {
            return lastAutoRichness;
        }

        /**
         * 设置混合比（0.6~1.4，越界钳制；非法参数返回 false）。
         * P5/P7 经济性/热管理杆：只影响<b>油耗</b>（P7：×杆值，自动富油不进油耗）与<b>温度</b>（×凸热因子，m_eff=杆×autoRichness）——
         * 稀=省油但更热，浓=费油但降温；应力/转速完全不受影响。
         * P7 奖励驱动：杆 1.0 = 出厂标定（任何高度油耗 ×1.0 无惩罚）；高空自动富油只降温（进 heatFactor）；
         * 经济系数 = 温度+实际混合比双因素（m_eff≈1.0 才解锁）——海拔补偿 = 拉到 m_eff≈1.0 吃经济折扣。
         * 可用 {@link #getEffectiveMixture()} 读实际值做海拔补偿校正。
         * P7 修订：混合比是纯引擎级存值、<b>无门控</b>——只有流体引擎的油耗/温度路径会消费它，
         * 非流体引擎（蒸汽）存了也不生效（effectiveMixture 强制 1.0、无流体室不耗油）。
         */
        @LuaFunction(mainThread = true)
        public final boolean setMixture(double value) {
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
         * P7：当前经济系数（0.75~1.0；停机 / 油门 0 → 1.0 无意义）。
         * 服务端每 tick 计算并同步（读缓存，≤1 tick 滞后）；温度与<b>实际混合比</b>双因素持续达标
         * （|T−T_opt(eff)|≤10 ∧ 0.8≤m_eff≤1.1）→ 解锁进度缓慢累积（15s 满）渐入 0.75，离开窗口 6s 流失；
         * 混合比不对 → 无奖励无惩罚。
         */
        @LuaFunction
        public final double getFuelEconomyFactor() {
            return lastEconomyFactor;
        }

        /** P6：是否已装冷却气道（信息用：风道散热分量消费 coolingStrength 的载体；setMixture/setCooling 均无门控） */
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

        /** P6：当前冷却强度（风门 0~1，默认 1.0 = 全开）。纯存值无门控——只有装冷却气道时风道散热分量才消费它（蒸汽引擎无风道消费，存了不生效） */
        @LuaFunction
        public final double getCooling() {
            return coolingStrength;
        }

        /**
         * P7 修订：设置冷却强度（风门 0~1，越界钳制；非法参数返回 false）——<b>无门控</b>，纯存值。
         * 只有装冷却气道时 K_DUCT 风道散热分量才消费它（只缩放风道散热，冲压/气压/环境散热不动；
         * 只能降 = 想更冷多装风道，真实 cowl flap）。蒸汽引擎无风道（温度钉在 BOILER_T_OPT 自调节），存了不生效。
         */
        @LuaFunction(mainThread = true)
        public final boolean setCooling(double value) {
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
        // 组网后源列表按新模块邻居重建（新 controller 的邻居集合可能变化）
        markSourcesDirtyNow();
    }

    /** BE 载入（读档/放置/方块加载）：源列表按当前邻居重建（下 tick 就扫） */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide)
            markSourcesDirtyNow();
    }

    /**
     * 模块邻居方块变化（放置/拆除/状态改变）→ 让整条引擎 controller 去抖重建源列表。
     * 由核心/燃烧室的 {@code neighborChanged} 调用（服务端）。只作用于存在 controller 的引擎。
     * <p>过滤：仅当邻居位置<b>当前带流体/物品能力</b>（罐/燃料箱出现）才触发重建——
     * 红石/装饰等普通方块变化不重建；罐被拆除后邻居无能力 → 不重建，但源列表在 drain 时
     * 对失效位置（capability 为 null）即时跳过，天然自愈。</p>
     */
    public static void onModuleNeighborChanged(Level level, BlockPos modulePos, BlockPos neighborPos) {
        if (level == null || level.isClientSide)
            return;
        if (neighborPos != null
                && level.getCapability(Capabilities.FluidHandler.BLOCK, neighborPos, null) == null
                && level.getCapability(Capabilities.ItemHandler.BLOCK, neighborPos, null) == null)
            return; // 无能力的邻居变化（红石/装饰等）不触发重建
        BlockPos controllerPos = engineControllerPos(level, modulePos);
        if (controllerPos != null
                && level.getBlockEntity(controllerPos) instanceof EngineCoreBlockEntity core) {
            core.markSourcesDirty();
        }
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
        // P2.5：拷贝储备与活动燃料（源列表按新 controller 的邻居重建，不拷贝——位置随 controller 变）
        waterReserve = donor.waterReserve;
        fluidFuelReserve = donor.fluidFuelReserve;
        fluidFuel = donor.fluidFuel;
        steamFuelFluid = donor.steamFuelFluid.copy();
        steamFuelType = donor.steamFuelType;
        steamFuelKey = donor.steamFuelKey;
        steamBurnTicksRemaining = donor.steamBurnTicksRemaining;
        steamSolidFuelKey = donor.steamSolidFuelKey;
        activeFuelType = donor.activeFuelType;
        activeFuelId = donor.activeFuelId;
        activeFuelTopt = donor.activeFuelTopt;
        markSourcesDirty();
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
     * 从引擎任一模块方块位置（核心成员 / 燃烧室 / 冷却气道）解析整条引擎 controller 坐标；
     * 该方块不属于任何已组网引擎（未连接核心）时返回 null。
     * <p>供模块方块（燃烧室 / 冷却气道）的 goggle tooltip 代理使用（{@code IProxyHoveringInformation}）。</p>
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

        // 冷却气道：与冷却计数同范围（核心成员 ∪ 燃烧室邻居）
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
        if (level != null && !level.isClientSide)
            markSourcesDirtyNow(); // controller 变更 → 源列表按新 controller 邻居重建
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
        markSourcesDirtyNow(); // 拆解重组 → 源列表按新模块重建
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
        if (level != null && !level.isClientSide)
            markSourcesDirtyNow(); // 组网结构变化 → 源列表按新模块邻居重建
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
        // P6：蒸汽类型 / 冷却气道（服务端权威；旧存档无字段 → 默认值）
        steamEngine = compound.getBoolean("SteamEngine");
        warmingUp = compound.getBoolean("WarmingUp");
        hasAirDuct = compound.getBoolean("AirDuct");
        // 经济/过冷/油耗系数：旧包/旧档兼容兜底（新包不再含此四字段，缺省即默认值，服务端每 tick 重算）
        lastEconomyFactor = compound.contains("EconomyFactor") ? compound.getFloat("EconomyFactor") : 1f;
        coolingStrength = compound.contains("CoolingStrength") ? compound.getFloat("CoolingStrength") : 1f;
        // P7：经济解锁进度（服务端权威，客户端据此推导经济系数）；P7+ 方案 B 起 EconomyFactor/OptimalTemp/
        // ColdFactor/FuelFactor 不再随包同步——contains 兜底保留（旧包/旧档兼容，缺省即默认值）
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

        // 方案 D：蒸汽倒计时外推基线（burnTicks 每 tick −效率 精确线性 → 客户端据此外推墙钟剩余秒）
        steamBurnTicksSyncTime = hasLevel() ? level.getGameTime() : 0;

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
        // P6：蒸汽类型 / 冷却气道（服务端每 tick 计算，同步客户端 Goggle；客户端不重算）
        compound.putBoolean("SteamEngine", steamEngine);
        compound.putBoolean("WarmingUp", warmingUp);
        compound.putBoolean("AirDuct", hasAirDuct);
        compound.putFloat("CoolingStrength", coolingStrength);
        // P7+ 方案 B：经济解锁进度（服务端权威，同步客户端 Goggle）——经济系数/过冷系数/油耗系数均为其
        // 纯函数（1−0.25×ecoProgress / coldPenalty(T) / 杆值×经济×过冷），客户端按公式现算，不再随包同步
        // （EconomyFactor/OptimalTemp/ColdFactor/FuelFactor 已从客户端包移除，read 保留旧包兼容兜底）。
        compound.putFloat("EcoProgress", ecoProgress);
        // 发热系数（heatFactor(m_eff)）依赖运动体真实高度（自动富油）——客户端无法可靠自算，必须服务端同步（踩坑 10）
        compound.putFloat("HeatFactor", lastHeatFactor);
        // 蒸汽室燃料显示（服务端计算，低频同步客户端 Goggle 燃料行；P7+ 方案 D：burnTicks 客户端线性外推）
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
     * 总应力输出 / 目前转速 / 连接的模块）、蒸汽动力室（状态 / 温度 / 油门）、冷却气道（温度 / 效率=风门）、
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
        // P3 方案 C：网络过载红字提示（对齐 Create——引擎照常运行烧油，只在 goggle 顶部红字提示；
        // 客户端 isOverStressed() 由同步的 capacity/stress 派生，见 Create KineticBlockEntity.read）
        if (isOverStressed()) {
            tooltip.add(Component.literal("    ")
                    .append(Component.translatable("tooltip.ccpe.engine.status.overloaded").withStyle(ChatFormatting.RED)));
        }
        try {
            Block hovered = level != null && level.isClientSide ? hoveredSourceBlock : null;
            if (hovered == MyModBlocks.steam_power_chamber.get()) {
                addSteamChamberTooltip(tooltip);
            } else if (hovered == MyModBlocks.cooling_air_duct.get()) {
                addCoolingDuctTooltip(tooltip);
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
                addModuleLine(tooltip, MyModBlocks.cooling_air_duct.get().getName(), ducts);
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

    // ---- P7+ 方案 B：可推导字段客户端按公式现算（与服务端公式一致；服务端返回权威值） ----
    // 仅纯函数/常量参与推导，不违反「客户端不重算」约束（那条只针对高度相关量 EffectiveMixture/HeatFactor，仍随包同步）。

    /** 经济系数：1 − (1−ECO_MIN)×ecoProgress（ecoProgress 服务端权威随包同步） */
    private float economyFactor() {
        return level != null && level.isClientSide ? 1f - (1f - ECO_MIN) * ecoProgress : lastEconomyFactor;
    }

    /** 过冷系数：coldPenalty(同步温度)，仅流体引擎运行中才计（与服务端 runningFluid>0 判定等价：流体引擎 running ⇒ 有运行燃烧室） */
    private float coldFactor() {
        if (level != null && level.isClientSide)
            return !steamEngine && running ? coldPenalty(temperature) : 1f;
        return lastColdFactor;
    }

    /** 最终油耗系数：杆值 × 经济 × 过冷；停机/蒸汽 → 1.0（与服务端一致） */
    private float fuelFactor() {
        if (level != null && level.isClientSide)
            return !steamEngine && running ? mixture * economyFactor() * coldFactor() : 1f;
        return lastFuelFactor;
    }

    /** 蒸汽倒计时剩余（P7+ 方案 D 客户端线性外推）：服务端低频同步 + burnTicks 每 tick −效率 精确线性；
     *  运行中才外推（停机/油门 0 时服务端冻结储备，不外推避免显示归零过早）；服务端返回权威值 */
    private float steamBurnTicksDisplay() {
        if (level != null && level.isClientSide && running && efficiency > 0f) {
            float remaining = steamBurnTicksRemaining
                    - efficiency * Math.max(0, level.getGameTime() - steamBurnTicksSyncTime);
            return Math.max(0f, remaining);
        }
        return steamBurnTicksRemaining;
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
                .append(Component.literal(String.format(Locale.ROOT, "%.1f%%", efficiency * 100))
                        .withStyle(ChatFormatting.AQUA)));
        // 燃料（正在使用的燃料；固体显示剩余燃烧时间，流体不显示——参考 simulated portable_engine）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.fuel").withStyle(ChatFormatting.GRAY))
                .append(steamFuelValueComponent()));
    }

    /** 蒸汽室燃料行值：无储备 → 红"无"；流体 → 绿燃料名（不显示时间/数量）；固体 → 绿"燃料名 xN" + 青" (时间)"
     *  （并行燃烧：数量 = 蒸汽室个数；时间 = 墙钟剩余秒 = burnTicks/(油门×20)——消耗 ∝ 油门，
     *   25% 油门燃料耐用 4 倍显示同步拉长；油门 0 停机不显示倒计时）。
     *  燃料名/服务端低频同步（事件 + 1Hz 心跳），时间用客户端线性外推（P7+ 方案 D，burnTicks 每 tick −效率），
     *  数量用客户端 scanModule 轻扫（同核心 tooltip 模块清单）。 */
    private Component steamFuelValueComponent() {
        if (steamFuelType.equals("fluid") && !steamFuelKey.isEmpty())
            return Component.translatable(steamFuelKey).withStyle(ChatFormatting.GREEN);
        if (steamFuelType.equals("solid") && !steamFuelKey.isEmpty()) {
            int chambers = scanModule().steamChambers().size();
            Component name = Component.translatable(steamFuelKey)
                    .append(Component.literal(" x" + chambers))
                    .withStyle(ChatFormatting.GREEN);
            // P7+ 方案 D：倒计时用客户端线性外推值（低频同步 + 每 tick −效率 精确线性，逐秒平滑跳动）
            float burnTicks = steamBurnTicksDisplay();
            if (burnTicks > 0 && efficiency > 0f) {
                int seconds = Math.max(0, Math.round(burnTicks / efficiency / 20f));
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

    /** 冷却气道 tooltip：温度 + 冷却（= setCooling 风门百分比） */
    private void addCoolingDuctTooltip(List<Component> tooltip) {
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
        // 状态行档位（仅流体引擎；蒸汽走暖机/正常）：停机（!running：油门 0/缺燃料/缺水）/
        // 过冷(<~97) / 正常(97~145) / 高效(145~165 经济带) / 正常(165~200) / 即将过热(200~220) / 过热锁定(≥220，滞回 200 解锁)
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
        } else if (temperature >= OVERHEAT_RESUME) {
            // 即将过热：T ≥ 200（滞回解锁阈值），200~220 预警区，≥220 过热锁定
            statusKey = "tooltip.ccpe.engine.status.warning";
            statusColor = ChatFormatting.GOLD;
        } else if (!steamEngine && temperature >= ENGINE_T_OPT - ECO_FLAT
                && temperature <= ENGINE_T_OPT + ECO_FLAT) {
            // P7：高效 = 经济带 |T−155|≤10（145~165），仅流体引擎
            statusKey = "tooltip.ccpe.engine.status.efficient";
            statusColor = ChatFormatting.AQUA;
        } else if (!steamEngine && coldFactor() > 1.01f) {
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
                .append(Component.literal(String.format(Locale.ROOT, "%.1f%%", efficiency * 100))
                        .withStyle(ChatFormatting.AQUA)));
        // 油耗 / 发热系数（仅流体引擎；蒸汽 Plan B = 恒温自调节、无这两行）
        if (!steamEngine) {
            // 油耗（P7+ 方案 B：客户端按公式现算「最终油耗系数」= 杆值 × 经济系数 × 过冷惩罚，即除油门/自动富油外的全部油耗因子；<1 = 省油中）
            // P7 修订：经济系数/油耗不以冷却气道为门控（无气道同样生效，过冷/经济区无气道可达）——setMixture/setCooling 均无门控（纯存值），冷却气道只是风门数值的消费载体
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.engine.economy").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("×" + String.format(Locale.ROOT, "%.2f", (double) fuelFactor()))
                            .withStyle(fuelFactor() < 1f ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
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
     * 应力容量（SU per RPM，Create 语义）：总容量 = 运行燃烧室数 × 8192 × 燃料 stress 倍率，
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
