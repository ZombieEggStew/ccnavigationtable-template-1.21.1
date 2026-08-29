package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.config.server.physics.SimPhysics;
import dev.simulated_team.simulated.service.SimConfigService;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.createmod.catnip.math.AngleHelper;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 自研风帆轴承（aero_bearing）方块实体。
 * <p>
 * 阶段 1-2 骨架（对照 {@code SwivelBearingBlockEntity}，去掉 cogwheel / ExtraKinetics）：
 * <ul>
 *   <li>字段：{@code subLevelID}（从动物理体）、{@code platePos}（plate 方块）、{@code handle}（约束句柄）、
 *       {@code targetAngleDegrees}、{@code assembleNextTick}、{@code assembling}、{@code lastException}；</li>
 *   <li>NBT 持久化：{@code SubLevelID} / {@code SwivelPlate} / {@code TargetAngle}（同 swivel 598-665 行）；</li>
 *   <li>空手右键 → {@code assembleNextTick} → {@link #assemble()} / {@link #disassemble()}；
 *       plate 方块在装配时自动放置到 sub-level plot 内（{@code aero_bearing_plate}）；</li>
 *   <li>约束：{@code RotaryConstraintConfiguration}（pos1/pos2 各自 plot 内坐标，normal 归一化）；</li>
 *   <li>驱动（转速 → 目标角 → PD 伺服）留待阶段 4，当前 {@link #updateServoCoefficients()} 简单锁定。</li>
 * </ul>
 * 参考来源：{@code references/Simulated-Project-main/.../swivel_bearing/SwivelBearingBlockEntity.java}、
 * {@code references/sable-main/.../RotaryConstraintConfiguration.java}。
 */
public class MyBearingBlockEntity extends KineticBlockEntity implements IDisplayAssemblyExceptions, BlockEntitySubLevelActor {

    /**
     * 如果轴承应于下一 tick 装配/拆卸
     */
    public boolean assembleNextTick;
    protected AssemblyException lastException;
    /**
     * 上一 tick 的目标角（度）
     */
    private double lastTargetAngleDegrees = 0;
    /**
     * 当前目标角（度）
     */
    private double targetAngleDegrees = 0;
    /**
     * 序列化角度输入（sequenced gearshift 等）的剩余可转角度（度）；-1 = 非序列化（连续旋转）。
     * 由 {@link #onSpeedChanged} 从网络传播来的 {@code sequenceContext} 换算，tick 里按此钳制推进量。
     */
    private double sequencedAngleLimit = -1;
    /**
     * Lua 控制模式（CC:T 外设）：开启后不再按应力网络转速推进目标角
     * （应力网络仅保留应力消耗），旋转角度由 {@code setTargetAngle} 直接控制。
     */
    private boolean controlMode = false;
    /**
     * CC:T 外设实例（懒加载）
     */
    @Nullable
    private IPeripheral peripheral;
    /**
     * 从动物理体（sub-level）的 UUID
     */
    @Nullable
    private UUID subLevelID;
    /**
     * 连接的 {@link MyBearingPlateBlock} 方块位置
     */
    @Nullable
    private BlockPos platePos;
    /**
     * 本轴承与从动物理体之间的旋转约束句柄
     */
    @Nullable
    private RotaryConstraintHandle handle;
    /**
     * 本 BE 正作为装配的一部分被移动/销毁
     */
    private boolean assembling;

    public MyBearingBlockEntity(final BlockPos pos, final BlockState state) {
        super(MyModBlockEntities.aero_bearing_entity.get(), pos, state);
        this.assembleNextTick = false;
    }

    @Override
    public void tick() {
        final Level level = this.getLevel();

        super.tick();

        if (level.isClientSide) {
            return;
        }

        // 装配或拆卸
        if (this.assembleNextTick) {
            if (!this.isAssembled()) {
                this.assemble();
            } else {
                this.disassemble();
            }
        }

        // 检查持久化，确保重载后约束重连（同 swivel 213-216 行）
        if (this.getSubLevelID() != null) {
            this.checkPersistence(this.getSubLevelID());
        }

        // ── 驱动：输入转速 → 目标角推进（同 swivel 218-259 行；动力来自 Create 应力网络 getSpeed()） ──
        this.lastTargetAngleDegrees = this.targetAngleDegrees;
        float angularSpeed = convertToAngular(this.limitSpeed(this.getSpeed()));

        boolean shouldUpdateAngle = this.isAssembled();

        if (this.controlMode) {
            // Lua 控制模式：角度由 setTargetAngle 直接控制，不再按应力转速累计（应力网络仅保留应力消耗）
            shouldUpdateAngle = false;
        } else if (this.sequencedAngleLimit >= 0) {
            // 序列化角度输入（sequenced gearshift 等）：每 tick 最多转剩余角度，转完即停 → 精确到位（同 swivel 224-226 行）
            angularSpeed = (float) Mth.clamp(angularSpeed, -this.sequencedAngleLimit, this.sequencedAngleLimit);
            this.sequencedAngleLimit = Math.max(0, this.sequencedAngleLimit - Math.abs(angularSpeed));
        } else {
            final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(this.level);
            // 非序列化且物理暂停时不推进目标角（同 swivel 228-232 行）
            if (physicsSystem == null || physicsSystem.getPaused()) {
                shouldUpdateAngle = false;
            }
        }

        if (shouldUpdateAngle) {
            // 负方向 FACING（旋转轴指向负轴）时转速取反（同 swivel 236-239 行）
            if (this.getBlockState().getValue(MyBearingBlock.FACING).getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
                angularSpeed *= -1.0f;
            }

            this.targetAngleDegrees += angularSpeed;
            this.targetAngleDegrees %= 360;

            final SubLevel attached = this.getAttachedSubLevel();
            if (attached != null && this.handle != null) {
                final SubLevel containing = this.getContainingSubLevel();

                if (angularSpeed != 0.0) {
                    final PhysicsPipeline pipeline = ((ServerSubLevelContainer) SubLevelContainer.getContainer(this.level)).physicsSystem().getPipeline();

                    if (containing instanceof final ServerSubLevel serverSubLevel) {
                        pipeline.wakeUp(serverSubLevel);
                    }

                    if (attached instanceof final ServerSubLevel serverSubLevel) {
                        pipeline.wakeUp(serverSubLevel);
                    }
                }
            }
        }

        this.assembleNextTick = false;
    }

    // ═══════════════ 装配 / 拆卸 ═══════════════

    public void assemble() {
        final BlockPos pos = this.getBlockPos();
        final BlockPos toAssemble = pos.relative(this.getBlockState().getValue(MyBearingBlock.FACING));
        final SimAssemblyHelper.AssemblyResult result;

        try {
            result = SimAssemblyHelper.assembleFromSingleBlock(this.level, pos, toAssemble, false, false);
            this.lastException = null;
        } catch (final AssemblyException e) {
            this.lastException = e;
            this.sendData();
            return;
        }

        this.sendData();

        final ServerSubLevel assembledSubLevel;
        final BlockPos assembleOffset;
        // link（plate）继承轴承的 FACING（plate 模型绕旋转轴对称，与 swivel 一致，无 ROTATION）
        final BlockState link = MyModBlocks.aero_bearing_plate.get().defaultBlockState()
                .setValue(MyBearingPlateBlock.FACING, this.getBlockState().getValue(MyBearingBlock.FACING));

        if (result != null) {
            assembledSubLevel = (ServerSubLevel) result.subLevel();
            assembleOffset = result.offset();
        } else {
            final ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(this.level);

            final Pose3d pose = new Pose3d();
            pose.position().set(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

            assembledSubLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
            final LevelPlot plot = assembledSubLevel.getPlot();

            final ChunkPos center = plot.getCenterChunk();
            plot.newEmptyChunk(center);
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, link, 3);

            final BlockPos plotAnchor = plot.getCenterBlock();
            final Vector3dc centerOfMass = assembledSubLevel.getMassTracker().getCenterOfMass();
            final Vector3d subLevelCenter = JOMLConversion.atLowerCornerOf(pos);

            if (centerOfMass != null) {
                subLevelCenter.add(centerOfMass.x() - plotAnchor.getX(), centerOfMass.y() - plotAnchor.getY(), centerOfMass.z() - plotAnchor.getZ());
            } else {
                assembledSubLevel.logicalPose().rotationPoint()
                        .set(plotAnchor.getX() + 0.5, plotAnchor.getY() + 0.5, plotAnchor.getZ() + 0.5);
            }

            assembledSubLevel.logicalPose().position().set(subLevelCenter.x, subLevelCenter.y, subLevelCenter.z);
            assembleOffset = plotAnchor.subtract(pos);

            final SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
            final PhysicsPipeline pipeline = physicsSystem.getPipeline();

            final SubLevel containingSubLevel = this.getContainingSubLevel();
            if (containingSubLevel != null) {
                SubLevelAssemblyHelper.kickFromContainingSubLevel((ServerLevel) this.level, physicsSystem, pipeline, assembledSubLevel, containingSubLevel);
                assembledSubLevel.logicalPose().orientation().set(containingSubLevel.logicalPose().orientation());
            }

            pipeline.teleport(assembledSubLevel, assembledSubLevel.logicalPose().position(), assembledSubLevel.logicalPose().orientation());
            assembledSubLevel.updateLastPose();
        }

        this.getLevel().setBlockAndUpdate(pos, this.getBlockState().setValue(MyBearingBlock.ASSEMBLED, true));

        this.attachConstraints(assembledSubLevel, this.getConstraintPos(toAssemble, assembleOffset));
        this.setSubLevelID(assembledSubLevel.getUniqueId());

        final BlockPos plotPos = pos.offset(assembleOffset);
        if (result != null) {
            this.getLevel().setBlockAndUpdate(plotPos, link);
        }
        final BlockEntity be = this.getLevel().getBlockEntity(plotPos);

        if (be instanceof final MyBearingPlateBlockEntity plateBE) {
            plateBE.setParent(this);
            this.setPlatePos(plotPos);
        }

        // 初始化目标角 = 当前物理朝向（同 swivel 锁定开始时的 setTargetAngleFromCurrentOrientation），
        // 避免 PD 伺服把从动物理体强扭到 0° 导致顶部持续晃动
        this.setTargetAngleFromCurrentOrientation();
    }

    public void disassemble() {
        if (this.isRemoved()) {
            return;
        }

        this.removeHandle();
        final SubLevel subLevel = SubLevelContainer.getContainer(this.level).getSubLevel(this.getSubLevelID());
        final BlockPos platePos = this.getPlatePos();
        if (platePos != null) {
            this.destroyPlate();

            if (Objects.equals(subLevel, Sable.HELPER.getContaining(this.level, this.getBlockPos()))) {
                this.level.playSound(null, platePos, net.minecraft.sounds.SoundEvents.ANVIL_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
            } else if (subLevel != null) {
                // 如果销毁 plate 已移除 sub-level，则跳过拆卸
                if (!subLevel.isRemoved()) {
                    SimAssemblyHelper.disassembleSubLevel(this.level, subLevel, platePos, this.getBlockPos(), Rotation.NONE, true);
                }
            }
        }

        this.getLevel().setBlockAndUpdate(this.getBlockPos(), this.getBlockState().setValue(MyBearingBlock.ASSEMBLED, false));

        this.setSubLevelID(null);
        this.setPlatePos(null);
        this.targetAngleDegrees = 0;
        this.sendData();
    }

    private void checkPersistence(final UUID id) {
        if (this.getPlatePos() != null && this.getLevel().isAreaLoaded(this.getPlatePos(), 1)) {
            if (!this.getLevel().getBlockState(this.getPlatePos()).is(MyModBlocks.aero_bearing_plate.get())) {
                return;
            }
        }

        final SubLevel subLevel = SubLevelContainer.getContainer(this.level).getSubLevel(id);
        this.validateConstraintHandle();

        if (this.handle == null) {
            this.reattachConstraint((ServerSubLevel) subLevel, true);
        }
    }

    public void reattachConstraint(final @Nullable ServerSubLevel plateSubLevel, final boolean updatePlate) {
        final BlockPos platePos = this.getPlatePos();

        if (platePos != null) {
            if (this.handle != null) {
                this.handle.remove();
            }

            if (updatePlate) {
                this.associatePlateWithParent();
            }

            final BlockState plateState = this.level.getBlockState(platePos);
            if (!plateState.is(MyModBlocks.aero_bearing_plate.get())) return;

            final Direction plateFacing = plateState.getValue(MyBearingPlateBlock.FACING);
            this.attachConstraints(plateSubLevel, JOMLConversion.toJOML(platePos.relative(plateFacing).getCenter()));
        }
    }

    public void associatePlateWithParent() {
        if (this.getPlatePos() != null) {
            if (this.getLevel().getBlockState(this.getPlatePos()).is(MyModBlocks.aero_bearing_plate.get())) {
                final MyBearingPlateBlockEntity plate = (MyBearingPlateBlockEntity) this.getLevel().getBlockEntity(this.getPlatePos());
                plate.setParent(this);
            }
        }
    }

    private void attachConstraints(final @Nullable ServerSubLevel plateSubLevel, final Vector3d attachPos) {
        final BlockPos platePos = this.getPlatePos();

        if (platePos == null) return;
        final BlockState plateState = this.level.getBlockState(platePos);

        if (!plateState.is(MyModBlocks.aero_bearing_plate.get())) return;

        final Vector3d anchorPos = JOMLConversion.toJOML(this.getBlockPos().relative(this.getBlockState().getValue(DirectionalKineticBlock.FACING)).getCenter());
        final Vec3 facingVec = Vec3.atLowerCornerOf(this.getBlockState().getValue(DirectionalKineticBlock.FACING).getNormal());
        final Vec3 plateFacingVec = Vec3.atLowerCornerOf(plateState.getValue(DirectionalKineticBlock.FACING).getNormal());

        final RotaryConstraintConfiguration constraint = new RotaryConstraintConfiguration(
                anchorPos,
                attachPos.sub(JOMLConversion.toJOML(plateFacingVec.scale(0.001f))),
                JOMLConversion.toJOML(facingVec),
                JOMLConversion.toJOML(plateFacingVec)
        );

        final ServerSubLevelContainer container = SubLevelContainer.getContainer((ServerLevel) this.getLevel());
        final ServerSubLevel containingSubLevel = (ServerSubLevel) Sable.HELPER.getContaining(this);
        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();

        if (containingSubLevel == plateSubLevel) return;
        this.handle = pipeline.addConstraint(containingSubLevel, plateSubLevel, constraint);
    }

    // ═══════════════ CC:T 外设（Lua 控制模式） ═══════════════

    /**
     * 获取此外设的 CC:T IPeripheral 实例（懒加载）。
     * 注册见 {@code compat/cc/CCPeripheralCapabilities.java}。
     */
    public IPeripheral getPeripheral() {
        if (peripheral == null) {
            peripheral = new Peripheral();
        }
        return peripheral;
    }

    /**
     * 内嵌外设类（同 TransmissionPeripheralBlockEntity 模式）：
     * <ul>
     *   <li>控制模式开启后，tick 不再按应力网络转速推进目标角（应力网络仅保留应力消耗），
     *       旋转角度由 {@code setTargetAngle} 直接控制——跳过「转速 × 时间 = 角度」的累计过程；</li>
     *   <li>角度为<b>服务端权威</b>（{@code targetAngleDegrees}，NBT 持久化），
     *       PD 伺服（plate BE 每物理 tick 调用 {@link #updateServoCoefficients()}）把从动物理体追到目标角。</li>
     * </ul>
     */
    private class Peripheral implements IPeripheral {
        @Override
        public String getType() {
            return "aero_bearing";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            if (this == other) return true;
            if (other instanceof MyBearingBlockEntity.Peripheral that) {
                return MyBearingBlockEntity.this.worldPosition
                        .equals(MyBearingBlockEntity.this.worldPosition);
            }
            return false;
        }

        // ═══════════════ Lua API ═══════════════

        /** 是否处于 Lua 控制模式 */
        @LuaFunction
        public final boolean isControlMode() {
            return controlMode;
        }

        /**
         * 进入/退出 Lua 控制模式。
         * <p>
         * 进入：目标角 = 当前物理朝向（不产生转动），此后旋转由 {@code setTargetAngle} 控制，
         * 应力网络转速不再推进角度（仅消耗应力）；
         * 退出：恢复「转速 → 目标角推进」驱动，从当前朝向继续累计。
         */
        @LuaFunction(mainThread = true)
        public final boolean setControlMode(boolean enabled) {
            if (enabled) {
                if (controlMode) return true;
                controlMode = true;
                initializeTargetAngleFromOrientation();
            } else {
                if (!controlMode) return true;
                controlMode = false;
                initializeTargetAngleFromOrientation();
            }
            setChanged();
            sendData();
            return true;
        }

        /** 当前目标角（度，服务端权威） */
        @LuaFunction
        public final double getTargetAngle() {
            return targetAngleDegrees;
        }

        /** 当前目标角（弧度，同 swivel 官方外设 getTargetAngleRad） */
        @LuaFunction
        public final double getTargetAngleRad() {
            return Math.toRadians(targetAngleDegrees);
        }

        /**
         * 绝对定位从动物理体到指定角度（度）；自动进入控制模式。
         * 需要先装配（右键装配生成从动物理体）；未装配返回 false。
         */
        @LuaFunction(mainThread = true)
        public final boolean setTargetAngle(double degrees) {
            if (!Double.isFinite(degrees)) return false;
            if (!isAssembled()) return false;

            if (!controlMode) {
                controlMode = true;
                initializeTargetAngleFromOrientation();
            }

            targetAngleDegrees = degrees;
            wakeUpSubLevels();
            setChanged();
            sendData();
            return true;
        }

        /** 是否已装配（有从动物理体） */
        @LuaFunction
        public final boolean isAssembled() {
            return MyBearingBlockEntity.this.isAssembled();
        }

        /** 装配：把 FACING 方向的结构组装成从动物理体（sub-level）；返回是否成功（已装配则直接返回 true） */
        @LuaFunction(mainThread = true)
        public final boolean assemble() {
            if (MyBearingBlockEntity.this.isAssembled()) return true;
            MyBearingBlockEntity.this.assemble();
            return MyBearingBlockEntity.this.isAssembled();
        }

        /** 拆卸：把从动物理体拆回世界方块；返回是否成功（未装配则直接返回 true） */
        @LuaFunction(mainThread = true)
        public final boolean disassemble() {
            if (!MyBearingBlockEntity.this.isAssembled()) return true;
            MyBearingBlockEntity.this.disassemble();
            return !MyBearingBlockEntity.this.isAssembled();
        }
    }

    /**
     * 把目标角初始化为当前物理朝向（进入控制模式 / 退出控制模式时调用，避免跳变或强扭）。
     */
    private void initializeTargetAngleFromOrientation() {
        this.setTargetAngleFromCurrentOrientation();
    }

    /**
     * 唤醒两侧 sub-level，让 PD 伺服立即驱动到新目标角（同 swivel tick 244-258 行）。
     */
    private void wakeUpSubLevels() {
        final SubLevel attached = this.getAttachedSubLevel();
        final SubLevel containing = this.getContainingSubLevel();
        final PhysicsPipeline pipeline = ((ServerSubLevelContainer) SubLevelContainer.getContainer(this.level)).physicsSystem().getPipeline();

        if (containing instanceof final ServerSubLevel serverSubLevel) {
            pipeline.wakeUp(serverSubLevel);
        }

        if (attached instanceof final ServerSubLevel serverSubLevel) {
            pipeline.wakeUp(serverSubLevel);
        }
    }

    // ═══════════════ 伺服（旋转从动物理体，同 swivel 357-401 行） ═══════════════

    /**
     * 当前实际角度（度）：从从动物理体的实时朝向计算（同 swivel 337-355 行公式）。
     * 未装配或 plate 缺失时回退到目标角。
     */
    public double getCurrentAngleDegrees() {
        final SubLevel attached = this.getAttachedSubLevel();
        final BlockPos platePos = this.getPlatePos();
        if (attached == null || platePos == null) {
            return this.targetAngleDegrees;
        }
        final BlockState plateState = this.level.getBlockState(platePos);
        if (!plateState.is(MyModBlocks.aero_bearing_plate.get())) {
            return this.targetAngleDegrees;
        }

        final Quaterniond orientationA = new Quaterniond();
        final Quaterniond blockOrientationA = new Quaterniond(this.getBlockState().getValue(DirectionalKineticBlock.FACING).getRotation());
        final Quaterniond blockOrientationB = new Quaterniond(plateState.getValue(DirectionalKineticBlock.FACING).getRotation());
        final Quaterniond orientationB = new Quaterniond(attached.logicalPose().orientation());
        final SubLevel containing = this.getContainingSubLevel();
        if (containing != null) {
            orientationA.set(containing.logicalPose().orientation());
        }

        final Quaterniond localB = new Quaterniond(orientationA).mul(blockOrientationA).conjugate()
                .mul(new Quaterniond(orientationB).mul(blockOrientationB));

        final double d = new Vec3(0.0, 1.0, 0.0).dot(new Vec3(localB.x(), localB.y(), localB.z()));
        return -2.0 * (float) Math.toDegrees(Math.atan2(-d, localB.w()));
    }

    /**
     * 把目标角更新为当前物理朝向（swivel 在锁定开始时调用，避免 PD 伺服强扭到 0° 导致晃动）。
     */
    private void setTargetAngleFromCurrentOrientation() {
        this.targetAngleDegrees = this.getCurrentAngleDegrees();
        this.lastTargetAngleDegrees = this.targetAngleDegrees;
    }

    /**
     * PD 伺服把从动物理体追到目标角（同 swivel 357-401 行）：
     * <ul>
     *   <li>kP/kD 按两侧 sub-level 惯性张量沿旋转轴的投影缩放（{@code swivelBearingStiffness/Damping}）；</li>
     *   <li>目标角用 {@code angleLerp} 在物理 tick 间插值（平滑）；</li>
     *   <li>aero_bearing 无 POWERED 锁定开关（swivel 的红石锁定），当前阶段恒为锁定态
     *       （防风帆被气流吹动），直接走 swivel 的锁定分支；</li>
     *   <li>目标角由 {@link #tick()} 按转速推进。</li>
     * </ul>
     */
    public void updateServoCoefficients() {
        this.validateConstraintHandle();
        if (!this.isAssembled() || this.handle == null) {
            return;
        }

        final SimPhysics config = SimConfigService.INSTANCE.server().physics;

        final SubLevel subLevelA = this.getContainingSubLevel();
        final SubLevel subLevelB = this.getAttachedSubLevel();

        final Vec3i facingVec3I = this.getBlockState().getValue(DirectionalKineticBlock.FACING).getNormal();
        final Vector3dc facingVec = new Vector3d(facingVec3I.getX(), facingVec3I.getY(), facingVec3I.getZ());

        double inertiaA = Double.MAX_VALUE;
        double inertiaB = Double.MAX_VALUE;
        final Vector3d temp = new Vector3d();
        if (subLevelA instanceof final ServerSubLevel serverSubLevel) {
            inertiaA = serverSubLevel.getMassTracker().getInertiaTensor().transform(facingVec, temp).dot(facingVec);
        }

        if (subLevelB instanceof final ServerSubLevel serverSubLevel) {
            inertiaB = serverSubLevel.getMassTracker().getInertiaTensor().transform(facingVec, temp).dot(facingVec);
        }

        final double totalInertia = Math.max(10.0,
                subLevelA != null && subLevelB != null ?
                        Math.max(inertiaA, inertiaB) : Math.min(inertiaA, inertiaB)
        );

        final SubLevelPhysicsSystem physicsSystem = ((ServerSubLevelContainer) SubLevelContainer.getContainer(this.level)).physicsSystem();

        final double kP = config.swivelBearingStiffness.get() * totalInertia;
        final double kD = config.swivelBearingDamping.get() * totalInertia;
        final float goal = AngleHelper.rad(AngleHelper.angleLerp(physicsSystem.getPartialPhysicsTick(), this.lastTargetAngleDegrees, this.targetAngleDegrees));

        this.handle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, goal, kP, kD, false, 0.0);
        this.handle.setContactsEnabled(false);
    }

    /**
     * 转速钳制（同 swivel {@code limitCogSpeed}）：过高转速 PD 追不上或抖动，
     * 上限用 simulated 的 {@code maxSwivelBearingSpeed}（默认 96 RPM）。
     */
    private float limitSpeed(final float speed) {
        final float maxSwivelRPM = SimConfigService.INSTANCE.server().blocks.maxSwivelBearingSpeed.getF();
        return Mth.clamp(speed, -maxSwivelRPM, maxSwivelRPM);
    }

    /**
     * 转速变化时更新序列化角度输入（同 swivel cogwheel 895-899 行）：
     * 网络传播来的 {@code sequenceContext}（TURN_ANGLE，如 sequenced gearshift / 曲柄）
     * 换算成「本段剩余可转角度」（{@code getEffectiveValue}），tick 里按此钳制推进量；
     * 非序列化输入（普通连续旋转）重置为 -1。
     */
    @Override
    public void onSpeedChanged(final float previousSpeed) {
        super.onSpeedChanged(previousSpeed);

        this.sequencedAngleLimit = -1;
        if (this.sequenceContext != null && this.sequenceContext.instruction() == SequencerInstructions.TURN_ANGLE) {
            this.sequencedAngleLimit = this.sequenceContext.getEffectiveValue(this.getTheoreticalSpeed());
        }
    }

    private void validateConstraintHandle() {
        if (this.handle != null && !this.handle.isValid()) {
            this.handle = null;
        }
    }

    // ═══════════════ NBT ═══════════════

    @Override
    protected void write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putDouble("TargetAngle", this.targetAngleDegrees);
        compound.putBoolean("ControlMode", this.controlMode);

        if (this.sequencedAngleLimit >= 0)
            compound.putDouble("SequencedAngleLimit", this.sequencedAngleLimit);

        final UUID id = this.getSubLevelID();
        if (id != null) {
            compound.putUUID("SubLevelID", id);
        }

        final BlockPos platePos = this.getPlatePos();
        if (platePos != null) {
            compound.put("SwivelPlate", NbtUtils.writeBlockPos(platePos));
        }

        AssemblyException.write(compound, registries, this.lastException);
    }

    @Override
    protected void read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        this.targetAngleDegrees = compound.getDouble("TargetAngle");
        this.controlMode = compound.getBoolean("ControlMode");
        this.sequencedAngleLimit = compound.contains("SequencedAngleLimit") ? compound.getDouble("SequencedAngleLimit") : -1;

        if (compound.hasUUID("SubLevelID")) {
            this.setSubLevelID(compound.getUUID("SubLevelID"));
        }

        if (compound.contains("SwivelPlate")) {
            final BlockPos blockPos = NbtUtils.readBlockPos(compound, "SwivelPlate").orElseThrow();
            this.setPlatePos(blockPos);
        }

        this.lastException = AssemblyException.read(compound, registries);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        this.removeHandle();
    }

    /**
     * 装配前调用（作为结构的一部分被移动时置标志，防止 remove 连锁破坏）
     */
    public void beforeAssembly() {
        this.assembling = true;
    }

    @Override
    public void remove() {
        if (!this.level.isClientSide && !this.assembling) {
            // 若确实被移除（而非卸载），连带破坏 plate
            this.destroyPlate();
        }

        super.remove();
    }

    public boolean isAssembled() {
        return this.getBlockState().getValue(MyBearingBlock.ASSEMBLED);
    }

    // ═══════════════ Sable actor ═══════════════

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        final SubLevel attachedSubLevel = this.getAttachedSubLevel();

        if (attachedSubLevel == null) {
            return null;
        }

        return List.of(attachedSubLevel);
    }

    // ═══════════════ 内部工具 ═══════════════

    private @Nullable SubLevel getAttachedSubLevel() {
        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        return container.getSubLevel(this.subLevelID);
    }

    private @Nullable SubLevel getContainingSubLevel() {
        return Sable.HELPER.getContaining(this);
    }

    private @NotNull Vector3d getConstraintPos(final BlockPos relative, final BlockPos offset) {
        return JOMLConversion.toJOML(relative.offset(offset).getCenter());
    }

    private void destroyPlate() {
        final BlockPos platePos = this.getPlatePos();
        if (platePos != null) {
            final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
            if (container == null) return;

            final SubLevel subLevel = container.getSubLevel(this.subLevelID);
            if (this.subLevelID != null && subLevel == null) return;

            if (this.getLevel().getBlockState(platePos).is(MyModBlocks.aero_bearing_plate.get())) {
                MyModBlocks.aero_bearing_plate.get().withBlockEntityDo(this.level, platePos, MyBearingPlateBlockEntity::beforeAssembly);
                this.getLevel().setBlock(platePos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private void removeHandle() {
        if (this.handle != null) {
            this.handle.remove();
            this.handle = null;
        }
    }

    public double getTargetAngleDegrees() {
        return this.targetAngleDegrees;
    }

    public void setTargetAngleDegrees(final double targetAngleDegrees) {
        this.targetAngleDegrees = targetAngleDegrees;
    }

    public @Nullable UUID getSubLevelID() {
        return this.subLevelID;
    }

    public void setSubLevelID(@Nullable final UUID subLevelID) {
        this.subLevelID = subLevelID;
    }

    public @Nullable BlockPos getPlatePos() {
        return this.platePos;
    }

    public void setPlatePos(@Nullable final BlockPos platePos) {
        this.platePos = platePos;
    }

    /**
     * 应力影响（Create 网络按 {@code impact × |输入转速|} 计费，转速越高消耗越大）：
     * 背面半个传动杆接入应力网络驱动从动物理体旋转，消耗应力（impact 同 swivel 注册值 4.0）。
     */
    @Override
    public float calculateStressApplied() {
        return 4.0f;
    }

    // ═══════════════ Create 护目镜 tooltip ═══════════════

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.aero_bearing.header")
                        .withStyle(ChatFormatting.WHITE)));

        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.aero_bearing.mode")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(controlMode
                                ? "tooltip.ccpe.aero_bearing.mode_control"
                                : "tooltip.ccpe.aero_bearing.mode_stress")
                        .withStyle(controlMode ? ChatFormatting.GOLD : ChatFormatting.AQUA)));

        // 仅 Lua 控制模式显示角度（应力驱动模式下角度由转速累计，无意义）
        if (controlMode) {
            tooltip.add(angleLine("tooltip.ccpe.aero_bearing.current_angle", this.getCurrentAngleDegrees()));
            tooltip.add(angleLine("tooltip.ccpe.aero_bearing.target_angle", this.getTargetAngleDegrees()));
        }

        tooltip.add(Component.empty());
        addStressImpactStats(tooltip, calculateStressApplied());
        return true;
    }

    private Component angleLine(final String key, final double angle) {
        return Component.literal("     ")
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°", angle))
                        .withStyle(ChatFormatting.AQUA));
    }

    @Override
    public AssemblyException getLastAssemblyException() {
        return this.lastException;
    }
}
