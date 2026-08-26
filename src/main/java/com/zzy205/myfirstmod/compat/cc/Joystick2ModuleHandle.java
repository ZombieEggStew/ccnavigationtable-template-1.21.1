package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 摇杆2 模块实例（经 {@link ControlDeskPeripheral#getModule(String)} 的 {@code "joystick_2"} 获取；
 * 照抄 {@link JoystickModuleHandle}，读 joystick2 独立轴值/输入租约）。
 * <p>
 * 直接读 BE 数值层（服务端权威轴值/输入租约）：原始值 = 该轴有无按键动作，
 * 轴值 = |轴值| 幅度（0..1），带符号 = 轴值（-1..1，+1 右摆/前推、-1 左摆/后拉）。
 * 全部 {@code mainThread=false}，Lua 侧高频轮询直接跑在 CC worker 线程，不占游戏主线程。
 */
public class Joystick2ModuleHandle {

    private final ControlDeskBlockEntity be;

    public Joystick2ModuleHandle(ControlDeskBlockEntity be) {
        this.be = be;
    }

    /**
     * X 轴（A/D）是否有按键动作：左/右方向键任一按住即 true。
     *
     * <pre>{@code
     * local joy2 = desk.getModule("joystick_2")
     * print(joy2.isJoystick2XActive(), joy2.getJoystick2XSigned())
     * }</pre>
     */
    @LuaFunction
    public final boolean isJoystick2XActive() {
        return be.isJoystick2XActive();
    }

    /**
     * Y 轴（W/S）是否有按键动作：前/后方向键任一按住即 true。
     */
    @LuaFunction
    public final boolean isJoystick2YActive() {
        return be.isJoystick2YActive();
    }

    /**
     * X 轴模拟量幅度（0..1，=|轴值|）：+1 = 右摆满偏 / -1 = 左摆满偏，符号见
     * {@link #getJoystick2XSigned}。
     */
    @LuaFunction
    public final double getJoystick2X() {
        return Math.abs(be.getJoystick2AxisX());
    }

    /**
     * Y 轴模拟量幅度（0..1，=|轴值|）：+1 = 前推满偏 / -1 = 后拉满偏，符号见
     * {@link #getJoystick2YSigned}。
     */
    @LuaFunction
    public final double getJoystick2Y() {
        return Math.abs(be.getJoystick2AxisY());
    }

    /**
     * X 轴带符号轴值（-1..1）：+1 = 右摆(D) / -1 = 左摆(A)。
     */
    @LuaFunction
    public final double getJoystick2XSigned() {
        return be.getJoystick2AxisX();
    }

    /**
     * Y 轴带符号轴值（-1..1）：+1 = 前推(W) / -1 = 后拉(S)。
     */
    @LuaFunction
    public final double getJoystick2YSigned() {
        return be.getJoystick2AxisY();
    }
}
