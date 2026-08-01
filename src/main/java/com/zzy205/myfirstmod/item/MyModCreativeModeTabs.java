package com.zzy205.myfirstmod.item;

import com.zzy205.myfirstmod.CCNavigationtable;
import com.zzy205.myfirstmod.block.MyModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class MyModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> MY_MOD_TAB =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB , CCNavigationtable.MOD_ID);

    public static final Supplier<CreativeModeTab> MY_MOD_TAB_SUPPLIER =
            MY_MOD_TAB.register("my_mod_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.ccpe.my_mod_tab"))
            .icon(() -> new ItemStack(MyModItems.test_item.get()))
            .displayItems((parameters, output) -> {
                output.accept(MyModItems.test_item);
                output.accept(MyModBlocks.test_block);
                output.accept(MyModBlocks.micro_peripheral_extender);
                output.accept(MyModBlocks.redstone_transceiver);
            })
            .build());
    public static void register(IEventBus modEventBus) {
        MY_MOD_TAB.register(modEventBus);
    }
}
