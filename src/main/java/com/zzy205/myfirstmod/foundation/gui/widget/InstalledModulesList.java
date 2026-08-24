package com.zzy205.myfirstmod.foundation.gui.widget;

import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 已安装控件列表 —— 展示安装到控制台的控件（物品栏图标 + 控件名称），可点击打开对应模块配置菜单。
 * <p>
 * 数据在构造时传入（对齐 {@link ScrollValueBar} 模式，不自行读取 BlockEntity）；
 * 每行一个控件：黑色底条（{@link MyUIElements#BAR_BACKGROUND}）+ 图标槽（{@link MyUIElements#ICON_DISPLAY}）
 * + 16×16 物品图标（{@link GuiGraphics#renderItem}）+ 名称；悬停整行高亮，左键点击触发
 * {@code onModuleClicked} 回调（参数 = 行号，由调用方决定打开哪个配置菜单）；
 * 空列表时显示提示文本（{@code gui.ccpe.control_desk.no_modules}）。
 */
public class InstalledModulesList extends AbstractWidget {

    private static final int ROW_H = 30;

    private static final int TEXT_X = 28;  // 名称相对控件左边缘
    private static final int TEXT_Y = 6;   // 名称相对行顶

    private static final int TEXT_COLOR = 0xFCFCEB;
    private static final int EMPTY_COLOR = 0x545454;
    /** 悬停高亮（半透明白，与 ScrollValueBar 一致） */
    private static final int HOVER_COLOR = 0x30FFFFFF;

    private final List<Entry> entries;
    private final int hitArea_x;  // 左边缘从图标槽开始，点击整行都触发回调

    private final int hitArea_w;  // 左边缘从图标槽开始，点击整行都触发回调
    private final int hitArea_h;  // 左边缘从图标槽开始，点击整行都触发回调
    @Nullable
    private Consumer<Integer> onModuleClicked;

    public InstalledModulesList(int x, int y, int width, List<Entry> entries) {
        super(x, y, width, Math.max(ROW_H, entries.size() * ROW_H), Component.empty());
        this.entries = entries;
        this.hitArea_x = getX() + 18;  // 左边缘从图标槽开始

        this.hitArea_w = this.width - 36;  // 宽度减去图标槽宽度
        this.hitArea_h = ROW_H - 4;  // 高度与行高一致
    }

    /** 链式设置点击回调（参数 = 被点击的行号；由调用方按行号打开对应模块配置菜单）。 */
    public InstalledModulesList withCallback(Consumer<Integer> onModuleClicked) {
        this.onModuleClicked = onModuleClicked;
        return this;
    }

    private static void playClickSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.3f));
    }


    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (entries.isEmpty()) {
            g.drawString(Minecraft.getInstance().font,
                    Component.translatable("gui.ccpe.control_desk.no_modules"),
                    getX() + TEXT_X, getY() + TEXT_Y, EMPTY_COLOR, false);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int rowY = getY() + i * ROW_H;
            MyUIElements.BAR_BACKGROUND.render(g, getX(), rowY);
            MyUIElements.ICON_DISPLAY.render(g, getX(), rowY + 5);
            // 悬停整行高亮（画在图标/文字之下，保持图标与文字清晰）
            if (isOverRow(i, mouseX, mouseY)) {
                g.fill(this.hitArea_x, rowY + 2, this.hitArea_x + this.hitArea_w, rowY + this.hitArea_h, HOVER_COLOR);
            }
            g.renderItem(entry.icon(), getX() + 22, rowY + 6);
            g.drawString(Minecraft.getInstance().font, entry.name(),
                    getX() + 48, rowY + 10, TEXT_COLOR, true);
        }
    }

    /** 鼠标是否位于第 index 行的整行区域内。 */
    private boolean isOverRow(int index, double mouseX, double mouseY) {
        int rowY = getY() + index * ROW_H + 2;
        return mouseX >= this.hitArea_x && mouseX < this.hitArea_x + this.hitArea_w
                && mouseY >= rowY && mouseY < rowY + this.hitArea_h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        for (int i = 0; i < entries.size(); i++) {
            if (isOverRow(i, mouseX, mouseY)) {
                if (onModuleClicked != null) {
                    playClickSound();
                    onModuleClicked.accept(i);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }

    /** 单行条目：物品图标 + 名称。 */
    public record Entry(ItemStack icon, Component name) {}
}
