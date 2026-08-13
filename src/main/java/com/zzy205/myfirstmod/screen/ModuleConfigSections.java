package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.monitor.ModuleType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 模块类型 → 专属配置区工厂的注册表。
 * 与 ModuleRenderBehavior 同构：只在有特殊设置的模块类型上注册，其余走空实现。
 */
public final class ModuleConfigSections {

    @FunctionalInterface
    public interface Factory {
        ModuleConfigSection create();
    }

    private static final Map<ModuleType, Factory> REGISTRY = new EnumMap<>(ModuleType.class);

    static {
        REGISTRY.put(ModuleType.BUTTON_1X1, ButtonConfigSection::new);
        REGISTRY.put(ModuleType.BUTTON_2X2, ButtonConfigSection::new);
        // TOGGLE_SWITCH / KNOB：暂无特殊设置，走 Empty
    }

    private ModuleConfigSections() {}

    /** name 为模块类型名或 "screen"。 */
    public static ModuleConfigSection create(String name) {
        if ("screen".equals(name)) return ModuleConfigSection.Empty.INSTANCE;
        ModuleType type = ModuleType.byName(name);
        if (type == null) return ModuleConfigSection.Empty.INSTANCE;
        Factory factory = REGISTRY.get(type);
        return factory == null ? ModuleConfigSection.Empty.INSTANCE : factory.create();
    }
}
