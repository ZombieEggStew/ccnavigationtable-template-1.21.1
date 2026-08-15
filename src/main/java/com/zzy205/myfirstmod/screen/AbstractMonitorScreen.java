package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.foundation.gui.widget.TooltipWidget;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 中间层 Screen：在原版 {@link Screen} 之上自动渲染子控件 tooltip。
 * <p>
 * 原版 Screen 不会为子控件画 tooltip，Create 的 AbstractSimiWidget（按钮等）依赖
 * 其 ContainerScreen 手动渲染；本类统一处理，子类无需再手动调用。
 * <ul>
 *   <li>{@link TooltipWidget}（ScrollValueBar / TextInputBar 等）：调用其 {@code renderTooltip}</li>
 *   <li>{@link AbstractSimiWidget}（Create 按钮/开关等）：渲染其 {@code getToolTip()}</li>
 * </ul>
 * 子类只需实现 {@link #renderCustom} 绘制背景/标题等，即可获得 tooltip 自动渲染。
 */
public abstract class AbstractMonitorScreen extends Screen {

    protected AbstractMonitorScreen(Component title) {
        super(title);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderCustom(g, mouseX, mouseY, partialTick); // 背景 + 标题等
        super.render(g, mouseX, mouseY, partialTick); // 控件
        renderWidgetTooltips(g, mouseX, mouseY);      // 自动 tooltip（最上层）
    }

    /** 子类在此绘制自定义背景/标题（在控件渲染之前调用）。 */
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** 自动渲染子控件 tooltip。 */
    private void renderWidgetTooltips(GuiGraphics g, int mouseX, int mouseY) {
        for (var widget : renderables) {
            if (widget instanceof TooltipWidget tooltipWidget) {
                tooltipWidget.renderTooltip(g, mouseX, mouseY);
            } else if (widget instanceof AbstractSimiWidget simi && simi.isMouseOver(mouseX, mouseY)) {
                var tooltip = simi.getToolTip();
                if (!tooltip.isEmpty()) {
                    g.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 禁用原版半透明渐变背景，使用自定义贴图代替
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
