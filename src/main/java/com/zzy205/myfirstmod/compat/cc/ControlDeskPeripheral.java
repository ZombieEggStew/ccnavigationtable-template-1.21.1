package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

/**
 * 控制台（controlDesk）的 CC:Tweaked 外设实现。
 * <p>
 * 通过 {@code pe.getPeripheral(ch)}（{@link PeripheralExtenderAPI#getPeripheral} 按全局频道查找）
 * 或 {@code peripheral.wrap(...)}（经 {@link CCPeripheralCapabilities} 能力注册）获取。
 * 与传感器、显示器共享同一全局频道命名空间（频道全局唯一，见 {@link ControlDeskRegistry}）。
 * <p>
 * Lua API 规划（见 memo/control-desk-seat.md「CC 外设」章节），<b>当前留空待实施</b>：
 * 操纵杆原始值 {@code isJoystickXActive()/isJoystickYActive()}（0/1）、轴值
 * {@code getJoystickX()/getJoystickY()}（0..1）、带符号 {@code getJoystickXSigned()/getJoystickYSigned()}（-1..1）；
 * 踏板 {@code isLeftPedalDown()/isRightPedalDown()}（待定）。实现时直接读数值层
 * （{@link ControlDeskBlockEntity#getJoystickAxisX}/{@link ControlDeskBlockEntity#getPedalLeftAxis} 等，服务端权威）。
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
}
