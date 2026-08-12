package com.zzy205.myfirstmod.foundation.gui.widget;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;

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
    private boolean selected;
    private final ScreenElement selectedIcon;
    private final ScreenElement unselectedIcon;

    public ToggleButton(int x, int y, ScreenElement selectedIcon, ScreenElement unselectedIcon, int hoverRgb) {
        super(x, y, unselectedIcon, hoverRgb);
        this.selectedIcon = selectedIcon;
        this.unselectedIcon = unselectedIcon;
    }

    /** 切换选中状态，自动更新图标（选中时图标偏移 +1,+1 模拟按下） */
    public void setSelected(boolean selected) {
        this.selected = selected;
        this.setIcon(selected ? shifted(selectedIcon) : unselectedIcon);
    }

    /** 给图标加 1px 偏移 */
    private static ScreenElement shifted(ScreenElement icon) {
        return (graphics, x, y) -> icon.render(graphics, x + 1, y + 1);
    }

    public boolean isSelected() {
        return this.selected;
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
