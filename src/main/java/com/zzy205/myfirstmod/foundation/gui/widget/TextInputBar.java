package com.zzy205.myfirstmod.foundation.gui.widget;

import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * 长文本输入条 —— 横条背景 + 图标 + 长输入框 + 内嵌 EditBox。
 * 与 {@link ScrollValueBar} 同风格，但用于自由文本输入：
 * 悬停输入区显示 tooltip 并高亮，点击输入区聚焦并定位光标。
 */
public class TextInputBar extends AbstractWidget implements TooltipWidget {

    // 相对横条左上角的布局偏移（与 MonitorModuleScreen 的悬浮文本条一致）
    private static final int ICON_X = 22;
    private static final int ICON_Y = 6;
    private static final int INPUT_Y = 5;
    private static final int TEXT_X = 48;
    private static final int TEXT_Y = 10;
    private static final int TEXT_W = 109;
    private static final int TEXT_H = 10;

    // 输入区命中区域（长输入框内部，与 ScrollValueBar 的滚轮命中区一致）
    private static final int HIT_X = 45;
    private static final int HIT_W = 100;
    private static final int HIT_H = 18;

    /** 悬停高亮（半透明白） */
    private static final int HOVER_COLOR = 0x30FFFFFF;

    private final ScreenElement icon;
    private final ScreenElement barBackground;
    private final ScreenElement inputBackground;
    private final EditBox textBox;
    private final List<Component> tooltipLines = new ArrayList<>();

    public TextInputBar(int x, int y, int width, int height,
                        String initialText, int maxLength, ScreenElement icon) {
        super(x, y, width, height, Component.empty());
        this.icon = icon;
        this.barBackground = MyUIElements.BAR_BACKGROUND;
        this.inputBackground = MyUIElements.INPUT_LONG;
        this.textBox = new EditBox(Minecraft.getInstance().font,
                x + TEXT_X, y + TEXT_Y, TEXT_W, TEXT_H, Component.empty());
        this.textBox.setMaxLength(maxLength);
        this.textBox.setBordered(false);
        this.textBox.setTextColor(0xFFFFFF);
        this.textBox.setValue(initialText);
    }

    public String getValue() { return textBox.getValue(); }
    public void setValue(String text) { textBox.setValue(text); }

    /** 设置占位提示文本（未聚焦且为空时显示）。 */
    public TextInputBar setHint(Component hint) {
        textBox.setHint(hint);
        return this;
    }

    /** 追加标题行（蓝色，置于首行）。 */
    public TextInputBar addToolTipTitle(Component title) {
        tooltipLines.add(0, title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        return this;
    }

    /** 追加说明行（灰色斜体）。 */
    public TextInputBar addToolTipInstruction(Component instruction) {
        tooltipLines.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        barBackground.render(g, x, y);
        icon.render(g, x + ICON_X, y + ICON_Y);
        inputBackground.render(g, x, y + INPUT_Y);
        // 悬停高亮
        if (isOverInputArea(mouseX, mouseY)) {
            g.fill(x + HIT_X, y + INPUT_Y, x + HIT_X + HIT_W, y + INPUT_Y + HIT_H, HOVER_COLOR);
        }
        // 内嵌文本框（含文本、光标与选区）
        textBox.render(g, mouseX, mouseY, partialTick);
    }

    private boolean isOverInputArea(double mouseX, double mouseY) {
        int x = this.getX() + HIT_X;
        int y = this.getY() + INPUT_Y;
        return mouseX >= x && mouseX < x + HIT_W
                && mouseY >= y && mouseY < y + HIT_H;
    }

    /** 悬停时渲染 tooltip（由 Screen 在 super.render() 之后调用，确保在最上层）。 */
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!isOverInputArea(mouseX, mouseY) || tooltipLines.isEmpty()) return;
        g.renderComponentTooltip(Minecraft.getInstance().font, tooltipLines, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible || button != 0) return false;
        if (isOverInputArea(mouseX, mouseY)) {
            // 若点击落在 EditBox 内部，则同步定位光标
            textBox.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return textBox.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return textBox.charTyped(codePoint, modifiers);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        textBox.setFocused(focused);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }
}
