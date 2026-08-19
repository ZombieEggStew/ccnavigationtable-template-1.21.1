package com.zzy205.myfirstmod;

import com.simibubi.create.content.logistics.packagerLink.WiFiParticle;
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
import com.zzy205.myfirstmod.network.PlayOrderEffectPayload;
import com.zzy205.myfirstmod.network.ReceiverSyncPayload;
import com.zzy205.myfirstmod.network.SensorFilterPayload;
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import com.zzy205.myfirstmod.network.SyncGridPayload;
import com.zzy205.myfirstmod.network.ModuleConfigPayload;
import com.zzy205.myfirstmod.network.ModulePressPayload;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import com.zzy205.myfirstmod.network.RemoveModulePayload;
import com.zzy205.myfirstmod.network.ModuleKnobRotatePayload;
import com.zzy205.myfirstmod.network.MonitorBackgroundPayload;
import com.zzy205.myfirstmod.network.MonitorChannelPayload;
import com.zzy205.myfirstmod.network.MonitorTransformPayload;
import com.zzy205.myfirstmod.network.PlaceScreenPayload;
import com.zzy205.myfirstmod.network.RemoveScreenPayload;
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

            // 服务端→客户端：播放下单 WiFi 粒子（WiFiParticle.Data 无法走网络编码，
            // 只能由客户端本地 level.addParticle 生成）
            registrar.playToClient(
                    PlayOrderEffectPayload.TYPE,
                    PlayOrderEffectPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var vec3 = net.minecraft.world.phys.Vec3.atCenterOf(payload.pos());
                        level.addParticle(new WiFiParticle.Data(), vec3.x, vec3.y, vec3.z, 1, 1, 1);
                    }
            );

            // 服务端→客户端：同步 Monitor 棋盘网格状态
            registrar.playToClient(
                    SyncGridPayload.TYPE,
                    SyncGridPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.getGridState().load(level.registryAccess(), payload.gridTag());
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

            // 客户端→服务端：保存 Monitor 全局频道
            registrar.playToServer(
                    MonitorChannelPayload.TYPE,
                    MonitorChannelPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.monitorPos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.setChannel(payload.channel());
                        }
                    }
            );

            // 客户端→服务端：保存 Monitor 背景
            registrar.playToServer(
                    MonitorBackgroundPayload.TYPE,
                    MonitorBackgroundPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.monitorPos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.setBackground(payload.background());
                        }
                    }
            );

            // 客户端→服务端：保存正式 Monitor 的可动变换（俯仰 / 偏航 / 偏移）
            registrar.playToServer(
                    MonitorTransformPayload.TYPE,
                    MonitorTransformPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.monitorPos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.setAngles(payload.pitch(), payload.yaw(), payload.offset());
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

            // 客户端→服务端：Monitor 模块按钮按下/释放/切换
            registrar.playToServer(
                    ModulePressPayload.TYPE,
                    ModulePressPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            var mod = monitorBE.getGridState().getModule(payload.moduleId());
                            boolean isToggle = mod != null && mod.type() == ModuleType.TOGGLE_SWITCH;
                            if (payload.pressed()) {
                                if (isToggle) monitorBE.toggleModule(payload.moduleId());
                                else monitorBE.pressModuleByPlayer(payload.moduleId());
                            } else {
                                if (!isToggle) monitorBE.releaseModuleByPlayer(payload.moduleId());
                            }
                        }
                    }
            );

            // 客户端→服务端：旋钮旋转角度
            registrar.playToServer(
                    ModuleKnobRotatePayload.TYPE,
                    ModuleKnobRotatePayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.rotateKnob(payload.moduleId(), payload.angle());
                        }
                    }
            );

            // 客户端→服务端：模块 / 屏幕的 ID 与配置修改
            registrar.playToServer(
                    ModuleConfigPayload.TYPE,
                    ModuleConfigPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.applyModuleConfig(payload.name(), payload.oldId(), payload.newId(), payload.config());
                        }
                    }
            );

            // 客户端→服务端：放置屏幕（两点矩形选择，可多个共存）
            registrar.playToServer(
                    PlaceScreenPayload.TYPE,
                    PlaceScreenPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            int id = monitorBE.addScreen(
                                    payload.gridX1(), payload.gridY1(),
                                    payload.gridX2(), payload.gridY2()
                            );
                            if (id >= 0 && !ctx.player().isCreative()) {
                                ctx.player().getMainHandItem().shrink(1);
                            }
                        }
                    }
            );

            // 客户端→服务端：移除指定格子的屏幕（扳手拆卸）
            registrar.playToServer(
                    RemoveScreenPayload.TYPE,
                    RemoveScreenPayload.STREAM_CODEC,
                    (payload, ctx) -> {
                        var level = ctx.player().level();
                        var be = level.getBlockEntity(payload.pos());
                        if (be instanceof MonitorBlockEntity monitorBE) {
                            monitorBE.removeScreenAt(payload.gridX(), payload.gridY());
                            // TODO: 归还屏幕物品
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
                event.registerBlockEntity(
                        PeripheralCapability.get(),
                        MyModBlockEntities.monitor_entity.get(),
                        (be, side) -> ((MonitorBlockEntity) be).getPeripheral()
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
