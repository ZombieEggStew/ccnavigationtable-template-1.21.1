package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 自研风帆轴承（my_bearing）方块实体。
 * <p>
 * 阶段 1-2 骨架（对照 {@code SwivelBearingBlockEntity}，去掉 cogwheel / ExtraKinetics）：
 * <ul>
 *   <li>字段：{@code subLevelID}（从动物理体）、{@code platePos}（plate 方块）、{@code handle}（约束句柄）、
 *       {@code targetAngleDegrees}、{@code assembleNextTick}、{@code assembling}、{@code lastException}；</li>
 *   <li>NBT 持久化：{@code SubLevelID} / {@code SwivelPlate} / {@code TargetAngle}（同 swivel 598-665 行）；</li>
 *   <li>空手右键 → {@code assembleNextTick} → {@link #assemble()} / {@link #disassemble()}；
 *       plate 方块在装配时自动放置到 sub-level plot 内（{@code my_bearing_plate}）；</li>
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
        super(MyModBlockEntities.my_bearing_entity.get(), pos, state);
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

        // ── 阶段 4（驱动）待实现 ──
        // this.lastTargetAngleDegrees = this.targetAngleDegrees;
        // float angularSpeed = convertToAngular(Math.abs(this.getSpeed())) * 方向;
        // this.targetAngleDegrees += angularSpeed; this.targetAngleDegrees %= 360;
        // 有动力时 wakeUp 两侧 sub-level；随后 plate BE 的 sable$physicsTick → updateServoCoefficients() 驱动约束

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
        final BlockState link = MyModBlocks.my_bearing_plate.get().defaultBlockState()
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
            if (!this.getLevel().getBlockState(this.getPlatePos()).is(MyModBlocks.my_bearing_plate.get())) {
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
            if (!plateState.is(MyModBlocks.my_bearing_plate.get())) return;

            final Direction plateFacing = plateState.getValue(MyBearingPlateBlock.FACING);
            this.attachConstraints(plateSubLevel, JOMLConversion.toJOML(platePos.relative(plateFacing).getCenter()));
        }
    }

    public void associatePlateWithParent() {
        if (this.getPlatePos() != null) {
            if (this.getLevel().getBlockState(this.getPlatePos()).is(MyModBlocks.my_bearing_plate.get())) {
                final MyBearingPlateBlockEntity plate = (MyBearingPlateBlockEntity) this.getLevel().getBlockEntity(this.getPlatePos());
                plate.setParent(this);
            }
        }
    }

    private void attachConstraints(final @Nullable ServerSubLevel plateSubLevel, final Vector3d attachPos) {
        final BlockPos platePos = this.getPlatePos();

        if (platePos == null) return;
        final BlockState plateState = this.level.getBlockState(platePos);

        if (!plateState.is(MyModBlocks.my_bearing_plate.get())) return;

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

    // ═══════════════ 伺服（阶段 4 填驱动，当前简单锁定） ═══════════════

    public void updateServoCoefficients() {
        this.validateConstraintHandle();
        if (!this.isAssembled() || this.handle == null) {
            return;
        }

        // 阶段 1-2：简单锁定当前目标角（kP/kD 常量）
        // 阶段 4：改为按从动物理体惯性张量缩放（同 swivel 363-401 行），
        // 并在 tick() 中由转速推进 targetAngleDegrees 实现连续旋转。
        final double kP = 6000.0;
        final double kD = 1500.0;
        final double goal = Math.toRadians(this.targetAngleDegrees);

        this.handle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, goal, kP, kD, false, 0.0);
        this.handle.setContactsEnabled(false);
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

            if (this.getLevel().getBlockState(platePos).is(MyModBlocks.my_bearing_plate.get())) {
                MyModBlocks.my_bearing_plate.get().withBlockEntityDo(this.level, platePos, MyBearingPlateBlockEntity::beforeAssembly);
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

    // 贯通轴不消耗应力（同 swivel 788-792 行）
    @Override
    public float calculateStressApplied() {
        return 0;
    }

    @Override
    public AssemblyException getLastAssemblyException() {
        return this.lastException;
    }
}
