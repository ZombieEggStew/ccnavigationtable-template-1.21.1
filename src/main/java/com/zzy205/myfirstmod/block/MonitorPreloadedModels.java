package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 预加载 Monitor 模块的 BakedModel，仿 control-panels 的 PreLoadedModel 模式。
 */
public class MonitorPreloadedModels {

    /** 模块主模型 */ 
    private static final Map<ModuleType, ResourceLocation> MAIN_LOC = new EnumMap<>(ModuleType.class);
    private static final Map<ModuleType, BakedModel> MAIN_MODEL = new EnumMap<>(ModuleType.class);

    /** 额外子部件模型（如钮子开关拉杆） */
    private static final Map<String, ResourceLocation> EXTRA_LOC = new HashMap<>();
    private static final Map<String, BakedModel> EXTRA_MODEL = new HashMap<>();

    public static final String TOGGLE_LEVER = "toggle_lever";
    public static final String KNOB_HANDLE = "knob_handle";

    static {
        MAIN_LOC.put(ModuleType.BUTTON_1X1, rl("block/button_1"));
        MAIN_LOC.put(ModuleType.BUTTON_2X2, rl("block/button_2"));
        MAIN_LOC.put(ModuleType.TOGGLE_SWITCH, rl("block/toggle/toggle_base"));
        MAIN_LOC.put(ModuleType.KNOB, rl("block/knob_1/knob_1_base"));
        EXTRA_LOC.put(TOGGLE_LEVER, rl("block/toggle/toggle"));
        EXTRA_LOC.put(KNOB_HANDLE, rl("block/knob_1/knob_1"));
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, path);
    }

    public static void init() {}

    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        MAIN_LOC.values().forEach(loc -> event.register(ModelResourceLocation.standalone(loc)));
        EXTRA_LOC.values().forEach(loc -> event.register(ModelResourceLocation.standalone(loc)));
    }

    public static void bakingCompleted(ModelEvent.BakingCompleted event) {
        for (var e : MAIN_LOC.entrySet()) {
            BakedModel m = event.getModels().get(ModelResourceLocation.standalone(e.getValue()));
            if (m != null) { MAIN_MODEL.put(e.getKey(), m); }
            else CCPeripheraExtender.LOGGER.error("[Models] MISSING main: {}", e.getKey());
        }
        for (var e : EXTRA_LOC.entrySet()) {
            BakedModel m = event.getModels().get(ModelResourceLocation.standalone(e.getValue()));
            if (m != null) { EXTRA_MODEL.put(e.getKey(), m); }
            else CCPeripheraExtender.LOGGER.error("[Models] MISSING extra: {}", e.getKey());
        }
    }

    @Nullable
    public static BakedModel getModel(ModuleType type) { return MAIN_MODEL.get(type); }

    @Nullable
    public static BakedModel getExtra(String key) { return EXTRA_MODEL.get(key); }
}
