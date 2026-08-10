package com.zzy205.myfirstmod;

import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.MyModBlocks;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import com.zzy205.myfirstmod.block.TransmissionPeripheralBlockEntity;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.compat.cc.CCPeripheralExtenderSetup;
import com.zzy205.myfirstmod.compat.cc.RedstoneTransceiverPeripheral;
import com.zzy205.myfirstmod.compat.cc.RedstoneTransceiverRegistry;
import com.zzy205.myfirstmod.compat.cc.PeripheralExtenderRegistry;
import com.zzy205.myfirstmod.item.MyModCreativeModeTabs;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.network.ReceiverSyncPayload;
import com.zzy205.myfirstmod.network.SensorFilterPayload;
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import com.zzy205.myfirstmod.network.RemoveModulePayload;
import com.zzy205.myfirstmod.screen.MyModMenus;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.ModList;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CCPeripheraExtender.MOD_ID)
public class CCPeripheraExtender {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "ccpe";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CCPeripheraExtender(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        MyModItems.register(modEventBus);
        MyModCreativeModeTabs.register(modEventBus);

        MyModBlocks.register(modEventBus);
        MyModBlockEntities.register(modEventBus);
        MyModMenus.register(modEventBus);

        // 注册自定义网络包（服务端→客户端推送 NBT）
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar(MOD_ID);
            registrar.playToClient(
                    SensorNbtPayload.TYPE,
                    SensorNbtPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        // 客户端：收到 NBT 后直接更新客户端 BE
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.sensorPos());
                        if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                            sensorBE.setCachedAttachedNBT(payload.nbt());
                        }
                    }
            );

            // 客户端→服务端：保存 filter 文本
            registrar.playToServer(
                    SensorFilterPayload.TYPE,
                    SensorFilterPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.sensorPos());
                        if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                            int newChannel = payload.scrolledValue();
                            if (newChannel != sensorBE.getScrolledValue()) {
                                int assigned = PeripheralExtenderRegistry
                                        .register(newChannel, sensorBE);
                                sensorBE.setScrolledValue(assigned);
                            }
                            sensorBE.setLoadMode(payload.loadMode());
                            sensorBE.refreshOccupiedChannels();
                        }
                    }
            );

            // 客户端→服务端：Receiver 完整数据同步
            registrar.playToServer(
                    ReceiverSyncPayload.TYPE,
                    ReceiverSyncPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof RedstoneTransceiverBlockEntity receiverBE) {
                            receiverBE.setBannerData(payload.data());
                            receiverBE.setLoadMode(payload.loadMode());
                            RedstoneTransceiverRegistry.updateChannels(receiverBE, payload.data());
                        }
                    }
            );

            // 客户端→服务端：Monitor 放置模块
            registrar.playToServer(
                    PlaceModulePayload.TYPE,
                    PlaceModulePayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            var type = ModuleType.byName(payload.moduleTypeName());
                            if (type != null) {
                                int id = monitorBE.tryPlaceModule(payload.gridX(), payload.gridY(), type);
                                if (id >= 0 && !ctx.player().isCreative()) {
                                    ctx.player().getMainHandItem().shrink(1);
                                }
                            }
                        }
                    }
            );

            // 客户端→服务端：Monitor 移除模块
            registrar.playToServer(
                    RemoveModulePayload.TYPE,
                    RemoveModulePayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            String removedType = monitorBE.tryRemoveModule(payload.moduleId());
                            if (removedType != null && !ctx.player().isCreative()) {
                                // TODO: 后续把模块物品返还给玩家
                            }
                        }
                    }
            );
        });

        // 注册 Receiver BlockEntity 为 CC:T 外设（支持 peripheral.wrap / peripheral.find）
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            if (ModList.get().isLoaded("computercraft")) {
                event.registerBlockEntity(
                        PeripheralCapability.get(),
                        MyModBlockEntities.redstone_transceiver_entity.get(),
                        (be, side) -> new RedstoneTransceiverPeripheral(
                                (RedstoneTransceiverBlockEntity) be)
                );
                event.registerBlockEntity(
                        PeripheralCapability.get(),
                        MyModBlockEntities.transmission_peripheral_entity.get(),
                        (be, side) -> ((TransmissionPeripheralBlockEntity) be).getPeripheral()
                );
            }
        });

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (CCNavigationtable) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative); 

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("CCPE common setup");

        // 注册 CC:Tweaked 传感器 Lua API
        if (ModList.get().isLoaded("computercraft")) {
            CCPeripheralExtenderSetup.register();
            LOGGER.info("CC:Tweaked sensor API registered");
        }
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
