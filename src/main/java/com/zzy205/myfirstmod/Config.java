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

    static final ModConfigSpec SPEC = BUILDER.build();
}
