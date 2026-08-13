package com.zzy205.myfirstmod.item;

import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;
import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.client.ToggleSwitchItemRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

public class MyModItems {
    public static final DeferredRegister.Items MyItems =
            DeferredRegister.createItems(CCPeripheraExtender.MOD_ID);

    // ── 仪表模块物品 ──
    public static final DeferredItem<Item> MODULE_BUTTON_1 = MyItems.register(
            "module_button_1", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> MODULE_TOGGLE_SWITCH = MyItems.register(
            "module_toggle_switch", () -> new Item(new Item.Properties()) {
                @Override
                public void initializeClient(Consumer<IClientItemExtensions> consumer) {
                    consumer.accept(SimpleCustomRenderer.create(this, new ToggleSwitchItemRenderer()));
                }
            });
            
    public static final DeferredItem<Item> MODULE_KNOB = MyItems.register(
            "module_knob", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> MODULE_SCREEN = MyItems.register(
            "module_screen", () -> new Item(new Item.Properties()));

    public static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        MyModItems.MyItems.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modEventBus) {
        MyItems.register(modEventBus);
    }
}
