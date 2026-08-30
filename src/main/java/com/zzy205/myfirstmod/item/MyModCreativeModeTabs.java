package com.zzy205.myfirstmod.item;

import com.zzy205.myfirstmod.CCPeripheralExtender;
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
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB , CCPeripheralExtender.MOD_ID);

    public static final Supplier<CreativeModeTab> MY_MOD_TAB_SUPPLIER =
            MY_MOD_TAB.register("my_mod_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.ccpe.my_mod_tab"))
            .icon(() -> new ItemStack(MyModBlocks.micro_peripheral_extender.get()))
            .displayItems((parameters, output) -> {
                output.accept(MyModBlocks.micro_peripheral_extender);
                output.accept(MyModBlocks.redstone_transceiver);
                output.accept(MyModBlocks.transmission_peripheral);
                output.accept(MyModBlocks.monitor);
                output.accept(MyModBlocks.my_control_desk);
                output.accept(MyModBlocks.aero_bearing);
                output.accept(MyModBlocks.static_port);
                output.accept(MyModBlocks.pitot_tube);
                output.accept(MyModBlocks.my_aero_sensor);
                output.accept(MyModItems.CONTROL_PEDAL);
                output.accept(MyModItems.CONTROL_JOYSTICK);
                output.accept(MyModItems.CONTROL_MONITOR_2);
                output.accept(MyModItems.CONTROL_THROTTLE);
                output.accept(MyModItems.CONTROL_JOYSTICK_2);
                output.accept(MyModItems.CONTROL_THROTTLE_2);
                output.accept(MyModItems.MODULE_BUTTON_1);
                output.accept(MyModItems.MODULE_TOGGLE_SWITCH);
                output.accept(MyModItems.MODULE_KNOB);
                output.accept(MyModItems.MODULE_SCREEN);
            })
            .build());
    public static void register(IEventBus modEventBus) {
        MY_MOD_TAB.register(modEventBus);
    }
}
