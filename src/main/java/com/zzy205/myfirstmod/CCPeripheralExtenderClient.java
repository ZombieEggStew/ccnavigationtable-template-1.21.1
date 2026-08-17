package com.zzy205.myfirstmod;

import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.TransmissionPeripheralRenderer;
import com.zzy205.myfirstmod.block.TransmissionPeripheralVisual;
import com.zzy205.myfirstmod.block.MonitorPreloadedModels;
import com.zzy205.myfirstmod.block.MonitorRenderer;
import com.zzy205.myfirstmod.block.PitchMonitorTestRenderer;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlock;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlockEntity;
import com.zzy205.myfirstmod.block.MyModPartialModels;
import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.client.PitchMonitorTestOutlineRenderer;
import com.zzy205.myfirstmod.screen.MyModMenus;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverScreen;
import com.zzy205.myfirstmod.screen.PeripheralExtenderScreen;
import com.zzy205.myfirstmod.screen.PitchMonitorTestScreen;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
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
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CCPeripheraExtender.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CCPeripheraExtender.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CCPeripheralExtenderClient {
    public CCPeripheralExtenderClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        MonitorGridOverlay.register();
        NeoForge.EVENT_BUS.addListener(PitchMonitorTestOutlineRenderer::onRenderHighlight);

        // 测试 monitor 右键打开俯仰/偏航菜单
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            var level = event.getLevel();
            if (!level.isClientSide()) return;
            BlockPos pos = event.getPos();
            if (!(level.getBlockState(pos).getBlock() instanceof PitchMonitorTestBlock)) return;
            if (event.getHand() != InteractionHand.MAIN_HAND) return;

            int pitch = 0;
            int yaw = 0;
            if (level.getBlockEntity(pos) instanceof PitchMonitorTestBlockEntity be) {
                pitch = Math.round(be.getPitchAngle());
                yaw = Math.round(be.getYawAngle());
            }
            Minecraft.getInstance().setScreen(new PitchMonitorTestScreen(pos, pitch, yaw));
            event.setCanceled(true);
        });

        // 预加载 Monitor 模块模型（仿 control-panels PreLoadedModel 模式）
        MonitorPreloadedModels.init();
        modEventBus.addListener(MonitorPreloadedModels::registerAdditional);
        modEventBus.addListener(MonitorPreloadedModels::bakingCompleted);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        CCPeripheraExtender.LOGGER.info("HELLO FROM CLIENT SETUP");
        CCPeripheraExtender.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

        // 注册 Flywheel Visual（shaft 渲染）
        SimpleBlockEntityVisualizer.builder(MyModBlockEntities.transmission_peripheral_entity.get())
                .factory(TransmissionPeripheralVisual::new)
                .skipVanillaRender(be -> VisualizationManager.supportsVisualization(be.getLevel()))
                .apply();

        // 初始化自定义 PartialModel（参照 Create 的 AllPartialModels.init()）
        MyModPartialModels.init();
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                MyModBlockEntities.transmission_peripheral_entity.get(),
                TransmissionPeripheralRenderer::new);
        event.registerBlockEntityRenderer(
                MyModBlockEntities.monitor_entity.get(),
                MonitorRenderer::new);
        event.registerBlockEntityRenderer(
            MyModBlockEntities.pitch_monitor_test_entity.get(),
            PitchMonitorTestRenderer::new);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), PeripheralExtenderScreen::new);
        event.register(MyModMenus.REDSTONE_TRANSCEIVER_MENU.get(), RedstoneTransceiverScreen::new);
    }
}
