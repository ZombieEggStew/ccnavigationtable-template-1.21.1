package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.TrailingWheelMountBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.ryanhcode.offroad.content.components.TireLike;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 从动轮悬架（{@code trailing_wheel_mount}）的 CC:Tweaked 外设——Lua 转向控制。
 * <p>
 * 与 offroad 原版「红石差速转向」不同，本 mod 的转向完全由 Lua 驱动：
 * {@code setSteering} 写入服务端权威的 {@link TrailingWheelMountBlockEntity#setSteeringSignal}，
 * 由 {@code computeYaw}（信号 ±15 → 约 ±30°）转成 yaw，旋转物理施力方向 / 滚动方向，并同步客户端渲染轮组偏转。
 * 不持久化：转向值只存内存，区块卸载 / 服务器重启后归零（直线）。
 * <p>
 * 参考：{@code references/CreateAvionics-main/.../offroad/peripherals/WheelMountPeripheral.java}
 * （其 mixin 方案针对 offroad 原生 BE；本类是自有 BE，直接访问字段，无需 mixin）。
 * 支持 {@code peripheral.wrap} / {@code peripheral.find("trailing_wheel_mount")}。
 */
public class TrailingWheelMountPeripheral implements IPeripheral {

    private final TrailingWheelMountBlockEntity be;

    public TrailingWheelMountPeripheral(TrailingWheelMountBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "trailing_wheel_mount";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof TrailingWheelMountPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    // ════════════════════ 转向控制 ════════════════════

    /**
     * 设置转向输入。正值向左、负值向右（约定照 offroad：信号为正 → 负 yaw），
     * 最大转向约 ±30°。多轮车同值并行转向；若要真实 Ackermann 几何，逐轮写入不同值。
     *
     * <pre>{@code
     * local wm = peripheral.find("trailing_wheel_mount")
     * wm.setSteering(1.0)   -- 满左
     * wm.setSteering(0.0)   -- 回正
     * wm.setSteering(-0.5)  -- 半右
     * }</pre>
     *
     * @param value 转向输入，范围 [-1, 1]，越界自动钳位
     */
    @LuaFunction(mainThread = true)
    public final void setSteering(final double value) {
        final double clamped = Mth.clamp(value, -1.0, 1.0);
        this.be.setSteeringSignal((int) Math.round(clamped * 15.0));
    }

    /**
     * @return 当前转向输入，范围 [-1, 1]（0 = 直线）
     */
    @LuaFunction
    public final double getSteering() {
        return this.be.getSteeringSignal() / 15.0;
    }

    /**
     * @return 当前转向角度（度），最大约 ±30°
     */
    @LuaFunction
    public final double getSteeringAngle() {
        return -(this.be.getSteeringSignal() / 15.0) * 45.0 * (30.0 / 45.0);
    }

    // ════════════════════ 轮子遥测 ════════════════════

    /** @return 是否已安装轮胎 */
    @LuaFunction
    public final boolean hasTire() {
        return this.tire() != null;
    }

    /** @return 已安装轮胎的半径（格）；无轮胎返回 0 */
    @LuaFunction
    public final double getTireRadius() {
        final TireLike tire = this.tire();
        return tire == null ? 0.0 : tire.radius();
    }

    /** @return 实时悬挂行程（格）；越大表示轮子垂得越低，压到底接近 0 */
    @LuaFunction
    public final double getExtension() {
        return this.be.getExtension();
    }

    /** @return 轮子自转角速度（弧度/tick） */
    @LuaFunction
    public final double getAngularVelocity() {
        return this.be.getAngularVelocity();
    }

    /** @return 是否离地悬空（无牵引力） */
    @LuaFunction
    public final boolean isLiftedUp() {
        return this.be.isLiftedUp();
    }

    /** @return 当前轮下地面摩擦系数（1.0 = 正常，冰面等更低） */
    @LuaFunction
    public final double getTouchingFriction() {
        return this.be.getTouchingFriction();
    }

    private @Nullable TireLike tire() {
        final ItemStack stack = this.be.getHeldItem();
        return stack.isEmpty() ? null : stack.get(OffroadDataComponents.TIRE);
    }
}
