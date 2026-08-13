package com.zzy205.myfirstmod.monitor;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 模块类型枚举 — 定义每种元件的名称、占用尺寸。
 */
public enum ModuleType {

    BUTTON_1X1("button_1", 1, 1),
    TOGGLE_SWITCH("toggle_switch", 1, 1),
    KNOB("knob", 2, 2),
    ;

    public final String name;
    public final int width;
    public final int height;

    ModuleType(String name, int width, int height) {
        this.name = name;
        this.width = width;
        this.height = height;
    }

    @Nullable
    public static ModuleType byName(String name) {
        for (ModuleType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return null;
    }

    /** 根据手持 ItemStack 判断是否为模块物品并返回对应类型。 */
    @Nullable
    public static ModuleType fromItem(ItemStack stack) {
        if (stack.isEmpty()) return null;
        String key = stack.getItem().toString();
        for (ModuleType t : values()) {
            if (key.equals("ccpe:module_" + t.name)) return t;
        }
        return null;
    }
}
