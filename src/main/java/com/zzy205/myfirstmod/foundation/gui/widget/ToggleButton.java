package com.zzy205.myfirstmod.foundation.gui.widget;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * 可切换状态的按钮 — 继承 HoverTintIconButton，增加选中/未选中双图标。
 * 选中时背景强制为 BUTTON_DOWN（凹陷外观），图标切换为 selectedIcon。
 * 
 * <pre>{@code
 * ToggleButton btn = new ToggleButton(x, y,
 *         MyIcons.LOCK,      // 选中时图标
 *         MyIcons.UNLOCK,    // 未选中时图标
 *         0x80FF80);         // 未选中时 hover 绿色高亮
 * btn.withCallback(() -> {
 *     btn.setSelected(!btn.isSelected());
 * });
 * addRenderableWidget(btn);
 * }</pre>
 */
public class ToggleButton extends HoverTintIconButton {
    private static final int COLOR_TITLE = 0x528FDE;
    private static final int COLOR_INSTRUCTION = 0x545454;
    private static final int COLOR_SELECTED = 0xFCFCFC;
    private static final int COLOR_UNSELECTED = 0xA8A8A8;

    private boolean selected;
    private final ScreenElement selectedIcon;
    private final ScreenElement unselectedIcon;

    /** tooltip 各部分（链式调用填充，动态重建 toolTip） */
    private Component tooltipTitle;
    private Component tooltipInstruction;
    private Component onLabel;
    private Component offLabel;

    public ToggleButton(int x, int y, ScreenElement selectedIcon, ScreenElement unselectedIcon, int hoverRgb) {
        super(x, y, unselectedIcon, hoverRgb);
        this.selectedIcon = selectedIcon;
        this.unselectedIcon = unselectedIcon;
    }

    /** 切换选中状态，自动更新图标（选中时图标偏移 +1,+1 模拟按下） */
    public void setSelected(boolean selected) {
        this.selected = selected;
        this.setIcon(selected ? shifted(selectedIcon) : unselectedIcon);
        updateTooltip();
    }

    /** 链式：追加标题行（蓝色）。 */
    public ToggleButton addToolTipTitle(Component title) {
        this.tooltipTitle = title;
        updateTooltip();
        return this;
    }

    /** 链式：追加说明行（斜体灰色）。 */
    public ToggleButton addToolTipInstruction(Component instruction) {
        this.tooltipInstruction = instruction;
        updateTooltip();
        return this;
    }

    /**
     * 链式：追加"开/关"两行选项，当前状态标记 "->"（白色），
     * 另一状态标记 ">"（灰色），切换状态时自动更新。
     */
    public ToggleButton addToolTipOnOff(Component onLabel, Component offLabel) {
        this.onLabel = onLabel;
        this.offLabel = offLabel;
        updateTooltip();
        return this;
    }

    /** 根据当前选中状态重建 toolTip（标题 + 说明 + 开/关选项）。 */
    private void updateTooltip() {
        if (tooltipTitle == null && tooltipInstruction == null && (onLabel == null || offLabel == null)) return;
        toolTip.clear();
        if (tooltipTitle != null) {
            toolTip.add(tooltipTitle.copy().withStyle(Style.EMPTY.withColor(COLOR_TITLE)));
        }
        if (tooltipInstruction != null) {
            toolTip.add(tooltipInstruction.copy()
                    .withStyle(Style.EMPTY.withColor(COLOR_INSTRUCTION).withItalic(true)));
        }
        if (onLabel != null && offLabel != null) {
            toolTip.add(optionLine(selected, onLabel));
            toolTip.add(optionLine(!selected, offLabel));
        }
    }

    private static Component optionLine(boolean isCurrent, Component label) {
        int color = isCurrent ? COLOR_SELECTED : COLOR_UNSELECTED;
        String prefix = isCurrent ? "-> " : "> ";
        return Component.literal(prefix)
                .withStyle(Style.EMPTY.withColor(color))
                .append(label.copy().withStyle(Style.EMPTY.withColor(color)));
    }

    /** 给图标加 1px 偏移 */
    private static ScreenElement shifted(ScreenElement icon) {
        return (graphics, x, y) -> icon.render(graphics, x + 1, y + 1);
    }

    public boolean isSelected() {
        return this.selected;
    }

    /** 设置禁用状态。禁用时按钮不可点击，但仍可显示 tooltip。 */
    public ToggleButton setDisabled(boolean disabled) {
        this.setActive(!disabled);
        return this;
    }

    public boolean isDisabled() {
        return !this.active;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isDisabled()) {
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void drawBg(GuiGraphics graphics, AllGuiTextures button) {
        if (this.selected) {
            // 选中时一律用 BUTTON_DOWN（凹陷外观），不响应 hover 着色
            super.drawBg(graphics, AllGuiTextures.BUTTON_DOWN);
            return;
        }
        // 未选中时走父类：正常 BUTTON / hover 绿色着色
        super.drawBg(graphics, button);
    }
}
