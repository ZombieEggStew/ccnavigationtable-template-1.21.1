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
            .comment("尾迹寿命下限（tick，20 tick = 1 秒）：适宜度为 0 时用这个值。默认: 360 (18 秒)")
            .defineInRange("contrailLifeMin", 360, 20, 24000);

    public static final ModConfigSpec.IntValue CONTRAIL_LIFE_MAX = CLIENT_BUILDER
            .comment("尾迹寿命上限（tick）：越冷越接近它。<b>性能主旋钮</b> —— 同屏粒子数 ≈ 发射密度 × 寿命。默认: 1200 (60 秒)")
            .defineInRange("contrailLifeMax", 1200, 20, 24000);

    public static final ModConfigSpec.DoubleValue CONTRAIL_SPACING = CLIENT_BUILDER
            .comment("尾迹发射间距（格）：机体每飞这么远吐一颗（温度不够冷时会按适宜度变稀，最多稀到 1/0.35）。<b>性能主旋钮</b>。默认: 1.2")
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

    public static final ModConfigSpec.IntValue EXHAUST_PARTICLES_PER_TICK = CLIENT_BUILDER
            .comment("排气粒子在满转速（256 RPM）时每 tick 生成的颗数，按 |转速|/256 线性缩放。<b>性能主旋钮</b>。默认: 10")
            .defineInRange("exhaustParticlesPerTick", 10, 0, 128);

    public static final ModConfigSpec.IntValue EXHAUST_LIFETIME = CLIENT_BUILDER
            .comment("排气粒子基准寿命（tick），实际再加 0~9 的随机。默认: 16")
            .defineInRange("exhaustLifetime", 16, 2, 600);

    public static final ModConfigSpec.DoubleValue EXHAUST_SCALE = CLIENT_BUILDER
            .comment("排气粒子出生尺寸（格）。默认: 0.3")
            .defineInRange("exhaustScale", 0.3, 0.01, 8.0);

    public static final ModConfigSpec.DoubleValue EXHAUST_SPEED = CLIENT_BUILDER
            .comment("排气粒子出生速度（格/tick，1 = 20 格/秒），实际再乘 (0.65 + 0.35×油门)。默认: 2.0")
            .defineInRange("exhaustSpeed", 2.0, 0.0, 16.0);

    public static final ModConfigSpec.DoubleValue EXHAUST_MAX_DISTANCE = CLIENT_BUILDER
            .comment("排气粒子发射距离上限（格，0 = 不限）。原版 addParticle 有 32 格硬限制，本模组用 9 参数 "
                    + "addAlwaysVisibleParticle 绕过，所以这里自己给上限。默认: 64")
            .defineInRange("exhaustMaxDistance", 64.0, 0.0, 512.0);

    static final ModConfigSpec SPEC = BUILDER.build();
    static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
}
