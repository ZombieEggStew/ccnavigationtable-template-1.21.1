package com.zzy205.myfirstmod.foundation.gui.widget;

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
 * 双滚轮数值输入条 —— 横条背景 + 左右两个数值槽位（各带图标 + 数值显示）。
 * 布局对齐 {@link DoubleInputBar}（双槽位），数值滚轮逻辑参考 {@link ScrollValueBar}
 * （滚轮修改、Shift 加速、范围钳位）；左右图标位均可换成 {@link ToggleButton}（同 ScrollValueBar）。
 */
public class DoubleScrollValueBar extends AbstractWidget implements TooltipWidget {

    // 相对横条左上角的布局偏移（对齐 DoubleInputBar 的双槽位布局）
    private static final int ICON_LEFT_X = 22;
    private static final int ICON_RIGHT_X = 102;
    private static final int ICON_Y = 6;
    private static final int INPUT_Y = 5;

    // 左右输入区（槽位）命中区域
    private static final int HIT_X_1 = 45;
    private static final int HIT_X_2 = 123;
    private static final int HIT_W = 47;
    private static final int HIT_H = 18;

    // 数值文本
    private static final int TEXT_Y = 10;
    private static final int TEXT_COLOR = 0xFCFCEB;

    /** 悬停高亮（半透明白） */
    private static final int HOVER_COLOR = 0x30FFFFFF;

    private final ScreenElement iconLeft;
    private final ScreenElement iconRight;
    private final ScreenElement barBackground = MyUIElements.BAR_BACKGROUND;
    private final ScreenElement inputBackground = MyUIElements.INPUT_DOUBLE;
    /** 槽位专用 tooltip（非空时优先于统一 tooltip） */
    private final List<Component> tooltipLinesLeft = new ArrayList<>();
    private final List<Component> tooltipLinesRight = new ArrayList<>();
    /** 统一 tooltip（左右槽位共用；未设置槽位专用时回退使用） */
    private final List<Component> tooltipLines = new ArrayList<>();

    /** 左右图标位的可交互 ToggleButton（非 null 时替代对应图标渲染并转发点击）。 */
    private ToggleButton toggleButtonLeft;
    private ToggleButton toggleButtonRight;

    private int leftValue;
    private int rightValue;
    /** 左右滚轮钳位范围（null = 不钳位） */
    private Integer minLeft, maxLeft, minRight, maxRight;

    public DoubleScrollValueBar(int x, int y, int width, int height,
                                ScreenElement iconLeft, ScreenElement iconRight,
                                int leftValue, int rightValue) {
        super(x, y, width, height, Component.empty());
        this.iconLeft = iconLeft;
        this.iconRight = iconRight;
        this.leftValue = leftValue;
        this.rightValue = rightValue;
    }

    // ════════════════════ 状态 ════════════════════

    public int getLeftValue() { return leftValue; }
    public int getRightValue() { return rightValue; }

    public DoubleScrollValueBar setLeftValue(int value) { this.leftValue = value; return this; }
    public DoubleScrollValueBar setRightValue(int value) { this.rightValue = value; return this; }

    /** 左右两个槽位应用相同的滚轮范围。 */
    public DoubleScrollValueBar range(int min, int max) {
        return rangeLeft(min, max).rangeRight(min, max);
    }

    /** 仅左槽位的滚轮范围。 */
    public DoubleScrollValueBar rangeLeft(int min, int max) {
        this.minLeft = min;
        this.maxLeft = max;
        this.leftValue = clamp(leftValue, min, max);
        return this;
    }

    /** 仅右槽位的滚轮范围。 */
    public DoubleScrollValueBar rangeRight(int min, int max) {
        this.minRight = min;
        this.maxRight = max;
        this.rightValue = clamp(rightValue, min, max);
        return this;
    }

    /** 链式设置左槽位图标处的 ToggleButton（替代左图标渲染并转发点击）。 */
    public DoubleScrollValueBar withToggleButtonLeft(ToggleButton button) {
        this.toggleButtonLeft = button;
        button.setPosition(getX() + ICON_LEFT_X, getY() + ICON_Y);
        return this;
    }

    /** 链式设置右槽位图标处的 ToggleButton（替代右图标渲染并转发点击）。 */
    public DoubleScrollValueBar withToggleButtonRight(ToggleButton button) {
        this.toggleButtonRight = button;
        button.setPosition(getX() + ICON_RIGHT_X, getY() + ICON_Y);
        return this;
    }

    // ════════════════════ 渲染 ════════════════════

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        barBackground.render(g, x, y);
        if (toggleButtonLeft != null) {
            toggleButtonLeft.render(g, mouseX, mouseY, partialTick);
        } else {
            iconLeft.render(g, x + ICON_LEFT_X, y + ICON_Y);
        }
        if (toggleButtonRight != null) {
            toggleButtonRight.render(g, mouseX, mouseY, partialTick);
        } else {
            iconRight.render(g, x + ICON_RIGHT_X, y + ICON_Y);
        }
        inputBackground.render(g, x, y + INPUT_Y);

        // 槽位悬停高亮
        if (isOverSlot(0, mouseX, mouseY)) {
            g.fill(x + HIT_X_1, y + INPUT_Y, x + HIT_X_1 + HIT_W, y + INPUT_Y + HIT_H, HOVER_COLOR);
        }
        if (isOverSlot(1, mouseX, mouseY)) {
            g.fill(x + HIT_X_2, y + INPUT_Y, x + HIT_X_2 + HIT_W, y + INPUT_Y + HIT_H, HOVER_COLOR);
        }

        drawSlotText(g, x, y, 0);
        drawSlotText(g, x, y, 1);
    }

    /** 槽位数值文本：槽位内居中（超宽省略号截断）。 */
    private void drawSlotText(GuiGraphics g, int x, int y, int slot) {
        String content = String.valueOf(slot == 0 ? leftValue : rightValue);
        var font = Minecraft.getInstance().font;
        int slotX = x + (slot == 0 ? HIT_X_1 : HIT_X_2);
        String fitted = fit(content, HIT_W - 4);
        Component text = Component.literal(fitted);
        int textWidth = font.width(fitted);
        int textX = slotX + (HIT_W - textWidth) / 2;
        g.drawString(font, text, textX, y + TEXT_Y, TEXT_COLOR, true);
    }

    private boolean isOverSlot(int slot, double mouseX, double mouseY) {
        int x = this.getX() + (slot == 0 ? HIT_X_1 : HIT_X_2);
        int y = this.getY() + INPUT_Y;
        return mouseX >= x && mouseX < x + HIT_W
                && mouseY >= y && mouseY < y + HIT_H;
    }

    private int slotAt(double mouseX, double mouseY) {
        for (int slot = 0; slot < 2; slot++) {
            if (isOverSlot(slot, mouseX, mouseY)) return slot;
        }
        return -1;
    }

    private String fit(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - 3) + "...";
    }

    // ════════════════════ 交互（滚轮调节，参考 ScrollValueBar） ════════════════════

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (toggleButtonLeft != null && toggleButtonLeft.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (toggleButtonRight != null && toggleButtonRight.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int slot = slotAt(mouseX, mouseY);
        if (slot < 0 || scrollY == 0) return false;
        // 滚轮向上 → 数值增大；向下 → 数值减小；Shift 加速
        int dir = scrollY > 0 ? 1 : -1;
        int jump = Screen.hasShiftDown() ? 10 : 1;
        if (slot == 0) {
            int newValue = applyRange(leftValue + dir * jump, minLeft, maxLeft);
            if (newValue != leftValue) {
                leftValue = newValue;
                playScrollSound();
            }
        } else {
            int newValue = applyRange(rightValue + dir * jump, minRight, maxRight);
            if (newValue != rightValue) {
                rightValue = newValue;
                playScrollSound();
            }
        }
        return true;
    }

    /** 钳位到范围；未设置范围（null）时不钳位。 */
    private static int applyRange(int value, Integer min, Integer max) {
        if (min != null && value < min) return min;
        if (max != null && value > max) return max;
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 滚轮调值的音效（与 ScrollValueBar 一致）。 */
    private static void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }

    // ════════════════════ tooltip 构建 ════════════════════

    /** 追加标题行（蓝色，置于首行）。 */
    public DoubleScrollValueBar addToolTipTitle(Component title) {
        tooltipLines.add(0, title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        return this;
    }

    /** 追加说明行（灰色斜体）。 */
    public DoubleScrollValueBar addToolTipInstruction(Component instruction) {
        tooltipLines.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        return this;
    }

    /** 追加左槽位标题行（蓝色，置于首行）。 */
    public DoubleScrollValueBar addToolTipTitleLeft(Component title) {
        tooltipLinesLeft.add(0, title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        return this;
    }

    /** 追加左槽位说明行（灰色斜体）。 */
    public DoubleScrollValueBar addToolTipInstructionLeft(Component instruction) {
        tooltipLinesLeft.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        return this;
    }

    /** 追加右槽位标题行（蓝色，置于首行）。 */
    public DoubleScrollValueBar addToolTipTitleRight(Component title) {
        tooltipLinesRight.add(0, title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        return this;
    }

    /** 追加右槽位说明行（灰色斜体）。 */
    public DoubleScrollValueBar addToolTipInstructionRight(Component instruction) {
        tooltipLinesRight.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        return this;
    }

    /** 整体替换右槽位 tooltip（标题 + 说明），用于右槽数值含义随状态（如档位/自由模式）变化时。 */
    public DoubleScrollValueBar setRightTooltip(Component title, Component instruction) {
        tooltipLinesRight.clear();
        if (title != null) {
            tooltipLinesRight.add(title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        }
        if (instruction != null) {
            tooltipLinesRight.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        }
        return this;
    }

    /** 悬停时渲染 tooltip（由外层 Screen 在 super.render() 之后调用，确保在最上层）。 */
    @Override
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        // 内嵌开关 tooltip 优先（ToggleButton 不自绘 tooltip）
        if (toggleButtonLeft != null && toggleButtonLeft.isMouseOver(mouseX, mouseY)) {
            var tooltip = toggleButtonLeft.getToolTip();
            if (!tooltip.isEmpty()) {
                g.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
            }
            return;
        }
        if (toggleButtonRight != null && toggleButtonRight.isMouseOver(mouseX, mouseY)) {
            var tooltip = toggleButtonRight.getToolTip();
            if (!tooltip.isEmpty()) {
                g.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
            }
            return;
        }
        // 滚轮槽位 tooltip（槽位专用优先，未设置时回退统一 tooltip）
        int slot = slotAt(mouseX, mouseY);
        if (slot < 0) return;
        List<Component> lines = slot == 0 ? tooltipLinesLeft : tooltipLinesRight;
        if (lines.isEmpty()) {
            lines = tooltipLines;
        }
        if (lines.isEmpty()) return;
        g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }
}
