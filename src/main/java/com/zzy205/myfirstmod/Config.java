package com.zzy205.myfirstmod;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

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

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
