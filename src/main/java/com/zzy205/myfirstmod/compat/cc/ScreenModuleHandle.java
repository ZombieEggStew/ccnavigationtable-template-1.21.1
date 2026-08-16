package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ScreenText;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 屏幕（screen）的 Lua 模块实例。
 * <p>
 * 通过 {@code monitor.getCellModule(x, y)}（屏幕占用的格子）或 {@code monitor.getModule(id)}
 * 获取。屏幕与普通模块共用同一 ID 命名空间，{@link #getType()} 返回 "screen"。
 * <p>
 * 除 {@link #getTooltip()} / {@link ModuleHandle#setTooltip(String)} 外，
 * 还提供仿 CC:T 终端的文本渲染 API：{@link #write(String, Optional)} / {@link #clear()} /
 * {@link #setCursorPos(double, double)} / {@link #setTextScale(double)} 等。
 */
public final class ScreenModuleHandle extends ModuleHandle {

    public ScreenModuleHandle(MonitorBlockEntity be, GridState.ScreenRegion screen) {
        super(be, screen.id(), "screen", screen.minX(), screen.minY(),
                screen.width(), screen.height());
    }

    /** 读取屏幕的悬停说明文字（tooltip）。 */
    @LuaFunction
    public final String getTooltip() {
        GridState.ScreenRegion screen = be.getGridState().getScreenById(id);
        return screen != null ? screen.tooltipText() : "";
    }

    // ═══════════════ 文本渲染 ═══════════════

    /** 当前文本缓冲，不存在返回 null。 */
    private @Nullable ScreenText text() {
        return be.getGridState().getScreenText(id);
    }

    /**
     * 在光标处写入文本（支持 {@code \n} 换行，写到右缘按溢出模式换行/截断）。
     * <p>
     * 可选参数 {@code z} 指定本次写入字符的层级（越大越靠前），省略时使用
     * {@link #setZIndex(double)} 设置的默认层级。
     *
     * <pre>{@code
     * scr.write("Hello")
     * scr.write("World\n", 2)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void write(String text, Optional<Double> z) {
        be.screenWrite(id, text, z.orElse(null));
    }

    /** 清空屏幕文本。 */
    @LuaFunction(mainThread = true)
    public final void clear() {
        be.screenClear(id);
    }

    /**
     * 设置光标位置，坐标系统与 {@link #drawRect(double, double, double, double, int, boolean, double)}
     * 的前两个参数完全一致：以屏幕内区左上角为原点，X 向右、Y 向下，1 单位 = 1/128 块。
     */
    @LuaFunction(mainThread = true)
    public final void setCursorPos(double x, double y) {
        be.screenSetCursor(id, x, y);
    }

    /** 读取光标位置，返回 {@code x, y}（drawRect 坐标）。 */
    @LuaFunction
    public final MethodResult getCursorPos() {
        ScreenText t = text();
        if (t == null) return MethodResult.of(0.0, 0.0);
        return MethodResult.of(t.getCursorX(), t.getCursorY());
    }

    /**
     * 设置整块屏幕的字号（字形高度，MC 像素，1px = 1/16 块）。
     * <p>
     * 只影响之后 {@link #write(String)} 写入的字形大小与推进量，不影响已写入文本的位置。
     */
    @LuaFunction(mainThread = true)
    public final void setTextScale(double scale) {
        be.screenSetTextScale(id, scale);
    }

    /** 读取当前字号。 */
    @LuaFunction
    public final double getTextScale() {
        ScreenText t = text();
        return t != null ? t.getTextScale() : ScreenText.DEFAULT_SCALE;
    }

    /** 设置前景色（0xRRGGBB，如 0xFF0000 为红色）。 */
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

    /**
     * 设置之后 {@link #write(String, Optional)} / {@link #drawRect} 未显式指定 z 时
     * 使用的默认层级（越大越靠前，默认 0；负值会被压进面板后面）。
     */
    @LuaFunction(mainThread = true)
    public final void setZIndex(double z) {
        be.screenSetZIndex(id, z);
    }

    /** 读取当前默认层级 z。 */
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
     *
     * <pre>{@code
     * scr.setOverflowMode("ellipsis")
     * scr.write("Hello World")
     * }</pre>
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

    // ═══════════════ 矩形绘制 ═══════════════

    /**
     * 在屏幕上画一个矩形。
     *
    * @param x         左上角 X（1/128 块，0 = 内区左缘，向右增大）
    * @param y         左上角 Y（1/128 块，0 = 内区上缘，向下增大）
    * @param width     宽度（1/128 块）
    * @param height    高度（1/128 块）
     * @param colour    颜色（0xRRGGBB，如 0xFF0000 为红色）
     * @param solid     是否实心（false = 只描边）
     * @param lineWidth 线宽（1/128 块，仅描边时生效）
     * @param z         层级（越大越靠前，省略时使用 {@link #setZIndex(double)} 设置的默认层级）
     *
     * <pre>{@code
     * scr.drawRect(0, 0, 2, 2, 0xFF0000, true, 1)          -- 默认层级
     * scr.drawRect(1, 1, 1, 1, 0x00FF00, false, 0.2, 3)    -- 层级 3，盖在默认层之上
     * }</pre>
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
     * 在屏幕上画一条线段。
     *
     * @param x1, y1    起点（1/128 块，原点在内区左上角）
     * @param x2, y2    终点（1/128 块）
     * @param colour    颜色（0xRRGGBB）
     * @param lineWidth 线宽（1/128 块）
     * @param z         层级（越大越靠前，省略时用 {@link #setZIndex(double)} 设置的默认层级）
     *
     * <pre>{@code
     * scr.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void drawLine(double x1, double y1, double x2, double y2,
                               int colour, double lineWidth, Optional<Double> z) {
        be.screenDrawLine(id, x1, y1, x2, y2, colour, lineWidth, z.orElse(null));
    }

    /**
     * 在屏幕上画一个圆（用正多边形逼近）。
     *
     * @param cx, cy    圆心（1/128 块）
     * @param radius    半径（1/128 块）
     * @param colour    颜色（0xRRGGBB）
     * @param solid     true = 实心圆，false = 圆环
     * @param lineWidth 线宽（1/128 块，仅 {@code solid=false} 时生效）
     * @param segments  多边形逼近段数（默认 32，越大越圆，最小 3）
     * @param z         层级（越大越靠前，省略时用 {@link #setZIndex(double)} 设置的默认层级）
     *
     * <pre>{@code
     * scr.drawCircle(8, 8, 4, 0xFFFF00, true, 1)          -- 实心圆
     * scr.drawCircle(8, 8, 4, 0x00FF00, false, 0.2, 48)   -- 48 段圆环
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void drawCircle(double cx, double cy, double radius, int colour,
                                 boolean solid, double lineWidth, Optional<Integer> segments, Optional<Double> z) {
        be.screenDrawCircle(id, cx, cy, radius, colour, solid, lineWidth,
                segments.orElse(32), z.orElse(null));
    }

    /**
     * 画一个点（等价于 1×1 单位的实心矩形）。
     *
     * @param x, y   左上角坐标（1/128 块）
     * @param colour 颜色（0xRRGGBB）
     * @param z      层级（越大越靠前，省略时用 {@link #setZIndex(double)} 设置的默认层级）
     *
     * <pre>{@code
     * scr.drawPoint(4, 4, 0xFF0000)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void drawPoint(double x, double y, int colour, Optional<Double> z) {
        be.screenDrawRect(id, x, y, 1.0, 1.0, colour, true, 0.0, z.orElse(null));
    }

    /** 清空所有图形（矩形 + 线段 + 圆），不影响文本。 */
    @LuaFunction(mainThread = true)
    public final void clearShapes() {
        be.screenClearShapes(id);
    }

    /**
     * 读取屏幕按当前字号可显示的行列数，返回 {@code cols, rows}。
     */
    @LuaFunction
    public final MethodResult getSize() {
        int[] size = be.getScreenSize(id);
        if (size == null) return MethodResult.of(1.0, 1.0);
        return MethodResult.of((double) size[0], (double) size[1]);
    }
}
