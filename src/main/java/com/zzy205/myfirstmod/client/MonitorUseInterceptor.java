package com.zzy205.myfirstmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 拦截（drain）Monitor / monitor_2 屏幕上的原版右键。
 * <p>
 * monitor_2 屏幕在 controlDesk 桌体碰撞体（y0..8）上方且带 22.5° 倾斜、大 Monitor 屏幕可
 * 旋出方块范围：准星瞄准屏幕时原版射线（{@code mc.hitResult}）可能 MISS 或命中屏幕后方的
 * 方块，导致右键穿透屏幕触发后方方块的交互（开箱/拉杆/扳手旋转/放置物品等）。
 * <p>
 * 本类在 {@link InputEvent.InteractionKeyMappingTriggered}（原版 {@code Minecraft.startUseItem}
 * 的入口，取消后整次右键被跳过，use-on-block 与 use-item 都不会执行）取消右键。
 * 屏幕交互全部由 {@link MonitorGridOverlay} / {@link Monitor2GridOverlay} 轮询按键状态 +
 * 发送 payload 完成，不依赖原版右键，拦截后互不干扰；底座/桌体本体（非屏幕）的右键
 * （扳手、菜单、模块安装等）不在拦截范围，保持原样。
 * <p>
 * 挥手动画：拦截后保留原版挥手动画，但 {@code startUseItem} 在长按期间每 4 tick 重复触发
 * （拖动旋钮 = 一直按住右键），若每次都挥手会变成一直挥手。这里只在「右键按下边沿」
 * （进入拖动/按下状态的瞬间）挥一次手，长按拖动期间的重复触发不挥手；右键松开后复位
 * 边沿跟踪（释放没有事件，须在客户端 tick 里复位）。
 */
public final class MonitorUseInterceptor {

    /** 上次事件触发时右键是否按下；用于判定「按下边沿」只挥一次手。 */
    private static boolean lastUseDown = false;

    private MonitorUseInterceptor() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MonitorUseInterceptor::onInteractionKeyMappingTriggered);
        NeoForge.EVENT_BUS.addListener(MonitorUseInterceptor::onClientTick);
    }

    private static void onInteractionKeyMappingTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return; // 只拦右键（攻击/取物不受影响）

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        Level level = player.level();

        // 按下边沿才挥手：事件在右键按下（consumeClick）与长按期间每 4 tick（rightClickDelay==0）
        // 都会到达，若每次都应挥手会变成拖动旋钮时一直挥手。
        boolean useDown = mc.options.keyUse.isDown();
        boolean pressEdge = useDown && !lastUseDown;
        lastUseDown = useDown;

        // 事件在客户端 tick 内触发（与 gameRenderer.pick(1.0F) 拾取 hitResult 同一插值刻度），
        // 用 1.0f 做检测与准星所见一致。两个检测器都自带遮挡检测，不会误拦被墙挡住的屏幕。
        float partialTick = 1.0f;
        if (MonitorHitDetector.find(level, player, partialTick) != null
                || Monitor2HitDetector.find(level, player, partialTick) != null) {
            event.setCanceled(true);
            // 只在进入交互（按下 / 开始拖动）的瞬间挥一次手
            event.setSwingHand(pressEdge);
        }
    }

    /**
     * 右键松开后复位边沿跟踪：{@link InputEvent.InteractionKeyMappingTriggered} 只在按下 /
     * 长按重复时触发、释放没有事件，必须在这里把 {@link #lastUseDown} 清掉，否则下一次
     * 按下会被误判为非边沿而不再挥手。
     */
    private static void onClientTick(ClientTickEvent.Pre event) {
        if (!Minecraft.getInstance().options.keyUse.isDown()) {
            lastUseDown = false;
        }
    }
}
