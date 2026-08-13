package com.zzy205.myfirstmod.channel;

/**
 * 频道滚动辅助 —— 三处频道 GUI 共用的「钳位 → 跳过占用 → 边界反向再跳占」逻辑。
 */
public final class ChannelScrollHelper {
    public static final int MIN = 0;
    public static final int MAX = 9999;

    private ChannelScrollHelper() {}

    /**
     * 以当前值滚动一步，返回跳过占用后的下一个频道。
     *
     * @param current   当前频道号
     * @param dir       滚动方向：1=增大, -1=减小
     * @param step      步长（Shift 加速时用 10）
     * @param myChannel 自身频道号（不视为占用）
     * @param occupied  已被占用的频道号数组
     */
    public static int next(int current, int dir, int step, int myChannel, int[] occupied) {
        int value = clamp(current + dir * step);
        value = skip(value, dir, myChannel, occupied);
        // 边界钳位后反向再跳占，防止钳位值恰好被占用（如 0 号频道）
        if (value < MIN) value = skip(MIN, 1, myChannel, occupied);
        if (value > MAX) value = skip(MAX, -1, myChannel, occupied);
        return clamp(value);
    }

    /** 从 0 开始找最小空闲频道。 */
    public static int findFree(int[] occupied) {
        int ch = 0;
        while (contains(occupied, ch)) ch++;
        return ch;
    }

    private static int skip(int value, int dir, int myChannel, int[] occupied) {
        int safety = 0;
        while (safety < 10000 && isOccupiedByOther(value, myChannel, occupied)) {
            value += dir;
            if (value < MIN || value > MAX) break;
            safety++;
        }
        return value;
    }

    private static boolean isOccupiedByOther(int channel, int myChannel, int[] occupied) {
        if (channel == myChannel) return false;
        return contains(occupied, channel);
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    private static int clamp(int v) {
        return v < MIN ? MIN : (v > MAX ? MAX : v);
    }
}
