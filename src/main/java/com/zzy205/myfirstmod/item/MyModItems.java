package com.zzy205.myfirstmod.item;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class MyModItems {
    public static final DeferredRegister.Items MyItems =
            DeferredRegister.createItems(CCPeripheraExtender.MOD_ID);

    // ── 仪表模块物品 ──
    public static final DeferredItem<Item> MODULE_BUTTON_1 = MyItems.register(
            "module_button_1", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> MODULE_BUTTON_2 = MyItems.register(
            "module_button_2", () -> new Item(new Item.Properties()));

    public static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        MyModItems.MyItems.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modEventBus) {
        MyItems.register(modEventBus);
    }
}
