package com.zzy205.myfirstmod.client;

import com.simibubi.create.Create;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 拦截（屏蔽）Monitor / monitor_2 屏幕上的 Create 护目镜悬停 tooltip。
 * <p>
 * Monitor 屏幕本体无碰撞体，原版射线（{@code mc.hitResult}）会穿透屏幕命中其后的方块；
 * 戴着护目镜时 Create 的 {@code GoggleOverlayRenderer} 会据此把后方方块的 goggle 信息
 * 显示出来（右键穿透已由 {@link MonitorUseInterceptor} 处理，悬停穿透在这里处理）。
 * <p>
 * Create 的护目镜信息以命名 HUD 图层 {@code create:goggle_info} 注册
 * （ClientEvents 里 {@code registerAbove(HOTBAR, ...)}），在 {@link RenderGuiLayerEvent.Pre}
 * 取消该图层即可整层屏蔽，不影响其它 HUD 图层。
 * <p>
 * 命中判定复用两个 overlay 在渲染阶段算好的结果（{@code isScreenHovered()}），与
 * {@link MonitorUseInterceptor} 同一来源、同一帧新鲜度（世界渲染先于 HUD 渲染，
 * 本事件在 HUD 阶段触发时标志已是本帧值）。
 */
public final class MonitorHoverInterceptor {

    /** Create 护目镜信息图层的名字（{@code Create.asResource("goggle_info")}）。 */
    private static final ResourceLocation GOGGLE_LAYER =
            ResourceLocation.fromNamespaceAndPath(Create.ID, "goggle_info");

    private MonitorHoverInterceptor() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MonitorHoverInterceptor::onRenderGuiLayer);
    }

    private static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!event.getName().equals(GOGGLE_LAYER)) return;
        if (MonitorGridOverlay.isScreenHovered() || Monitor2GridOverlay.isScreenHovered()) {
            event.setCanceled(true);
        }
    }
}
