package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import static java.lang.Math.*;

/**
 * 航空集成计算机（AIC）方块实体：罗盘的重力摆动画模拟，照抄 {@link InsBlockEntity}
 * （simulated:gimbal_sensor 同款：重力扭矩永远水平 + 指北扭矩 + 外壳角速度甩动 +
 * 惯性/阻尼/限位碰撞）。
 * <p>
 * 与 INS 的差异：
 * <ul>
 * <li><b>无 BodySensorRegistry 注册</b>（AIC 暂不接传感器，服务端无逻辑，tick 只跑客户端）；</li>
 * <li><b>base 四元数 = blockstate FACING 旋转</b>（{@link #getBaseQuaternion()}，
 *     与 blockstate JSON 变体旋转一致），罗盘跟随机身 6 向朝向；</li>
 * <li>渲染时罗盘先平移到局部位置 {@link AicBlock#COMPASS_POS} 再绕该点旋转。
 *     模拟的逆变换链（重力/指北/外壳角速度）同步补上 base 逆（{@link #transformBaseInverse}）。</li>
 * </ul>
 */
public class AicBlockEntity extends BlockEntity {

    /** 物理体姿态缺省值：单位姿态（不在物理体上时罗盘保持世界水平） */
    private static final Pose3dc IDENTITY_POSE = new Pose3d(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d(1.0));
    /** 主/副轴限位角（与 INS 一致，固定 90°） */
    private static final int PRIMARY_LIMIT_DEG = 90;
    private static final int SECONDARY_LIMIT_DEG = 90;
    private static final double PRIMARY_LIMIT = Math.toRadians(PRIMARY_LIMIT_DEG);
    private static final double SECONDARY_LIMIT = Math.toRadians(SECONDARY_LIMIT_DEG);

    // ── 客户端动画状态（照抄 gimbal_sensor） ──
    private final Vector3d previousAngles = new Vector3d(0, 0, 0);
    private final Vector3d angleInertia = new Vector3d(110, 110, 34);
    private final Vector3d angleDamping = new Vector3d(0.2, 0.2, 0.2);
    /** ponder 里可关掉模拟（本 mod 无 ponder，恒 true） */
    public boolean updateVisualRotation = true;
    private final CompassTarget compassTarget = new CompassTarget();
    private Vector3d eulerAngles = new Vector3d(0, 0, 0);
    private Vector3d angleVelocities = new Vector3d(0, 0, 0);
    private Quaterniond lastShellOrientation = null;

    public AicBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.aic_entity.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            this.randomNudge();
        }
    }

    /** 只跑客户端动画；服务端无逻辑（AIC 暂不接传感器） */
    public static void tick(Level level, BlockPos pos, BlockState state, AicBlockEntity be) {
        if (level.isClientSide) {
            be.tickClient();
        }
    }

    private void tickClient() {
        final SubLevel subLevel = SableCompat.getContainingSubLevel(this);
        final Pose3dc pose = subLevel != null ? subLevel.logicalPose() : IDENTITY_POSE;
        this.animateClientRotation(subLevel, pose);
    }

    /** 扳手/空手右键扰动：给角速度随机初值；偏航（z）不瞬移也不归零，只给小幅随机角速度（同 INS 修正） */
    public void randomNudge() {
        final Vec3 v = VecHelper.offsetRandomly(new Vec3(0, 0, 0), this.level.random, 0.2f);
        this.angleVelocities.set(v.x, v.y, v.z);
        this.angleVelocities.z = (this.level.random.nextFloat() * 2 - 1) * 0.15;
        this.eulerAngles.x = 0;
        this.eulerAngles.y = 0;
    }

    // ═══════════════ 客户端重力摆模拟（照抄 gimbal_sensor） ═══════════════

    void animateClientRotation(final SubLevel subLevel, final Pose3dc pose) {

        this.previousAngles.set(this.eulerAngles);

        final Vector3d shellVelocity = this.getShellVelocity(subLevel);

        final Vector3d acceleration = new Vector3d();
        if (this.updateVisualRotation) this.addGravityTorque(pose, acceleration);

        final Vec3 globalPosition = pose.transformPosition(Vec3.atCenterOf(this.getBlockPos()));
        this.compassTarget.update(globalPosition, this.level);
        final Vector3d target = new Vector3d();
        this.compassTarget.getTarget(target);

        this.addCompassTorque(pose, acceleration, target);
        if (this.compassTarget.isRandom())
            acceleration.z += (2 * this.level.random.nextFloat() - 1) * 2.1;

        acceleration.div(this.angleInertia);
        final Vector3d relativeVelocity = this.angleVelocities.add(shellVelocity, new Vector3d());
        final Vector3d currentDamping = relativeVelocity.mul(this.angleDamping);
        this.angleVelocities.add(acceleration).sub(currentDamping);

        final Vector3d totalVelocity = this.angleVelocities.add(shellVelocity, new Vector3d());
        this.eulerAngles.add(totalVelocity);

        this.collide(this.eulerAngles, totalVelocity, 1, PRIMARY_LIMIT);
        this.collide(this.eulerAngles, totalVelocity, 0, SECONDARY_LIMIT);
        totalVelocity.sub(shellVelocity, this.angleVelocities);
    }

    /**
     * 重力扭矩：世界重力 → 方块局部系（物理体姿态逆 + blockstate facing 逆）→
     * 逆 Y（yaw）→ 逆 Z（roll）到罗盘系，与罗盘"下"方向叉积。
     */
    void addGravityTorque(final Pose3dc pose, final Vector3d torque) {
        final Vec3 worldPos = SableCompat.projectOutOfSubLevel(this.level, this.getBlockPos());
        final Vector3d localGravity = worldPos != null
                ? new Vector3d(DimensionPhysicsData.getGravity(this.level, new Vector3d(worldPos.x, worldPos.y, worldPos.z)))
                : new Vector3d(0, -1, 0); // 兜底：世界重力向下
        this.transformBaseInverse(localGravity, pose);

        // 层级（外→内）：yaw(Y) → roll(Z) → pitch(X)，重力变换到 gimbal 系：先逆 Y 再逆 Z
        this.transformCompassInverse(localGravity);
        this.transformPrimaryInverse(localGravity);

        final Vector3d localDown = new Vector3d(0, -1, 0).rotateX(this.eulerAngles.y);
        final Vector3d localTorque = localDown.cross(localGravity);

        torque.x += localTorque.z;
        torque.y += localTorque.x;
    }

    /** 偏航标记指北扭矩（驱动 z 轴）：目标变换到 yaw(Y) 最外层系 */
    void addCompassTorque(final Pose3dc pose, final Vector3d torque, final Vector3d target) {
        this.transformBaseInverse(target, pose);
        this.transformCompassInverse(target);

        final Vector3d localTorque = new Vector3d(0, 0, -1).cross(target);
        torque.z += localTorque.y;
    }

    /** 外壳角速度：上一帧与当前帧姿态差旋转 → 按层级分解到各层角速度，让罗盘随物理体翻转"甩动" */
    private Vector3d getShellVelocity(final SubLevel subLevel) {
        final Vector3d shellVelocity = new Vector3d();
        if (subLevel != null) {
            final Pose3d pose = subLevel.logicalPose();

            if (this.lastShellOrientation == null) {
                this.lastShellOrientation = new Quaterniond(pose.orientation());
            } else {
                final Quaterniond rotationDiff = this.lastShellOrientation.div(pose.orientation(), new Quaterniond());
                final Vector3d angularVelocity = new Vector3d(rotationDiff.x, rotationDiff.y, rotationDiff.z).mul(2);
                this.transformBaseInverse(angularVelocity, pose);
                shellVelocity.z = angularVelocity.y;      // yaw（Y）层：base 系 Y 分量
                this.transformCompassInverse(angularVelocity);
                shellVelocity.x = angularVelocity.z;      // roll（Z）层：yaw 系 Z 分量
                this.transformPrimaryInverse(angularVelocity);
                shellVelocity.y = angularVelocity.x;      // pitch（X）层：roll 系 X 分量
                this.lastShellOrientation.set(pose.orientation());
            }
        } else
            this.lastShellOrientation = null;
        return shellVelocity;
    }

    /** 限位碰撞：角度钳制到 ±limit，越界速度反弹 ×-0.9 */
    private void collide(final Vector3d position, final Vector3d velocity, final int index, final double limit) {
        double p = position.get(index);
        double v = velocity.get(index);
        final double m = p > 0 ? 1 : -1;
        p *= m;
        v *= m;
        if (p >= limit) {
            p = limit;
            if (v > 0)
                v *= -0.9;
        }
        position.setComponent(index, p * m);
        velocity.setComponent(index, v * m);
    }

    // ═══════════════ 渲染变换 ═══════════════

    /**
     * 基座四元数 = blockstate FACING 旋转，与 blockstate JSON 变体旋转一致
     * （原版 BlockModelRotation：{@code rotateYXZ(-y·rad, -x·rad, 0)} = Ry(−θy)·Rx(−θx)）：
     * up 单位 / down x180 / north x90 / east x90+y90 / south x90+y180 / west x90+y270。
     */
    public Quaternionf getBaseQuaternion() {
        final Direction facing = getBlockState().getValue(AicBlock.FACING);
        final float y = switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        final float x = switch (facing) {
            case DOWN -> 180;
            case NORTH, SOUTH, EAST, WEST -> 90;
            default -> 0;
        };
        return new Quaternionf().rotateYXZ(-(float) Math.toRadians(y), -(float) Math.toRadians(x), 0);
    }

    /** 滚转（绕局部 Z，eulerAngles.x） */
    public Quaternionf applyPrimaryQuaternion(final Quaternionf Q, final float partialTick) {
        Q.rotateZ(this.lerp((float) this.previousAngles.x, (float) this.eulerAngles.x, partialTick));
        return Q;
    }

    /** 俯仰（绕局部 X，eulerAngles.y） */
    public Quaternionf applySecondaryQuaternion(final Quaternionf Q, final float partialTick) {
        Q.rotateX(this.lerp((float) this.previousAngles.y, (float) this.eulerAngles.y, partialTick));
        return Q;
    }

    /** 偏航（绕局部 Y，eulerAngles.z） */
    public Quaternionf applyCompassQuaternion(final Quaternionf Q, final float partialTick) {
        Q.rotateY(this.lerp((float) this.previousAngles.z, (float) this.eulerAngles.z, partialTick));
        return Q;
    }

    /**
     * 世界向量 → 方块模型局部系：先物理体姿态逆，再 blockstate facing 逆
     * （渲染链 base 在最外层，模拟的逆变换链必须同步补上 base 逆，与 INS 同约束）。
     */
    private Vector3d transformBaseInverse(final Vector3d v, final Pose3dc ctx) {
        ctx.orientation().transformInverse(v);
        v.rotate(new Quaterniond(this.getBaseQuaternion()).conjugate());
        return v;
    }

    /** 逆 roll（绕 Z，中间层） */
    private Vector3d transformPrimaryInverse(final Vector3d v) {
        v.rotateZ(-this.eulerAngles.x);
        return v;
    }

    /** 逆 yaw（绕 Y，最外层） */
    private Vector3d transformCompassInverse(final Vector3d v) {
        v.rotateY(-this.eulerAngles.z);
        return v;
    }

    float lerp(final float a, final float b, final float t) {
        return a * (1 - t) + b * t;
    }

    // ═══════════════ 罗盘指针目标（照抄 gimbal_sensor：自然维度指北，非自然维度随机） ═══════════════

    static class CompassTarget {
        private final Vector3d target = new Vector3d(0, 0, 0);
        private final Vector3d randomTarget = new Vector3d(0, 0, 0);
        private int randomTargetTimer = 0;
        private double randomTargetLength = 3;
        private boolean isRandom = false;

        public void update(final Vec3 pos, final Level level) {
            this.isRandom = !level.dimensionType().natural();
            if (!this.isRandom) {
                this.target.set(0, 0, -1);
            } else {
                final RandomSource r = level.random;
                if (this.randomTargetTimer-- < 0) {

                    final float radius = 1.0f;
                    this.randomTarget.set(
                            (r.nextFloat() - .5f) * 2 * radius,
                            (r.nextFloat() - .5f) * 2 * radius,
                            (r.nextFloat() - .5f) * 2 * radius);
                    this.randomTargetTimer = level.random.nextInt(5, 15);
                }
                final float nudge = 0.3f;
                this.randomTarget.add(
                        (r.nextFloat() - .5f) * 2 * nudge,
                        (r.nextFloat() - .5f) * 2 * nudge,
                        (r.nextFloat() - .5f) * 2 * nudge);
                this.randomTarget.normalize();
                final double step = 0.5;
                this.target.mul(1 - step).fma(step, this.randomTarget);
                this.target.normalize();
            }

        }

        public boolean isRandom() {
            return this.isRandom;
        }

        public void setRandomTargetLength(final double s) {
            this.randomTargetLength = s;
        }

        public Vector3d getTarget(final Vector3d v) {
            return this.target.mul(this.isRandom() ? this.randomTargetLength : 1, v);
        }
    }
}
