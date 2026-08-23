package com.zzy205.myfirstmod.foundation.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 双按键绑定条 —— 横条背景 + 左右两个按键槽位（各带图标 + 按键名显示），
 * 用于让玩家设置按键。按键捕获逻辑参考 aeroworks ModuleScreen：
 * 点击槽位进入捕获态 → 键盘按键 / 鼠标按键均可绑定（存 {@code InputConstants.Key.getName()} 字符串）→ ESC 取消；
 * 右键点击槽位清除绑定。
 * <p>
 * 捕获完成通过 {@link #onBindCaptured(BiConsumer)} 回调（side：0=左槽位，1=右槽位；keyName 为空串表示清除）。
 */
public class DoubleInputBar extends AbstractWidget implements TooltipWidget {

    // 相对横条左上角的布局偏移（对齐 JoystickModuleScreen / PedalModuleScreen 中的使用位置）
    private static final int ICON_LEFT_X = 22;
    private static final int ICON_RIGHT_X = 102;
    private static final int ICON_Y = 6;
    private static final int INPUT_Y = 5;

    // 左右输入区（槽位）命中区域
    private static final int HIT_X_1 = 45;
    private static final int HIT_X_2 = 123;
    private static final int HIT_W = 47;
    private static final int HIT_H = 18;

    // 按键名文本
    private static final int TEXT_Y = 10;
    private static final int TEXT_COLOR = 0xFCFCEB;

    /** 悬停高亮（半透明白） */
    private static final int HOVER_COLOR = 0x30FFFFFF;

    /** 取消捕获的键（ESC） */
    private static final int KEY_ESC = 256;

    private final ScreenElement iconLeft;
    private final ScreenElement iconRight;
    private final ScreenElement barBackground = MyUIElements.BAR_BACKGROUND;
    private final ScreenElement inputBackground = MyUIElements.INPUT_DOUBLE;
    private final List<Component> tooltipLines = new ArrayList<>();

    /** 按键名（InputConstants.Key.getName() 格式，如 "key.keyboard.q"）；空串 = 未绑定 */
    private String leftKey = "";
    private String rightKey = "";
    /** 当前捕获中的槽位：-1 无，0 左，1 右 */
    private int capturingSide = -1;
    private BiConsumer<Integer, String> onCaptured;

    public DoubleInputBar(int x, int y, int width, int height, ScreenElement iconLeft, ScreenElement iconRight) {
        super(x, y, width, height, Component.empty());
        this.iconLeft = iconLeft;
        this.iconRight = iconRight;
    }

    // ════════════════════ 状态 ════════════════════

    public String getLeftKey() { return leftKey; }
    public String getRightKey() { return rightKey; }

    public DoubleInputBar setLeftKey(String key) { this.leftKey = key == null ? "" : key; return this; }
    public DoubleInputBar setRightKey(String key) { this.rightKey = key == null ? "" : key; return this; }

    public boolean isCapturing() { return capturingSide >= 0; }

    /** 捕获完成回调：side（0=左，1=右），keyName（空串 = 清除绑定）。 */
    public DoubleInputBar onBindCaptured(BiConsumer<Integer, String> callback) {
        this.onCaptured = callback;
        return this;
    }

    // ════════════════════ 渲染 ════════════════════

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        barBackground.render(g, x, y);
        iconLeft.render(g, x + ICON_LEFT_X, y + ICON_Y);
        iconRight.render(g, x + ICON_RIGHT_X, y + ICON_Y);
        inputBackground.render(g, x, y + INPUT_Y);

        // 槽位悬停高亮（捕获态不改颜色，仅以 >内容< + 下划线标识）
        if (isOverSlot(0, mouseX, mouseY)) {
            g.fill(x + HIT_X_1, y + INPUT_Y, x + HIT_X_1 + HIT_W, y + INPUT_Y + HIT_H, HOVER_COLOR);
        }
        if (isOverSlot(1, mouseX, mouseY)) {
            g.fill(x + HIT_X_2, y + INPUT_Y, x + HIT_X_2 + HIT_W, y + INPUT_Y + HIT_H, HOVER_COLOR);
        }

        drawSlotText(g, x, y, 0);
        drawSlotText(g, x, y, 1);
    }

    /** 槽位文本：槽位内居中；捕获中格式为 "> " + 下划线内容 + " <"（仅内容下划线）。 */
    private void drawSlotText(GuiGraphics g, int x, int y, int slot) {
        String keyName = slot == 0 ? leftKey : rightKey;
        String content = displayName(keyName);
        var font = Minecraft.getInstance().font;
        int slotX = x + (slot == 0 ? HIT_X_1 : HIT_X_2);
        boolean capturing = capturingSide == slot;

        Component text;
        int textWidth;
        if (capturing) {
            String fitted = fit(content, HIT_W - 12);
            text = Component.literal("> ")
                    .append(Component.literal(fitted).withStyle(style -> style.withUnderlined(true)))
                    .append(Component.literal(" <"));
            textWidth = font.width("> ") + font.width(fitted) + font.width(" <");
        } else {
            String fitted = fit(content, HIT_W - 4);
            text = Component.literal(fitted);
            textWidth = font.width(fitted);
        }
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

    /** 按键名转显示名；空串显示「未绑定」。 */
    private String displayName(String keyName) {
        if (keyName.isEmpty()) {
            return Component.translatable("gui.ccpe.control_desk.bind_unbound").getString();
        }
        try {
            return InputConstants.getKey(keyName).getDisplayName().getString();
        } catch (Exception e) {
            return keyName;
        }
    }

    private String fit(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - 3) + "...";
    }

    // ════════════════════ 交互（按键捕获，参考 aeroworks ModuleScreen） ════════════════════

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturingSide >= 0) {
            // 捕获中：点击任意鼠标键作为绑定（参考 aeroworks commitBind）
            capture(InputConstants.Type.MOUSE.getOrCreate(button).getName());
            return true;
        }
        int slot = slotAt(mouseX, mouseY);
        if (slot >= 0) {
            if (button == 0) {
                // 左键：进入捕获态
                capturingSide = slot;
                playClickSound();
            } else if (button == 1) {
                // 右键：清除绑定
                clear(slot);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturingSide >= 0) {
            if (keyCode == KEY_ESC) {
                capturingSide = -1; // 取消捕获
            } else {
                capture(InputConstants.getKey(keyCode, scanCode).getName());
            }
            return true;
        }
        return false;
    }

    /** 捕获完成：结束捕获态，更新显示、播放改键音效并回调。 */
    private void capture(String keyName) {
        int side = capturingSide;
        capturingSide = -1;
        setKey(side, keyName);
        playBindSound();
        if (onCaptured != null) {
            onCaptured.accept(side, keyName);
        }
    }

    /** 清除绑定：更新显示、播放点击音效并回调空串。 */
    private void clear(int side) {
        setKey(side, "");
        playClickSound();
        if (onCaptured != null) {
            onCaptured.accept(side, "");
        }
    }

    private void setKey(int side, String keyName) {
        if (side == 0) leftKey = keyName;
        else rightKey = keyName;
    }

    // ════════════════════ 音效 ════════════════════

    /** 点击进入捕获 / 清除绑定的音效（参考 aeroworks playUiClick）。 */
    private static void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f));
    }

    /** 改键成功的音效（与 ScrollValueBar 滚动音效一致）。 */
    private static void playBindSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }

    // ════════════════════ tooltip 构建 ════════════════════

    /** 追加标题行（蓝色，置于首行）。 */
    public DoubleInputBar addToolTipTitle(Component title) {
        tooltipLines.add(0, title.copy().withStyle(Style.EMPTY.withColor(0x528FDE)));
        return this;
    }

    /** 追加说明行（灰色斜体）。 */
    public DoubleInputBar addToolTipInstruction(Component instruction) {
        tooltipLines.add(instruction.copy().withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        return this;
    }

    /** 悬停时渲染 tooltip（由外层 Screen 在 super.render() 之后调用，确保在最上层）。 */
    @Override
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (slotAt(mouseX, mouseY) < 0 || tooltipLines.isEmpty()) return;
        g.renderComponentTooltip(Minecraft.getInstance().font, tooltipLines, mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }
}
