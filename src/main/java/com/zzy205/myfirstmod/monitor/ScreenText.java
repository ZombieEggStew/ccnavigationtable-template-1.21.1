package com.zzy205.myfirstmod.monitor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个屏幕（screen）的字符缓冲，仿 CC:T 终端的最小实现。
 * <p>
 * 文本不再使用「行列格子」，而是像 {@link #addRect} 一样用屏幕局部坐标直接定位：
 * 每个字符记录自己的左上角 {@code (x, y)}，单位与 drawRect 一致（1/128 块，原点在内区左上角）。
 * 光标也是该坐标系下的 {@code (x, y)}，{@link #write} 写入后按字号向右推进。
 * <p>
 * 文本只有前景色，<b>没有背景色</b>（需要背景由调用方自己 drawRect）。每个字符/矩形
 * 都带一个 {@code z} 层级（越大越靠前），未显式指定时使用 {@link #zIndex} 默认值。
 * <p>
 * 字号单位：MC 像素（1px = 1/16 块）。
 */
public class ScreenText {

    /** 默认字号（字形高度，MC 像素）。 */
    public static final double DEFAULT_SCALE = 0.5;
    public static final double MIN_SCALE = 0.05;
    public static final double MAX_SCALE = 8.0;
    /** 行距 = 字号 × 该系数。 */
    public static final double LINE_SPACING = 1.2;
    /** 字形为正方形（vanilla ascii.png 8×8 图集），列宽 = 字号。 */
    public static final double CHAR_ASPECT = 1.0;

    /**
     * 矩形 / 光标坐标单位换算：1 个 drawRect 单位 = 1/128 块，
     * 而字号单位是 MC 像素（1px = 1/16 块），故 1px = 8 个 drawRect 单位。
     */
    public static final double RECT_UNITS_PER_PX = 8.0;

    public static final int DEFAULT_TEXT_COLOUR = 0xFFFFFF;
    /** 默认层级（z），越大越靠前。 */
    public static final double DEFAULT_Z = 0.0;

    private final List<TextChar> chars = new ArrayList<>();
    private final List<Rect> rects = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();
    private double cursorX = 0; // drawRect 坐标（1/128 块）
    private double cursorY = 0; // drawRect 坐标（1/128 块）
    private int textColour = DEFAULT_TEXT_COLOUR;
    private double zIndex = DEFAULT_Z;
    private double textScale = DEFAULT_SCALE;
    private OverflowMode overflowMode = OverflowMode.WRAP;

    public ScreenText() {}

    /** 文本超出单行宽度时的处理方式。 */
    public enum OverflowMode {
        /** 直接截断，丢弃超出部分。 */
        TRUNCATE("truncate"),
        /** 多截断一点，用 "..." 补充。 */
        ELLIPSIS("ellipsis"),
        /** 自动换到下一行（默认）。 */
        WRAP("wrap");

        public final String name;

        OverflowMode(String name) { this.name = name; }

        /** 按名称解析；未知名称回退到 WRAP。 */
        public static OverflowMode byName(String name) {
            for (OverflowMode m : values()) {
                if (m.name.equals(name)) return m;
            }
            return WRAP;
        }
    }

    /**
     * 一个已写入的字符：{@code (x, y)} 为字符左上角（drawRect 坐标，1/128 块），
     * {@code z} 为层级（越大越靠前）。
     */
    public record TextChar(double x, double y, char ch, double z) {}

    /**
     * 一个矩形绘制指令。
     *
     * @param x         左上角 X（1/128 块，0 = 内区左缘，向右增大）
     * @param y         左上角 Y（1/128 块，0 = 内区上缘，向下增大）
     * @param width     宽（1/128 块）
     * @param height    高（1/128 块）
     * @param colour    颜色（0xRRGGBB）
     * @param solid     是否实心（false = 只描边）
     * @param lineWidth 线宽（1/128 块，仅描边时生效）
     * @param z         层级（越大越靠前）
     */
    public record Rect(double x, double y, double width, double height,
                       int colour, boolean solid, double lineWidth, double z) {}

    /**
     * 一条线段绘制指令（坐标均为 drawRect 坐标，1/128 块）。
     */
    public record Line(double x1, double y1, double x2, double y2,
                       int colour, double lineWidth, double z) {}

    /**
     * 一个圆绘制指令（坐标均为 drawRect 坐标，1/128 块）。
     *
     * @param segments 多边形逼近的段数（>= 3）
     */
    public record Circle(double cx, double cy, double radius, int colour,
                         boolean solid, double lineWidth, int segments, double z) {}

    // ── 布局 ──

    /** 由屏幕内区宽（像素）与字号计算列数。 */
    public static int colsFor(double innerWidthPx, double scale) {
        double pitch = Math.max(0.01, scale * CHAR_ASPECT);
        return Math.max(1, (int) Math.floor(innerWidthPx / pitch));
    }

    /** 由屏幕内区高（像素）与字号计算行数。 */
    public static int rowsFor(double innerHeightPx, double scale) {
        double pitch = Math.max(0.01, scale * LINE_SPACING);
        return Math.max(1, (int) Math.floor(innerHeightPx / pitch));
    }

    /** 单个字形宽度（drawRect 单位）。 */
    public double glyphWidth() {
        return textScale * CHAR_ASPECT * RECT_UNITS_PER_PX;
    }

    /** 单个行高（drawRect 单位）。 */
    public double lineHeight() {
        return textScale * LINE_SPACING * RECT_UNITS_PER_PX;
    }

    // ── 访问器 ──

    public List<TextChar> getChars() { return chars; }

    public List<Rect> getRects() { return rects; }

    public List<Line> getLines() { return lines; }

    public List<Circle> getCircles() { return circles; }

    public double getCursorX() { return cursorX; }

    public double getCursorY() { return cursorY; }

    public int getTextColour() { return textColour; }

    public double getZIndex() { return zIndex; }

    public double getTextScale() { return textScale; }

    public OverflowMode getOverflowMode() { return overflowMode; }

    // ── 修改 ──

    public void setTextScale(double scale) {
        this.textScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    public void setOverflowMode(OverflowMode mode) {
        this.overflowMode = mode != null ? mode : OverflowMode.WRAP;
    }

    public void setTextColour(int colour) { this.textColour = colour & 0xFFFFFF; }

    /** 设置之后 write/drawRect 未显式指定 z 时使用的默认层级。 */
    public void setZIndex(double z) { this.zIndex = z; }

    /** 设置光标位置（drawRect 坐标，原点在内区左上角），只收拢到非负。 */
    public void setCursor(double x, double y) {
        this.cursorX = Math.max(0, x);
        this.cursorY = Math.max(0, y);
    }

    /** 追加一个矩形绘制指令。 */
    public void addRect(double x, double y, double width, double height,
                        int colour, boolean solid, double lineWidth, double z) {
        rects.add(new Rect(x, y, Math.max(0, width), Math.max(0, height),
                colour & 0xFFFFFF, solid, Math.max(0, lineWidth), z));
    }

    /** 追加一条线段绘制指令。 */
    public void addLine(double x1, double y1, double x2, double y2,
                        int colour, double lineWidth, double z) {
        lines.add(new Line(x1, y1, x2, y2, colour & 0xFFFFFF, Math.max(0, lineWidth), z));
    }

    /** 追加一个圆绘制指令。 */
    public void addCircle(double cx, double cy, double radius, int colour,
                          boolean solid, double lineWidth, int segments, double z) {
        circles.add(new Circle(cx, cy, Math.max(0, radius), colour & 0xFFFFFF,
                solid, Math.max(0, lineWidth), Math.max(3, segments), z));
    }

    /** 清空所有矩形。 */
    public void clearRects() {
        rects.clear();
    }

    /** 清空所有图形（矩形 + 线段 + 圆），不影响文本。 */
    public void clearShapes() {
        rects.clear();
        lines.clear();
        circles.clear();
    }

    public void clear() {
        chars.clear();
        rects.clear();
        lines.clear();
        circles.clear();
        cursorX = 0;
        cursorY = 0;
    }

    /**
     * 在光标处写入文本（支持 {@code \n} 换行，{@code \r} 忽略）。
     * <p>
     * 每写入一个字符，光标按「字号 × 宽高比」向右推进；{@code \n} 让光标回行首并下移一行。
     * 当当前字符放不下（越过屏幕内区右缘）时按 {@link #overflowMode} 处理：
     * {@link OverflowMode#WRAP} 换行、{@link OverflowMode#TRUNCATE} 截断、
     * {@link OverflowMode#ELLIPSIS} 截断并把本行末尾最多 3 个字符替换成 "."。
     *
     * @param innerWidthUnits 屏幕内区宽度（drawRect 单位，用于右缘换行判定）
     * @param z               本次写入字符的层级（越大越靠前）
     */
    public void write(String text, double innerWidthUnits, double z) {
        if (text == null || text.isEmpty()) return;
        double glyphW = glyphWidth();
        double lineH = lineHeight();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r') continue;
            if (ch == '\n') {
                newline(lineH);
                continue;
            }

            // 当前字符将越过右缘：按 overflowMode 处理
            if (cursorX + glyphW > innerWidthUnits) {
                switch (overflowMode) {
                    case TRUNCATE -> { return; }
                    case ELLIPSIS -> { appendEllipsis(); return; }
                    case WRAP -> newline(lineH);
                }
            }

            chars.add(new TextChar(cursorX, cursorY, ch, z));
            cursorX += glyphW;
        }
    }

    /** 把本行末尾最多 3 个字符替换成 "."（实现 "..."）。 */
    private void appendEllipsis() {
        int replaced = 0;
        for (int i = chars.size() - 1; i >= 0 && replaced < 3; i--) {
            TextChar c = chars.get(i);
            if (Math.abs(c.y() - cursorY) > 1e-9) break; // 只处理当前行
            chars.set(i, new TextChar(c.x(), c.y(), '.', c.z()));
            replaced++;
        }
    }

    private void newline(double lineH) {
        cursorX = 0;
        cursorY += lineH;
    }

    // ── NBT ──

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("cursorX", cursorX);
        tag.putDouble("cursorY", cursorY);
        tag.putInt("textColour", textColour);
        tag.putDouble("zIndex", zIndex);
        tag.putDouble("textScale", textScale);
        tag.putString("overflowMode", overflowMode.name);

        ListTag charList = new ListTag();
        for (TextChar ch : chars) {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", ch.x());
            c.putDouble("y", ch.y());
            c.putInt("ch", ch.ch());
            c.putDouble("z", ch.z());
            charList.add(c);
        }
        tag.put("chars", charList);

        ListTag rectList = new ListTag();
        for (Rect rect : rects) {
            CompoundTag r = new CompoundTag();
            r.putDouble("x", rect.x());
            r.putDouble("y", rect.y());
            r.putDouble("w", rect.width());
            r.putDouble("h", rect.height());
            r.putInt("colour", rect.colour());
            r.putBoolean("solid", rect.solid());
            r.putDouble("lineWidth", rect.lineWidth());
            r.putDouble("z", rect.z());
            rectList.add(r);
        }
        tag.put("rects", rectList);

        ListTag lineList = new ListTag();
        for (Line l : lines) {
            CompoundTag t = new CompoundTag();
            t.putDouble("x1", l.x1());
            t.putDouble("y1", l.y1());
            t.putDouble("x2", l.x2());
            t.putDouble("y2", l.y2());
            t.putInt("colour", l.colour());
            t.putDouble("lineWidth", l.lineWidth());
            t.putDouble("z", l.z());
            lineList.add(t);
        }
        tag.put("lines", lineList);

        ListTag circleList = new ListTag();
        for (Circle c : circles) {
            CompoundTag t = new CompoundTag();
            t.putDouble("cx", c.cx());
            t.putDouble("cy", c.cy());
            t.putDouble("radius", c.radius());
            t.putInt("colour", c.colour());
            t.putBoolean("solid", c.solid());
            t.putDouble("lineWidth", c.lineWidth());
            t.putInt("segments", c.segments());
            t.putDouble("z", c.z());
            circleList.add(t);
        }
        tag.put("circles", circleList);

        return tag;
    }

    public void load(CompoundTag tag) {
        chars.clear();
        if (tag.contains("chars")) {
            ListTag charList = tag.getList("chars", Tag.TAG_COMPOUND);
            for (int i = 0; i < charList.size(); i++) {
                CompoundTag c = charList.getCompound(i);
                double z = c.contains("z") ? c.getDouble("z") : DEFAULT_Z;
                chars.add(new TextChar(c.getDouble("x"), c.getDouble("y"), (char) c.getInt("ch"), z));
            }
        }

        rects.clear();
        if (tag.contains("rects")) {
            ListTag rectList = tag.getList("rects", Tag.TAG_COMPOUND);
            for (int i = 0; i < rectList.size(); i++) {
                CompoundTag r = rectList.getCompound(i);
                double z = r.contains("z") ? r.getDouble("z") : DEFAULT_Z;
                rects.add(new Rect(r.getDouble("x"), r.getDouble("y"), r.getDouble("w"), r.getDouble("h"),
                        r.getInt("colour"), r.getBoolean("solid"), r.getDouble("lineWidth"), z));
            }
        }
        lines.clear();
        if (tag.contains("lines")) {
            ListTag list = tag.getList("lines", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                double z = t.contains("z") ? t.getDouble("z") : DEFAULT_Z;
                lines.add(new Line(t.getDouble("x1"), t.getDouble("y1"), t.getDouble("x2"), t.getDouble("y2"),
                        t.getInt("colour"), t.getDouble("lineWidth"), z));
            }
        }

        circles.clear();
        if (tag.contains("circles")) {
            ListTag list = tag.getList("circles", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                double z = t.contains("z") ? t.getDouble("z") : DEFAULT_Z;
                int segments = t.contains("segments") ? t.getInt("segments") : 32;
                circles.add(new Circle(t.getDouble("cx"), t.getDouble("cy"), t.getDouble("radius"),
                        t.getInt("colour"), t.getBoolean("solid"), t.getDouble("lineWidth"), segments, z));
            }
        }

        cursorX = Math.max(0, tag.getDouble("cursorX"));
        cursorY = Math.max(0, tag.getDouble("cursorY"));
        textColour = tag.contains("textColour") ? tag.getInt("textColour") : DEFAULT_TEXT_COLOUR;
        zIndex = tag.contains("zIndex") ? tag.getDouble("zIndex") : DEFAULT_Z;
        textScale = tag.contains("textScale") ? tag.getDouble("textScale") : DEFAULT_SCALE;
        overflowMode = OverflowMode.byName(tag.getString("overflowMode"));
    }
}
