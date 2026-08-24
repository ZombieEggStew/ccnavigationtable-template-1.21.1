package com.zzy205.myfirstmod;

import net.neoforged.neoforge.common.ModConfigSpec;

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

    static final ModConfigSpec SPEC = BUILDER.build();
    static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
}
