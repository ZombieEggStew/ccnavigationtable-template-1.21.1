package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
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
    public static final String BUTTON_1_HEAD = "button_1_head";
    public static final String BUTTON_1_INDICATOR = "button_1_indicator";

    // 屏幕 9 宫格部件（单一 corner / edge / center 模型 + 代码旋转）
    public static final String SCREEN_CORNER = "screen_corner";
    public static final String SCREEN_EDGE   = "screen_edge";
    public static final String SCREEN_CENTER = "screen_center";
    public static final String PITCH_TEST_CASE = "pitch_test_case";

    /** 背景面板贴图数量（对应 MonitorBackground.KEYS 的下标 0..4） */
    public static final int BACKGROUND_COUNT = 5;
    /** 背景贴图占位模型位置（用于把贴图缝到方块图集上） */
    private static final ResourceLocation[] BG_LOC = new ResourceLocation[BACKGROUND_COUNT];
    /** 缝好的背景贴图精灵，下标 = MonitorBackground.indexOf(key) */
    private static final TextureAtlasSprite[] BG_SPRITE = new TextureAtlasSprite[BACKGROUND_COUNT];

    private static final RandomSource RANDOM = RandomSource.create(42L);

    static {
        MAIN_LOC.put(ModuleType.BUTTON_1X1, rl("block/button_1/button_1_base"));
        MAIN_LOC.put(ModuleType.TOGGLE_SWITCH, rl("block/toggle/toggle_base"));
        MAIN_LOC.put(ModuleType.KNOB, rl("block/knob_1/knob_1_base"));
        EXTRA_LOC.put(TOGGLE_LEVER, rl("block/toggle/toggle"));
        EXTRA_LOC.put(KNOB_HANDLE, rl("block/knob_1/knob_1"));
        EXTRA_LOC.put(BUTTON_1_HEAD, rl("block/button_1/button_1_head"));
        EXTRA_LOC.put(BUTTON_1_INDICATOR, rl("block/button_1/button_1_indicator"));
        EXTRA_LOC.put(SCREEN_CORNER, rl("block/screen/screen_corner"));
        EXTRA_LOC.put(SCREEN_EDGE,   rl("block/screen/screen_edge"));
        EXTRA_LOC.put(SCREEN_CENTER, rl("block/screen/screen_center"));
        EXTRA_LOC.put(PITCH_TEST_CASE, rl("block/monitor/my_monitor_case"));
        for (int i = 0; i < BACKGROUND_COUNT; i++) {
            BG_LOC[i] = rl("block/monitor_bg/bg_" + i);
        }
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, path);
    }

    public static void init() {}

    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        MAIN_LOC.values().forEach(loc -> event.register(ModelResourceLocation.standalone(loc)));
        EXTRA_LOC.values().forEach(loc -> event.register(ModelResourceLocation.standalone(loc)));
        for (ResourceLocation loc : BG_LOC) {
            event.register(ModelResourceLocation.standalone(loc));
        }
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
        for (int i = 0; i < BACKGROUND_COUNT; i++) {
            BakedModel m = event.getModels().get(ModelResourceLocation.standalone(BG_LOC[i]));
            if (m == null) {
                CCPeripheraExtender.LOGGER.error("[Models] MISSING bg: {}", i);
                continue;
            }
            var quads = m.getQuads(null, null, RANDOM, ModelData.EMPTY, null);
            if (!quads.isEmpty()) {
                BG_SPRITE[i] = quads.get(0).getSprite();
            } else {
                CCPeripheraExtender.LOGGER.error("[Models] bg has no quad: {}", i);
            }
        }
    }

    @Nullable
    public static BakedModel getModel(ModuleType type) { return MAIN_MODEL.get(type); }

    @Nullable
    public static BakedModel getExtra(String key) { return EXTRA_MODEL.get(key); }

    @Nullable
    public static BakedModel getPitchTestCase() { return EXTRA_MODEL.get(PITCH_TEST_CASE); }

    /** 按下标取背景贴图精灵（下标来自 {@code MonitorBackground.indexOf(key)}）。 */
    @Nullable
    public static TextureAtlasSprite getBackgroundSprite(int index) {
        if (index < 0 || index >= BACKGROUND_COUNT) return BG_SPRITE[0];
        return BG_SPRITE[index];
    }
}
