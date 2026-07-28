package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class MyModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CCNavigationtable.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<MySensorMenu>> SENSOR_MENU =
            MENUS.register("sensor_menu", () -> IMenuTypeExtension.create(MySensorMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
