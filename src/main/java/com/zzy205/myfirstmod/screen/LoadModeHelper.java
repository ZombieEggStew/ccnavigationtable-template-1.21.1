package com.zzy205.myfirstmod.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * 加载模式滚动选择器的复用逻辑，供 Sensor 和 Receiver 屏幕共用。
 * <p>
 * 在物理体上时：0=关闭, 2=加载物理体；不在物理体上时：0=关闭, 1=加载区块
 */
public final class LoadModeHelper {

    private static final String[] ALL_MODE_KEYS = {"off", "chunk", "physics"};
    private static final String[] ALL_DESC_KEYS = {"off.desc", "chunk.desc", "physics.desc"};
    private static final String KEY_PREFIX = "gui.ccnavigationtable.load_mode.";

    /** 选择器宽度（像素） */
    public static final int HIT_W = 72;
    /** 选择器高度（像素，用于点击/悬浮检测） */
    public static final int HIT_H = 10;

    private LoadModeHelper() {}

    // ═══════════════ 模式值转换 ═══════════════

    /** 将 loadMode (0/1/2) 映射为显示索引 (0/1) */
    private static int toIndex(int loadMode) { return loadMode == 0 ? 0 : 1; }

    /** 将显示索引 (0/1) 映射为 loadMode (0/1 或 0/2) */
    private static int toMode(int index, boolean onPhysicsBody) {
        return index == 0 ? 0 : (onPhysicsBody ? 2 : 1);
    }

    /** 获取有效模式值数组（用于 tooltip 遍历） */
    private static int[] getModeValues(boolean onPhysicsBody) {
        return onPhysicsBody ? new int[]{0, 2} : new int[]{0, 1};
    }

    // ═══════════════ 渲染 ═══════════════

    /**
     * 渲染"加载: xxx"标签。
     */
    public static void renderLabel(GuiGraphics g, Font font, int x, int y,
                                    int loadMode, boolean onPhysicsBody) {
        String modeName = I18n.get(KEY_PREFIX + ALL_MODE_KEYS[loadMode]);
        String label = I18n.get("gui.ccnavigationtable.load_mode") + ": " + modeName;
        g.drawString(font, label, x, y, 0xfcfceb, true);
    }

    /**
     * 渲染悬浮 tooltip——只显示当前上下文中有效的两个选项。
     */
    public static void renderTooltip(GuiGraphics g, Font font,
                                      int screenX, int screenY,
                                      int loadMode, boolean onPhysicsBody,
                                      int mouseX, int mouseY) {
        if (mouseX < screenX || mouseX > screenX + HIT_W
                || mouseY < screenY || mouseY > screenY + HIT_H) return;

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.ccnavigationtable.load_mode")
                .withStyle(Style.EMPTY.withColor(0x528FDE)));
        lines.add(Component.translatable("gui.ccnavigationtable.scroll_to_change")
                .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));

        for (int m : getModeValues(onPhysicsBody)) {
            String prefix = (m == loadMode) ? "-> " : "> ";
            int color = (m == loadMode) ? 0x55FFFF : 0xAAAAAA;
            MutableComponent line = Component.translatable(KEY_PREFIX + ALL_MODE_KEYS[m])
                    .append(": ")
                    .append(Component.translatable(KEY_PREFIX + ALL_DESC_KEYS[m]));
            lines.add(Component.literal(prefix).append(line)
                    .withStyle(Style.EMPTY.withColor(color)));
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    // ═══════════════ 滚动 ═══════════════

    /**
     * 处理滚轮事件。在两个有效选项之间切换。
     *
     * @return 新的 loadMode 值，不匹配时返回 -1
     */
    public static int handleScroll(int screenX, int screenY,
                                    int loadMode, boolean onPhysicsBody,
                                    double mouseX, double mouseY, double scrollY) {
        if (mouseX < screenX || mouseX > screenX + HIT_W
                || mouseY < screenY || mouseY > screenY + HIT_H
                || scrollY == 0) return -1;

        int idx = toIndex(loadMode);
        idx = (idx + 1) % 2;  // 两个选项之间切换，忽略方向
        return toMode(idx, onPhysicsBody);
    }
}
