package com.zzy205.myfirstmod;

import com.zzy205.myfirstmod.screen.MyModMenus;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverScreen;
import com.zzy205.myfirstmod.screen.PeripheralExtenderScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CCPeripheraExtender.MOD_ID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CCPeripheraExtender.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CCPeripheralExtenderClient {
    public CCPeripheralExtenderClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        CCPeripheraExtender.LOGGER.info("HELLO FROM CLIENT SETUP");
        CCPeripheraExtender.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), PeripheralExtenderScreen::new);
        event.register(MyModMenus.REDSTONE_TRANSCEIVER_MENU.get(), RedstoneTransceiverScreen::new);
    }
}
