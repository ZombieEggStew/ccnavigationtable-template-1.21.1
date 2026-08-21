package com.zzy205.myfirstmod;

import com.mojang.logging.LogUtils;
import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.MyModBlocks;
import com.zzy205.myfirstmod.compat.cc.CCPeripheralCapabilities;
import com.zzy205.myfirstmod.compat.cc.CCPeripheralExtenderSetup;
import com.zzy205.myfirstmod.item.MyModCreativeModeTabs;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.network.ModPackets;
import com.zzy205.myfirstmod.screen.MyModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CCPeripheralExtender.MOD_ID)
public class CCPeripheralExtender {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "ccpe";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CCPeripheralExtender(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        MyModItems.register(modEventBus);
        MyModCreativeModeTabs.register(modEventBus);

        MyModBlocks.register(modEventBus);
        MyModBlockEntities.register(modEventBus);
        MyModMenus.register(modEventBus);

        // 注册全部自定义网络包（按功能域拆分在 network 包内）
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, ModPackets::register);

        // 注册 CC:T 外设 capability（支持 peripheral.wrap / peripheral.find）
        modEventBus.addListener(RegisterCapabilitiesEvent.class, CCPeripheralCapabilities::register);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("CCPE common setup");

        // 注册 CC:Tweaked 传感器 Lua API
        if (ModList.get().isLoaded("computercraft")) {
            CCPeripheralExtenderSetup.register();
            LOGGER.info("CC:Tweaked sensor API registered");
        }
    }
}
