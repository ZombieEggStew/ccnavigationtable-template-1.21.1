package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.SequenceContext;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import com.simibubi.create.foundation.utility.CreateLang;
import com.zzy205.myfirstmod.Config;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * CC:T 可控变速器 / 舵机。仅接受 Lua 控制，不接受红石信号。
 * <p>
 * 通过 {@code peripheral.wrap("right")} 使用：
 * <pre>{@code
 * -- 变速器模式
 * local t = peripheral.wrap("right")
 * t.setRatio(0.5)   -- 下游速度 = 上游 × 50%
 * t.setTargetSpeed(128) -- 或直接指定下游转速
 *
 * -- 舵机模式（服务器权威角度定位，类 TiltAdapter 段式精确同步）
 * t.setServoMode(true)
 * t.setServoSpeed(0)      -- 0 = 默认（运动速度 = 输入转速），>0 指定输出转速（上限 96 RPM）
 * t.setServoAngle(90)     -- 输出轴绝对定位到 +90°（±180° 单圈，走最短路径）
 * print(t.getServoAngle()) -- 服务器权威当前实际角度
 * t.resetServo()          -- 归位：当前位置重新定义为 0°，不旋转
 * t.setServoMode(false)   -- 回到变速器模式
 * }</pre>
 * 舵机模式下 {@code setRatio} / {@code setTargetSpeed} 会被拒绝（返回 false）；
 * 变速器模式下调用 {@code setServoAngle} 会自动切入舵机模式。
 * 运动速度 = 输入转速（无输入动力时舵机不动），输出端转速可被 {@code setServoSpeed} 覆盖甚至加速。
 * <p>
 * 运动层为 TiltAdapter 段式状态机：段开始锁定终点、flicker 门控延迟 attach、
 * 单段 ≤179°、到位后 settle 2 tick 再 detach，规避 Create flicker 惩罚与 180° 插值二义性。
 * 段内同向改目标会实时延长/缩短当前段终点（re-aim，零打断），反向仍等段结束。
 */
public class TransmissionPeripheralBlockEntity extends SplitShaftBlockEntity {

    /** 舵机输出角度范围（单圈绝对定位） */
    public static final float MAX_SERVO_ANGLE = 180f;
    /** 舵机输出转速上限（RPM） */
    public static final float MAX_SERVO_RPM = 96f;
    /** 变速器模式目标转速上限（RPM） */
    public static final float MAX_TRANSMISSION_RPM = 256f;

    /** 到位后保留 modifier 的 tick 数，让下游钳到端点（Create sequenced gearshift 语义） */
    private static final int SEGMENT_SETTLE_TICKS = 2;
    /** 重新 attach 前允许的最大 flicker 分数（Create 惩罚上限 128，留足余量） */
    private static final int FLICKER_THRESHOLD = 60;
    /** 每次段开始（attach）计入的 flicker tally（同 FlickerAwareTicker） */
    private static final int FLICKER_COST = 10;

    private static final float VALUE_EPSILON = 1e-4f;

    /** 当前变速比 0.0 ~ 1.0，默认全速 */
    private double ratio = 1.0;

    /** 目标转速模式（true 时 ratio 由 targetSpeed / sourceSpeed 自动计算） */
    private boolean useTargetMode = false;

    /** 目标下游转速 0.0 ~ 256.0 */
    private double targetSpeed = 256.0;

    // ── 舵机模式状态（服务端权威，客户端经 NBT 每 tick 同步）──

    /** 舵机模式开关（与变速器模式互斥） */
    private boolean servoMode = false;
    /** 段式角度状态机（requested / active / current 分离） */
    private ServoMotionState motion = new ServoMotionState();
    /** Lua 指定输出转速（RPM）；0 = 默认（输出转速 = 输入转速） */
    private float servoRpm = 0f;
    /** 段内同向重瞄开关（默认关）。开时高频变化输入下目标在 ±180 边界来回摆动可能
     *  错误定位；关时回退到「等段结束」语义，定位稳定 */
    private boolean servoReaimEnabled = false;
    /** 段开始已排队，等待 flicker 分数降到阈值 */
    private boolean segmentStartQueued = false;
    /** flicker 门控自计数（同 FlickerAwareTicker.internalTally） */
    private int flickerTally = 0;
    /** 段到位后的 settle 剩余 tick */
    private int segmentSettleTicks = 0;

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

        // ═══════════════ Lua API：变速器模式 ═══════════════

        @LuaFunction
        public final double getRatio() {
            return ratio;
        }

        /** 设置变速比；舵机模式下拒绝并返回 false */
        @LuaFunction(mainThread = true)
        public final boolean setRatio(double r) {
            if (servoMode) return false;
            double newRatio = Math.max(r, 0.0);  // 允许 >1.0 加速
            if (!useTargetMode && Math.abs(ratio - newRatio) < VALUE_EPSILON) return true;

            useTargetMode = false;
            detachKinetics();
            ratio = newRatio;
            attachKinetics();
            setChanged();
            sendData();
            return true;
        }

        @LuaFunction
        public final double getTargetSpeed() {
            return targetSpeed;
        }

        /** 设置目标下游转速；舵机模式下拒绝并返回 false */
        @LuaFunction(mainThread = true)
        public final boolean setTargetSpeed(double speed) {
            if (servoMode) return false;
            double clamped = Mth.clamp(speed, 0.0, MAX_TRANSMISSION_RPM);
            double rounded = Math.round(clamped * 100.0) / 100.0;
            if (useTargetMode && Math.abs(targetSpeed - rounded) < VALUE_EPSILON) return true;

            useTargetMode = true;
            targetSpeed = rounded;
            applySpeed();
            return true;
        }

        // ═══════════════ Lua API：舵机模式 ═══════════════

        @LuaFunction
        public final boolean getServoMode() {
            return servoMode;
        }

        /** 开启/关闭舵机模式；返回是否成功。开启时自动归位到 0°（当前位置重新定义为 0，不旋转） */
        @LuaFunction(mainThread = true)
        public final boolean setServoMode(boolean enabled) {
            if (enabled) {
                resetServo();  // 开启即归位（含已在舵机模式时再次开启）
                return true;
            }
            if (!servoMode) return true;
            servoMode = false;
            segmentStartQueued = false;
            motion.cancelSegment();
            segmentSettleTicks = 0;
            sequenceContext = null;
            detachKinetics();
            attachKinetics();
            setChanged();
            sendData();
            return true;
        }

        /** 服务器权威的当前输出轴角度（±180°） */
        @LuaFunction
        public final double getServoAngle() {
            return ServoMotionState.wrap(motion.currentAngle());
        }

        /** 绝对定位输出轴到指定角度（±180° 单圈，走最短路径）；变速器模式下会自动切入舵机模式 */
        @LuaFunction(mainThread = true)
        public final boolean setServoAngle(double degrees) {
            if (!Float.isFinite((float) degrees)) return false;
            if (!servoMode) enableServoMode();

            float clamped = Mth.clamp((float) degrees, -MAX_SERVO_ANGLE, MAX_SERVO_ANGLE);
            motion.requestTarget(clamped);
            setChanged();
            sendData();
            return true;
        }

        /** Lua 指定的输出转速（RPM）；0 = 默认（运动速度 = 输入转速） */
        @LuaFunction
        public final double getServoSpeed() {
            return servoRpm;
        }

        /** 设置舵机输出转速（0~96 RPM，可大于输入转速以加速）；0 恢复默认 */
        @LuaFunction(mainThread = true)
        public final boolean setServoSpeed(double rpm) {
            if (!Float.isFinite((float) rpm)) return false;
            float clamped = Mth.clamp((float) rpm, 0f, MAX_SERVO_RPM);
            float rounded = Math.round(clamped * 100f) / 100f;
            if (Math.abs(servoRpm - rounded) < VALUE_EPSILON) return true;

            servoRpm = rounded;
            if (servoMode && motion.isActive()) {
                // 转速变化会使已设 SequenceContext 失配：结束当前段，下一段用新速度
                cancelActiveSegment();
                setChanged();
            } else {
                setChanged();
                sendData();
            }
            return true;
        }

        /** 段内同向重瞄当前开关状态（默认关） */
        @LuaFunction
        public final boolean getServoReaim() {
            return servoReaimEnabled;
        }

        /**
         * 开关段内同向重瞄（re-aim）。
         * <p>
         * 开：段中把目标改为同方向的更远/更近位置时，实时延长/缩短当前段终点，
         * 响应快；但高频变化输入下目标在 ±180 边界来回摆动时可能错误定位。
         * 关（默认）：回退到「等当前段结束再响应新目标」的原始段式语义，定位稳定但响应慢。
         */
        @LuaFunction(mainThread = true)
        public final boolean setServoReaim(boolean enabled) {
            if (servoReaimEnabled == enabled) return true;
            servoReaimEnabled = enabled;
            setChanged();
            sendData();
            return true;
        }

        /** 重新归位：把当前输出位置重新定义为 0°，目标也置 0，不产生任何旋转 */
        @LuaFunction(mainThread = true)
        public final boolean resetServo() {
            if (!servoMode) {
                enableServoMode();
                return true;
            }

            // 停止当前运动（若有），确保不再继续旋转
            if (motion.isActive()) {
                cancelActiveSegment();
            }
            // 归位：当前位置即新 0°，目标 0°
            motion = new ServoMotionState();
            segmentStartQueued = false;
            segmentSettleTicks = 0;
            sequenceContext = null;
            detachKinetics();
            setChanged();
            sendData();
            return true;
        }
    }
    // ═══════════════ 舵机模式：模式切换 ═══════════════

    private void enableServoMode() {
        servoMode = true;
        motion = new ServoMotionState();  // 归位到 0
        segmentStartQueued = false;
        segmentSettleTicks = 0;
        // 确保 detached，段开始时再 attach（避免重复 handleAdded）
        detachKinetics();
        setChanged();
        sendData();
    }

    // ═══════════════ 舵机模式：服务端段式状态机 ═══════════════

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        updateStressImpact();
        tickServoServer();
    }

    /**
     * 每 tick 对账网络缓存的应力 impact：变速器目标模式下 impact 随输入转速变化
     * （ratio 模式与舵机模式在段内基本恒定，此处为空操作）。网络缓存值变化时主动刷新。
     */
    private void updateStressImpact() {
        if (!hasNetwork()) return;
        float old = lastStressApplied;
        float impact = calculateStressApplied();  // 同时写入 lastStressApplied
        if (Math.abs(impact - old) > VALUE_EPSILON) {
            getOrCreateNetwork().updateStressFor(this, impact);
        }
    }

    /**
     * TiltAdapter 段式推进：段开始锁定终点，段内线性推进；到位 settle 后先 detach 再清段；
     * 无动力则取消当前段。段开始经 flicker 门控延迟 attach。
     */
    private void tickServoServer() {
        if (!servoMode) return;

        if (flickerTally > 0) flickerTally--;

        if (segmentStartQueued && canAttachForSegment()) {
            segmentStartQueued = false;
            startPendingSegment();
        }

        if (motion.isActive()) {
            float driveSpeed = getServoDriveSpeed();
            if (driveSpeed <= 0f) {
                cancelActiveSegment();
            } else if (segmentSettleTicks > 0) {
                segmentSettleTicks--;
                if (segmentSettleTicks == 0) {
                    finishActiveSegment();
                }
            } else {
                // 段内同向重瞄（可开关）：Lua 在段中把目标改为同方向的更远/更近位置时，
                // 实时延长/缩短本段终点，无需等当前段走完（反向仍等段结束）。
                // 高频变化输入下可 setServoReaim(false) 关闭，回退到「等段结束」语义
                if (servoReaimEnabled) {
                    motion.reaimIfSameDirection();
                }
                if (motion.advance(KineticBlockEntity.convertToAngular(driveSpeed))) {
                    segmentSettleTicks = SEGMENT_SETTLE_TICKS;
                    sendData();
                } else {
                    sendData();
                }
            }
        }

        queueSegmentStartIfNeeded();
    }

    private boolean canAttachForSegment() {
        return flickerTally <= FLICKER_THRESHOLD && getFlickerScore() <= FLICKER_THRESHOLD;
    }

    private void queueSegmentStartIfNeeded() {
        if (segmentStartQueued || motion.isActive() || !motion.needsSegment()
            || getServoDriveSpeed() <= 0f) {
            return;
        }
        if (canAttachForSegment()) {
            startPendingSegment();
        } else {
            segmentStartQueued = true;
        }
    }

    /** 开始一段不可变动力段，朝最新请求目标前进（单段 ≤179°） */
    private void startPendingSegment() {
        float driveSpeed = getServoDriveSpeed();
        if (driveSpeed <= 0f || motion.isActive()) return;
        if (!motion.startSegment(ServoMotionState.MAX_SEGMENT_ANGLE)) return;

        flickerTally += FLICKER_COST;
        sequenceContext = new SequenceContext(SequencerInstructions.TURN_ANGLE,
                (double) (motion.remainingAngle() / driveSpeed));
        attachKinetics();
        setChanged();
        sendData();
    }

    /** 在旧 modifier 仍可见时先 detach，再清段，确保输出子树停在端点 */
    private void finishActiveSegment() {
        detachKinetics();
        motion.finishSegment();
        segmentSettleTicks = 0;
        sequenceContext = null;
        setChanged();
        sendData();
    }

    private void cancelActiveSegment() {
        detachKinetics();
        motion.cancelSegment();
        segmentSettleTicks = 0;
        sequenceContext = null;
        setChanged();
        sendData();
    }

    /**
     * 有效输出转速：无输入动力时为 0（舵机不动）；Lua 指定 servoRpm > 0 时用它（可加速），
     * 否则 = 输入转速；两者统一钳到 {@link #MAX_SERVO_RPM}（96）。
     * 与 {@link #getRotationSpeedModifier} 的缩放一致，保证 BE 推进与下游转速同步。
     */
    private float getServoDriveSpeed() {
        float sourceSpeed = Math.abs(getTheoreticalSpeed());
        if (sourceSpeed < 0.01f) return 0f;
        float speed = servoRpm > 0f ? servoRpm : sourceSpeed;
        return Math.min(speed, MAX_SERVO_RPM);
    }

    /** 客户端显示用：从最近同步的权威角度向目标做有界逼近（无状态、无累积误差） */
    public float getServoDisplayAngle(float partialTicks) {
        if (!servoMode) return 0f;
        float current = ServoMotionState.wrap(motion.currentAngle());
        float target = ServoMotionState.wrap(motion.renderTarget());
        float driveSpeed = servoRpm > 0f ? servoRpm : Math.abs(getSpeed());
        driveSpeed = Math.min(driveSpeed, MAX_SERVO_RPM);
        if (driveSpeed > 0f) {
            float arc = ServoMotionState.shortestArc(current, target);
            float maxStep = KineticBlockEntity.convertToAngular(driveSpeed) * partialTicks;
            current = ServoMotionState.wrap(current + Mth.clamp(arc, -maxStep, maxStep));
        }
        return current;
    }

    /** 是否处于舵机模式 */
    public boolean isServoMode() {
        return servoMode;
    }

    /** 该面是否为舵机输出面（非动力输入面） */
    public boolean isServoOutputFace(Direction face) {
        return hasSource() && face != getSourceFacing();
    }

    // ═══════════════ Create 护目镜 tooltip ═══════════════

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.transmission_peripheral.header")
                        .withStyle(ChatFormatting.WHITE)));

        if (servoMode) {
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.transmission_peripheral.mode")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("tooltip.ccpe.transmission_peripheral.mode_servo")
                            .withStyle(ChatFormatting.GOLD)));
            tooltip.add(angleLine("tooltip.ccpe.transmission_peripheral.current_angle",
                    ServoMotionState.wrap(motion.currentAngle())));
            tooltip.add(angleLine("tooltip.ccpe.transmission_peripheral.target_angle",
                    ServoMotionState.wrap(motion.requestedTarget())));
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.transmission_peripheral.servo_reaim")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable(servoReaimEnabled
                                    ? "tooltip.ccpe.transmission_peripheral.servo_reaim_on"
                                    : "tooltip.ccpe.transmission_peripheral.servo_reaim_off")));
        } else {
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.transmission_peripheral.mode")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("tooltip.ccpe.transmission_peripheral.mode_transmission")
                            .withStyle(ChatFormatting.AQUA)));
            if (useTargetMode) {
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("tooltip.ccpe.transmission_peripheral.target_speed")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format(Locale.ROOT, "%.1f RPM", targetSpeed))
                                .withStyle(ChatFormatting.WHITE)));
            } else {
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("tooltip.ccpe.transmission_peripheral.ratio")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format(Locale.ROOT, "%.1f%%", ratio * 100.0))
                                .withStyle(ChatFormatting.WHITE)));
            }
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.transmission_peripheral.output_speed")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(Locale.ROOT, "%.1f RPM", getOutputSpeed()))
                            .withStyle(ChatFormatting.AQUA)));
        }

        tooltip.add(Component.empty());
        addStressImpactStats(tooltip, calculateStressApplied());
        return true;
    }

    @Override
    protected void addStressImpactStats(List<Component> tooltip, float stressAtBase) {
        CreateLang.translate("gui.goggles.kinetic_stats")
            .forGoggles(tooltip);
        
        CreateLang.translate("tooltip.stressImpact")
            .style(ChatFormatting.GRAY)
            .forGoggles(tooltip);

        float stressTotal = stressAtBase * Math.abs(getTheoreticalSpeed());

        CreateLang.number(stressTotal)
            .translate("generic.unit.stress")
            .style(ChatFormatting.AQUA)
            .space()
            .add(CreateLang.translate("gui.goggles.at_current_speed")
                .style(ChatFormatting.DARK_GRAY))
            .forGoggles(tooltip, 1);
    }

    private Component angleLine(String key, float angle) {
        return Component.literal("     ")
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°", angle))
                        .withStyle(ChatFormatting.AQUA));
    }

    /** 变速器模式下游输出转速（RPM，绝对值） */
    private float getOutputSpeed() {
        if (!hasSource()) return 0f;
        return Math.abs(getSpeed() * getRotationSpeedModifier(getSourceFacing().getOpposite()));
    }

    // ═══════════════ 速度修改 ═══════════════

    @Override
    public float getRotationSpeedModifier(Direction face) {
        if (!hasSource() || face == getSourceFacing()) return 1f;
        float sourceSpeed = Math.abs(getTheoreticalSpeed());
        if (sourceSpeed < 0.01f) return 0f;

        if (servoMode) {
            if (!motion.isActive()) return 0f;
            float outputSpeed = servoRpm > 0f ? servoRpm : sourceSpeed;
            return motion.activeDirection() * Math.min(outputSpeed / sourceSpeed, MAX_SERVO_RPM / sourceSpeed);
        }

        if (useTargetMode) {
            return (float) (targetSpeed / sourceSpeed);
        }
        // 比率模式：限制实际输出 ≤ 256
        float maxRatio = MAX_TRANSMISSION_RPM / sourceSpeed;
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

    // ═══════════════ 应力 ═══════════════

    /**
     * 应力影响（Create 网络按 {@code impact × |输入转速|} 计费，tooltip 同公式）：
     * <ul>
     *   <li>变速器模式：实际应力 = Config.servoStressImpact × |输出转速 − 输入转速|
     *       （ratio = 1 时 Δ = 0 不耗应力），impact 反算为 {@code 系数 × Δ / |输入转速|}；</li>
     *   <li>舵机模式：实际应力 = Config.servoModeStressImpact × |真实输出转速|
     *       （输出可被 setServoSpeed 覆盖/加速；静止时输出为 0 不耗应力）。</li>
     * </ul>
     */
    @Override
    public float calculateStressApplied() {
        float impact;
        if (servoMode) {
            float sourceSpeed = Math.abs(getTheoreticalSpeed());
            if (sourceSpeed < 0.01f || !motion.isActive()) {
                impact = 0f;
            } else {
                float realOutputSpeed = Math.abs(getRotationSpeedModifier(getSourceFacing().getOpposite()) * sourceSpeed);
                impact = Config.SERVO_MODE_STRESS_IMPACT.get().floatValue() * realOutputSpeed / sourceSpeed;
            }
        } else {
            float inputSpeed = Math.abs(getTheoreticalSpeed());
            if (inputSpeed < 0.01f) {
                impact = 0f;
            } else {
                float delta = Math.abs(getOutputSpeed() - inputSpeed);
                impact = Config.SERVO_STRESS_IMPACT.get().floatValue() * delta / inputSpeed;
            }
        }
        this.lastStressApplied = impact;
        return impact;
    }

    // ═══════════════ 下游序列上下文 ═══════════════

    /** 让客户端与下游组件获得 SequenceContext（旋转多少度的合同） */
    @Override
    protected boolean syncSequenceContext() {
        return true;
    }

    // ═══════════════ NBT 持久化 ═══════════════

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putDouble("Ratio", ratio);
        tag.putBoolean("UseTargetMode", useTargetMode);
        tag.putDouble("TargetSpeed", targetSpeed);
        tag.putBoolean("ServoMode", servoMode);
        tag.putFloat("ServoRpm", servoRpm);
        tag.putBoolean("ServoReaim", servoReaimEnabled);
        tag.putFloat("ServoRequestedTarget", motion.requestedTarget());
        tag.putFloat("ServoActiveTarget", motion.activeTarget());
        tag.putFloat("ServoCurrentAngle", motion.currentAngle());
        tag.putFloat("ServoRemainingAngle", motion.remainingAngle());
        tag.putInt("ServoActiveDirection", motion.activeDirection());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        ratio = tag.getDouble("Ratio");
        useTargetMode = tag.getBoolean("UseTargetMode");
        targetSpeed = tag.getDouble("TargetSpeed");
        servoMode = tag.getBoolean("ServoMode");
        servoRpm = tag.getFloat("ServoRpm");
        // 旧存档无该键时保持默认关闭，避免升级后行为突变
        servoReaimEnabled = tag.contains("ServoReaim") ? tag.getBoolean("ServoReaim") : false;
        motion.restore(
                tag.getFloat("ServoRequestedTarget"),
                tag.getFloat("ServoActiveTarget"),
                tag.getFloat("ServoCurrentAngle"),
                tag.getFloat("ServoRemainingAngle"),
                tag.getInt("ServoActiveDirection")
        );
    }
}
