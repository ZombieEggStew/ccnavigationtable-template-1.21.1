package com.zzy205.myfirstmod.item;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class MyModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CCPeripheralExtender.MOD_ID);

    // ── 仪表模块物品 ──
    public static final DeferredItem<Item> MODULE_BUTTON_1 = ITEMS.register(
            "module_button_1", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> MODULE_TOGGLE_SWITCH = ITEMS.register(
            "module_toggle_switch", () -> new Item(new Item.Properties()));
            
    public static final DeferredItem<Item> MODULE_KNOB = ITEMS.register(
            "module_knob", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> MODULE_SCREEN = ITEMS.register(
            "module_screen", () -> new Item(new Item.Properties()));

    // ── controlDesk 控件物品（可安装到控制台上）──
    public static final DeferredItem<Item> CONTROL_PEDAL = ITEMS.register(
            "pedal", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CONTROL_JOYSTICK = ITEMS.register(
            "joystick", () -> new Item(new Item.Properties()));
    // monitor_2 / throttle / joystick_2 共用桌体后缘上方插槽（互斥安装）
    public static final DeferredItem<Item> CONTROL_MONITOR_2 = ITEMS.register(
            "monitor_2", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CONTROL_THROTTLE = ITEMS.register(
            "throttle", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CONTROL_JOYSTICK_2 = ITEMS.register(
            "joystick_2", () -> new Item(new Item.Properties()));
    // joystick_3 = 原始操纵杆（joystick）的换皮版：逻辑完全照抄 joystick（独立配置/轴值/输入租约），只换模型
    public static final DeferredItem<Item> CONTROL_JOYSTICK_3 = ITEMS.register(
            "joystick_3", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CONTROL_THROTTLE_2 = ITEMS.register(
            "throttle_2", () -> new Item(new Item.Properties()));

    // ── 航空电子物品 ──
    // 注：飞行管理计算机（fmc）是方块，BlockItem 由 MyModBlocks.registerBlocks → registerBlockItems 自动注册，
    //     不要在 MyModItems 重复注册同名物品。

    /** 创建指定普通 Monitor 模块的物品栈；未知类型返回空栈。 */
    public static ItemStack monitorModuleStack(ModuleType type) {
        if (type == null) return ItemStack.EMPTY;
        return switch (type) {
            case BUTTON_1X1 -> new ItemStack(MODULE_BUTTON_1.get());
            case TOGGLE_SWITCH -> new ItemStack(MODULE_TOGGLE_SWITCH.get());
            case KNOB -> new ItemStack(MODULE_KNOB.get());
        };
    }

    public static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        ITEMS.register(name, () -> {
            Block b = block.get();
            // P6 物理排斥：燃烧室用自定义物品（ChamberBlockItem#place 拦截「流体/蒸汽混装」放置，
            // DENY 音效 + 状态条提示，同 create:factory_gauge 的 FactoryPanelBlockItem#place 模式）
            if (b instanceof com.zzy205.myfirstmod.block.FluidCombustionChamberBlock
                    || b instanceof com.zzy205.myfirstmod.block.SteamPowerChamberBlock)
                return new ChamberBlockItem(b, new Item.Properties());
            return new BlockItem(b, new Item.Properties());
        });
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
