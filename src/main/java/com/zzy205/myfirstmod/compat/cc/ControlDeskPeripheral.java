package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

/**
 * 控制台（controlDesk）的 CC:Tweaked 外设实现。
 * <p>
 * 通过 {@code pe.getPeripheral(ch)}（{@link PeripheralExtenderAPI#getPeripheral} 按全局频道查找）
 * 或 {@code peripheral.wrap(...)}（经 {@link CCPeripheralCapabilities} 能力注册）获取。
 * 与传感器、显示器共享同一全局频道命名空间（频道全局唯一，见 {@link ControlDeskRegistry}）。
 * <p>
 * Lua API（按 memo/control-desk-seat.md 定稿；直接读数值层 = BE 服务端权威状态）：
 * <ul>
 *   <li>操纵杆原始值 {@link #isJoystickXActive}/{@link #isJoystickYActive}（boolean：该轴有无按键动作）</li>
 *   <li>操纵杆轴值 {@link #getJoystickX}/{@link #getJoystickY}（0..1 幅度）+ 带符号
 *       {@link #getJoystickXSigned}/{@link #getJoystickYSigned}（-1..1）</li>
 *   <li>踏板 {@link #isLeftPedalDown}/{@link #isRightPedalDown}（boolean：踏板处于踩下方向，
 *       即轴值 &gt; 0；抬起方向为 false）</li>
 * </ul>
 * <b>注意</b>：CC:Tweaked 把 {@code @LuaFunction} 方法收集成 Lua 表返回；没有任何 Lua 方法的对象
 * 会被判为 unknown type 返回 nil（CobaltLuaMachine#toValue），因此外设至少要有一个 Lua 方法。
 */
public class ControlDeskPeripheral implements IPeripheral {

    private final ControlDeskBlockEntity be;

    public ControlDeskPeripheral(ControlDeskBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccpe:control_desk";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof ControlDeskPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public ControlDeskBlockEntity getBlockEntity() {
        return be;
    }

    // ═══════════════ 操纵杆：原始值（该轴有无按键动作） ═══════════════

    /**
     * X 轴（A/D）是否有按键动作：左/右方向键任一按住即 true。
     *
     * <pre>{@code
     * local d = pe.getPeripheral(0)
     * print(d.isJoystickXActive())  -- false / true
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final boolean isJoystickXActive() {
        return be.isJoystickXActive();
    }

    /**
     * Y 轴（W/S）是否有按键动作：前/后方向键任一按住即 true。
     */
    @LuaFunction(mainThread = true)
    public final boolean isJoystickYActive() {
        return be.isJoystickYActive();
    }

    // ═══════════════ 操纵杆：轴值 ═══════════════

    /**
     * X 轴模拟量幅度（0..1，=|轴值|）：+1 = 右摆满偏 / -1 = 左摆满偏，符号见
     * {@link #getJoystickXSigned}。
     */
    @LuaFunction(mainThread = true)
    public final double getJoystickX() {
        return Math.abs(be.getJoystickAxisX());
    }

    /**
     * Y 轴模拟量幅度（0..1，=|轴值|）：+1 = 前推满偏 / -1 = 后拉满偏，符号见
     * {@link #getJoystickYSigned}。
     */
    @LuaFunction(mainThread = true)
    public final double getJoystickY() {
        return Math.abs(be.getJoystickAxisY());
    }

    /**
     * X 轴带符号轴值（-1..1）：+1 = 右摆(D) / -1 = 左摆(A)。
     */
    @LuaFunction(mainThread = true)
    public final double getJoystickXSigned() {
        return be.getJoystickAxisX();
    }

    /**
     * Y 轴带符号轴值（-1..1）：+1 = 前推(W) / -1 = 后拉(S)。
     */
    @LuaFunction(mainThread = true)
    public final double getJoystickYSigned() {
        return be.getJoystickAxisY();
    }

    // ═══════════════ 踏板 ═══════════════

    /**
     * 左踏板是否处于踩下方向（轴值 &gt; 0，含回正过程中的余量）；抬起方向返回 false。
     */
    @LuaFunction(mainThread = true)
    public final boolean isLeftPedalDown() {
        return be.getPedalLeftAxis() > 0f;
    }

    /**
     * 右踏板是否处于踩下方向（轴值 &gt; 0，含回正过程中的余量）；抬起方向返回 false。
     */
    @LuaFunction(mainThread = true)
    public final boolean isRightPedalDown() {
        return be.getPedalRightAxis() > 0f;
    }
}
