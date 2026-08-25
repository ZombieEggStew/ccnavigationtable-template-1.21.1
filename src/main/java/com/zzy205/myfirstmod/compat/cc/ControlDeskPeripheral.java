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
 * 用法（对齐 Monitor 的「外设 → 模块实例」模式）：先 {@link #getModule(String)} 拿到对应控件的
 * 模块实例，再在实例上调用状态读取方法（全部 {@code mainThread=false}，Lua 侧高频轮询不占主线程）：
 * <pre>{@code
 * local d = pe.getPeripheral(0)
 * local pedal = d.getModule("pedal")        -- 未安装返回 nil
 * print(pedal.isLeftPedalDown())
 * local joy = d.getModule("joystick")
 * print(joy.getJoystickXSigned())
 * }</pre>
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

    /**
     * 获取指定控件的模块实例（Lua API 在此实例上调用）：
     * <ul>
     *   <li>{@code "pedal"} → {@link PedalModuleHandle}（左右踏板踩下判断）</li>
     *   <li>{@code "joystick"} → {@link JoystickModuleHandle}（操纵杆原始值/轴值/带符号）</li>
     * </ul>
     * 未安装对应控件或名称未知返回 nil。
     *
     * <pre>{@code
     * local pedal = desk.getModule("pedal")
     * if pedal then print(pedal.isLeftPedalDown()) end
     * }</pre>
     *
     * @param name 控件名（"pedal" / "joystick"，大小写不敏感）
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Object getModule(String name) {
        if (name == null) return null;
        return switch (name.toLowerCase()) {
            case "pedal" -> be.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)
                    ? new PedalModuleHandle(be) : null;
            case "joystick" -> be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)
                    ? new JoystickModuleHandle(be) : null;
            default -> null;
        };
    }
}
