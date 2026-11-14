package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
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
 * 对照 {@code SwivelBearingBlockEntity}，去掉 cogwheel / ExtraKinetics：
 * <ul>
 *   <li>字段：{@code subLevelID}（从动物理体）、{@code platePos}（plate 方块）、{@code handle}（约束句柄）、
 *       {@code targetAngleDegrees}、{@code assembleNextTick}、{@code assembling}、{@code lastException}；</li>
 *   <li>NBT 持久化：{@code SubLevelID} / {@code SwivelPlate} / {@code TargetAngle} / {@code CommandedAngle} /
 *       {@code LuaRotationSpeed} / {@code Locked}（同 swivel 598-665 行）；</li>
 *   <li>空手右键 → {@code assembleNextTick} → {@link #assemble()} / {@link #disassemble()}；
 *       plate 方块在装配时自动放置到 sub-level plot 内（{@code aero_bearing_plate}）；</li>
 *   <li>约束：{@code RotaryConstraintConfiguration}（pos1/pos2 各自 plot 内坐标，normal 归一化）；</li>
 *   <li><b>锁定模式</b>（Lua {@code setLocked}，行为同 swivel）：默认锁定 = PD 伺服把从动物理体锁在目标角；
 *       未锁定 = 关节只剩被动摩擦阻尼（{@code swivelBearingFriction}），可被外力自由带动；</li>
 *   <li><b>驱动策略</b>：Lua {@code setTargetAngle} 只写命令终点 {@code commandedAngleDegrees}；
 *       伺服参考 {@code targetAngleDegrees} 每 tick 以「顶盘旋转速度」（默认 = 输入转速，
 *       可由 Lua {@code setRotationSpeed} 固定为 0~96 RPM；默认上限 96 RPM）推进，见 {@link #advanceTowardsCommandedAngle()}。
 *       <b>没有动力不能转</b>：输入转速为 0 时顶盘不动。装配后输入动力另外只做两件事：
 *       按 {@link #calculateStressApplied()} 计费应力；经 plate 贯通把转速传进 sub-level 内部机械
 *       （见 {@link #propagateRotationTo} 等三个网络连接覆写）。</li>
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
     * 当前目标角（度，伺服参考）：每 tick 以受限速度朝 {@link #commandedAngleDegrees} 推进
     */
    private double targetAngleDegrees = 0;
    /**
     * Lua 命令角（度）：{@code setTargetAngle} 写入的终点。
     * 伺服参考 {@link #targetAngleDegrees} 以「顶盘旋转速度」为上限逐 tick 逼近它，到位后两者相等。
     */
    private double commandedAngleDegrees = 0;
    /**
     * Lua 配置的顶盘旋转速度（RPM，0~96）。
     * <p>
     * {@code 0} = 自动：顶盘旋转速度 = {@code min(输入转速, 96)}（见 {@link #getEffectiveRotationSpeedRpm()}）。
     * 一旦通过 Lua 设过非 0 值，就一直沿用该值（不再跟随输入转速），直到再次设为 0。
     */
    private float configuredRotationSpeedRpm = 0;
    /**
     * 锁定状态（Lua 可切换，同 swivel 的锁定语义）：
     * <ul>
     *   <li>{@code true}（默认）= PD 伺服把从动物理体锁在目标角，防风帆被气流吹动；</li>
     *   <li>{@code false} = 关节只保留被动摩擦阻尼（{@code swivelBearingFriction}），
     *       从动物理体可被外力（气流/碰撞）自由带动。</li>
     * </ul>
     * 切回锁定时以当前物理朝向为起点（同 swivel 189-203 行），不会强扭跳变。
     */
    private boolean locked = true;
    // 注：原 sequencedAngleLimit（sequenced gearshift 剩余角）与 controlMode 两个字段已移除——
    // 输入动力只做应力计费与贯通传输，顶盘角度恒由 Lua 控制（CC 外设见下）。
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

        // ── 驱动策略：顶盘 / 从动物理体的物理角只由 Lua 命令驱动，且需要动力 ──
        // 装配后应力网络的作用：
        //   1. 按 {@link #calculateStressApplied()} 计费应力（输入有转速即消耗）；
        //   2. 经 plate 贯通（{@link #propagateRotationTo}）把转速原样传给 sub-level 内部机械；
        //   3. <b>作为顶盘转动的动力来源</b>：输入转速为 0 时顶盘一律不转（没有动力不能转）。
        // Lua {@code setTargetAngle} 只写入终点 {@code commandedAngleDegrees}，
        // 伺服参考 {@code targetAngleDegrees} 每 tick 以「顶盘旋转速度」推进，逼近终点后停住。
        // lastTargetAngleDegrees 供物理 tick 间的角度插值（AngleHelper.angleLerp）。
        this.lastTargetAngleDegrees = this.targetAngleDegrees;

        if (this.isAssembled()) {
            this.advanceTowardsCommandedAngle();
        }

        this.assembleNextTick = false;
    }

    // ═══════════════ 限速到位（Lua 命令 → 伺服参考） ═══════════════

    /**
     * 顶盘旋转速度上限（RPM）：输入转速再高，顶盘最多按 96 RPM 走
     * （取值对齐 swivel 的 {@code maxSwivelBearingSpeed} 默认 96）。
     * Lua {@code setRotationSpeed} 的参数也按 0~96 钳制。
     */
    public static final float MAX_PLATE_ROTATION_RPM = 96.0f;

    /**
     * 当前生效的顶盘旋转速度（RPM）：
     * <ul>
     *   <li>Lua 设过非 0 值 → 该值（已被钳到 0~96）；</li>
     *   <li>否则 → {@code min(|输入转速|, 96)}（默认：顶盘转速 = 输入转速，最高 96）。</li>
     * </ul>
     */
    public float getEffectiveRotationSpeedRpm() {
        if (this.configuredRotationSpeedRpm > 0.0f) {
            return Math.min(this.configuredRotationSpeedRpm, MAX_PLATE_ROTATION_RPM);
        }

        return Math.min(Math.abs(this.getSpeed()), MAX_PLATE_ROTATION_RPM);
    }

    /**
     * 切换锁定状态（<b>行为同 simulated:swivel_bearing</b>）：
     * <ul>
     *   <li>{@code true} = PD 伺服把从动物理体锁在目标角（默认，防风帆被气流吹动）；</li>
     *   <li>{@code false} = 关节只保留被动摩擦阻尼（{@code swivelBearingFriction}），
     *       从动物理体可被气流/碰撞等外力自由带动，且不推进 Lua 角度命令。</li>
     * </ul>
     * 切回锁定时把伺服参考角与命令终点都对齐到当前物理朝向
     * （同 swivel 189-203 行 {@code setTargetAngleFromCurrentOrientation}），不会强扭跳变；
     * 之后由 Lua {@code setTargetAngle} 重新下命令。
     */
    public void setLocked(final boolean locked) {
        if (this.locked == locked) {
            return;
        }

        this.locked = locked;
        if (locked) {
            this.setTargetAngleFromCurrentOrientation();
        }

        this.wakeUpSubLevels();
        this.setChanged();
        this.sendData();
    }

    /** 是否处于锁定模式（默认 true） */
    public boolean isLocked() {
        return this.locked;
    }

    /**
     * 以「顶盘旋转速度」为上限，把伺服参考 {@link #targetAngleDegrees} 逐 tick 推向
     * Lua 命令终点 {@link #commandedAngleDegrees}（走最短路径）。
     * <p>
     * 旋转速度（RPM）见 {@link #getEffectiveRotationSpeedRpm()}：Lua 设过非 0 值时恒用该值，
     * 否则为 {@code min(|输入转速|, 96)}。每 tick 步进角度 = {@code convertToAngular(rpm)}（同 swivel 的换算）。
     * <p>
     * <b>动力门控</b>：输入转速（应力网络 {@code getSpeed()}）为 0 时直接返回——没有动力，顶盘不转。
     */
    private void advanceTowardsCommandedAngle() {
        if (this.commandedAngleDegrees == this.targetAngleDegrees) {
            return;
        }

        // 未锁定时不推进参考角：关节处于自由 + 摩擦阻尼状态，角度由物理外力决定
        if (!this.locked) {
            return;
        }

        // 没有动力（输入转速为 0）就不驱动：顶盘只能被动力带动
        if (this.getSpeed() == 0.0f) {
            return;
        }

        final double stepDegrees = Math.abs(convertToAngular(this.getEffectiveRotationSpeedRpm()));

        if (stepDegrees <= 0.0) {
            return;
        }

        // 走最短路径：把角度差归一化到 (-180, 180]
        double delta = this.commandedAngleDegrees - this.targetAngleDegrees;
        delta = delta % 360.0;
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta <= -180.0) {
            delta += 360.0;
        }

        if (Math.abs(delta) <= stepDegrees) {
            // 本 tick 可到位：直接对齐终点，避免在终点附近来回抖动
            this.targetAngleDegrees = this.commandedAngleDegrees;
        } else {
            this.targetAngleDegrees += Math.signum(delta) * stepDegrees;
        }

        this.wakeUpSubLevels();
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
        this.commandedAngleDegrees = 0;
        this.lastTargetAngleDegrees = 0;
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
     *   <li><b>角度唯一来源</b>：顶盘 / 从动物理体的物理角只能由 {@code setTargetAngle} 设定；
     *       输入动力的转速不推进角度（只计费应力并经 plate 贯通传给 sub-level 内部）；</li>
     *   <li>角度为<b>服务端权威</b>（{@code targetAngleDegrees}，NBT 持久化），
     *       PD 伺服（plate BE 每物理 tick 调用 {@link #updateServoCoefficients()}）把从动物理体锁在该目标角。</li>
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

        /**
         * 是否处于 Lua 控制模式。
         * <p>
         * 恒为 {@code true}：本轴承的顶盘 / 从动物理体物理角<b>只能</b>由 Lua {@code setTargetAngle} 改变，
         * 输入动力的转速不再推进角度（只做应力计费与贯通传输）。
         */
        @LuaFunction
        public final boolean isControlMode() {
            return true;
        }

        /**
         * 兼容旧脚本的空实现：无论 {@code enabled} 为何都返回 {@code true}。
         * <p>
         * 「应力驱动模式」（输入转速直接推角度）已取消——输入动力装配后只消耗并传输应力，
         * 顶盘不跟着转；要转动请用 {@code setTargetAngle}。
         */
        @LuaFunction(mainThread = true)
        public final boolean setControlMode(boolean enabled) {
            return true;
        }

        /** 当前伺服参考角（度，服务端权威；正以「顶盘旋转速度」逼近命令终点） */
        @LuaFunction
        public final double getTargetAngle() {
            return targetAngleDegrees;
        }

        /** 当前伺服参考角（弧度，同 swivel 官方外设 getTargetAngleRad） */
        @LuaFunction
        public final double getTargetAngleRad() {
            return Math.toRadians(targetAngleDegrees);
        }

        /** Lua 命令终点角（度）：{@code setTargetAngle} 写入的值，到位后与 {@code getTargetAngle()} 相等 */
        @LuaFunction
        public final double getCommandedAngle() {
            return commandedAngleDegrees;
        }

        /**
         * 绝对定位顶盘 / 从动物理体到指定角度（度）——唯一的角度来源。
         * <p>
         * 顶盘不会瞬移：伺服参考角每 tick 以「顶盘旋转速度」逼近该终点（见 {@code setRotationSpeed}）。
         * <b>没有动力（背面输入转速为 0）时不会转动</b>，动力恢复后继续朝终点走。
         * 需要先装配；未装配返回 false。
         */
        @LuaFunction(mainThread = true)
        public final boolean setTargetAngle(double degrees) {
            if (!Double.isFinite(degrees)) return false;
            if (!isAssembled()) return false;

            commandedAngleDegrees = degrees;
            wakeUpSubLevels();
            setChanged();
            sendData();
            return true;
        }

        /**
         * 设置顶盘旋转速度（RPM），范围 <b>0~96</b>（自动钳制）。
         * <p>
         * {@code 0} = 自动：顶盘旋转速度 = {@code min(输入转速, 96)}（默认）；非 0 则一直沿用该值，
         * 不再跟随输入转速。每 tick 步进角度 = {@code convertToAngular(rpm)}，即按该速度走完
         * {@code setTargetAngle} 的差值。<b>速度只是「怎么走」，能不能走仍取决于动力</b>：
         * 输入转速为 0 时顶盘不转。
         *
         * @param rpm 目标转速（RPM）；负数按绝对值处理，超过 96 自动钳到 96
         * @return 实际生效的配置值（0 表示自动）
         */
        @LuaFunction(mainThread = true)
        public final double setRotationSpeed(double rpm) {
            if (!Double.isFinite(rpm)) return configuredRotationSpeedRpm;

            configuredRotationSpeedRpm = (float) Mth.clamp(Math.abs(rpm), 0.0, MAX_PLATE_ROTATION_RPM);
            setChanged();
            sendData();
            return configuredRotationSpeedRpm;
        }

        /** 当前配置的顶盘旋转速度（RPM）；{@code 0} 表示自动（= 输入转速，最高 96） */
        @LuaFunction
        public final double getRotationSpeed() {
            return configuredRotationSpeedRpm;
        }

        /** 输入转速（RPM，应力网络 {@code getSpeed()}）——顶盘转动的动力前提 */
        @LuaFunction
        public final double getInputSpeed() {
            return getSpeed();
        }

        /**
         * 切换锁定 / 未锁定模式（<b>行为同 simulated:swivel_bearing</b>）。
         * <ul>
         *   <li>{@code true}（默认）：PD 伺服把顶盘锁在目标角，{@code setTargetAngle} 生效；</li>
         *   <li>{@code false}：关节只剩被动摩擦阻尼（{@code swivelBearingFriction}），
         *       顶盘可被气流/碰撞等外力自由带动；此模式下不推进角度命令（
         *       {@code setTargetAngle} 的差值会被挂起，等切回锁定后以当前朝向为起点重新开始）。</li>
         * </ul>
         * 切回锁定时以当前物理朝向为起点，不会跳变。
         *
         * @return 切换后的锁定状态
         */
        @LuaFunction(mainThread = true)
        public final boolean setLocked(boolean locked) {
            MyBearingBlockEntity.this.setLocked(locked);
            return MyBearingBlockEntity.this.isLocked();
        }

        /** 是否处于锁定模式（默认 true） */
        @LuaFunction
        public final boolean isLocked() {
            return MyBearingBlockEntity.this.isLocked();
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
     * 唤醒两侧 sub-level，让 PD 伺服立即跟上新的伺服参考角（同 swivel tick 244-258 行）。
     * <p>
     * 在 {@code setTargetAngle} / 每 tick 推进参考角时调用。
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
     * 把伺服参考角与命令终点都初始化为当前物理朝向（装配后调用，同 swivel 锁定开始时的
     * {@code setTargetAngleFromCurrentOrientation}），避免 PD 伺服把从动物理体强扭到 0° 导致晃动。
     */
    private void setTargetAngleFromCurrentOrientation() {
        final double current = this.getCurrentAngleDegrees();
        this.targetAngleDegrees = current;
        this.commandedAngleDegrees = current;
        this.lastTargetAngleDegrees = current;
    }

    /**
     * 伺服更新（同 swivel 357-401 行），按 {@link #locked} 分两支：
     * <ul>
     *   <li><b>未锁定</b>：只给被动摩擦阻尼（{@code swivelBearingFriction}，swivel 365-369 行），
     *       从动物理体可被外力（气流/碰撞）自由带动；</li>
     *   <li><b>锁定</b>：PD 伺服把从动物理体锁在目标角——
     *       kP/kD 按两侧 sub-level 惯性张量沿旋转轴的投影缩放（{@code swivelBearingStiffness/Damping}），
     *       目标角用 {@code angleLerp} 在物理 tick 间插值（平滑），
     *       目标角只由 Lua {@code setTargetAngle} 设定并以「顶盘旋转速度」限速逼近
     *       （{@link #advanceTowardsCommandedAngle()}），输入转速为 0 时不转动。</li>
     * </ul>
     */
    public void updateServoCoefficients() {
        this.validateConstraintHandle();
        if (!this.isAssembled() || this.handle == null) {
            return;
        }

        final SimPhysics config = SimConfigService.INSTANCE.server().physics;

        if (!this.locked) {
            // 未锁定：被动摩擦阻尼（同 swivel 365-369 行），不施加角度伺服
            this.handle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, 0.0, 0.0, config.swivelBearingFriction.get(), false, 0.0);
            return;
        }

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
        compound.putDouble("CommandedAngle", this.commandedAngleDegrees);
        compound.putFloat("LuaRotationSpeed", this.configuredRotationSpeedRpm);
        compound.putBoolean("Locked", this.locked);

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
        this.commandedAngleDegrees = compound.contains("CommandedAngle")
                ? compound.getDouble("CommandedAngle")
                : this.targetAngleDegrees;
        this.configuredRotationSpeedRpm = compound.contains("LuaRotationSpeed")
                ? Mth.clamp(compound.getFloat("LuaRotationSpeed"), 0.0f, MAX_PLATE_ROTATION_RPM)
                : 0.0f;
        this.locked = !compound.contains("Locked") || compound.getBoolean("Locked");
        this.lastTargetAngleDegrees = this.targetAngleDegrees;

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

    /** 伺服参考角（度）：每 tick 以「顶盘旋转速度」逼近命令终点 */
    public double getTargetAngleDegrees() {
        return this.targetAngleDegrees;
    }

    /** 命令终点角（度）：{@code setTargetAngle} 写入的目标，顶盘以限速走到这里后停住 */
    public double getCommandedAngleDegrees() {
        return this.commandedAngleDegrees;
    }

    /** 配置的顶盘旋转速度（RPM，0 = 自动跟随输入转速） */
    public float getConfiguredRotationSpeedRpm() {
        return this.configuredRotationSpeedRpm;
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

    // ═══════════════ Create 应力网络：贯通传动（同 swivel 753-770 行） ═══════════════

    /**
     * 把 plate（顶盘）位置交给应力网络扫描。
     * <p>
     * plate 位于本轴承的 sub-level plot 内，与轴承在级别坐标上相距很远，
     * Create 默认只扫 6 邻居 + 对角位置，永远找不到它；不显式补上 {@code platePos}，
     * 网络从轴承出发就断在这里，顶盘与结构内部传动拿不到转速。
     * 同 {@code SwivelBearingBlockEntity#addPropagationLocations}。
     */
    @Override
    public List<BlockPos> addPropagationLocations(final IRotate block, final BlockState state, final List<BlockPos> neighbours) {
        if (this.getPlatePos() != null) {
            neighbours.add(this.getPlatePos());
        }

        return super.addPropagationLocations(block, state, neighbours);
    }

    /**
     * 轴承 → 顶盘：传动比 1（同速同向），即<b>输入转速 = 输出转速</b>（贯穿传动杆）。
     * <p>
     * 返回非 0 会短路 {@code RotationPropagator.getAxisModifier}，因此不受两侧朝向/轴方向修正影响。
     * 同 {@code SwivelBearingBlockEntity#propagateRotationTo}。
     */
    @Override
    public float propagateRotationTo(final KineticBlockEntity target, final BlockState stateFrom, final BlockState stateTo, final BlockPos diff, final boolean connectedViaAxes, final boolean connectedViaCogs) {
        return this.getPlatePos() != null && stateTo.getBlock() instanceof MyBearingPlateBlock
                ? 1
                : super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    /**
     * {@code RotationPropagator.isConnected} 的自定义连接兜底（plate 侧同样有镜像实现）。
     * 同 {@code SwivelBearingBlockEntity#isCustomConnection}。
     */
    @Override
    public boolean isCustomConnection(final KineticBlockEntity other, final BlockState state, final BlockState otherState) {
        return this.getPlatePos() != null && otherState.getBlock() instanceof MyBearingPlateBlock;
    }

    // ═══════════════ Create 护目镜 tooltip ═══════════════

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.aero_bearing.header")
                        .withStyle(ChatFormatting.WHITE)));

        // 角度恒由 Lua 控制（限速逼近命令终点），故总是显示当前角 / 命令终点角
        tooltip.add(angleLine("tooltip.ccpe.aero_bearing.current_angle", this.getCurrentAngleDegrees()));
        tooltip.add(angleLine("tooltip.ccpe.aero_bearing.target_angle", this.commandedAngleDegrees));

        // 锁定状态（Lua setLocked 切换；未锁定时顶盘可被外力自由带动）
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.aero_bearing.locked").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(this.locked
                                ? "tooltip.ccpe.aero_bearing.locked_yes"
                                : "tooltip.ccpe.aero_bearing.locked_no")
                        .withStyle(this.locked ? ChatFormatting.AQUA : ChatFormatting.GOLD)));

        // 动力与转速：输入转速是「能不能转」的前提，旋转速度是「转多快」（自动时最高 96 RPM）
        tooltip.add(rpmLine("tooltip.ccpe.aero_bearing.input_speed", this.getSpeed(), null));
        tooltip.add(rpmLine("tooltip.ccpe.aero_bearing.plate_speed",
                this.getEffectiveRotationSpeedRpm(),
                this.configuredRotationSpeedRpm > 0.0f
                        ? null
                        : "tooltip.ccpe.aero_bearing.plate_speed_auto"));

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

    /**
     * 转速行：主值 {@code %.1f RPM}，可选附加一个后缀标签（如「自动（=输入转速）」）。
     */
    private Component rpmLine(final String key, final float rpm, final @Nullable String suffixKey) {
        final Component line = Component.literal("     ")
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f RPM", rpm))
                        .withStyle(ChatFormatting.AQUA));

        return suffixKey == null
                ? line
                : line.copy().append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                        .append(Component.translatable(suffixKey).withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public AssemblyException getLastAssemblyException() {
        return this.lastException;
    }
}
