package com.zzy205.myfirstmod.foundation.gui.widget;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 具备独立 tooltip 渲染能力的控件。
 * 由外层 Screen（如 {@code AbstractMonitorScreen}）在控件渲染完成后统一调用
 * {@link #renderTooltip}，使 tooltip 显示在最上层。
 */
public interface TooltipWidget {

    /** 在控件之上渲染自身 tooltip（内部自行做悬停命中判断）。 */
    void renderTooltip(GuiGraphics g, int mouseX, int mouseY);
}
