package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * CC:T 可控变速器。仅接受 Lua 控制，不接受红石信号。
 * <p>
 * 通过 {@code peripheral.wrap("right")} 使用：
 * <pre>{@code
 * local t = peripheral.wrap("right")
 * t.setRatio(0.5)   -- 下游速度 = 上游 × 50%
 * print(t.getRatio())
 * }</pre>
 */
public class TransmissionPeripheralBlockEntity extends SplitShaftBlockEntity {

    /** 当前变速比 0.0 ~ 1.0，默认全速 */
    private double ratio = 1.0;

    /** 目标转速模式（true 时 ratio 由 targetSpeed / sourceSpeed 自动计算） */
    private boolean useTargetMode = false;

    /** 目标下游转速 0.0 ~ 256.0 */
    private double targetSpeed = 256.0;

    /** CC:T 外设实例（懒加载），不直接在 BE 上实现 IPeripheral 以避免 getType() 与 BlockEntity.getType() 冲突 */
    @Nullable
    private IPeripheral peripheral;

    public TransmissionPeripheralBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.transmission_peripheral_entity.get(), pos, state);
    }

    /** 获取此外设的 CC:T IPeripheral 实例 */
    public IPeripheral getPeripheral() {
        if (peripheral == null) {
            peripheral = new Peripheral();
        }
        return peripheral;
    }

    // ═══════════════ 内嵌外设类 ═══════════════

    private class Peripheral implements IPeripheral {
        @Override
        public String getType() {
            return "ccpe:transmission_peripheral";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            if (this == other) return true;
            if (other instanceof TransmissionPeripheralBlockEntity.Peripheral that) {
                return TransmissionPeripheralBlockEntity.this.worldPosition
                        .equals(TransmissionPeripheralBlockEntity.this.worldPosition);
            }
            return false;
        }

        // ═══════════════ Lua API ═══════════════

        @LuaFunction
        public final double getRatio() {
            return ratio;
        }

        @LuaFunction(mainThread = true)
        public final void setRatio(double r) {
            double newRatio = Math.max(r, 0.0);  // 允许 >1.0 加速
            if (!useTargetMode && Math.abs(ratio - newRatio) < 1e-4) return;

            useTargetMode = false;
            detachKinetics();
            ratio = newRatio;
            attachKinetics();
            setChanged();
            sendData();
        }

        @LuaFunction
        public final double getTargetSpeed() {
            return targetSpeed;
        }

        @LuaFunction(mainThread = true)
        public final void setTargetSpeed(double speed) {
            double clamped = Mth.clamp(speed, 0.0, 256.0);
            double rounded = Math.round(clamped * 100.0) / 100.0;
            if (useTargetMode && Math.abs(targetSpeed - rounded) < 1e-4) return;

            useTargetMode = true;
            targetSpeed = rounded;
            applySpeed();
        }
    }

    // ═══════════════ 速度修改 ═══════════════

    @Override
    public float getRotationSpeedModifier(Direction face) {
        if (!hasSource() || face == getSourceFacing()) return 1f;
        float sourceSpeed = Math.abs(getTheoreticalSpeed());
        if (sourceSpeed < 0.01f) return 0f;

        if (useTargetMode) {
            return (float) (targetSpeed / sourceSpeed);
        }
        // 比率模式：限制实际输出 ≤ 256
        float maxRatio = 256f / sourceSpeed;
        return (float) Math.min(ratio, maxRatio);
    }

    private void applySpeed() {
        detachKinetics();
        if (useTargetMode) {
            float sourceSpeed = Math.abs(getTheoreticalSpeed());
            if (sourceSpeed > 0.01f) {
                ratio = targetSpeed / sourceSpeed;  // 允许 >1.0 加速
            }
        }
        attachKinetics();
        setChanged();
        sendData();
    }

    // ═══════════════ NBT 持久化 ═══════════════

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putDouble("Ratio", ratio);
        tag.putBoolean("UseTargetMode", useTargetMode);
        tag.putDouble("TargetSpeed", targetSpeed);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        ratio = tag.getDouble("Ratio");
        useTargetMode = tag.getBoolean("UseTargetMode");
        targetSpeed = tag.getDouble("TargetSpeed");
    }
}
