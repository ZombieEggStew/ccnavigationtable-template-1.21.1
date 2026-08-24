package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.JoystickTilt;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.HashMap;
import java.util.Map;

/**
 * 虚拟摇杆 HUD overlay（测试用，默认关闭）。
 * <p>
 * 注册：MOD 总线 {@link RegisterGuiLayersEvent} 挂在原版 HOTBAR 之上（{@link LayeredDraw.Layer}，
 * 开任意界面时原版不渲染 Gui，本层随之隐藏；F1 隐藏 HUD 时手动跳过）。
 * <p>
 * 绘制：贴图方案 —— 底座 {@code textures/gui/joy_stick_ui.png} + 摇杆头 {@code textures/gui/crosshair.png}，
 * 位置 = 圆心 + <b>指数逼近后的模拟轴</b> × 行程（aeroworks SMOOTHED 模式：数值层每 tick 线性累加，
 * 显示层指数追逐，与 3D 动画同源）。
 * <p>
 * 显示条件：客户端配置 {@code joystickOverlayEnabled}（默认关闭）+ 坐垫操作模式 + 联动中有操纵杆。
 */
public class JoystickOverlay implements LayeredDraw.Layer {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("ccpe", "joystick_overlay");
    private static final ResourceLocation BASE_TEX =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/joy_stick_ui.png");
    private static final ResourceLocation KNOB_TEX =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/crosshair.png");

    /** 右下角边距（到屏幕边缘） */
    private static final int MARGIN = 24;
    /** 底座贴图尺寸 */
    private static final int BASE_SIZE = 57;
    /** 摇杆头贴图尺寸 */
    private static final int KNOB_SIZE = 3;
    /** 摇杆头最大行程（圆心到准心中心的最大偏移，保持准心不出盘） */
    private static final int TRAVEL = BASE_SIZE / 2 - KNOB_SIZE / 2 - 2;

    /**
     * 底座贴图运行时透明度倍率（贴图本身偏浓时压淡；重画了更淡的贴图可改回 1.0）。
     */
    private static final float BASE_ALPHA = 0.45f;

    /** 显示层指数平滑值（键 "joyX" / "joyY"，追逐 {@link SeatControlState} 的 tick 轴值） */
    private static final Map<String, Float> SMOOTHED = new HashMap<>();

    /** MOD 总线 RegisterGuiLayersEvent 注册（挂在 HOTBAR 之上）。 */
    public static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ID, new JoystickOverlay());
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        // 显示受客户端配置 joystickOverlayEnabled 控制（默认关闭，避免破坏沉浸感）
        if (mc.options.hideGui || !Config.JOYSTICK_OVERLAY_ENABLED.get()
                || !SeatControlState.isOperating() || !SeatControlState.hasJoystick()) {
            SMOOTHED.clear();
            return;
        }

        // 动画层指数逼近（帧时间修正），与 3D 动画同源
        float frameTicks = delta.getGameTimeDeltaTicks();
        float x = JoystickTilt.approach(SMOOTHED.getOrDefault("joyX", 0f), SeatControlState.getAxisX(), frameTicks);
        float y = JoystickTilt.approach(SMOOTHED.getOrDefault("joyY", 0f), SeatControlState.getAxisY(), frameTicks);
        SMOOTHED.put("joyX", x);
        SMOOTHED.put("joyY", y);

        int cx = g.guiWidth() - MARGIN - BASE_SIZE / 2;
        int cy = g.guiHeight() - MARGIN - BASE_SIZE / 2;

        // 底座：半透明圆盘（强制开启混合 + 透明度倍率，避免 alpha 未混合或过浓显示为纯黑）
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, BASE_ALPHA);
        g.blit(BASE_TEX, cx - BASE_SIZE / 2, cy - BASE_SIZE / 2,
                0f, 0f, BASE_SIZE, BASE_SIZE, BASE_SIZE, BASE_SIZE);

        // 摇杆头：准心（屏幕 y 向下，故 y 取反）
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        int kx = Math.round(cx + x * TRAVEL) - KNOB_SIZE / 2;
        int ky = Math.round(cy - y * TRAVEL) - KNOB_SIZE / 2;
        g.blit(KNOB_TEX, kx, ky, 0f, 0f, KNOB_SIZE, KNOB_SIZE, KNOB_SIZE, KNOB_SIZE);
        RenderSystem.disableBlend();
    }
}
