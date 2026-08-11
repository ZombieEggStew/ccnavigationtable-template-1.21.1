package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 预加载 Monitor 模块的 BakedModel，仿 control-panels 的 PreLoadedModel 模式。
 * 必须通过 ModelEvent.RegisterAdditional / BakingCompleted 两步烘焙。
 */
public class MonitorPreloadedModels {

    private static final Map<ModuleType, ResourceLocation> LOCATIONS = new EnumMap<>(ModuleType.class);
    private static final Map<ModuleType, BakedModel> MODELS = new EnumMap<>(ModuleType.class);

    static {
        LOCATIONS.put(ModuleType.BUTTON_1X1,
                ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "block/button_1"));
        LOCATIONS.put(ModuleType.BUTTON_2X2,
                ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "block/button_2"));
    }

    /** 触发类加载（在客户端构造中调用） */
    public static void init() {}

    /** 注册 standalone 模型到烘焙管线 */
    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        for (ResourceLocation loc : LOCATIONS.values()) {
            event.register(ModelResourceLocation.standalone(loc));
        }
    }

    /** 烘焙完成后取出 BakedModel */
    public static void bakingCompleted(ModelEvent.BakingCompleted event) {
        for (var entry : LOCATIONS.entrySet()) {
            BakedModel model = event.getModels().get(ModelResourceLocation.standalone(entry.getValue()));
            if (model != null) {
                MODELS.put(entry.getKey(), model);
                CCPeripheraExtender.LOGGER.info("[MonitorPreloadedModels] Baked: {} -> {}", entry.getKey(), model);
            } else {
                CCPeripheraExtender.LOGGER.error("[MonitorPreloadedModels] MISSING: {}", entry.getKey());
            }
        }
    }

    @Nullable
    public static BakedModel getModel(ModuleType type) {
        return MODELS.get(type);
    }
}
