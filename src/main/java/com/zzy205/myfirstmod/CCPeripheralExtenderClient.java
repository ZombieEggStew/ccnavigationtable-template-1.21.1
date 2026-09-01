package com.zzy205.myfirstmod;

import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.MyModBlocks;
import com.zzy205.myfirstmod.block.TransmissionPeripheralRenderer;
import com.zzy205.myfirstmod.block.TransmissionPeripheralVisual;
import com.zzy205.myfirstmod.block.MyBearingRenderer;
import com.zzy205.myfirstmod.block.MyBearingVisual;
import com.zzy205.myfirstmod.block.InsRenderer;
import com.zzy205.myfirstmod.block.InsVisual;
import com.zzy205.myfirstmod.block.AicRenderer;
import com.zzy205.myfirstmod.block.AicVisual;
import com.zzy205.myfirstmod.block.ControlDeskVisual;
import com.zzy205.myfirstmod.block.ControlDeskRenderer;
import com.zzy205.myfirstmod.block.MonitorVisual;
import com.zzy205.myfirstmod.block.MonitorPreloadedModels;
import com.zzy205.myfirstmod.block.MonitorRenderer;
import com.zzy205.myfirstmod.block.MyModPartialModels;
import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.client.Monitor2GridOverlay;
import com.zzy205.myfirstmod.client.MonitorUseInterceptor;
import com.zzy205.myfirstmod.client.MonitorBackgrounds;
import com.zzy205.myfirstmod.client.MonitorOutlineRenderer;
import com.zzy205.myfirstmod.client.ControlDeskPlacementOverlay;
import com.zzy205.myfirstmod.client.DeskTopGridOverlay;
import com.zzy205.myfirstmod.client.ControlDeskGhostPreviewRenderer;
import com.zzy205.myfirstmod.client.JoystickOverlay;
import com.zzy205.myfirstmod.client.SeatControlListener;
import com.zzy205.myfirstmod.screen.MyModMenus;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverScreen;
import com.zzy205.myfirstmod.screen.PeripheralExtenderScreen;
import com.zzy205.myfirstmod.screen.ShortRangeLinkerScreen;

import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.TooltipModifier;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.world.item.Item;
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
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
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
        Monitor2GridOverlay.register();
        MonitorUseInterceptor.register();
        ControlDeskPlacementOverlay.register();
        DeskTopGridOverlay.register();
        ControlDeskGhostPreviewRenderer.register();
        SeatControlListener.register();
        NeoForge.EVENT_BUS.addListener(MonitorOutlineRenderer::onRenderHighlight);

        // 预加载 Monitor 模块模型（仿 control-panels PreLoadedModel 模式）
        MonitorPreloadedModels.init();
        modEventBus.addListener(MonitorPreloadedModels::registerAdditional);
        modEventBus.addListener(MonitorPreloadedModels::bakingCompleted);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 流体端口物品 tooltip（参考 CreateFluidLogistics fluid_hatch 模式：ItemDescription，
        // 平时只显示"按住 SHIFT 查看"提示，按住 SHIFT 展开 summary + 用法；Create ClientEvents 自动应用）。
        // 注意：不能在 mod 构造器里注册 —— 那时方块 DeferredHolder 尚未绑定（unbound value 崩溃），
        // 必须等 FMLClientSetupEvent（注册已完成）后再取。
        Item fluidPortItem = MyModBlocks.fluid_port.get().asItem();
        TooltipModifier.REGISTRY.register(fluidPortItem,
                new ItemDescription.Modifier(fluidPortItem, FontHelper.Palette.STANDARD_CREATE));

        // 注册 Flywheel Visual（shaft 渲染）
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.transmission_peripheral_entity.get())
                .factory(TransmissionPeripheralVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 注册 Flywheel Visual（aero_bearing 背面半个传动杆）。
        // 轴承本体模型由 blockstate 渲染，半轴是唯一动态部分，Flywheel 可用时跳过 vanilla BE 渲染。
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.aero_bearing_entity.get())
                .factory(MyBearingVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 注册 Flywheel Visual（控制台踏板/操纵杆叠加渲染）。
        // monitor_2 表面小 Monitor 的屏幕 9 宫格 + 文字无法用 Flywheel 表达，仍需 BER 绘制，
        // 故不跳过 vanilla 渲染（对齐 Monitor 模式）；ControlDeskRenderer 内部在 Flywheel
        // 可用时跳过控件模型（由 Visual 实例化），仅补画 monitor_2 屏幕动态内容。
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.control_desk_entity.get())
                .factory(ControlDeskVisual::new)
                .neverSkipVanillaRender()
                .apply();

        // 注册 Flywheel Visual（Monitor 外壳：bearing/case 实例化渲染）。
        // 动态内容（背景/模块/屏幕/文字）仍在 BER，故不跳过 vanilla 渲染；
        // MonitorRenderer 内部会在 Flywheel 可用时跳过外壳绘制。
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.monitor_entity.get())
                .factory(MonitorVisual::new)
                .neverSkipVanillaRender()
                .apply();

        // 注册 Flywheel Visual（惯性导航系统：万向环/罗盘盘实例化渲染，外壳由 blockstate 渲染）。
        // Flywheel 可用时跳过 vanilla BE 渲染（外壳静态，无 BER 必须内容）。
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.ins_entity.get())
                .factory(InsVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 注册 Flywheel Visual（航空集成计算机：可旋转罗盘实例化渲染，外壳/gyro 由 blockstate 渲染）。
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.aic_entity.get())
                .factory(AicVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 初始化自定义 PartialModel（参照 Create 的 AllPartialModels.init()）
        MyModPartialModels.init();
        event.enqueueWork(MonitorBackgrounds::reload);
    }

    @SubscribeEvent
    static void registerGuiLayers(RegisterGuiLayersEvent event) {
        // 虚拟摇杆 HUD（挂在原版 HOTBAR 之上）
        JoystickOverlay.register(event);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                MyModBlockEntities.transmission_peripheral_entity.get(),
                TransmissionPeripheralRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.aero_bearing_entity.get(),
                MyBearingRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.control_desk_entity.get(),
                ControlDeskRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.monitor_entity.get(),
                MonitorRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.ins_entity.get(),
                InsRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.aic_entity.get(),
                AicRenderer::new);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), PeripheralExtenderScreen::new);
        event.register(MyModMenus.REDSTONE_TRANSCEIVER_MENU.get(), RedstoneTransceiverScreen::new);
        event.register(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), ShortRangeLinkerScreen::new);
    }
}
