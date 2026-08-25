package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 脚踏板模块实例（经 {@link ControlDeskPeripheral#getModule(String)} 的 {@code "pedal"} 获取）。
 * <p>
 * 直接读 BE 数值层（服务端权威轴值，见 {@link ControlDeskBlockEntity#getPedalLeftAxis}）：
 * 全部 {@code mainThread=false}，Lua 侧高频轮询直接跑在 CC worker 线程，不占游戏主线程。
 */
public class PedalModuleHandle {

    private final ControlDeskBlockEntity be;

    public PedalModuleHandle(ControlDeskBlockEntity be) {
        this.be = be;
    }

    /**
     * 左踏板模拟量（-1..1）：+1 = 完全踩下（+z）/ -1 = 完全抬起（-z）/ 0 = 中间。
     *
     * <pre>{@code
     * local pedal = desk.getModule("pedal")
     * print(pedal.getLeftPedal())  -- -1 .. 1
     * }</pre>
     */
    @LuaFunction
    public final double getLeftPedal() {
        return be.getPedalLeftAxis();
    }

    /**
     * 右踏板模拟量（-1..1）：+1 = 完全踩下（+z）/ -1 = 完全抬起（-z）/ 0 = 中间。
     */
    @LuaFunction
    public final double getRightPedal() {
        return be.getPedalRightAxis();
    }

    /**
     * 左右踏板模拟量差值 = 右 − 左（-2..2）：正 = 右踏板踩得更深 / 负 = 左踏板踩得更深。
     *
     * <pre>{@code
     * print(pedal.getPedalDifference())  -- 右 - 左
     * }</pre>
     */
    @LuaFunction
    public final double getPedalDifference() {
        return be.getPedalRightAxis() - be.getPedalLeftAxis();
    }

    /**
     * 左踏板是否处于踩下方向（轴值 &gt; 0，含回正过程中的余量）；抬起方向返回 false。
     */
    @LuaFunction
    public final boolean isLeftPedalDown() {
        return be.getPedalLeftAxis() > 0f;
    }

    /**
     * 右踏板是否处于踩下方向（轴值 &gt; 0，含回正过程中的余量）；抬起方向返回 false。
     */
    @LuaFunction
    public final boolean isRightPedalDown() {
        return be.getPedalRightAxis() > 0f;
    }

    /**
     * 左踏板是否处于抬起方向（轴值 &lt; 0，含回正过程中的余量）；踩下方向返回 false。
     */
    @LuaFunction
    public final boolean isLeftPedalUp() {
        return be.getPedalLeftAxis() < 0f;
    }

    /**
     * 右踏板是否处于抬起方向（轴值 &lt; 0，含回正过程中的余量）；踩下方向返回 false。
     */
    @LuaFunction
    public final boolean isRightPedalUp() {
        return be.getPedalRightAxis() < 0f;
    }
}
