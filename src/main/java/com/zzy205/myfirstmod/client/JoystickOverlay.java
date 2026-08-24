package com.zzy205.myfirstmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 虚拟摇杆 HUD overlay（Unity 式最基本的操纵杆方向显示，阶段一：仅操纵杆）。
 * <p>
 * 显示条件：坐垫操作模式 + 联动控制台中至少一个装了操纵杆（见 {@link SeatControlState}，由
 * {@link SeatControlListener} 每 tick 更新）；GUI 打开 / F1 隐藏 HUD 时不显示。
 * <p>
 * 渲染：右下角固定位置，底座圆环 + 摇杆头（位置 = 圆心 + 方向向量 × 行程，屏幕 y 向下故 y 取反）。
 * 贴图：textures/gui/virtual_joystick_base.png（64×64）+ virtual_joystick_knob.png（24×24，占位美术可替换）。
 */
public class JoystickOverlay {

    private static final ResourceLocation BASE_TEX =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/virtual_joystick_base.png");
    private static final ResourceLocation KNOB_TEX =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/virtual_joystick_knob.png");

    /** 右下角边距（到屏幕边缘） */
    private static final int MARGIN = 24;
    private static final int BASE_SIZE = 64;
    private static final int KNOB_SIZE = 24;
    /** 摇杆头最大行程（圆心到摇杆头的最大偏移） */
    private static final int TRAVEL = (BASE_SIZE - KNOB_SIZE) / 2;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(JoystickOverlay::onRenderGui);
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) return;
        if (!SeatControlState.isOperating() || !SeatControlState.hasJoystick()) return;

        GuiGraphics g = event.getGuiGraphics();
        int cx = g.guiWidth() - MARGIN - BASE_SIZE / 2;
        int cy = g.guiHeight() - MARGIN - BASE_SIZE / 2;

        // 底座
        g.blit(BASE_TEX, cx - BASE_SIZE / 2, cy - BASE_SIZE / 2,
                0f, 0f, BASE_SIZE, BASE_SIZE, BASE_SIZE, BASE_SIZE);

        // 摇杆头：屏幕 y 向下为正，故 y 取反
        float joyX = SeatControlState.getJoyX();
        float joyY = SeatControlState.getJoyY();
        int kx = Math.round(cx + joyX * TRAVEL) - KNOB_SIZE / 2;
        int ky = Math.round(cy - joyY * TRAVEL) - KNOB_SIZE / 2;
        g.blit(KNOB_TEX, kx, ky, 0f, 0f, KNOB_SIZE, KNOB_SIZE, KNOB_SIZE, KNOB_SIZE);
    }
}
