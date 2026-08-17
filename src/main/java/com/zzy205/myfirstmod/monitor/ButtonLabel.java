package com.zzy205.myfirstmod.monitor;

/**
 * 按钮模块（button_1）表面的标签文字。
 * <p>
 * 通过 Lua 的 {@code setLabel} 系列方法写入：{@link #text()} 为显示内容（空串 = 不渲染），
 * {@link #x()} / {@link #y()} 为相对标签原点（按钮表面视觉中心，见渲染器中的原点常量）的偏移
 * （MC 像素，1px = 1/16 块，+x 右、+y 上，默认 0,0），{@link #scale()} 为字号缩放
 * （块/字体像素，默认与旋钮角度显示一致），{@link #color()} 为文字颜色（0xRRGGBB，默认白色），
 * {@link #dropShadow()} 为是否绘制投影（默认开启）。
 */
public record ButtonLabel(String text, double x, double y, double scale, int color, boolean dropShadow) {

    /** 默认字号：与旋钮表面角度显示所用的缩放完全一致（1/512 块/字体像素）。 */
    public static final double DEFAULT_SCALE = 1.0 / 512.0;
    public static final double MIN_SCALE = 1.0 / 4096.0;
    public static final double MAX_SCALE = 1.0 / 8.0;

    /** 默认颜色：白色。 */
    public static final int DEFAULT_COLOR = 0xFFFFFF;

    /** 默认投影：开启（与旋钮角度文字一致）。 */
    public static final boolean DEFAULT_DROP_SHADOW = true;

    /** 空标签（全部默认值）。 */
    public static final ButtonLabel EMPTY =
            new ButtonLabel("", 0, 0, DEFAULT_SCALE, DEFAULT_COLOR, DEFAULT_DROP_SHADOW);

    /** 无任何可显示内容时返回 true。 */
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }

    /** 是否为默认值（空文本 + 全部默认属性），用于省略 NBT 序列化。 */
    public boolean isDefault() {
        return isEmpty()
                && Math.abs(x) < 1e-9
                && Math.abs(y) < 1e-9
                && Math.abs(scale - DEFAULT_SCALE) < 1e-9
                && color == DEFAULT_COLOR
                && dropShadow == DEFAULT_DROP_SHADOW;
    }

    /** 把字号缩放收敛到合理范围。 */
    public static double clampScale(double scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** 把颜色收敛到 0xRRGGBB。 */
    public static int clampColor(int color) {
        return color & 0xFFFFFF;
    }
}
