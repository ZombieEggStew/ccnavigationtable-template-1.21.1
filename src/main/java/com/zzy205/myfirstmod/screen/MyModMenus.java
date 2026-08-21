package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class MyModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CCPeripheralExtender.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<PeripheralExtenderMenu>> PERIPHERAL_EXTENDER_MENU =
            MENUS.register("peripheral_extender_menu", () -> IMenuTypeExtension.create(PeripheralExtenderMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<RedstoneTransceiverMenu>> REDSTONE_TRANSCEIVER_MENU =
            MENUS.register("redstone_transceiver_menu", () -> IMenuTypeExtension.create(RedstoneTransceiverMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
