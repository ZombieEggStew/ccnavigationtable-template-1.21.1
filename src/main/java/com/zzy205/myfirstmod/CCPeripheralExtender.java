package com.zzy205.myfirstmod;

import com.mojang.logging.LogUtils;
import com.tterrag.registrate.Registrate;
import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.MyModBlocks;
import com.zzy205.myfirstmod.compat.cc.CCPeripheralCapabilities;
import com.zzy205.myfirstmod.compat.cc.CCPeripheralExtenderSetup;
import com.zzy205.myfirstmod.compat.cc.GlobalChannelRegistry;
import com.zzy205.myfirstmod.compat.cc.SensorSystemAPI;
import com.zzy205.myfirstmod.compat.cc.ShortRangeLinkerRegistry;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CCPeripheralExtender.MOD_ID)
public class CCPeripheralExtender {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "ccpe";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Registrate 实例：{@link Registrate#create} 内部已挂载注册与数据生成事件
     * （RegisterEvent / GatherDataEvent 等，见 Registrate.create → registerEventListeners），
     * 无需手动 registerEventListeners。
     */
    public static final Registrate REGISTRATE = Registrate.create(MOD_ID);

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

        // 触发 Registrate 条目构建（red_position_light 等）；实际入册由 Registrate 在 RegisterEvent 时完成
        RegistrateBlocks.init();

        // 注册全部自定义网络包（按功能域拆分在 network 包内）
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, ModPackets::register);

        // 注册 CC:T 外设 capability（支持 peripheral.wrap / peripheral.find）
        modEventBus.addListener(RegisterCapabilitiesEvent.class, CCPeripheralCapabilities::register);

        // 全局频道注册表是静态字段：服务器停止（回主菜单/关世界）时清空，防止旧世界设备残留占用频道
        NeoForge.EVENT_BUS.addListener(CCPeripheralExtender::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CCPeripheralExtender::onServerStopping);

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

    /** 服务器启动（进游戏/开世界）：刷新 aeronautics 螺旋桨配置静态缓存（T/A，进游戏缓存一次）。 */
    private static void onServerStarting(ServerStartingEvent event) {
        SensorSystemAPI.refreshAeroConfig();
    }

    /** 服务器停止（关世界/回主菜单）：清空静态全局频道注册表，避免跨世界残留占用频道。 */
    private static void onServerStopping(ServerStoppingEvent event) {
        GlobalChannelRegistry.clear();
        ShortRangeLinkerRegistry.clear();
    }
}
