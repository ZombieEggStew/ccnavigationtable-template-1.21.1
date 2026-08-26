package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorGridHost;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ScreenText;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.LuaTable;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectLuaTable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 屏幕（screen）的 Lua 模块实例（格子模型）。
 * <p>
 * 通过 {@code monitor.getCellModule(x, y)}（屏幕占用的格子）或 {@code monitor.getModule(id)}
 * 获取。屏幕与普通模块共用同一 ID 命名空间，{@link #getType()} 返回 "screen"。
 * <p>
 * 文本层为定长格子数组（LCD 帧缓冲语义）：{@link #setGrid(int, int)} 设定格子数，
 * {@link #write(String)} 从光标处逐格写入覆盖，{@link #fill(int, int, int, int, int)}
 * 批量设置背景色，{@link #draw(LuaTable)} 整屏一次传输（原子替换）。
 * 图形层（{@link #drawRect} / {@link #drawLine} / {@link #drawCircle}）保持自由定位与 z 层级。
 */
public final class ScreenModuleHandle extends ModuleHandle {

    public ScreenModuleHandle(MonitorGridHost be, GridState.ScreenRegion screen) {
        super(be, screen.id(), GridState.SCREEN_NAME, screen.minX(), screen.minY(),
                screen.width(), screen.height());
    }

    /** 读取屏幕的悬停说明文字（tooltip）。 */
    @LuaFunction
    public final String getTooltip() {
        GridState.ScreenRegion screen = be.getGridState().getScreenById(id);
        return screen != null ? screen.tooltipText() : "";
    }

    // ═══════════════ 格子布局 ═══════════════

    /** 当前文本缓冲，不存在返回 null。 */
    private @Nullable ScreenText text() {
        return be.getGridState().getScreenText(id);
    }

    /**
     * 设定屏幕格子数（cols × rows），格子铺满屏幕内区，字形尺寸由格子反推。
     * 重设会清空文本层（CC:T resize 语义）。
     *
     * <pre>{@code
     * scr.setGrid(10, 6)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setGrid(int cols, int rows) {
        be.screenSetGrid(id, cols, rows);
    }

    /** 读取当前格子数，返回 {@code cols, rows}。 */
    @LuaFunction
    public final MethodResult getGrid() {
        int[] grid = be.getScreenGrid(id);
        if (grid == null) return MethodResult.of(1.0, 1.0);
        return MethodResult.of((double) grid[0], (double) grid[1]);
    }

    /**
     * 按格子反推字号（等价于重设格子数）：
     * {@code cols = 内区宽 / scale}，{@code rows = 内区高 / (scale × 1.2)}。
     *
     * @param scale      字号（MC 像素，1px = 1/16 块）
     * @param lineSpacing 可选，格子高/格子宽比（行距系数，默认 1.2；传 1.0 得到正方形格子）
     *
     * <pre>{@code
     * scr.setTextScale(0.5)          -- 默认高宽比 1.2
     * scr.setTextScale(0.5, 1.0)     -- 正方形格子
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setTextScale(double scale, Optional<Double> lineSpacing) {
        be.screenSetTextScale(id, scale, lineSpacing.orElse(null));
    }

    /** 读取当前格子数（与 {@link #getGrid()} 相同），返回 {@code cols, rows}。 */
    @LuaFunction
    public final MethodResult getTextScale() {
        return getGrid();
    }

    // ═══════════════ 文本渲染（格子模型） ═══════════════

    /**
     * 从光标处逐格写入文本（支持 {@code \n} 换行；行尾按溢出模式 wrap/truncate/ellipsis）。
     * 写入覆盖该格字符与前景色，背景色保持不变（fill 设置的填充色不被覆盖）。
     *
     * <pre>{@code
     * scr.write("Hello")
     * scr.write("World\n")
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void write(String text) {
        be.screenWrite(id, text);
    }

    /**
     * 在固定区域内写入文本（每帧刷新定宽字段用，如时钟/计数器）。
     * 以 (col,row) 为起点、{@code width} 格宽的**单行区域**内写入 {@code text}：
     * 区域内**未写入文本的格子自动清空为空格**（前景色用当前 {@code setTextColour} 设置的颜色），
     * 区域内格子**背景色保留**（fill 底色不清）；区域外不动；光标不变。
     * <p>
     * {@code align} 为 {@code "left"}（默认）/ {@code "right"} / {@code "center"}；
     * 文本超过区域宽度时截断：左/中保留开头，右对齐保留末尾。
     *
     * <pre>{@code
     * scr.writeField(1, 1, 2, "15", "right")   -- |15|
     * scr.writeField(1, 1, 2, "6",  "right")   -- | 6|  ← 十位自动清空
     * }</pre>
     *
     * @param col   区域起始列（1 起）
     * @param row   区域行（1 起）
     * @param width 区域宽度（格，≤ 0 无操作）
     * @param text  要写入的文本
     * @param align 对齐方式（可选，默认 "left"）
     */
    @LuaFunction(mainThread = true)
    public final void writeField(int col, int row, int width, String text, Optional<String> align) {
        be.screenWriteField(id, col, row, width, text, align.orElse("left"));
    }

    /** 清空屏幕全部内容（格子 + 图形 + 光标），保留格子数。 */
    @LuaFunction(mainThread = true)
    public final void clear() {
        be.screenClear(id);
    }

    /**
     * 设置光标位置（格子坐标，1 起，CC:T 风格）。
     *
     * <pre>{@code
     * scr.setCursorPos(1, 1)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setCursorPos(int col, int row) {
        be.screenSetCursor(id, col, row);
    }

    /** 读取光标位置，返回 {@code col, row}（格子坐标，1 起）。 */
    @LuaFunction
    public final MethodResult getCursorPos() {
        ScreenText t = text();
        if (t == null) return MethodResult.of(1.0, 1.0);
        return MethodResult.of((double) t.getCursorCol(), (double) t.getCursorRow());
    }

    /** 设置前景色（0xRRGGBB，如 0xFF0000 为红色），影响之后 write 的字符颜色。 */
    @LuaFunction(mainThread = true)
    public final void setTextColour(int colour) {
        be.screenSetTextColour(id, colour);
    }

    /** 读取前景色（0xRRGGBB）。 */
    @LuaFunction
    public final int getTextColour() {
        ScreenText t = text();
        return t != null ? t.getTextColour() : ScreenText.DEFAULT_TEXT_COLOUR;
    }

    /** 设置之后 drawRect/drawLine/drawCircle 未显式指定 z 时使用的默认层级（越大越靠前）。 */
    @LuaFunction(mainThread = true)
    public final void setZIndex(double z) {
        be.screenSetZIndex(id, z);
    }

    /** 读取当前默认层级 z（仅图形层）。 */
    @LuaFunction
    public final double getZIndex() {
        ScreenText t = text();
        return t != null ? t.getZIndex() : ScreenText.DEFAULT_Z;
    }

    /**
     * 设置文本超出单行宽度时的处理方式：
     * <ul>
     *   <li>{@code "truncate"}：直接截断，丢弃超出部分</li>
     *   <li>{@code "ellipsis"}：多截断一点，末尾补 {@code "..."}</li>
     *   <li>{@code "wrap"}：自动换到下一行（默认）</li>
     * </ul>
     */
    @LuaFunction(mainThread = true)
    public final void setOverflowMode(String mode) {
        be.screenSetOverflowMode(id, mode);
    }

    /** 读取当前溢出处理方式，返回 "truncate" / "ellipsis" / "wrap"。 */
    @LuaFunction
    public final String getOverflowMode() {
        ScreenText t = text();
        return t != null ? t.getOverflowMode().name : ScreenText.OverflowMode.WRAP.name;
    }

    // ═══════════════ 填充（背景色） ═══════════════

    /**
     * 批量设置格子背景色（纯色填充，分段进度条用）。
     * 只改背景色，字符与前景色不变；与 write 叠加即「色块 + 文字」。
     *
     * <pre>{@code
     * scr.fill(1, 1, 10, 1, 0xFF0000)  -- 第一行前 10 格红色底
     * }</pre>
     *
     * @param col,row 起始格（1 起）
     * @param w,h     宽高（格，超出自动裁剪）
     * @param colour  颜色（0xRRGGBB）
     */
    @LuaFunction(mainThread = true)
    public final void fill(int col, int row, int w, int h, int colour) {
        be.screenFill(id, col, row, w, h, colour);
    }

    /**
     * 定宽区域填充（每帧刷新分段进度条用）：以 (col,row) 为起点、{@code width} 格宽的
     * **单行区域**内，把前 {@code count} 格背景设为 {@code colour}，**区域内其余格子
     * 背景自动清成透明**（进度减少时多余色块自动消失）；区域外与字符不动。
     * <p>
     * {@code align} 为 {@code "left"}（默认）/ {@code "right"} / {@code "center"}，
     * 决定 count 格在区域内的锚定方向；{@code count} 钳制到 [0, width]，传 0 即清空整个区域。
     *
     * <pre>{@code
     * scr.fillField(1, 2, 10, 7, 0x00FF00, "left")   -- 区域前 7 格绿色，其余透明
     * scr.fillField(1, 2, 10, 3, 0x00FF00, "left")   -- 进度减少：第 4..10 格自动清透明
     * }</pre>
     *
     * @param col    区域起始列（1 起）
     * @param row    区域行（1 起）
     * @param width  区域宽度（格，≤ 0 无操作）
     * @param count  要填充的格数（钳制到 [0, width]；0 = 全清）
     * @param colour 填充颜色（0xRRGGBB）
     * @param align  对齐方式（可选，默认 "left"）
     */
    @LuaFunction(mainThread = true)
    public final void fillField(int col, int row, int width, int count, int colour, Optional<String> align) {
        be.screenFillField(id, col, row, width, count, colour, align.orElse("left"));
    }

    // ═══════════════ 整屏批量传输 ═══════════════

    /**
     * 整屏一次传输（原子替换语义）：一次调用传整屏所有需要绘制的格子与可选图形，
     * 服务端清空后重建，客户端收到完整新画面，无中间态。
     * <p>
     * {@code batch} 为 Lua table，两段式结构：
     * <ul>
     *   <li>{@code cells}：每格一个数组 {@code {col, row, char, fg?, bg?}}（col/row 1 起；
     *       fg 省略沿用当前前景色，bg 省略为透明）</li>
     *   <li>{@code shapes}（可选）：图形数组，每项为带 {@code type} 字段的 table：
     *       {@code {type="rect", x, y, w, h, colour, solid?, lineWidth?, z?}} /
     *       {@code {type="line", x1, y1, x2, y2, colour, lineWidth?, z?}} /
     *       {@code {type="circle", cx, cy, radius, colour, solid?, lineWidth?, segments?, z?}} /
     *       {@code {type="point", x, y, colour, z?}}；z 省略用当前默认层级</li>
     * </ul>
     *
     * <pre>{@code
     * scr.draw({
     *   cells = {
     *     {1, 1, "A", 0xFFFFFF, 0x000000},
     *     {2, 1, "B", 0xFF0000},
     *   },
     *   shapes = {
     *     {type = "rect", x = 0, y = 0, w = 8, h = 8, colour = 0x00FF00, solid = true},
     *   },
     * })
     * }</pre>
     *
     * 只更新其中一层时可用 {@link #drawCells(LuaTable)} / {@link #drawShapes(LuaTable)}
     * （单层替换，另一层保持不变）。
     *
     * @throws LuaException 解析失败时抛出（整屏保持不变，不会部分应用）
     */
    @LuaFunction(mainThread = true)
    public final void draw(LuaTable<?, ?> batch) throws LuaException {
        ParsedShapes shapes = parseShapes(batch);
        be.screenDraw(id, parseCells(batch), shapes.rects(), shapes.lines(), shapes.circles());
    }

    // ═══════════════ 单层批量传输 ═══════════════

    /**
     * 只替换文本层（格子 + 光标）：一次调用传全部需要绘制的格子，
     * 服务端清空文本层后逐格写入（原子替换）。**图形层（rect/line/circle）保持不变**。
     * <p>
     * 参数结构与 {@link #draw(LuaTable)} 的 {@code cells} 段一致（外层仍是
     * {@code {cells = {...}}}）：每格一个数组 {@code {col, row, char, fg?, bg?}}
     * （col/row 1 起；fg 省略沿用当前前景色，bg 省略为透明）。
     * 替换会清空格子并把光标复位到 (1,1)，省略的格子为空白。
     *
     * <pre>{@code
     * scr.drawCells({ cells = {
     *   {1, 1, "A", 0xFFFFFF, 0x000000},
     *   {2, 1, "B", 0xFF0000},
     * }})
     * }</pre>
     *
     * @throws LuaException 解析失败时抛出（文本层保持不变，不会部分应用）
     */
    @LuaFunction(mainThread = true)
    public final void drawCells(LuaTable<?, ?> batch) throws LuaException {
        be.screenReplaceCells(id, parseCells(batch));
    }

    /**
     * 只替换图形层（rect/line/circle）：一次调用传全部需要绘制的图形，
     * 服务端清空图形层后写入（原子替换）。**文本层（格子 + 光标）保持不变**。
     * <p>
     * 参数结构与 {@link #draw(LuaTable)} 的 {@code shapes} 段一致（外层仍是
     * {@code {shapes = {...}}}）：每项为带 {@code type} 字段的 table：
     * {@code {type="rect", x, y, w, h, colour, solid?, lineWidth?, z?}} /
     * {@code {type="line", x1, y1, x2, y2, colour, lineWidth?, z?}} /
     * {@code {type="circle", cx, cy, radius, colour, solid?, lineWidth?, segments?, z?}} /
     * {@code {type="point", x, y, colour, z?}}；z 省略用当前默认层级。
     *
     * <pre>{@code
     * scr.drawShapes({ shapes = {
     *   {type = "rect", x = 0, y = 0, w = 8, h = 8, colour = 0x00FF00, solid = true},
     * }})
     * }</pre>
     *
     * @throws LuaException 解析失败时抛出（图形层保持不变，不会部分应用）
     */
    @LuaFunction(mainThread = true)
    public final void drawShapes(LuaTable<?, ?> batch) throws LuaException {
        ParsedShapes shapes = parseShapes(batch);
        be.screenReplaceShapes(id, shapes.rects(), shapes.lines(), shapes.circles());
    }

    /** draw/drawCells 共享：解析 cells 段（{cells = {{col,row,char,fg?,bg?}, ...}}）。 */
    private List<int[]> parseCells(LuaTable<?, ?> batch) throws LuaException {
        List<int[]> cells = new ArrayList<>();
        Object cellsObj = batch.get("cells");
        if (cellsObj instanceof Map<?, ?> cellsMap) {
            LuaTable<?, ?> cellsTable = cellsMap instanceof LuaTable<?, ?> lt ? lt : new ObjectLuaTable(cellsMap);
            for (int i = 1; i <= cellsTable.length(); i++) {
                Object row = cellsTable.get(i);
                if (!(row instanceof Map<?, ?> rowMap)) {
                    throw new LuaException("cells[" + i + "] must be a table {col, row, char, fg?, bg?}");
                }
                LuaTable<?, ?> rt = rowMap instanceof LuaTable<?, ?> lt2 ? lt2 : new ObjectLuaTable(rowMap);
                int col = rt.getInt(1);
                int rowIdx = rt.getInt(2);
                String s = rt.getString(3);
                char ch = s == null || s.isEmpty() ? ' ' : s.charAt(0);
                int fg = rt.length() > 3 ? rt.getInt(4) : currentTextColour();
                int bg = rt.length() > 4 ? rt.getInt(5) : ScreenText.TRANSPARENT_BG;
                cells.add(new int[] { col, rowIdx, ch, fg, bg });
            }
        }
        return cells;
    }

    /** draw/drawShapes 共享：解析 shapes 段（{shapes = {{type="rect"|"line"|"circle"|"point", ...}, ...}}）。 */
    private ParsedShapes parseShapes(LuaTable<?, ?> batch) throws LuaException {
        List<ScreenText.Rect> rects = new ArrayList<>();
        List<ScreenText.Line> lines = new ArrayList<>();
        List<ScreenText.Circle> circles = new ArrayList<>();
        Object shapesObj = batch.get("shapes");
        if (shapesObj instanceof Map<?, ?> shapesMap) {
            LuaTable<?, ?> shapesTable = shapesMap instanceof LuaTable<?, ?> lt ? lt : new ObjectLuaTable(shapesMap);
            for (int i = 1; i <= shapesTable.length(); i++) {
                Object shape = shapesTable.get(i);
                if (!(shape instanceof Map<?, ?> shapeMap)) {
                    throw new LuaException("shapes[" + i + "] must be a table");
                }
                LuaTable<?, ?> st = shapeMap instanceof LuaTable<?, ?> lt2 ? lt2 : new ObjectLuaTable(shapeMap);
                String type = st.getString("type");
                double z = st.optFiniteDouble("z").orElse(currentZIndex());
                if (type == null) throw new LuaException("shapes[" + i + "] missing 'type'");
                switch (type) {
                    case "rect" -> rects.add(new ScreenText.Rect(
                            st.getFiniteDouble("x"), st.getFiniteDouble("y"),
                            st.getFiniteDouble("w"), st.getFiniteDouble("h"),
                            st.getInt("colour"),
                            st.optBoolean("solid").orElse(true),
                            st.optFiniteDouble("lineWidth").orElse(0.0),
                            z));
                    case "line" -> lines.add(new ScreenText.Line(
                            st.getFiniteDouble("x1"), st.getFiniteDouble("y1"),
                            st.getFiniteDouble("x2"), st.getFiniteDouble("y2"),
                            st.getInt("colour"),
                            st.optFiniteDouble("lineWidth").orElse(0.0),
                            z));
                    case "circle" -> circles.add(new ScreenText.Circle(
                            st.getFiniteDouble("cx"), st.getFiniteDouble("cy"),
                            st.getFiniteDouble("radius"), st.getInt("colour"),
                            st.optBoolean("solid").orElse(true),
                            st.optFiniteDouble("lineWidth").orElse(0.0),
                            st.optInt("segments").orElse(32),
                            z));
                    case "point" -> rects.add(new ScreenText.Rect(
                            st.getFiniteDouble("x"), st.getFiniteDouble("y"),
                            1.0, 1.0, st.getInt("colour"), true, 0.0, z));
                    default -> throw new LuaException("Unknown shape type: " + type);
                }
            }
        }
        return new ParsedShapes(rects, lines, circles);
    }

    private record ParsedShapes(List<ScreenText.Rect> rects, List<ScreenText.Line> lines,
                                List<ScreenText.Circle> circles) {}

    private int currentTextColour() {
        ScreenText t = text();
        return t != null ? t.getTextColour() : ScreenText.DEFAULT_TEXT_COLOUR;
    }

    private double currentZIndex() {
        ScreenText t = text();
        return t != null ? t.getZIndex() : ScreenText.DEFAULT_Z;
    }

    // ═══════════════ 图形层（自由定位 + z 层级） ═══════════════

    /**
     * 在屏幕上画一个矩形（图形层，自由定位，不受格子约束）。
     *
     * @param x         左上角 X（1/128 块，0 = 内区左缘，向右增大）
     * @param y         左上角 Y（1/128 块，0 = 内区上缘，向下增大）
     * @param width     宽度（1/128 块）
     * @param height    高度（1/128 块）
     * @param colour    颜色（0xRRGGBB）
     * @param solid     是否实心（false = 只描边）
     * @param lineWidth 线宽（1/128 块，仅描边时生效）
     * @param z         层级（越大越靠前，省略时使用 {@link #setZIndex(double)} 设置的默认层级）
     */
    @LuaFunction(mainThread = true)
    public final void drawRect(double x, double y, double width, double height,
                               int colour, boolean solid, double lineWidth, Optional<Double> z) {
        be.screenDrawRect(id, x, y, width, height, colour, solid, lineWidth, z.orElse(null));
    }

    /** 清空所有已画的矩形。 */
    @LuaFunction(mainThread = true)
    public final void clearRects() {
        be.screenClearRects(id);
    }

    /**
     * 在屏幕上画一条线段（图形层）。
     *
     * @param x1, y1    起点（1/128 块，原点在内区左上角）
     * @param x2, y2    终点（1/128 块）
     * @param colour    颜色（0xRRGGBB）
     * @param lineWidth 线宽（1/128 块）
     * @param z         层级（越大越靠前，省略时用默认层级）
     */
    @LuaFunction(mainThread = true)
    public final void drawLine(double x1, double y1, double x2, double y2,
                               int colour, double lineWidth, Optional<Double> z) {
        be.screenDrawLine(id, x1, y1, x2, y2, colour, lineWidth, z.orElse(null));
    }

    /**
     * 在屏幕上画一个圆（图形层，用正多边形逼近）。
     *
     * @param cx, cy    圆心（1/128 块）
     * @param radius    半径（1/128 块）
     * @param colour    颜色（0xRRGGBB）
     * @param solid     true = 实心圆，false = 圆环
     * @param lineWidth 线宽（1/128 块，仅 {@code solid=false} 时生效）
     * @param segments  多边形逼近段数（默认 32，越大越圆，最小 3）
     * @param z         层级（越大越靠前，省略时用默认层级）
     */
    @LuaFunction(mainThread = true)
    public final void drawCircle(double cx, double cy, double radius, int colour,
                                 boolean solid, double lineWidth, Optional<Integer> segments, Optional<Double> z) {
        be.screenDrawCircle(id, cx, cy, radius, colour, solid, lineWidth,
                segments.orElse(32), z.orElse(null));
    }

    /**
     * 画一个点（图形层，等价于 1×1 单位的实心矩形）。
     *
     * @param x, y   左上角坐标（1/128 块）
     * @param colour 颜色（0xRRGGBB）
     * @param z      层级（越大越靠前，省略时用默认层级）
     */
    @LuaFunction(mainThread = true)
    public final void drawPoint(double x, double y, int colour, Optional<Double> z) {
        be.screenDrawRect(id, x, y, 1.0, 1.0, colour, true, 0.0, z.orElse(null));
    }

    /** 清空所有图形（矩形 + 线段 + 圆），不影响文本层。 */
    @LuaFunction(mainThread = true)
    public final void clearShapes() {
        be.screenClearShapes(id);
    }

    /** 读取屏幕当前格子数（与 {@link #getGrid()} 相同），返回 {@code cols, rows}。 */
    @LuaFunction
    public final MethodResult getSize() {
        return getGrid();
    }
}
