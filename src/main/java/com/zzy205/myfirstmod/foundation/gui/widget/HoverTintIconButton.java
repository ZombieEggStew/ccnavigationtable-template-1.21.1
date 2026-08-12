package com.zzy205.myfirstmod.foundation.gui.widget;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.widget.IconButton;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 带自定义 RGB 着色 hover 效果的 IconButton。
 * 用法：new HoverTintIconButton(x, y, AllIcons.I_CONFIRM, 0x80FF80)
 */
public class HoverTintIconButton extends IconButton {
    private final float hoverR;
    private final float hoverG;
    private final float hoverB;

    public HoverTintIconButton(int x, int y, ScreenElement icon, int hoverRgb) {
        super(x, y, icon);
        this.hoverR = (float) (hoverRgb >> 16 & 0xFF) / 255.0f;
        this.hoverG = (float) (hoverRgb >> 8 & 0xFF) / 255.0f;
        this.hoverB = (float) (hoverRgb & 0xFF) / 255.0f;
    }

    @Override
    protected void drawBg(GuiGraphics graphics, AllGuiTextures button) {
        if (button == AllGuiTextures.BUTTON_HOVER) {
            graphics.setColor(this.hoverR, this.hoverG, this.hoverB, 1.0f);
            super.drawBg(graphics, button);
            graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }
        super.drawBg(graphics, button);
    }
}
