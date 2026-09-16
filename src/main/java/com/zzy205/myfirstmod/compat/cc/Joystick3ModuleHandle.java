package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 操纵杆3 模块实例（经 {@link ControlDeskPeripheral#getModule(String)} 的 {@code "joystick_3"} 获取；
 * 照抄 {@link JoystickModuleHandle}，读 joystick3 独立轴值/输入租约——原始操纵杆换皮版，逻辑完全一样）。
 * <p>
 * 直接读 BE 数值层（服务端权威轴值/输入租约）：原始值 = 该轴有无按键动作，
 * 轴值 = |轴值| 幅度（0..1），带符号 = 轴值（-1..1，+1 右摆/前推、-1 左摆/后拉）。
 * 全部 {@code mainThread=false}，Lua 侧高频轮询直接跑在 CC worker 线程，不占游戏主线程。
 */
public class Joystick3ModuleHandle {

    private final ControlDeskBlockEntity be;

    public Joystick3ModuleHandle(ControlDeskBlockEntity be) {
        this.be = be;
    }

    /**
     * X 轴（A/D）是否有按键动作：左/右方向键任一按住即 true。
     *
     * <pre>{@code
     * local joy3 = desk.getModule("joystick_3")
     * print(joy3.isAxisXActive(), joy3.getAxisXSigned())
     * }</pre>
     */
    @LuaFunction
    public final boolean isAxisXActive() {
        return be.isJoystick3XActive();
    }

    /**
     * Y 轴（W/S）是否有按键动作：前/后方向键任一按住即 true。
     */
    @LuaFunction
    public final boolean isAxisYActive() {
        return be.isJoystick3YActive();
    }

    /**
     * X 轴正方向（右摆）是否有按键动作（原始值，服务端输入租约）。
     *
     * <pre>{@code
     * local joy3 = desk.getModule("joystick_3")
     * print(joy3.isAxisXPositive(), joy3.isAxisXNegative(),
     *       joy3.isAxisYPositive(), joy3.isAxisYNegative())
     * }</pre>
     */
    @LuaFunction
    public final boolean isAxisXPositive() {
        return be.isJoystick3XPositive();
    }

    /**
     * X 轴负方向（左摆）是否有按键动作（原始值，服务端输入租约）。
     */
    @LuaFunction
    public final boolean isAxisXNegative() {
        return be.isJoystick3XNegative();
    }

    /**
     * Y 轴正方向（前推）是否有按键动作（原始值，服务端输入租约）。
     */
    @LuaFunction
    public final boolean isAxisYPositive() {
        return be.isJoystick3YPositive();
    }

    /**
     * Y 轴负方向（后拉）是否有按键动作（原始值，服务端输入租约）。
     */
    @LuaFunction
    public final boolean isAxisYNegative() {
        return be.isJoystick3YNegative();
    }

    /**
     * X 轴模拟量幅度（0..1，=|轴值|）：+1 = 右摆满偏 / -1 = 左摆满偏，符号见
     * {@link #getAxisXSigned}。
     */
    @LuaFunction
    public final double getAxisX() {
        return Math.abs(be.getJoystick3AxisX());
    }

    /**
     * Y 轴模拟量幅度（0..1，=|轴值|）：+1 = 前推满偏 / -1 = 后拉满偏，符号见
     * {@link #getAxisYSigned}。
     */
    @LuaFunction
    public final double getAxisY() {
        return Math.abs(be.getJoystick3AxisY());
    }

    /**
     * X 轴带符号轴值（-1..1）：+1 = 右摆(D) / -1 = 左摆(A)。
     */
    @LuaFunction
    public final double getAxisXSigned() {
        return be.getJoystick3AxisX();
    }

    /**
     * Y 轴带符号轴值（-1..1）：+1 = 前推(W) / -1 = 后拉(S)。
     */
    @LuaFunction
    public final double getAxisYSigned() {
        return be.getJoystick3AxisY();
    }
}
