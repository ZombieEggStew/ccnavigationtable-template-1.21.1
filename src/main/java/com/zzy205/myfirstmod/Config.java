package com.zzy205.myfirstmod;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 配置文件（ccpe-common.toml / ccpe-client.toml）。
 * <p>
 * 各配置项的标题与说明在 NeoForge 配置界面（Mods → CC Peripheral Extender → Config）中
 * 通过 lang 文件（assets/ccpe/lang/en_us.json / zh_cn.json）本地化：
 * <ul>
 * <li>标题 = {@code ccpe.configuration.<配置路径>}</li>
 * <li>说明 = {@code ccpe.configuration.<配置路径>.tooltip}</li>
 * </ul>
 * 注意：NeoForge 21.1 的 {@link ModConfigSpec} <b>不要</b>对每个配置项调用 {@code .translation()}——
 * 配置界面会把该值<b>原样</b>当作标题 key（不追加配置路径，所有条目同 key 会显示为原始前缀）；
 * 不设置时界面回退到 {@code <modId>.configuration.<路径>}（= {@code ccpe.configuration.<路径>}，
 * 与 lang key 一致，实测标题/说明均正常解析）。{@code .comment()} 文本仍写入 TOML，
 * 作为 lang 缺失时的回退说明。
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SENSOR_NBT_POLL_INTERVAL = BUILDER
            .comment("Sensor NBT viewer poll interval in ticks (20 ticks = 1 second). Set to 0 to disable auto-refresh.")
            .defineInRange("sensorNbtPollInterval", 20, 0, 200);

    public static final ModConfigSpec.BooleanValue SENSOR_CHUNK_LOAD_ENABLED = BUILDER
            .comment("Whether sensors automatically force-load a 3x3 chunk area around themselves.")
            .define("sensorChunkLoadEnabled", true);

    public static final ModConfigSpec.IntValue SENSOR_MAX_FORCE_LOAD = BUILDER
            .comment("Maximum number of sensors that can simultaneously force-load chunks. Set to 0 for unlimited.")
            .defineInRange("sensorMaxForceLoad", 32, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue SENSOR_PORTAL_TICKET_RADIUS = BUILDER
            .comment("PORTAL ticket radius (in chunks) for Sable physics structures with sensors attached. Each sensor loads (2*R+1)² chunks around the structure's world position. Default: 3 (= 7×7 chunks).")
            .defineInRange("sensorPortalTicketRadius", 3, 1, 32);

    public static final ModConfigSpec.DoubleValue SERVO_STRESS_IMPACT = BUILDER
            .comment("Stress coefficient for the Transmission Peripheral in transmission mode: actual stress = value × |output RPM − input RPM|.")
            .defineInRange("servoStressImpact", 2.0, 0.0, 1024.0);

    public static final ModConfigSpec.DoubleValue SERVO_MODE_STRESS_IMPACT = BUILDER
            .comment("Stress coefficient for the Transmission Peripheral in servo mode: actual stress = value × |real output RPM| (the output speed may be overridden/accelerated by setServoSpeed).")
            .defineInRange("servoModeStressImpact", 2.0, 0.0, 1024.0);

    // ── 引擎源抽取批次倍数（P2.5 批量补料：批次 = 引擎数量 × K，进游戏时缓存一次）──
    public static final ModConfigSpec.DoubleValue ENGINE_SOURCE_BATCH_MULTIPLIER = BUILDER
            .comment("Engine source batch multiplier (K): each refill drains chamber count × K (water/fuel mb, or solid fuel items). "
                    + "Higher = fewer refills/availability checks but larger single drains. Cached once when entering a world.")
            .defineInRange("engineSourceBatchMultiplier", 1.0, 0.25, 64.0);

    // ── 引擎水冷模块应力消耗（每工作模块恒定 SU，进游戏时缓存一次）──
    public static final ModConfigSpec.DoubleValue ENGINE_WATER_COOLING_STRESS = BUILDER
            .comment("Stress (SU) each water cooling module takes from the engine's kinetic network. "
                    + "Constant per working module (tank holds coolant, loop opening > 0), independent of RPM / coolant amount / efficiency. "
                    + "Applies to fluid engines only (water cooling has no effect on steam engines). 0 = water cooling is free. "
                    + "Cached once when entering a world.")
            .defineInRange("engineWaterCoolingStress", 512.0, 0.0, 65536.0);

    // ── 调试：飞行数据记录器（方案 B）──
    public static final ModConfigSpec.BooleanValue FLIGHT_RECORDER_ENABLED = BUILDER
            .comment("Flight data recorder (debug): log per-tick body data for every FMC/AIC/INS-registered aircraft into <gameDir>/flight_logs/*.csv "
                    + "(see compat/cc/FlightDataRecorder.java). Active while at least one FMC/AIC/INS sensor is on a Sable physics body "
                    + "(INS-only bodies get kinematics/velocity columns; physics columns read nan). "
                    + "Default OFF; enable in the config only when you need to record a flight.")
            .define("flightDataRecorderEnabled", false);

    public static final ModConfigSpec.IntValue FLIGHT_RECORDER_INTERVAL_TICKS = BUILDER
            .comment("Flight data recorder sampling interval in ticks (1 = every tick / 20 Hz).")
            .defineInRange("flightDataRecorderIntervalTicks", 1, 1, 200);

    // ── 客户端：Monitor 模块渲染 ──
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MONITOR_OUTLINE_R = CLIENT_BUILDER
            .comment("Monitor 模块边框颜色 - 红 (0-255)。默认: 90")
            .defineInRange("monitorOutlineR", 90, 0, 255);

    public static final ModConfigSpec.IntValue MONITOR_OUTLINE_G = CLIENT_BUILDER
            .comment("Monitor 模块边框颜色 - 绿 (0-255)。默认: 143")
            .defineInRange("monitorOutlineG", 143, 0, 255);

    public static final ModConfigSpec.IntValue MONITOR_OUTLINE_B = CLIENT_BUILDER
            .comment("Monitor 模块边框颜色 - 蓝 (0-255)。默认: 60")
            .defineInRange("monitorOutlineB", 60, 0, 255);

    public static final ModConfigSpec.IntValue MONITOR_OUTLINE_A = CLIENT_BUILDER
            .comment("Monitor 模块边框颜色 - 透明度 (0-255)。默认: 255")
            .defineInRange("monitorOutlineA", 255, 0, 255);

    public static final ModConfigSpec.DoubleValue MONITOR_GRID_LINE_WIDTH = CLIENT_BUILDER
            .comment("Monitor 棋盘网格线粗细倍率 (0.0-2.0)。默认: 1.0")
            .defineInRange("monitorGridLineWidth", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.DoubleValue MONITOR_OUTLINE_LINE_WIDTH = CLIENT_BUILDER
            .comment("Monitor 模块边框/预览框线条粗细倍率 (0.0-2.0)。默认: 1.0")
            .defineInRange("monitorOutlineLineWidth", 1.0, 0.0, 2.0);

    // ── 客户端：坐垫操作模式 HUD ──
    public static final ModConfigSpec.BooleanValue JOYSTICK_OVERLAY_ENABLED = CLIENT_BUILDER
            .comment("坐垫操作模式下显示虚拟摇杆 HUD（默认关闭，避免破坏沉浸感；需要时在客户端配置中开启）。")
            .define("joystickOverlayEnabled", false);

    // ── 客户端：引擎活塞"噗嗤"音效 ──
    public static final ModConfigSpec.DoubleValue ENGINE_FLUID_PUFF_VOLUME = CLIENT_BUILDER
            .comment("流体燃烧室活塞'噗嗤'音效音量 (0.0-2.0，0 = 关闭该音效逻辑节省性能)。默认: 0.05（每活塞周期触发一次，Create STEAM 声，频率随转速）。")
            .defineInRange("engineFluidPuffVolume", 0.05, 0.0, 2.0);

    public static final ModConfigSpec.DoubleValue ENGINE_STEAM_PUFF_VOLUME = CLIENT_BUILDER
            .comment("蒸汽动力室活塞'噗嗤'音效音量 (0.0-2.0，0 = 关闭该音效逻辑节省性能)。默认: 0.1（每活塞周期触发一次，Create STEAM 声，频率随转速）。")
            .defineInRange("engineSteamPuffVolume", 0.1, 0.0, 2.0);

    // ═══════════════ 客户端：定向喷管粒子（性能主旋钮） ═══════════════
    //
    // 全部是<b>纯客户端</b>参数：尾迹/排气粒子只在客户端生成与推进（ThrustNozzleBlockEntity#tickClient、
    // client.particle.ContrailParticle / ExhaustParticle），所以放进 ccpe-client.toml。
    // 同屏粒子数 ≈ 发射密度 × 寿命，这两个（contrailSpacing / contrailLifeMax）是最大的性能杠杆；
    // contrailMaxLive 是硬上限（满了停发），防止把寿命调到几分钟后把帧率打死。

    public static final ModConfigSpec.BooleanValue CONTRAIL_ENABLED = CLIENT_BUILDER
            .comment("高空尾迹总开关。关闭 = 完全没有尾迹（只剩喷口排气）。默认: 开启")
            .define("contrailEnabled", true);

    public static final ModConfigSpec.IntValue CONTRAIL_LIFE_MIN = CLIENT_BUILDER
            .comment("尾迹寿命下限（tick，20 tick = 1 秒）：适宜度 s 越低越接近它。默认: 360 (18 秒)")
            .defineInRange("contrailLifeMin", 360, 20, 24000);

    public static final ModConfigSpec.IntValue CONTRAIL_LIFE_MAX = CLIENT_BUILDER
            .comment("尾迹寿命上限（tick）：适宜度越高越接近它（Y 180~240 为满）。<b>性能主旋钮</b> —— 同屏粒子数 ≈ 发射密度 × 寿命。默认: 1200 (60 秒)")
            .defineInRange("contrailLifeMax", 1200, 20, 24000);

    public static final ModConfigSpec.DoubleValue CONTRAIL_SPACING = CLIENT_BUILDER
            .comment("尾迹基础发射间距（格，适宜度 s=1 时）：实际间距 = 本值 ÷ s ÷ 有效油门。"
                    + "适宜度是纯高度线性曲线（Y100 起、180~240 满、320 归零）⇒ 密度与高度严格线性。<b>性能主旋钮</b>。默认: 1.2")
            .defineInRange("contrailSpacing", 1.2, 0.1, 32.0);

    public static final ModConfigSpec.IntValue CONTRAIL_MAX_PER_TICK = CLIENT_BUILDER
            .comment("尾迹单 tick 最多补发几颗（防低帧率/瞬移时一帧吐出一长串）。默认: 6")
            .defineInRange("contrailMaxPerTick", 6, 1, 64);

    public static final ModConfigSpec.IntValue CONTRAIL_MAX_LIVE = CLIENT_BUILDER
            .comment("尾迹粒子存活总数上限（0 = 不限）。性能安全阀：满了就停止发射，旧粒子死亡后腾出配额；"
                    + "表现为「尾迹长到某处不再延长」而不是掉帧。默认: 6000")
            .defineInRange("contrailMaxLive", 6000, 0, 200000);

    public static final ModConfigSpec.DoubleValue CONTRAIL_MAX_DISTANCE = CLIENT_BUILDER
            .comment("尾迹发射距离上限（格，0 = 不限）：离最近玩家超过它就不再发射（远处看不见还每 tick 推进是纯浪费）。默认: 96")
            .defineInRange("contrailMaxDistance", 96.0, 0.0, 512.0);

    public static final ModConfigSpec.DoubleValue CONTRAIL_WIDTH = CLIENT_BUILDER
            .comment("尾迹出生宽度（格，quad 半径；可见宽度约 2 倍），寿命内按 sqrt(t) 继续扩散。越大越糊、填充率开销越大。默认: 1.0")
            .defineInRange("contrailWidth", 1.0, 0.05, 8.0);

    public static final ModConfigSpec.IntValue CONTRAIL_WIDTH_TAU = CLIENT_BUILDER
            .comment("尾迹扩散时间常数（tick）：宽度 = 出生宽度 × sqrt(1 + age/本值)，越小散得越快。"
                    + "经验值 ≈ 寿命/4（寿命改了记得一起改）。默认: 300")
            .defineInRange("contrailWidthTau", 300, 10, 24000);

    public static final ModConfigSpec.DoubleValue CONTRAIL_ALPHA = CLIENT_BUILDER
            .comment("尾迹出生不透明度上限（0~1）：实际会随扩散倍数同步变淡（质量守恒），再叠淡入淡出。默认: 0.6")
            .defineInRange("contrailAlpha", 0.6, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue CONTRAIL_DRIFT_DISTANCE = CLIENT_BUILDER
            .comment("单颗粒子一生飘荡距离（格）：速度 = 本值 ÷ 寿命，方向随机、匀速直线。越大尾迹越蓬松。默认: 4.0")
            .defineInRange("contrailDriftDistance", 4.0, 0.0, 64.0);

    // ── 尾迹「喷出初速度」：沿排气方向的额外初速度，大小/方向随机，之后指数衰减 ──

    public static final ModConfigSpec.DoubleValue CONTRAIL_BURST_SPEED_MIN = CLIENT_BUILDER
            .comment("尾迹粒子的<b>喷出初速度下限</b>（格/tick，1 = 20 格/秒）：出生时除了随机飘荡，"
                    + "再沿<b>排气方向</b>额外获得一个初速度，之后按 contrailBurstDamp 逐渐减小。"
                    + "每颗粒子的初速度大小在本项与 contrailBurstSpeedMax 之间随机。"
                    + "总喷出距离 ≈ 初速度 ÷ (1 − contrailBurstDamp)。0 = 关闭喷出（回到旧版纯匀速飘荡）。默认: 0.08")
            .defineInRange("contrailBurstSpeedMin", 0.08, 0.0, 8.0);

    public static final ModConfigSpec.DoubleValue CONTRAIL_BURST_SPEED_MAX = CLIENT_BUILDER
            .comment("尾迹粒子的<b>喷出初速度上限</b>（格/tick）：每颗粒子随机取 [下限, 上限]。"
                    + "与下限相等 = 所有粒子同速。默认: 0.25")
            .defineInRange("contrailBurstSpeedMax", 0.25, 0.0, 8.0);

    public static final ModConfigSpec.DoubleValue CONTRAIL_BURST_SPREAD = CLIENT_BUILDER
            .comment("喷出初速度的<b>方向锥半角</b>（度，0~180）：0 = 全部严格沿排气方向；"
                    + "20 = 在排气方向周围 20° 的锥内随机（球冠内均匀取）；180 = 整个球面随机。默认: 25")
            .defineInRange("contrailBurstSpread", 25.0, 0.0, 180.0);

    public static final ModConfigSpec.DoubleValue CONTRAIL_BURST_DAMP = CLIENT_BUILDER
            .comment("喷出初速度的<b>每 tick 保留比例</b>（0.5~0.999）：v → v × 本值，所以速度大小逐渐减小。"
                    + "越小衰减越快、喷出距离越短；0.94 ⇒ 每秒剩约 29%、总喷出距离 ≈ 初速度 × 16.7。默认: 0.94")
            .defineInRange("contrailBurstDamp", 0.94, 0.5, 0.999);

    public static final ModConfigSpec.IntValue EXHAUST_PARTICLES_PER_TICK = CLIENT_BUILDER
            .comment("排气粒子在满转速（256 RPM）时每 tick 生成的颗数，按 |转速|/256 线性缩放。<b>性能主旋钮</b>。默认: 10")
            .defineInRange("exhaustParticlesPerTick", 10, 0, 128);

    public static final ModConfigSpec.IntValue EXHAUST_LIFETIME = CLIENT_BUILDER
            .comment("排气粒子基准寿命（tick），实际再加 0~9 的随机。默认: 16")
            .defineInRange("exhaustLifetime", 16, 2, 600);

    public static final ModConfigSpec.DoubleValue EXHAUST_SCALE = CLIENT_BUILDER
            .comment("排气粒子出生尺寸（格）。默认: 0.3")
            .defineInRange("exhaustScale", 0.3, 0.01, 8.0);

    public static final ModConfigSpec.DoubleValue EXHAUST_ALPHA = CLIENT_BUILDER
            .comment("排气粒子的不透明度<b>上限</b>（0~1，0 = 完全透明/看不见）。这是出生淡入后最浓的值，"
                    + "之后随寿命单调变淡、死亡时降到 <b>exhaustAlphaMin</b>（见那一项的说明）。"
                    + "曲线 = (下限 + (本值−下限) × (1−寿命进度)^衰减指数) × 淡入。整体调浓/调淡都改这里。默认: 0.62")
            .defineInRange("exhaustAlpha", 0.62, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue EXHAUST_ALPHA_MIN = CLIENT_BUILDER
            .comment("排气粒子的不透明度<b>下限</b>（0~1）：寿命末期会衰减到的最淡值，也就是\"残留下限\"。"
                    + "0 = 旧行为（一路淡到完全透明）；>0 时尾段不会淡没，粒子一直可见到寿命结束才被移除"
                    + "（因此尾段可能看到轻微\"硬切\"）。会自动夹到不超过 exhaustAlpha。默认: 0.0")
            .defineInRange("exhaustAlphaMin", 0.0, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue EXHAUST_SPEED = CLIENT_BUILDER
            .comment("排气粒子出生速度（格/tick，1 = 20 格/秒），实际再乘 (0.65 + 0.35×油门)。默认: 2.0")
            .defineInRange("exhaustSpeed", 2.0, 0.0, 16.0);

    public static final ModConfigSpec.DoubleValue EXHAUST_GRAVITY = CLIENT_BUILDER
            .comment("排气粒子的轻微重力（格/tick²，0 = 不受重力）。实际终端下沉速度 = 重力 × f/(1−f)，"
                    + "f = 0.965×(1−0.28×寿命进度)，所以下坠是前重后轻的（出生后最快、末期几乎挂住）。"
                    + "默认: 0.02（用户进游戏实测定稿）")
            .defineInRange("exhaustGravity", 0.02, 0.0, 0.1);

    public static final ModConfigSpec.DoubleValue EXHAUST_MAX_DISTANCE = CLIENT_BUILDER
            .comment("排气粒子发射距离上限（格，0 = 不限）。原版 addParticle 有 32 格硬限制，本模组用 9 参数 "
                    + "addAlwaysVisibleParticle 绕过，所以这里自己给上限。默认: 64")
            .defineInRange("exhaustMaxDistance", 64.0, 0.0, 512.0);

    // ── 客户端：进气粒子（负转速 = 吸力时，空气被吸进口面） ──

    public static final ModConfigSpec.BooleanValue INTAKE_ENABLED = CLIENT_BUILDER
            .comment("吸力（负转速）时的进气粒子总开关。关闭 = 反转风扇时喷口完全没有粒子。默认: 开启")
            .define("intakeEnabled", true);

    public static final ModConfigSpec.IntValue INTAKE_PARTICLES_PER_TICK = CLIENT_BUILDER
            .comment("进气粒子在满转速（256 RPM）时每 tick 生成的颗数，按 |转速|/256 线性缩放。<b>性能主旋钮</b>。默认: 8")
            .defineInRange("intakeParticlesPerTick", 8, 0, 128);

    public static final ModConfigSpec.DoubleValue INTAKE_FUNNEL_LENGTH = CLIENT_BUILDER
            .comment("进气漏斗长度（格）：<b>满转速 256 RPM 时</b>粒子最远在喷口前方这么远的位置出生，"
                    + "然后加速被吸进口面。实际按 |转速|/256 线性缩放（128 RPM ⇒ 一半，64 RPM ⇒ 四分之一）。"
                    + "越大越像\"长吸气柱\"。默认: 8.0")
            .defineInRange("intakeFunnelLength", 8.0, 0.4, 32.0);

    public static final ModConfigSpec.DoubleValue INTAKE_SPEED = CLIENT_BUILDER
            .comment("进气速度（格/tick，1 = 20 格/秒）：寿命 = 出生距离 ÷ 本值，所以调大 = 吸得更快、粒子更短命；"
                    + "实际再乘 (0.6 + 0.4×油门)。默认: 0.5")
            .defineInRange("intakeSpeed", 0.5, 0.05, 8.0);

    public static final ModConfigSpec.DoubleValue INTAKE_SCALE = CLIENT_BUILDER
            .comment("进气粒子出生尺寸（格），寿命内缩小到 0.3 倍后消失。默认: 0.28")
            .defineInRange("intakeScale", 0.28, 0.01, 8.0);

    public static final ModConfigSpec.DoubleValue INTAKE_ALPHA = CLIENT_BUILDER
            .comment("进气粒子的不透明度<b>上限</b>（0~1，0 = 完全透明/看不见）。这是淡入后最浓的值，"
                    + "之后随寿命一路变淡、到口面降到 <b>intakeAlphaMin</b>。"
                    + "曲线 = (下限 + (本值−下限) × (1−寿命进度)^0.7) × 2 tick 淡入。默认: 0.55")
            .defineInRange("intakeAlpha", 0.55, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue INTAKE_ALPHA_MIN = CLIENT_BUILDER
            .comment("进气粒子的不透明度<b>下限</b>（0~1）：被吸到口面时衰减到的最淡值。"
                    + "0 = 旧行为（到口面前完全透明、像被吞掉）；>0 时口面上会留下一小片淡淡的粒子。"
                    + "会自动夹到不超过 intakeAlpha。默认: 0.0")
            .defineInRange("intakeAlphaMin", 0.0, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue INTAKE_MAX_DISTANCE = CLIENT_BUILDER
            .comment("进气粒子发射距离上限（格，0 = 不限）：离最近玩家超过它就不再发射。默认: 64")
            .defineInRange("intakeMaxDistance", 64.0, 0.0, 512.0);

    static final ModConfigSpec SPEC = BUILDER.build();
    static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
}
