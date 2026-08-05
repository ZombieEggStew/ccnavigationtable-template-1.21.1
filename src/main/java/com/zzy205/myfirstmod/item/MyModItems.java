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

    // private static final Item.Properties test_properties = new Item.Properties();
    // public static final DeferredItem<Item> test_item =
    //         MyItems.register("test_item", () -> new Item(test_properties));

    public static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        MyModItems.MyItems.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modEventBus) {
        MyItems.register(modEventBus);
    }
}
