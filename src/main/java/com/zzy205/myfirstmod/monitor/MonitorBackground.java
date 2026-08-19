package com.zzy205.myfirstmod.monitor;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monitor 背景选项与默认值。
 * 内部键（KEYS）用于持久化，显示名可翻译（{@code gui.ccpe.monitor_menu.background.*}）。
 */
public final class MonitorBackground {

    /** 内部键（持久化用，稳定不变） */
    public static final String[] KEYS = {
            "cardboard", "black_checkerboard", "brown_checkerboard", "blue_checkerboard", "wood", "black"
    };
    public static final String DEFAULT = "blue_checkerboard";

    private static final String KEY_PREFIX = "gui.ccpe.monitor_menu.background.";

    private static final int DEFAULT_INDEX;

    static {
        int idx = 0;
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(DEFAULT)) {
                idx = i;
                break;
            }
        }
        DEFAULT_INDEX = idx;
    }

    private MonitorBackground() {}

    /** 返回选项下标；未知键回退到默认选项下标。 */
    public static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return DEFAULT_INDEX;
    }

    /** 按下标取内部键（自动环绕）。 */
    public static String keyAt(int index) {
        return KEYS[Math.floorMod(index, KEYS.length)];
    }

    /** 是否为合法内部键。 */
    public static boolean isValid(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) return true;
        }
        return isCustomKey(key);
    }

    /** 全部选项的显示名（可翻译）。 */
    public static Component[] displayNames() {
        return displayNames(List.of());
    }

    /** 内置背景后追加客户端扫描到的外部图片。 */
    public static Component[] displayNames(List<String> customKeys) {
        List<Component> names = new ArrayList<>(KEYS.length + customKeys.size());
        for (String key : KEYS) names.add(displayName(key));
        for (String key : customKeys) names.add(Component.literal(key.substring("custom/".length())));
        return names.toArray(Component[]::new);
    }

    /** 内置背景后追加客户端扫描到的外部图片。 */
    public static String keyAt(int index, List<String> customKeys) {
        int count = KEYS.length + customKeys.size();
        int normalized = Math.floorMod(index, count);
        return normalized < KEYS.length ? KEYS[normalized] : customKeys.get(normalized - KEYS.length);
    }

    /** 从图片文件名构造稳定的自定义持久化键；不接受目录或特殊字符。 */
    public static String customKey(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]*\\.(png|jpe?g)")) return null;
        return "custom/" + normalized;
    }

    private static boolean isCustomKey(String key) {
        return key != null && key.matches("custom/[a-z0-9][a-z0-9_.-]*\\.(png|jpe?g)");
    }

    /** 单个内部键的显示名。 */
    public static Component displayName(String key) {
        return Component.translatable(KEY_PREFIX + key);
    }
}
