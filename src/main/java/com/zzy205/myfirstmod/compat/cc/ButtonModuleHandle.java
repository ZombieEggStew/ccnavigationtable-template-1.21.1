package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorGridHost;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;

/**
 * 按钮模块（button_1）的 Lua 模块实例。
 * <p>
 * 按钮为瞬时型：{@link #press()} 按下、{@link #release()} 弹起，{@link #isPressed()} 读取当前按下状态。
 */
public final class ButtonModuleHandle extends ModuleHandle {

    public ButtonModuleHandle(MonitorGridHost be, MonitorModule module) {
        super(be, module.id(), module.type().name, module.gridX(), module.gridY(),
                module.getWidth(), module.getHeight());
    }

    /** 按下按钮（瞬时）。 */
    @LuaFunction(mainThread = true)
    public final void press() {
        be.pressModule(id);
    }

    /** 弹起按钮（瞬时）。 */
    @LuaFunction(mainThread = true)
    public final void release() {
        be.releaseModule(id);
    }

    /** 当前是否处于按下状态。 */
    @LuaFunction
    public final boolean isPressed() {
        return be.getGridState().isPressed(id);
    }

    /** 玩家累计点击次数（每次玩家按下 +1；Lua 的 press() 不计数）。 */
    @LuaFunction
    public final int getClickCount() {
        return be.getGridState().getClickCount(id);
    }

    /**
     * 自上次读取以来，按钮是否被玩家点击过（读取后清除标志，适合边沿检测）。
     *
     * <pre>{@code
     * while true do
     *   if btn.wasClicked() then print("玩家点击了按钮") end
     *   os.sleep(0.05)
     * end
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final boolean wasClicked() {
        return be.getGridState().consumeClick(id);
    }

    /** 清除"未读点击"标志（不读取）。 */
    @LuaFunction(mainThread = true)
    public final void clearClicked() {
        be.getGridState().clearClick(id);
    }

    // ── 玩家互动锁 ──

    /**
     * 设置玩家互动开关。
     * <p>
     * enabled=false 时按钮由 Lua 完全控制：玩家点击不会改变按下状态，
     * 但仍会更新 {@link #wasClicked()} / {@link #getClickCount()}，便于脚本自定义按钮行为。
     *
     * @param enabled true = 允许玩家互动（默认），false = 仅 Lua 控制
     */
    @LuaFunction(mainThread = true)
    public final void setPlayerControl(boolean enabled) {
        be.setButtonPlayerControl(id, enabled);
    }

    /** 当前是否允许玩家互动（默认 true）。 */
    @LuaFunction
    public final boolean getPlayerControl() {
        return !be.getGridState().isPlayerLocked(id);
    }

    // ── 灯带控制 ──

    /**
     * 设置灯带亮度（0..1），并自动切换到"代码控制"模式（玩家互动不再改变灯带）。
     *
     * @param level 亮度 0..1（0 = 熄灭，1 = 最亮）
     */
    @LuaFunction(mainThread = true)
    public final void setLight(double level) {
        be.setButtonLight(id, (float) level);
    }

    /** 读取灯带亮度（0..1，代码控制模式下由 {@link #setLight(double)} 设定）。 */
    @LuaFunction
    public final double getLight() {
        return be.getGridState().getLightBrightness(id);
    }

    /**
     * 设置灯带是否由代码控制。
     * <p>
     * true = 灯带亮度只随 {@link #setLight(double)} 改变；
     * false = 自动模式，灯带随按下状态点亮（默认）。
     */
    @LuaFunction(mainThread = true)
    public final void setLightControl(boolean codeControlled) {
        be.setButtonLightControl(id, codeControlled);
    }

    /** 灯带当前是否由代码控制。 */
    @LuaFunction
    public final boolean isLightControlled() {
        return be.getGridState().isLightCodeControlled(id);
    }

    // ── 表面标签 ──

    /**
     * 在按钮表面写文字（参考旋钮角度文字的渲染方式，默认居中、字号与旋钮角度一致、白色）。
     * <p>
     * 传入空串 {@code ""} 可清除显示，但会保留之前设置的位置/字号/颜色，
     * 下次写入文字时继续沿用。
     *
     * <pre>{@code
     * btn.setLabel("START")
     * btn.setLabelPosition(0.2, 0.1)   -- 相对原点：右移 0.2px、上移 0.1px
     * btn.setLabelScale(1 / 256)       -- 字号放大为旋钮角度的 2 倍
     * btn.setLabelColour(0xFF0000)     -- 红色
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setLabel(String text) {
        be.setButtonLabelText(id, text);
    }

    /** 读取按钮表面文字（未设置时返回空串）。 */
    @LuaFunction
    public final String getLabel() {
        return be.getGridState().getButtonLabel(id).text();
    }

    /**
     * 设置标签相对标签原点的位置偏移。
     * <p>
     * 单位：MC 像素（1px = 1/16 块）；{@code x} 向右为正、{@code y} 向上为正，
     * {@code (0, 0)} 表示标签原点（按钮表面视觉中心，默认）。
     */
    @LuaFunction(mainThread = true)
    public final void setLabelPosition(double x, double y) {
        be.setButtonLabelPosition(id, x, y);
    }

    /** 读取标签位置偏移，返回 {@code x, y}（MC 像素）。 */
    @LuaFunction
    public final MethodResult getLabelPosition() {
        var label = be.getGridState().getButtonLabel(id);
        return MethodResult.of(label.x(), label.y());
    }

    /**
     * 设置标签字号（块/字体像素）。
     * <p>
     * 默认 {@code 1/512}（与旋钮角度显示完全一致）；值越大字越大，例如 {@code 1/256} 为两倍大。
     */
    @LuaFunction(mainThread = true)
    public final void setLabelScale(double scale) {
        be.setButtonLabelScale(id, scale);
    }

    /** 读取标签字号（块/字体像素，默认 1/512）。 */
    @LuaFunction
    public final double getLabelScale() {
        return be.getGridState().getButtonLabel(id).scale();
    }

    /**
     * 设置标签颜色（0xRRGGBB，默认白色 0xFFFFFF）。
     *
     * <pre>{@code
     * btn.setLabelColour(0xFF0000)  -- 红色
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setLabelColour(int colour) {
        be.setButtonLabelColor(id, colour);
    }

    /** 读取标签颜色（0xRRGGBB，默认 0xFFFFFF）。 */
    @LuaFunction
    public final int getLabelColour() {
        return be.getGridState().getButtonLabel(id).color();
    }

    /**
     * 设置标签是否绘制投影（drawInBatch 的 dropShadow 参数）。
     * <p>
     * 默认开启（true，与旋钮角度文字一致）；关闭（false）可去掉文字下方的阴影。
     *
     * <pre>{@code
     * btn.setDropShadow(false)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setDropShadow(boolean dropShadow) {
        be.setButtonLabelDropShadow(id, dropShadow);
    }

    /** 标签当前是否绘制投影（默认 true）。 */
    @LuaFunction
    public final boolean getDropShadow() {
        return be.getGridState().getButtonLabel(id).dropShadow();
    }
}
