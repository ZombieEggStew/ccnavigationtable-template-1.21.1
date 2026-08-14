package com.zzy205.myfirstmod.foundation.gui.widget;

import com.zzy205.myfirstmod.channel.ChannelScrollHelper;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * 滚轮数值输入条 —— 横条背景 + 图标 + 输入框 + 数值。
 * 鼠标悬停在输入框区域滚轮可修改数值（Shift 加速、跳过占用频道/ID）。
 */
public class ScrollValueBar extends AbstractWidget {

    // 相对横条左上角的布局偏移（与 MonitorModuleScreen 的 bar_id 一致）
    private static final int ICON_X = 22;
    private static final int ICON_Y = 6;
    private static final int INPUT_Y = 5;
    private static final int VALUE_X = 50;
    private static final int VALUE_Y = 10;
    private static final int VALUE_COLOR = 0xFCFCEB;

    // 滚轮命中区（输入框区域）
    private static final int HIT_X = 45;
    private static final int HIT_W = 109;
    private static final int HIT_H = 18;

    /** 悬停高亮（半透明白） */
    private static final int HOVER_COLOR = 0x30FFFFFF;

    private final ScreenElement icon;
    private final ScreenElement barBackground;
    private final ScreenElement inputBackground;
    private final int myValue;
    private final int[] occupied;
    /** 离散选项（非 null 时进入离散模式，value 为下标，标签可翻译） */
    private final Component[] optionLabels;
    private final List<Component> tooltipLines = new ArrayList<>();
    /** 是否在 tooltip 中自动列出所有离散选项 */
    private boolean tooltipOptions = false;

    private int value;

    public ScrollValueBar(int x, int y, int width, int height,
                          int value, int myValue, int[] occupied,
                          ScreenElement icon) {
        super(x, y, width, height, Component.empty());
        this.value = value;
        this.myValue = myValue;
        this.occupied = occupied;
        this.optionLabels = null;
        this.icon = icon;
        this.barBackground = MyUIElements.BAR_BACKGROUND;
        this.inputBackground = MyUIElements.SCROLL_INPUT_LONG;
    }

    /** 离散选项模式：value 为选项下标，滚轮循环切换。 */
    public ScrollValueBar(int x, int y, int width, int height,
                          int value, Component[] optionLabels, ScreenElement icon) {
        super(x, y, width, height, Component.empty());
        this.value = value;
        this.optionLabels = optionLabels;
        this.myValue = -1;
        this.occupied = new int[0];
        this.icon = icon;
        this.barBackground = MyUIElements.BAR_BACKGROUND;
        this.inputBackground = MyUIElements.SCROLL_INPUT_LONG;
    }

    public int getValue() { return value; }

    /** 当前显示的文本（离散模式下为选项标签，数值模式下为数字）。 */
    private Component displayText() {
        return optionLabels != null
                ? optionLabels[Math.floorMod(value, optionLabels.length)]
                : Component.literal(String.valueOf(value));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        barBackground.render(g, x, y);
        icon.render(g, x + ICON_X, y + ICON_Y);
        inputBackground.render(g, x, y + INPUT_Y);
        // 悬停高亮
        if (isOverHitArea(mouseX, mouseY)) {
            g.fill(x + HIT_X, y + INPUT_Y, x + HIT_X + HIT_W, y + INPUT_Y + HIT_H, HOVER_COLOR);
        }
        g.drawString(Minecraft.getInstance().font, displayText(),
                x + VALUE_X, y + VALUE_Y, VALUE_COLOR, true);
    }

    private boolean isOverHitArea(double mouseX, double mouseY) {
        int x = this.getX() + HIT_X;
        int y = this.getY() + INPUT_Y;
        return mouseX >= x && mouseX < x + HIT_W
                && mouseY >= y && mouseY < y + HIT_H;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isOverHitArea(mouseX, mouseY) || scrollY == 0) return false;
        // 滚轮向下 → 下一个选项/数值
        int dir = scrollY > 0 ? -1 : 1;
        int newValue;
        if (optionLabels != null) {
            newValue = Math.floorMod(value + dir, optionLabels.length);
        } else {
            int jump = Screen.hasShiftDown() ? 10 : 1;
            newValue = ChannelScrollHelper.next(value, dir, jump, myValue, occupied);
        }
        if (newValue != value) {
            value = newValue;
            playScrollSound();
        }
        return true;
    }

    // ════════════════ tooltip 构建 ════════════════

    /** 追加普通行。 */
    public ScrollValueBar addToolTipLine(Component line) {
        tooltipLines.add(line);
        return this;
    }

    /** 追加标题行（蓝色，置于首行）。 */
    public ScrollValueBar addToolTipTitle(Component title) {
        tooltipLines.add(0, title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        return this;
    }

    /** 追加说明行（灰色斜体）。 */
    public ScrollValueBar addToolTipInstruction(Component instruction) {
        tooltipLines.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        return this;
    }

    /** 自动在 tooltip 中列出所有离散选项（未选中 "> "，选中 "-> "）。 */
    public ScrollValueBar addToolTipOptions() {
        this.tooltipOptions = true;
        return this;
    }

    /** 悬停时渲染 tooltip（由 Screen 在 super.render() 之后调用，确保在最上层）。 */
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!isOverHitArea(mouseX, mouseY)) return;
        List<Component> lines = new ArrayList<>(tooltipLines);
        if (tooltipOptions && optionLabels != null) {
            int selected = Math.floorMod(value, optionLabels.length);
            for (int i = 0; i < optionLabels.length; i++) {
                boolean isSelected = i == selected;
                int color = isSelected ? 0xFCFCFC : 0xA8A8A8;
                Style style = Style.EMPTY.withColor(color);
                String prefix = isSelected ? "-> " : "> ";
                lines.add(Component.literal(prefix).withStyle(style)
                        .append(optionLabels[i].copy().withStyle(style)));
            }
        }
        if (lines.isEmpty()) return;
        g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
    }

    private void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }
}
