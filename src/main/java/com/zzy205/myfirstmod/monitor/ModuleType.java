package com.zzy205.myfirstmod.monitor;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 模块类型枚举 — 定义每种元件的名称、占用尺寸、对应物品的注册名。
 */
public enum ModuleType {

    BUTTON_1X1("button_1", "module_button_1", 1, 1),
    TOGGLE_SWITCH("toggle_switch", "module_toggle_switch", 1, 1),
    KNOB("knob", "module_knob", 2, 2),
    ;

    public final String name;
    /** 对应模块物品的注册名（item → ModuleType 的稳定映射键，替代字符串拼接）。 */
    public final ResourceLocation itemId;
    public final int width;
    public final int height;

    ModuleType(String name, String itemPath, int width, int height) {
        this.name = name;
        this.itemId = ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, itemPath);
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
        for (ModuleType t : values()) {
            if (stack.is(BuiltInRegistries.ITEM.get(t.itemId))) return t;
        }
        return null;
    }
}
