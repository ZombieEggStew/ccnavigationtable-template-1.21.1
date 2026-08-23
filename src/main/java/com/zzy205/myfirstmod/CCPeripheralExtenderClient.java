package com.zzy205.myfirstmod;

import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.TransmissionPeripheralRenderer;
import com.zzy205.myfirstmod.block.TransmissionPeripheralVisual;
import com.zzy205.myfirstmod.block.ControlDeskVisual;
import com.zzy205.myfirstmod.block.ControlDeskRenderer;
import com.zzy205.myfirstmod.block.MonitorVisual;
import com.zzy205.myfirstmod.block.MonitorPreloadedModels;
import com.zzy205.myfirstmod.block.MonitorRenderer;
import com.zzy205.myfirstmod.block.MyModPartialModels;
import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.client.MonitorBackgrounds;
import com.zzy205.myfirstmod.client.MonitorOutlineRenderer;
import com.zzy205.myfirstmod.screen.MyModMenus;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverScreen;
import com.zzy205.myfirstmod.screen.PeripheralExtenderScreen;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CCPeripheralExtender.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CCPeripheralExtender.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CCPeripheralExtenderClient {
    public CCPeripheralExtenderClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        MonitorGridOverlay.register();
        NeoForge.EVENT_BUS.addListener(MonitorOutlineRenderer::onRenderHighlight);

        // 预加载 Monitor 模块模型（仿 control-panels PreLoadedModel 模式）
        MonitorPreloadedModels.init();
        modEventBus.addListener(MonitorPreloadedModels::registerAdditional);
        modEventBus.addListener(MonitorPreloadedModels::bakingCompleted);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 注册 Flywheel Visual（shaft 渲染）
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.transmission_peripheral_entity.get())
                .factory(TransmissionPeripheralVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 注册 Flywheel Visual（控制台踏板/操纵杆叠加渲染）
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.control_desk_entity.get())
                .factory(ControlDeskVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 注册 Flywheel Visual（Monitor 外壳：bearing/case 实例化渲染）。
        // 动态内容（背景/模块/屏幕/文字）仍在 BER，故不跳过 vanilla 渲染；
        // MonitorRenderer 内部会在 Flywheel 可用时跳过外壳绘制。
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.monitor_entity.get())
                .factory(MonitorVisual::new)
                .neverSkipVanillaRender()
                .apply();

        // 初始化自定义 PartialModel（参照 Create 的 AllPartialModels.init()）
        MyModPartialModels.init();
        event.enqueueWork(MonitorBackgrounds::reload);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                MyModBlockEntities.transmission_peripheral_entity.get(),
                TransmissionPeripheralRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.control_desk_entity.get(),
                ControlDeskRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.monitor_entity.get(),
                MonitorRenderer::new);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), PeripheralExtenderScreen::new);
        event.register(MyModMenus.REDSTONE_TRANSCEIVER_MENU.get(), RedstoneTransceiverScreen::new);
    }
}
