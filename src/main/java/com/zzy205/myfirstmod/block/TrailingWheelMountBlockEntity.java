package com.zzy205.myfirstmod.block;

import dev.ryanhcode.offroad.content.components.TireLike;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;

/**
 * 从动轮悬架方块实体——offroad {@code WheelMountBlockEntity} 的「无动力」移植。
 * <p>
 * 参考来源：{@code references/Simulated-Project-main/offroad/.../wheel_mount/WheelMountBlockEntity.java}
 * （含 {@code WheelMountInventory} 单槽语义），删改点见 {@code memo/wheel-axle-design.md}：
 * <ul>
 * <li><b>不继承 {@code KineticBlockEntity}</b>：无 Create 转速/应力/轴——完全从动；</li>
 * <li><b>删除驱动项</b>：{@code sable$physicsTick} 只保留弹簧支撑 + 阻尼 + 侧滑摩擦
 *     （贴地轮被车身推着滚，不主动产生前进推力）；</li>
 * <li><b>删除红石转向/刹车/悬挂强度滚轮</b>：首版极简，刚度用常量
 *     {@value #SUSPENSION_STRENGTH}；</li>
 * <li><b>客户端滚动</b>：贴地时由车身平移驱动轮子角度（从动），离地后停转
 *     （原 offroad 空中由转速带动，此处无转速 → 自然停）；</li>
 * <li>轮胎栈照抄 offroad 单槽语义（NBT {@code CurrentStack}），轮胎 = 任意带
 *     {@code offroad:TIRE} 数据组件的物品。</li>
 * </ul>
 * 服务端施力沿用 offroad 批处理模式：{@code sable$physicsTick} 只把冲量攒进
 * {@link #queuedWheelMounts}，由 {@link #onPhysicsTick}（Sable 物理 tick 事件，
 * 见 {@code CCPeripheralExtender} 注册）统一 {@code applyForcesAndReset}。
 */
public class TrailingWheelMountBlockEntity extends BlockEntity implements BlockEntitySubLevelActor, Clearable {

    /** 悬挂强度（offroad ScrollValueBehaviour 默认值 10；首版不做滚轮 UI，用常量） */
    public static final int SUSPENSION_STRENGTH = 10;

    private static final double MAX_ALLOWED_EXTENSION = 0.65;
    private static final double NO_WHEEL_EXTENSION = 0.5;

    private static final Collection<TrailingWheelMountBlockEntity> queuedWheelMounts = new ObjectOpenHashSet<>();

    private ItemStack heldItem = ItemStack.EMPTY;
    private double extension = NO_WHEEL_EXTENSION, lastExtension = this.extension;
    private double lastAngle, angle;
    private double angularVelocity = 0.0;
    private double touchingFriction = 1.0;
    private boolean liftedUp = false;

    private final Vector3d queuedForcePos = new Vector3d();
    private final Vector3d queuedForce = new Vector3d();
    private final ForceTotal forceTotal = new ForceTotal();

    public TrailingWheelMountBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.trailing_wheel_mount_entity.get(), pos, state);
    }

    // ── 客户端动画 / 服务端静态 tick ──

    public static void tick(Level level, BlockPos pos, BlockState state, TrailingWheelMountBlockEntity be) {
        if (!level.isClientSide) {
            return; // 服务端物理由 Sable 的 sable$physicsTick 驱动，无需 vanilla tick
        }
        be.tickClientAnimation();
    }

    /**
     * 客户端视觉状态（照 offroad WheelMountBlockEntity.tick 客户端分支）：
     * <ul>
     * <li>{@code extension}：射线找地 → 悬挂行程，供渲染器画 tele/弹簧伸缩；</li>
     * <li>{@code angle}：贴地时由车身平移速度推导滚动角（从动——被推着滚），
     *     离地/无物理体时自然停转（无动力输入，不做 offroad 的 rpm 驱动）。</li>
     * </ul>
     */
    private void tickClientAnimation() {
        final ItemStack item = this.getHeldItem();
        final TireLike tire = item.get(OffroadDataComponents.TIRE);

        if (tire == null) {
            this.angle = 0.0;
            this.lastAngle = 0.0;
            this.lastExtension = this.extension;
            this.extension = Mth.lerp(0.6, this.extension, NO_WHEEL_EXTENSION);
            return;
        }

        final float radius = tire.radius();
        final SubLevel subLevel = Sable.HELPER.getContaining(this);

        this.lastExtension = this.extension;
        this.extension = Mth.lerp(0.7, this.extension, this.computeMaxExtension(radius));

        if (subLevel == null || this.liftedUp) {
            // 从动轮：离地无动力 → 轮子停转（保留 offroad 的分支结构，转速项恒 0）
            this.angularVelocity = Mth.lerp(0.2, this.angularVelocity, 0.0);
            this.lastAngle = this.angle;
            this.angle += this.angularVelocity;
            return;
        }

        final Direction facing = this.getBlockState().getValue(TrailingWheelMountBlock.HORIZONTAL_FACING);
        final Vector3d velocity = Sable.HELPER.getVelocity(this.level, JOMLConversion.atCenterOf(this.getBlockPos().relative(facing)));
        final Vector3d localVelocity = subLevel.logicalPose().transformNormalInverse(velocity).div(20.0);
        final Direction.Axis axis = facing.getAxis();

        Vec3i normal = Direction.get(Direction.AxisDirection.POSITIVE, axis).getNormal();
        normal = new Vec3i(normal.getZ(), 0, normal.getX());
        final Vector3dc normalD = this.getRollDirection(normal);

        final double translation = localVelocity.dot(normalD);

        // 贴地：轮子滚动角度 = 车身平移 / 周长（从动轮视觉，不打滑）
        final double circumference = Math.PI * radius * 2.0;
        final double angularDelta = -translation / circumference * Math.PI * 2.0;

        this.lastAngle = this.angle;
        this.angle += angularDelta;
        this.angularVelocity = angularDelta;
    }

    // ── 悬挂行程（射线找地） ──

    private double computeMaxExtension(final float radius) {
        final SubLevel subLevel = Sable.HELPER.getContaining(this);

        if (subLevel == null) {
            return MAX_ALLOWED_EXTENSION;
        }

        final Direction facing = this.getBlockState().getValue(TrailingWheelMountBlock.HORIZONTAL_FACING);
        final Pose3dc pose = subLevel.logicalPose();

        final Direction.Axis axis = facing.getAxis();
        Vec3i normal = Direction.get(Direction.AxisDirection.POSITIVE, axis).getNormal();
        normal = new Vec3i(normal.getZ(), 0, normal.getX());
        final Vector3dc rotatedAxis = this.getRollDirection(normal);

        final TerrainCastResult extensionToTerrain = this.computeMaxExtensionToTerrain(rotatedAxis, pose);
        final double unclampedExtension = extensionToTerrain.maxExtension() - radius;

        this.liftedUp = unclampedExtension > MAX_ALLOWED_EXTENSION;
        if (extensionToTerrain.minInteractingBlock() == null) {
            this.touchingFriction = 1.0;
        } else {
            this.touchingFriction = fudgeFriction(PhysicsBlockPropertyHelper.getFriction(this.level.getBlockState(extensionToTerrain.minInteractingBlock())));
        }

        return Mth.clamp(unclampedExtension, -0.45, MAX_ALLOWED_EXTENSION);
    }

    /** 给滚动方向向量一点基础摩擦下限（照抄 offroad，避免完全无摩擦时表现怪异） */
    public static double fudgeFriction(final double realValue) {
        if (realValue < 1) {
            return 0.1 + 0.9 * realValue;
        }
        return realValue;
    }

    private record TerrainCastResult(double maxExtension, @NotNull Direction normal,
                                     @Nullable SubLevel subLevel, @Nullable BlockPos minInteractingBlock) {}

    private TerrainCastResult computeMaxExtensionToTerrain(final Vector3dc normalD, final Pose3dc pose) {
        final Direction facing = this.getBlockState().getValue(TrailingWheelMountBlock.HORIZONTAL_FACING);
        final Vec3 wheelPosCenter = this.getBlockPos().relative(facing).getCenter();
        double minExtension = 5.0;
        Direction minNormal = Direction.UP;
        SubLevel minHitSubLevel = null;
        BlockPos minInteractingBlock = null;

        for (int i = -1; i <= 1; i++) {
            final Vec3 localPosO = wheelPosCenter.add(JOMLConversion.toMojang(normalD).scale(i));

            final ClipContext clipContext = new ClipContext(localPosO, localPosO.subtract(0.0, 5.0, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
            ((ClipContextExtension) clipContext).sable$setIgnoredSubLevel(Sable.HELPER.getContaining(this));
            final BlockHitResult clipResult = this.level.clip(clipContext);

            if (clipResult.getType() == HitResult.Type.MISS) {
                continue;
            }

            final SubLevel hitSubLevel = Sable.HELPER.getContaining(this.level, clipResult.getLocation());
            final Vec3 localHitPos = pose.transformPositionInverse(hitSubLevel == null ? clipResult.getLocation() : hitSubLevel.logicalPose().transformPosition(clipResult.getLocation()));

            if (localHitPos.y > wheelPosCenter.y) {
                continue;
            }

            if (localPosO.distanceTo(localHitPos) < 0.05) {
                continue;
            }

            final double dist = wheelPosCenter.y - localHitPos.y;

            if (dist <= 1e-5) {
                continue;
            }

            final Direction dir = clipResult.getDirection();
            final Vector3d hitNormal = new Vector3d(dir.getStepX(), dir.getStepY(), dir.getStepZ());

            if (hitSubLevel != null) {
                hitSubLevel.logicalPose().transformNormal(hitNormal);
            }
            pose.transformNormalInverse(hitNormal);

            if (hitNormal.dot(0.0, 1.0, 0.0) < 0.5) {
                continue;
            }

            minExtension = Math.min(minExtension, dist);
            minNormal = clipResult.getDirection();
            minHitSubLevel = hitSubLevel;
            minInteractingBlock = clipResult.getBlockPos();
        }

        return new TerrainCastResult(minExtension, minNormal, minHitSubLevel, minInteractingBlock);
    }

    /** 滚动方向（水平、与 facing 轴垂直）。offroad 此处还会做转向 yaw 旋转；无转向 → 恒等 */
    private @NotNull Vector3dc getRollDirection(final Vec3i normal) {
        return new Vector3d(normal.getX(), normal.getY(), normal.getZ());
    }

    // ── Sable 物理：弹簧支撑（服务端权威，从动） ──

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        final ItemStack item = this.getHeldItem();
        final TireLike tire = item.get(OffroadDataComponents.TIRE);
        final BlockPos blockPos = this.getBlockPos();

        if (tire == null) {
            return;
        }

        final float radius = tire.radius();
        final double suspensionRestDistance = MAX_ALLOWED_EXTENSION;

        final Direction facing = this.getBlockState().getValue(TrailingWheelMountBlock.HORIZONTAL_FACING);
        final Vec3 localPos = blockPos.relative(facing).getCenter();
        this.queuedForcePos.set(localPos.x, localPos.y, localPos.z);
        final double normalMass = 1.0 / subLevel.getMassTracker().getInverseNormalMass(this.queuedForcePos, OrientedBoundingBox3d.UP);

        // 刚度用常量（offroad 由滚轮 ScrollValueBehaviour 提供，首版固定 10）
        final double effectiveStrength = SUSPENSION_STRENGTH;
        final double normalMassScaling = Math.min(normalMass / effectiveStrength, 1.0) * 10.0;

        final double strengthMul = effectiveStrength * normalMassScaling * 2;
        final double springStrength = effectiveStrength * normalMassScaling * 40;
        final double dampingStrength = effectiveStrength * normalMassScaling;

        final Pose3d pose = subLevel.logicalPose();

        final Direction.Axis axis = facing.getAxis();
        Vec3i normal = Direction.get(Direction.AxisDirection.POSITIVE, axis).getNormal();
        final Vector3dc sideD = this.getRollDirection(normal);
        normal = new Vec3i(normal.getZ(), 0, normal.getX());
        final Vector3dc normalD = this.getRollDirection(normal);

        final TerrainCastResult extensionToTerrain = this.computeMaxExtensionToTerrain(normalD, pose);
        final double maxExtension = extensionToTerrain.maxExtension();

        this.extension = Mth.lerp(1.0, this.extension, maxExtension);

        if (maxExtension > suspensionRestDistance + radius + 0.25) {
            this.extension = suspensionRestDistance;
            return;
        }

        final double distance = (suspensionRestDistance / 6.0) + this.extension;
        final double springLength = Mth.clamp(distance - radius, 0.0, suspensionRestDistance);

        final Vector3d velocity = Sable.HELPER.getVelocity(this.level, JOMLConversion.toJOML(localPos));
        final Vector3d localVelocity = pose.transformNormalInverse(velocity);

        final double dampingForce = -localVelocity.y * dampingStrength;
        final double springForce = ((suspensionRestDistance - springLength) * springStrength + dampingForce) * timeStep;

        final Vec3i rayHitNormal = extensionToTerrain.normal().getNormal();

        Vec3 localForce = new Vec3(springForce * rayHitNormal.getX(), springForce * rayHitNormal.getY(), springForce * rayHitNormal.getZ());
        if (extensionToTerrain.subLevel() != null) {
            localForce = extensionToTerrain.subLevel().logicalPose().transformNormal(localForce);
        }
        localForce = pose.transformNormalInverse(localForce);

        this.queuedForce.set(localForce.x, localForce.y, localForce.z);

        // 阻尼 + 从动摩擦：不做驱动（offroad 此处用 Create getSpeed() 推车——已删），
        // 保留沿滚动方向的微小滚动阻力与横向侧滑摩擦，避免车体横滑/无限滑行
        {
            if (extensionToTerrain.minInteractingBlock() != null) {
                this.touchingFriction = fudgeFriction(PhysicsBlockPropertyHelper.getFriction(this.level.getBlockState(extensionToTerrain.minInteractingBlock())));
            } else {
                this.touchingFriction = 1.0;
            }
            this.touchingFriction = Math.max(this.touchingFriction, tire.minimumFriction());

            // 从动轮滚动阻力（offroad 的 brakeStrength 红石输入置 0，仅保留基础滚动摩擦项）
            final double surfaceBraking = Math.min(this.touchingFriction, 1.0);
            final double rollingFrictionStrength = 0.075 * surfaceBraking;
            this.queuedForce.fma(localVelocity.dot(normalD) * -rollingFrictionStrength * strengthMul * timeStep, normalD);

            // 侧滑摩擦（横向防滑，保留）
            this.queuedForce.fma(localVelocity.dot(sideD) * -0.6 * this.touchingFriction * strengthMul * timeStep, sideD);
        }

        this.forceTotal.applyImpulseAtPoint(subLevel, this.queuedForcePos, this.queuedForce);
        queuedWheelMounts.add(this);
    }

    // ── Sable 物理 tick 批处理（照 offroad OffroadCommonEvents） ──

    /** Sable 物理 tick 事件入口：每个物理 substep 后把排队轮子的冲量统一施加（由 CCPeripheralExtender 注册） */
    public static void onPhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        for (final TrailingWheelMountBlockEntity blockEntity : queuedWheelMounts) {
            if (blockEntity.isRemoved()) {
                continue;
            }
            blockEntity.applyBatchedForces();
        }
        queuedWheelMounts.clear();
    }

    private void applyBatchedForces() {
        final SubLevel subLevel = Sable.HELPER.getContaining(this);

        if (subLevel == null) {
            return;
        }

        final RigidBodyHandle handle = RigidBodyHandle.of((ServerSubLevel) subLevel);
        if (handle != null) {
            handle.applyForcesAndReset(this.forceTotal);
        }
    }

    // ── 轮胎槽（单槽，照 offroad WheelMountInventory 语义） ──

    public ItemStack getHeldItem() {
        return this.heldItem;
    }

    /** 服务端设置轮胎；同步到客户端并标记保存 */
    public void setHeldItem(final ItemStack stack) {
        this.heldItem = stack.copy();
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 2);
        }
    }

    @Override
    public void clearContent() {
        this.setHeldItem(ItemStack.EMPTY);
    }

    // ── NBT / 网络同步（MonitorBlockEntity 模式） ──

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("CurrentStack", this.heldItem.saveOptional(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.heldItem = ItemStack.parseOptional(registries, tag.getCompound("CurrentStack"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("CurrentStack", this.heldItem.saveOptional(registries));
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（默认返回 null 会导致快照不同步）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            loadAdditional(tag, registries);
        }
    }

    // ── 渲染访问器 ──

    /** 渲染用的悬挂行程（partialTick 插值） */
    public float getLerpedExtension(final float partialTick) {
        return (float) Mth.lerp(partialTick, this.lastExtension, this.extension);
    }

    /** 渲染用的轮子滚动角（partialTick 插值） */
    public float getLerpedAngle(final float partialTicks) {
        return (float) Mth.lerp(partialTicks, this.lastAngle, this.angle);
    }
}
