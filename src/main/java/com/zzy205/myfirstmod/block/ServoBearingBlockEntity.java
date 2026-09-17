package com.zzy205.myfirstmod.block;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Lua 舵机轴承（ccpe:servo_bearing）方块实体。
 * <p>
 * 旋转逻辑<b>照抄</b> {@code create:mechanical_bearing}（{@code MechanicalBearingBlockEntity}）：
 * 每 tick {@code angle += angularSpeed}、{@code angle %= 360}，然后 {@link #applyRotation()} 把角度
 * 直接设定给 {@link ControlledContraptionEntity}（运动学刚性，指哪打哪）。动力源不是应力网络，
 * 而是 CC:Tweaked Lua 外设的目标角（照抄 mechanical_bearing 的 Sequencer TURN_ANGLE 钳制：
 * 每 tick 至多转 {@link #MAX_ANGULAR_SPEED} 度、剩余度数递减、到位即停）。
 * <p>
 * <b>客户端视觉效果修复</b>（与 mechanical_bearing 的关键差异）：
 * 原版用 {@code clientAngleDiff} 指数追赶服务端角度（read 回退 + 每 tick 差值减半），
 * 导致末端最后几度视觉爬行、转速越高越明显。本实现去掉整条追赶链：
 * <ul>
 *   <li>{@link #read} 直接用服务端同步的 {@code angle}（不回退、不追赶）；</li>
 *   <li>客户端 {@link #tick} 不再推进角度，只 {@link #applyRotation()}；</li>
 *   <li>{@code lazyTickRate=1}（每 tick 同步一次角度，服务端权威 → 客户端滞后 ≤ 1 tick）；</li>
 *   <li>{@link #getInterpolatedAngle} 改为插值式 {@code angleLerp(prevAngle, angle, pt)}，
 *       渲染 = 服务端角度轨迹 + 帧间插值 → 匀速平滑、末端 50ms 内到位、无爬行。</li>
 * </ul>
 * 无红石、无应力网络（{@code calculateStressApplied} 沿用默认 0）。
 * <p>
 * 参考来源：{@code references/Create-mc1.21.1-dev/.../bearing/MechanicalBearingBlockEntity.java}。
 */
public class ServoBearingBlockEntity extends KineticBlockEntity
        implements IControlContraption, IDisplayAssemblyExceptions {

    /**
     * 舵机最大角速度（度/游戏 tick）。照 mechanical_bearing 的 Sequencer 钳制语义：
     * 目标角推进时每 tick 至多转这么多度，最后一步补足剩余、精确到位。
     * 当前值 18°/tick = 360°/s。
     */
    public static final float MAX_ANGULAR_SPEED = 18f;

    /** 是否应于下一 tick 装配/拆卸（空手右键触发） */
    public boolean assembleNextTick;
    protected AssemblyException lastException;
    /** 被驱动的 contraption 实体 */
    @Nullable
    protected ControlledContraptionEntity movedContraption;
    /** 当前角度（服务端权威；客户端为最近一次同步值） */
    protected float angle;
    /** 上一 tick 的角度（渲染插值用） */
    private float prevAngle;
    /** 是否已装配（有 contraption） */
    protected boolean running;
    /**
     * Lua 目标角模式：剩余可转角度（带符号，度）。{@code setTargetAngle} 时设为最短路径差值，
     * 每 tick 按 {@link #MAX_ANGULAR_SPEED} 推进并递减，到 0 即精确到位。0 = 无目标（静止）。
     */
    private double targetAngleLimit = 0;
    /** CC:T 外设实例（懒加载） */
    @Nullable
    private IPeripheral peripheral;

    public ServoBearingBlockEntity(final BlockPos pos, final BlockState state) {
        super(MyModBlockEntities.servo_bearing_entity.get(), pos, state);
        setLazyTickRate(1);
    }

    @Override
    public void tick() {
        super.tick();
        prevAngle = angle;

        if (level.isClientSide) {
            // 客户端修复：不推进角度，直接应用服务端同步值（read 已赋值），实体角度由 applyRotation 同步
            applyRotation();
            return;
        }

        // 装配/拆卸（无动力、无 movementMode：空手右键交替）
        if (assembleNextTick) {
            assembleNextTick = false;
            if (running) {
                disassemble();
            } else {
                assemble();
            }
            return;
        }

        if (!running)
            return;

        // 目标角推进（照抄 mechanical_bearing TURN_ANGLE 钳制；动力源 = Lua 目标角）
        if (targetAngleLimit != 0) {
            final float step = Mth.clamp(MAX_ANGULAR_SPEED, 0f, (float) Math.abs(targetAngleLimit))
                    * (float) Math.signum(targetAngleLimit);
            targetAngleLimit -= step;
            angle = (angle + step) % 360;
        }

        applyRotation();
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        // lazyTickRate=1：每 tick 同步一次角度（服务端权威 → 客户端滞后 ≤ 1 tick）
        if (movedContraption != null && !level.isClientSide)
            sendData();
    }

    /**
     * 渲染用插值角度（顶部转盘 / contraption 用）。
     * <p>
     * <b>优先委托给 contraption 实体</b>：{@code movedContraption.getAngle(partialTicks)}
     * 与结构的渲染角度完全同源（实体的 prevAngle/angle 由实体 tick 同步维护，插值连续、
     * 视觉流畅），避免顶部转盘用 BE 自己维护的 prevAngle/angle（BE.tick 与 read 网络更新
     * 时序不同步 → 偶发卡帧、与结构角度略微不一致）。
     * <p>
     * 未装配（无实体）时回退到自身插值（{@code prevAngle → angle}）。
     */
    public float getInterpolatedAngle(final float partialTicks) {
        if (movedContraption != null && !movedContraption.isRemoved()) {
            return movedContraption.getAngle(partialTicks);
        }
        return AngleHelper.angleLerp(partialTicks, prevAngle, angle);
    }

    // ═══════════════ 装配 / 拆卸（照抄 mechanical_bearing） ═══════════════

    public void assemble() {
        if (!(level.getBlockState(worldPosition).getBlock() instanceof ServoBearingBlock))
            return;

        final Direction direction = getBlockState().getValue(ServoBearingBlock.FACING);
        final BearingContraption contraption = new BearingContraption(false, direction);
        try {
            if (!contraption.assemble(level, worldPosition))
                return;
            lastException = null;
        } catch (AssemblyException e) {
            lastException = e;
            sendData();
            return;
        }

        contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
        movedContraption = ControlledContraptionEntity.create(level, this, contraption);
        final BlockPos anchor = worldPosition.relative(direction);
        movedContraption.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
        movedContraption.setRotationAxis(direction.getAxis());
        level.addFreshEntity(movedContraption);

        AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(level, worldPosition);

        running = true;
        angle = 0;
        sendData();
    }

    public void disassemble() {
        if (!running && movedContraption == null)
            return;
        angle = 0;
        targetAngleLimit = 0;
        if (movedContraption != null) {
            movedContraption.disassemble();
            AllSoundEvents.CONTRAPTION_DISASSEMBLE.playOnServer(level, worldPosition);
        }

        movedContraption = null;
        running = false;
        assembleNextTick = false;
        sendData();
    }

    protected void applyRotation() {
        if (movedContraption == null)
            return;
        movedContraption.setAngle(angle);
        final BlockState blockState = getBlockState();
        if (blockState.hasProperty(BlockStateProperties.FACING))
            movedContraption.setRotationAxis(blockState.getValue(BlockStateProperties.FACING).getAxis());
    }

    // ═══════════════ IControlContraption（照抄 mechanical_bearing） ═══════════════

    @Override
    public void attach(final ControlledContraptionEntity contraption) {
        final BlockState blockState = getBlockState();
        if (!(contraption.getContraption() instanceof BearingContraption))
            return;
        if (!blockState.hasProperty(ServoBearingBlock.FACING))
            return;

        this.movedContraption = contraption;
        setChanged();
        final BlockPos anchor = worldPosition.relative(blockState.getValue(ServoBearingBlock.FACING));
        movedContraption.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
        if (!level.isClientSide) {
            this.running = true;
            sendData();
        }
    }

    @Override
    public void onStall() {
        if (!level.isClientSide)
            sendData();
    }

    @Override
    public boolean isValid() {
        return !isRemoved();
    }

    @Override
    public boolean isAttachedTo(final AbstractContraptionEntity contraption) {
        return movedContraption == contraption;
    }

    @Override
    public BlockPos getBlockPosition() {
        return worldPosition;
    }

    // ═══════════════ NBT ═══════════════

    @Override
    public void write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("Running", running);
        compound.putFloat("Angle", angle);
        if (targetAngleLimit != 0)
            compound.putDouble("TargetAngleLimit", targetAngleLimit);
        AssemblyException.write(compound, registries, lastException);
    }

    @Override
    protected void read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        running = compound.getBoolean("Running");
        // 客户端修复：直接用服务端同步的 angle（不回退、不追赶）
        angle = compound.getFloat("Angle");
        targetAngleLimit = compound.contains("TargetAngleLimit") ? compound.getDouble("TargetAngleLimit") : 0;
        lastException = AssemblyException.read(compound, registries);
    }

    @Override
    public void remove() {
        if (!level.isClientSide)
            disassemble();
        super.remove();
    }

    // ═══════════════ 内部工具 ═══════════════

    public boolean isRunning() {
        return running;
    }

    /** 当前实际角度（度，0..360，服务端权威） */
    public float getAngle() {
        return angle;
    }

    /** 目标角度（度，0..360）= 当前角度 + 剩余目标；无目标时 = 当前角度 */
    public float getTargetAngle() {
        return normalizeDegrees((float) (angle + targetAngleLimit));
    }

    private static float normalizeDegrees(final float deg) {
        final float n = deg % 360f;
        return n < 0 ? n + 360f : n;
    }

    public double getTargetAngleLimit() {
        return targetAngleLimit;
    }

    // ═══════════════ CC:T 外设（Lua 控制） ═══════════════

    /**
     * 获取此外设的 CC:T IPeripheral 实例（懒加载）。注册见 {@code compat/cc/CCPeripheralCapabilities.java}。
     */
    public IPeripheral getPeripheral() {
        if (peripheral == null) {
            peripheral = new Peripheral();
        }
        return peripheral;
    }

    private class Peripheral implements IPeripheral {
        @Override
        public String getType() {
            return "servo_bearing";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            if (this == other) return true;
            if (other instanceof ServoBearingBlockEntity.Peripheral that) {
                return ServoBearingBlockEntity.this.worldPosition
                        .equals(that.outer().worldPosition);
            }
            return false;
        }

        private ServoBearingBlockEntity outer() {
            return ServoBearingBlockEntity.this;
        }

        /** 是否已装配（有 contraption） */
        @LuaFunction
        public final boolean isAssembled() {
            return running;
        }

        /** 装配：把 FACING 方向的结构组装成 contraption；返回是否成功（已装配则直接返回 true） */
        @LuaFunction(mainThread = true)
        public final boolean assemble() {
            if (running) return true;
            ServoBearingBlockEntity.this.assemble();
            return running;
        }

        /** 拆卸：把 contraption 拆回世界方块；返回是否成功（未装配则直接返回 true） */
        @LuaFunction(mainThread = true)
        public final boolean disassemble() {
            if (!running) return true;
            ServoBearingBlockEntity.this.disassemble();
            return !running;
        }

        /**
         * 绝对定位到指定角度（度）。走最短路径，以 {@link #MAX_ANGULAR_SPEED} 恒速转动、精确到位。
         * 需要先装配；未装配返回 false。
         */
        @LuaFunction(mainThread = true)
        public final boolean setTargetAngle(double degrees) {
            if (!Double.isFinite(degrees)) return false;
            if (!running) return false;

            targetAngleLimit = AngleHelper.getShortestAngleDiff(angle, (float) degrees);
            setChanged();
            sendData();
            return true;
        }

        /** 目标角度（度，0..360） */
        @LuaFunction
        public final double getTargetAngle() {
            return ServoBearingBlockEntity.this.getTargetAngle();
        }

        /** 当前实际角度（度，0..360） */
        @LuaFunction
        public final double getAngle() {
            return ServoBearingBlockEntity.this.getAngle();
        }
    }

    // ═══════════════ Create 护目镜 tooltip ═══════════════

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.servo_bearing.header")
                        .withStyle(ChatFormatting.WHITE)));
        tooltip.add(angleLine("tooltip.ccpe.servo_bearing.current_angle", this.getAngle()));
        tooltip.add(angleLine("tooltip.ccpe.servo_bearing.target_angle", this.getTargetAngle()));
        return true;
    }

    private Component angleLine(final String key, final float angle) {
        return Component.literal("     ")
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°", angle))
                        .withStyle(ChatFormatting.AQUA));
    }

    @Override
    public AssemblyException getLastAssemblyException() {
        return lastException;
    }
}
