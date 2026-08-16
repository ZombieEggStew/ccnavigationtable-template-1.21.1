package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 按钮模块（button_1）的 Lua 模块实例。
 * <p>
 * 按钮为瞬时型：{@link #press()} 按下、{@link #release()} 弹起，{@link #isPressed()} 读取当前按下状态。
 */
public final class ButtonModuleHandle extends ModuleHandle {

    public ButtonModuleHandle(MonitorBlockEntity be, MonitorModule module) {
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
}
