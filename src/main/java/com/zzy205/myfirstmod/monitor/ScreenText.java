package com.zzy205.myfirstmod.monitor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个屏幕（screen）的文本缓冲 — 格子模型（LCD 帧缓冲语义，方案三）。
 * <p>
 * 文本层是<b>定长格子数组</b>：每格 = 字符 + 前景色 + 背景色，写入即覆盖该格，
 * 同位置永远只有一个值（无重叠面片），体积固定、不再随运行时长增长。
 * 格子数由用户经 {@link #setGrid(int, int)} 设定（{@code setTextScale} 为按格子反推字号的别名），
 * 字形尺寸由格子推导（{@code cellW = 可绘制区宽 / cols}）。
 * <p>
 * 定位为<b>光标制</b>：{@code setCursorPos(col, row)}，1 起（CC:T 风格），
 * {@link #write(String)} 从光标处逐格写入（保留 wrap / truncate / ellipsis 溢出处理）。
 * <p>
 * 图形层（矩形 / 线段 / 圆）保持自由定位（1/128 块）与 z 层级，不受格子约束，
 * 但仅在 screen 模块可绘制区域内绘制。
 * <p>
 * 背景色为 24 位 RGB；{@link #TRANSPARENT_BG}（-1）表示透明（不绘制背景 quad）。
 */
public class ScreenText {

    /** 可绘制区域内缩（块），每侧 1/64 块 = 1/4 px = 2 drawRect 单位。 */
    public static final double DRAWABLE_INSET = 1.0 / 64.0;
    /** 格子数上限（防滥用；本规模 6×6px 屏远小于此）。 */
    public static final int MAX_COLS = 128;
    public static final int MAX_ROWS = 128;
    /** 默认格子数（用户 setGrid 之前的兜底值，接近旧默认字号 0.5 在 6×6px 屏的 11×9 字符）。 */
    public static final int DEFAULT_COLS = 12;
    public static final int DEFAULT_ROWS = 10;
    /** 透明背景色标记（不渲染背景 quad）。 */
    public static final int TRANSPARENT_BG = -1;

    public static final int DEFAULT_TEXT_COLOUR = 0xFFFFFF;
    /** 默认层级（z），越大越靠前（仅图形层使用）。 */
    public static final double DEFAULT_Z = 0.0;

    /** 字形为正方形（vanilla ascii.png 8×8 图集），列宽 = 字号。 */
    public static final double CHAR_ASPECT = 1.0;
    /** 行距 = 字号 × 该系数（setTextScale 反推行数用；也是格子高/格子宽比，默认 1.2）。 */
    public static final double LINE_SPACING = 1.2;
    /** 1 个 drawRect 单位 = 1/128 块，1px = 1/16 块，故 1px = 8 个 drawRect 单位。 */
    public static final double RECT_UNITS_PER_PX = 8.0;

    private int cols = DEFAULT_COLS;
    private int rows = DEFAULT_ROWS;
    /** 格子内容：字符（' ' = 空格）。 */
    private char[] cells;
    /** 格子前景色（0xRRGGBB）。 */
    private int[] fg;
    /** 格子背景色（0xRRGGBB 或 {@link #TRANSPARENT_BG}）。 */
    private int[] bg;
    /** 光标列（1 起）。 */
    private int cursorCol = 1;
    /** 光标行（1 起）。 */
    private int cursorRow = 1;
    /** write 使用的前景色。 */
    private int textColour = DEFAULT_TEXT_COLOUR;
    /** 图形层默认层级 z（越大越靠前）。 */
    private double zIndex = DEFAULT_Z;
    /** 文本超出单行宽度时的处理方式。 */
    private OverflowMode overflowMode = OverflowMode.WRAP;

    private final List<Rect> rects = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();

    public ScreenText() {
        allocate(DEFAULT_COLS, DEFAULT_ROWS);
    }

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

    /** 定宽字段（{@link #writeField}）内的文本对齐方式。 */
    public enum Align {
        /** 靠区域左缘，右侧留空。 */
        LEFT("left"),
        /** 靠区域右缘，左侧留空（数字/时钟常用）。 */
        RIGHT("right"),
        /** 区域居中，两侧均分留空。 */
        CENTER("center");

        public final String name;

        Align(String name) { this.name = name; }

        /** 按名称解析；未知名称回退到 LEFT。 */
        public static Align byName(String name) {
            for (Align a : values()) {
                if (a.name.equals(name)) return a;
            }
            return LEFT;
        }
    }

    /**
     * 一个矩形绘制指令（图形层，自由定位）。
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
     * 一条线段绘制指令（图形层，坐标均为 drawRect 坐标，1/128 块）。
     */
    public record Line(double x1, double y1, double x2, double y2,
                       int colour, double lineWidth, double z) {}

    /**
     * 一个圆绘制指令（图形层，坐标均为 drawRect 坐标，1/128 块）。
     *
     * @param segments 多边形逼近的段数（>= 3）
     */
    public record Circle(double cx, double cy, double radius, int colour,
                         boolean solid, double lineWidth, int segments, double z) {}

    // ── 布局（由格子数推导） ──

    /** 由屏幕内区宽（像素）与字号计算列数。 */
    public static int colsFor(double innerWidthPx, double scale) {
        double pitch = Math.max(0.01, scale * CHAR_ASPECT);
        return Math.max(1, (int) Math.floor(innerWidthPx / pitch));
    }

    /** 由屏幕内区高（像素）与字号计算行数（用默认行距系数 {@link #LINE_SPACING}）。 */
    public static int rowsFor(double innerHeightPx, double scale) {
        return rowsFor(innerHeightPx, scale, LINE_SPACING);
    }

    /** 由屏幕内区高（像素）、字号与格子高宽比（行距系数）计算行数。 */
    public static int rowsFor(double innerHeightPx, double scale, double lineSpacing) {
        double pitch = Math.max(0.01, scale * Math.max(0.01, lineSpacing));
        return Math.max(1, (int) Math.floor(innerHeightPx / pitch));
    }

    /** 单格字形宽度（drawRect 单位）= 可绘制区宽 / cols。 */
    public double glyphWidthUnits(double innerWidthUnits) {
        return Math.max(1, innerWidthUnits / cols);
    }

    /** 单格行高（drawRect 单位）= 可绘制区高 / rows。 */
    public double lineHeightUnits(double innerHeightUnits) {
        return Math.max(1, innerHeightUnits / rows);
    }

    // ── 访问器 ──

    public int getCols() { return cols; }

    public int getRows() { return rows; }

    public int getCursorCol() { return cursorCol; }

    public int getCursorRow() { return cursorRow; }

    public int getTextColour() { return textColour; }

    public double getZIndex() { return zIndex; }

    public OverflowMode getOverflowMode() { return overflowMode; }

    /** 格子字符（(col,row) 1 起；越界返回空格）。 */
    public char getCellChar(int col, int row) {
        int i = index(col, row);
        return i < 0 ? ' ' : cells[i];
    }

    /** 格子前景色（(col,row) 1 起；越界返回默认前景色）。 */
    public int getCellFg(int col, int row) {
        int i = index(col, row);
        return i < 0 ? DEFAULT_TEXT_COLOUR : fg[i];
    }

    /** 格子背景色（(col,row) 1 起；越界返回 {@link #TRANSPARENT_BG}）。 */
    public int getCellBg(int col, int row) {
        int i = index(col, row);
        return i < 0 ? TRANSPARENT_BG : bg[i];
    }

    public List<Rect> getRects() { return rects; }

    public List<Line> getLines() { return lines; }

    public List<Circle> getCircles() { return circles; }

    /** 是否存在可绘制内容（任意非空格字符、非透明背景格或图形）。 */
    public boolean hasContent() {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != ' ' || bg[i] != TRANSPARENT_BG) return true;
        }
        return !rects.isEmpty() || !lines.isEmpty() || !circles.isEmpty();
    }

    private int index(int col, int row) {
        if (col < 1 || col > cols || row < 1 || row > rows) return -1;
        return (row - 1) * cols + (col - 1);
    }

    // ── 格子数 / 光标 ──

    /**
     * 重设格子数并清空文本层（CC:T resize 语义），光标回到 (1,1)。
     */
    public void setGrid(int newCols, int newRows) {
        allocate(clampCols(newCols), clampRows(newRows));
    }

    /**
     * {@code setTextScale} 的别名语义：按格子反推字号（等价于重设格子）。
     * 字形尺寸 = 内区宽 / cols，故 {@code cols = colsFor(innerWidthPx, scale)}；
     * 行距系数用默认 {@link #LINE_SPACING}（1.2）。
     * 重设格子并清空文本层。
     */
    public void setTextScale(double scale, double innerWidthPx, double innerHeightPx) {
        setTextScale(scale, LINE_SPACING, innerWidthPx, innerHeightPx);
    }

    /**
     * 带格子高宽比的 {@code setTextScale}：{@code lineSpacing} 为格子高/格子宽比
     * （行距系数，默认 {@link #LINE_SPACING} = 1.2；传 1.0 得到正方形格子）。
     * 等价于按字号 + 高宽比重设格子，重设会清空文本层。
     */
    public void setTextScale(double scale, double lineSpacing, double innerWidthPx, double innerHeightPx) {
        setGrid(colsFor(innerWidthPx, scale), rowsFor(innerHeightPx, scale, lineSpacing));
    }

    /** 设置光标位置（格子坐标，1 起；自动收拢到格子范围内）。 */
    public void setCursorPos(int col, int row) {
        this.cursorCol = clamp(col, 1, cols);
        this.cursorRow = clamp(row, 1, rows);
    }

    public void setTextColour(int colour) { this.textColour = colour & 0xFFFFFF; }

    /** 设置图形层默认层级（越大越靠前）。 */
    public void setZIndex(double z) { this.zIndex = z; }

    public void setOverflowMode(OverflowMode mode) {
        this.overflowMode = mode != null ? mode : OverflowMode.WRAP;
    }

    private void allocate(int c, int r) {
        this.cols = c;
        this.rows = r;
        int n = c * r;
        cells = new char[n];
        fg = new int[n];
        bg = new int[n];
        java.util.Arrays.fill(cells, ' ');
        java.util.Arrays.fill(fg, DEFAULT_TEXT_COLOUR);
        java.util.Arrays.fill(bg, TRANSPARENT_BG);
        cursorCol = 1;
        cursorRow = 1;
    }

    private static int clampCols(int v) { return clamp(v, 1, MAX_COLS); }
    private static int clampRows(int v) { return clamp(v, 1, MAX_ROWS); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    // ── 写入（LCD 帧缓冲语义） ──

    /**
     * 从光标处逐格写入文本（支持 {@code \n} 换行，{@code \r} 忽略）。
     * <p>
     * 每写入一个字符覆盖该格（字符 + 当前前景色），光标右移一格；
     * <b>背景色保持不变</b>（fill 设置的填充色不被 write 覆盖，支持「色块 + 文字」叠加）。
     * 到达行尾时按 {@link #overflowMode} 处理：
     * {@link OverflowMode#WRAP} 换行、{@link OverflowMode#TRUNCATE} 截断、
     * {@link OverflowMode#ELLIPSIS} 截断并把本行末尾最多 3 格替换成 "."。
     * 到达最后一行之后继续写入会被丢弃。
     */
    public void write(String text) {
        if (text == null || text.isEmpty()) return;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r') continue;
            if (ch == '\n') {
                newline();
                continue;
            }
            if (cursorRow > rows) return; // 屏幕已满（最后一行之后丢弃）

            // 当前字符将越过行尾：按 overflowMode 处理
            if (cursorCol > cols) {
                switch (overflowMode) {
                    case TRUNCATE -> { return; }
                    case ELLIPSIS -> { appendEllipsis(); return; }
                    case WRAP -> newline();
                }
                if (cursorRow > rows) return;
            }

            int idx = index(cursorCol, cursorRow);
            cells[idx] = ch;
            fg[idx] = textColour;
            cursorCol++;
        }
    }

    /** 把当前行末尾最多 3 格替换成 "."（实现 "..."），不改变光标。 */
    private void appendEllipsis() {
        int row = cursorRow;
        int end = Math.min(cols, cursorCol - 1);
        int start = Math.max(1, end - 2);
        for (int c = start; c <= end; c++) {
            int idx = index(c, row);
            cells[idx] = '.';
        }
    }

    private void newline() {
        cursorCol = 1;
        cursorRow++;
    }

    /**
     * 在固定区域内写入文本（每帧刷新定宽字段用）。
     * <p>
     * 以 (col,row) 为起点、{@code width} 格宽的**单行区域**内写入 {@code text}：
     * 区域内**未写入文本的格子字符清空为空格**（前景色用当前 {@link #textColour}），
     * 区域内格子**背景色保留**（fill 底色不被清掉）；区域外的格子完全不动。
     * <p>
     * 对齐由 {@code align} 决定（{@link Align#LEFT} 靠左 / {@link Align#RIGHT} 靠右 /
     * {@link Align#CENTER} 居中）；文本超过区域宽度时截断：
     * 左对齐/居中保留文本开头，右对齐保留文本末尾（printf {@code %2s} 风格）。
     * 光标位置不变。
     *
     * @param col   区域起始列（1 起，自动钳制到格子范围）
     * @param row   区域行（1 起，自动钳制到格子范围）
     * @param width 区域宽度（格，≤ 0 时无操作；超出格子范围自动裁剪）
     * @param text  要写入的文本（null 视为空字符串）
     * @param align 对齐方式（null 回退 {@link Align#LEFT}）
     */
    public void writeField(int col, int row, int width, String text, Align align) {
        if (width <= 0) return;
        int r = clamp(row, 1, rows);
        int c0 = clamp(col, 1, cols);
        int c1 = clamp(col + width - 1, 1, cols);
        if (c0 > c1) return;
        Align a = align != null ? align : Align.LEFT;
        String s = text != null ? text : "";

        // 区域内先全部清成空格（前景色用当前色，背景保留）
        for (int c = c0; c <= c1; c++) {
            int idx = index(c, r);
            cells[idx] = ' ';
            fg[idx] = textColour;
        }

        int len = s.length();
        int span = c1 - c0 + 1;
        if (len == 0) return;
        // 截断：左/中保留开头，右对齐保留末尾
        int take = Math.min(len, span);
        int srcStart = (a == Align.RIGHT && len > span) ? len - span : 0;
        // 起始列：左=c0，右=c1-take+1，中=c0+(span-take)/2
        int dstStart = switch (a) {
            case RIGHT -> c1 - take + 1;
            case CENTER -> c0 + (span - take) / 2;
            default -> c0;
        };
        for (int i = 0; i < take; i++) {
            int idx = index(dstStart + i, r);
            if (idx < 0) continue; // 理论上不会越界，防御
            cells[idx] = s.charAt(srcStart + i);
            fg[idx] = textColour;
        }
    }

    /**
     * 批量设置格子背景色（纯色填充，分段进度条用）。
     * 只改背景色，字符与前景色不变。
     *
     * @param col,row 起始格（1 起）
     * @param w,h     宽高（格，超出自动裁剪）
     * @param colour  颜色（0xRRGGBB）
     */
    public void fill(int col, int row, int w, int h, int colour) {
        int c0 = clamp(col, 1, cols);
        int r0 = clamp(row, 1, rows);
        int c1 = clamp(col + Math.max(0, w) - 1, 1, cols);
        int r1 = clamp(row + Math.max(0, h) - 1, 1, rows);
        int col24 = colour & 0xFFFFFF;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                bg[index(c, r)] = col24;
            }
        }
    }

    /**
     * 定宽区域填充（每帧刷新分段进度条用）：以 (col,row) 为起点、{@code width} 格宽的
     * **单行区域**内，把前 {@code count} 格背景设为 {@code colour}（按 {@code align}
     * 锚定：{@link Align#LEFT} 靠起点 / {@link Align#RIGHT} 靠终点 / {@link Align#CENTER} 居中），
     * **区域内其余格子背景清成透明**（进度减少时多余色块自动消失）；区域外不动，字符不动。
     * <p>
     * {@code count} 钳制到 [0, width]，传 0 即清空整个区域（等同 fillClear 用途）。
     *
     * @param col    区域起始列（1 起，自动钳制到格子范围）
     * @param row    区域行（1 起，自动钳制到格子范围）
     * @param width  区域宽度（格，≤ 0 无操作；超出格子范围自动裁剪）
     * @param count  要填充的格数（钳制到 [0, width]）
     * @param colour 填充颜色（0xRRGGBB）
     * @param align  对齐方式（null 回退 {@link Align#LEFT}）
     */
    public void fillField(int col, int row, int width, int count, int colour, Align align) {
        if (width <= 0) return;
        int r = clamp(row, 1, rows);
        int c0 = clamp(col, 1, cols);
        int c1 = clamp(col + width - 1, 1, cols);
        if (c0 > c1) return;
        Align a = align != null ? align : Align.LEFT;

        // 区域内全部清成透明背景
        for (int c = c0; c <= c1; c++) {
            bg[index(c, r)] = TRANSPARENT_BG;
        }

        int span = c1 - c0 + 1;
        int take = clamp(count, 0, span);
        if (take == 0) return;
        int start = switch (a) {
            case RIGHT -> c1 - take + 1;
            case CENTER -> c0 + (span - take) / 2;
            default -> c0;
        };
        int col24 = colour & 0xFFFFFF;
        for (int i = 0; i < take; i++) {
            int idx = index(start + i, r);
            if (idx < 0) continue; // 理论上不会越界，防御
            bg[idx] = col24;
        }
    }

    /**
     * 整屏替换（draw(batch) 的原子语义）：先清空文本层（格子 + 图形 + 光标），
     * 再逐格写入与图形。所有格子同位置永远只有一个值，无中间态。
     *
     * @param newCells 每格一行：{col, row, char, fg, bg}（col/row 1 起；fg/bg 省略用默认值）
     */
    public void replaceAll(List<int[]> newCells, List<Rect> newRects,
                           List<Line> newLines, List<Circle> newCircles) {
        replaceCells(newCells);
        replaceShapes(newRects, newLines, newCircles);
    }

    /**
     * 单层替换文本层（drawCells 的原子语义）：清空全部格子与光标（保留格子数），
     * 再逐格写入。**图形层（rect/line/circle）保持不变**。
     * 光标复位到 (1,1)；省略 fg 的格子用当前前景色 {@link #textColour}，省略 bg 为透明。
     *
     * @param newCells 每格一行：{col, row, char, fg, bg}（col/row 1 起；fg/bg 省略用默认值）
     */
    public void replaceCells(List<int[]> newCells) {
        allocate(cols, rows); // 保留当前格子数，清空格子与光标；rects/lines/circles 不动
        if (newCells != null) {
            for (int[] cell : newCells) {
                if (cell == null || cell.length < 3) continue;
                int idx = index(cell[0], cell[1]);
                if (idx < 0) continue;
                cells[idx] = (char) cell[2];
                fg[idx] = cell.length > 3 ? (cell[3] & 0xFFFFFF) : textColour;
                bg[idx] = cell.length > 4 ? (cell[4] & 0xFFFFFF) : TRANSPARENT_BG;
            }
        }
    }

    /**
     * 单层替换图形层（drawShapes 的原子语义）：清空全部图形（rect/line/circle），
     * 再写入传入图形。**文本层（格子 + 光标）保持不变**。
     */
    public void replaceShapes(List<Rect> newRects, List<Line> newLines, List<Circle> newCircles) {
        rects.clear();
        if (newRects != null) rects.addAll(newRects);
        lines.clear();
        if (newLines != null) lines.addAll(newLines);
        circles.clear();
        if (newCircles != null) circles.addAll(newCircles);
    }

    // ── 图形层（自由定位） ──

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

    /** 清空所有图形（矩形 + 线段 + 圆），不影响文本层。 */
    public void clearShapes() {
        rects.clear();
        lines.clear();
        circles.clear();
    }

    /** 清空全部内容（格子 + 图形 + 光标），保留格子数。 */
    public void clear() {
        allocate(cols, rows);
        rects.clear();
        lines.clear();
        circles.clear();
    }

    // ── NBT ──

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cols", cols);
        tag.putInt("rows", rows);
        tag.putInt("cursorCol", cursorCol);
        tag.putInt("cursorRow", cursorRow);
        tag.putInt("textColour", textColour);
        tag.putDouble("zIndex", zIndex);
        tag.putString("overflowMode", overflowMode.name);

        // 定长格子数组（紧凑编码：char[] / int[]，弃逐格 CompoundTag）
        int[] charArray = new int[cells.length];
        for (int i = 0; i < cells.length; i++) charArray[i] = cells[i];
        tag.putIntArray("cells", charArray);
        tag.putIntArray("fg", fg);
        tag.putIntArray("bg", bg);

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
        // 旧版（自由定位，无 "cols"）为破坏性变更：忽略旧文本，用默认格子数
        int loadCols = tag.contains("cols") ? clampCols(tag.getInt("cols")) : DEFAULT_COLS;
        int loadRows = tag.contains("rows") ? clampRows(tag.getInt("rows")) : DEFAULT_ROWS;
        allocate(loadCols, loadRows);

        if (tag.contains("cells")) {
            int[] charArray = tag.getIntArray("cells");
            int[] fgArray = tag.getIntArray("fg");
            int[] bgArray = tag.getIntArray("bg");
            int n = Math.min(cells.length, charArray.length);
            for (int i = 0; i < n; i++) {
                cells[i] = (char) charArray[i];
                if (i < fgArray.length) fg[i] = fgArray[i];
                if (i < bgArray.length) bg[i] = bgArray[i];
            }
        }

        cursorCol = clamp(tag.getInt("cursorCol"), 1, cols);
        cursorRow = clamp(tag.getInt("cursorRow"), 1, rows);
        textColour = tag.contains("textColour") ? tag.getInt("textColour") : DEFAULT_TEXT_COLOUR;
        zIndex = tag.contains("zIndex") ? tag.getDouble("zIndex") : DEFAULT_Z;
        overflowMode = OverflowMode.byName(tag.getString("overflowMode"));

        rects.clear();
        if (tag.contains("rects")) {
            ListTag rectList = tag.getList("rects", Tag.TAG_COMPOUND);
            for (int i = 0; i < rectList.size(); i++) {
                CompoundTag rectTag = rectList.getCompound(i);
                double z = rectTag.contains("z") ? rectTag.getDouble("z") : DEFAULT_Z;
                rects.add(new Rect(rectTag.getDouble("x"), rectTag.getDouble("y"), rectTag.getDouble("w"), rectTag.getDouble("h"),
                        rectTag.getInt("colour"), rectTag.getBoolean("solid"), rectTag.getDouble("lineWidth"), z));
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
    }
}
