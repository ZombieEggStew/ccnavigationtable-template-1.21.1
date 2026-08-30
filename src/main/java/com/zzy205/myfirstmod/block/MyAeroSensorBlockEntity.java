package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
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
 * 惯性导航系统方块实体：动画与角度计算照抄 {@code simulated:gimbal_sensor}
 * （GimbalSensorBlockEntity）。
 * <p>
 * <b>永远水平</b>的视觉来自客户端每 tick 的重力摆模拟
 * （{@link #animateClientRotation}）：把真实重力方向变换到罗盘局部系后与罗盘
 * "下"方向叉积得到扭矩，经惯性/阻尼/限位积分出欧拉角——罗盘被重力"托"着
 * 始终水平，物理体翻滚时有惯性摆动感。渲染时用 previous→current 插值平滑。
 * <p>
 * 服务端每 tick 由物理体姿态计算真实倾角 {@link #XAngle}/{@link #ZAngle}
 * （保留 getter，供未来读取）。
 * <p>
 * 与原版的差异（本 mod 简化）：
 * <ul>
 * <li>无 ScrollValueBehaviour 滚轮调节——限位角固定 {@value #PRIMARY_LIMIT_DEG}°；</li>
 * <li>无红石输出（gimbal_sensor 的四向 0–15 倾角信号）；</li>
 * <li>无 blockstate 旋转——base 恒为单位四元数，模型保持默认朝向；</li>
 * <li>部件层级（外→内）为 test(Y 偏航) → gimbal(Z 滚转) → compass(X 俯仰)，
 *     与 gimbal_sensor 的 needle(Y) 最内不同；z 轴（偏航）模拟由 test 标记使用。</li>
 * </ul>
 */
public class MyAeroSensorBlockEntity extends BlockEntity {

    /** 物理体姿态缺省值：单位姿态（不在物理体上时罗盘保持世界水平） */
    private static final Pose3dc IDENTITY_POSE = new Pose3d(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d(1.0));
    /** 主/副轴限位角（原版由滚轮 ScrollValueBehaviour 在 ±90° 内调节，本 mod 固定 90°） */
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

    /** 服务端真实倾角（供未来 Lua API 读取） */
    private double ZAngle;
    private double XAngle;

    public MyAeroSensorBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.my_aero_sensor_entity.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            this.randomNudge();
        }
    }

    /** 客户端跑动画模拟，服务端算倾角（替代 gimbal_sensor 的 SmartBlockEntity.tick） */
    public static void tick(Level level, BlockPos pos, BlockState state, MyAeroSensorBlockEntity be) {
        if (level.isClientSide) {
            be.tickClient();
        } else {
            be.tickServer();
        }
    }

    private void tickClient() {
        final SubLevel subLevel = SableCompat.getContainingSubLevel(this);
        final Pose3dc pose = subLevel != null ? subLevel.logicalPose() : IDENTITY_POSE;
        this.animateClientRotation(subLevel, pose);
    }

    private void tickServer() {
        final SubLevel subLevel = SableCompat.getContainingSubLevel(this);
        if (subLevel == null)
            return;

        final Vector3d ld = JOMLConversion.toJOML(Vec3.atLowerCornerOf(Direction.DOWN.getNormal()));
        subLevel.logicalPose().orientation().transformInverse(ld);

        // 世界"下"方向在方块局部系的投影 → 两个倾角
        this.XAngle = ld.y() < 0 || ld.z() * ld.z() > 0.001 ? atan2(ld.z(), -ld.y()) : 0;
        this.ZAngle = ld.y() < 0 || ld.x() * ld.x() > 0.001 ? atan2(ld.x(), -ld.y()) : 0;
    }

    /** 扳手扰动：给角速度一个随机初值 + 罗盘随机初始朝向 */
    public void randomNudge() {
        final Vec3 v = VecHelper.offsetRandomly(new Vec3(0, 0, 0), this.level.random, 0.2f);
        this.angleVelocities.set(v.x, v.y, v.z);
        this.eulerAngles.set(0, 0, this.level.random.nextFloat() * Math.PI * 2);
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

    /** 重力扭矩：世界重力 → 方块局部系 → test(Y) → gimbal(Z) 系，与罗盘"下"方向叉积 */
    void addGravityTorque(final Pose3dc pose, final Vector3d torque) {
        final Vec3 worldPos = SableCompat.projectOutOfSubLevel(this.level, this.getBlockPos());
        final Vector3d localGravity = worldPos != null
                ? new Vector3d(DimensionPhysicsData.getGravity(this.level, new Vector3d(worldPos.x, worldPos.y, worldPos.z)))
                : new Vector3d(0, -1, 0); // 兜底：世界重力向下
        this.transformBaseInverse(localGravity, pose);

        // 层级（外→内）：test(Y) → gimbal(Z) → compass(X)，重力变换到 gimbal 系：先逆 Y（test）再逆 Z（gimbal）
        this.transformCompassInverse(localGravity);//from base to test ring (inverse yaw)
        this.transformPrimaryInverse(localGravity);//from test ring to gimbal ring (inverse roll)

        // the down direction of the compass, relative to the gimbal ring
        //（compass 相对 gimbal 只绕 X，compass 局部 X = gimbal 局部 X）
        final Vector3d localDown = new Vector3d(0, -1, 0).rotateX(this.eulerAngles.y);
        final Vector3d localTorque = localDown.cross(localGravity);

        torque.x += localTorque.z;
        torque.y += localTorque.x;
    }

    /** 偏航标记指北扭矩（驱动 z 轴）：目标变换到 test(Y) 最外层系 */
    void addCompassTorque(final Pose3dc pose, final Vector3d torque, final Vector3d target) {
        this.transformBaseInverse(target, pose);
        this.transformCompassInverse(target);//from base to test ring (inverse yaw)

        final Vector3d localTorque = new Vector3d(0, 0, -1).cross(target);
        torque.z += localTorque.y;
    }

    /** 外壳角速度：上一帧与当前帧姿态差旋转 → 按层级分解到各层角速度，让部件随物理体翻转"甩动" */
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
                shellVelocity.z = angularVelocity.y;      // test（Y）层：base 系 Y 分量
                this.transformCompassInverse(angularVelocity);//→ test 系
                shellVelocity.x = angularVelocity.z;      // gimbal（Z）层：test 系 Z 分量（gimbal Z 轴 = test 局部 Z）
                this.transformPrimaryInverse(angularVelocity);//→ gimbal 系
                shellVelocity.y = angularVelocity.x;      // compass（X）层：gimbal 系 X 分量（compass X 轴 = gimbal 局部 X）
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

    // ═══════════════ 渲染变换（照抄 gimbal_sensor） ═══════════════

    /** 基座四元数：无 blockstate 旋转，恒为单位四元数（模型保持默认朝向） */
    public Quaternionf getBaseQuaternion() {
        return new Quaternionf();
    }

    /** 万向环：base + 绕 Z（滚转） */
    public Quaternionf applyPrimaryQuaternion(final Quaternionf Q, final float partialTick) {
        Q.rotateZ(this.lerp((float) this.previousAngles.x, (float) this.eulerAngles.x, partialTick));
        return Q;
    }

    /** 罗盘盘：base + 绕 Z + 绕 X（俯仰） */
    public Quaternionf applySecondaryQuaternion(final Quaternionf Q, final float partialTick) {
        Q.rotateX(this.lerp((float) this.previousAngles.y, (float) this.eulerAngles.y, partialTick));
        return Q;
    }

    /** 指针：+ 绕 Y（当前未渲染 needle，保留以备启用） */
    public Quaternionf applyCompassQuaternion(final Quaternionf Q, final float partialTick) {
        Q.rotateY(this.lerp((float) this.previousAngles.z, (float) this.eulerAngles.z, partialTick));
        return Q;
    }

    /** 世界向量 → 方块局部系（无 base Y 旋转：方块不区分朝向） */
    private Vector3d transformBaseInverse(final Vector3d v, final Pose3dc ctx) {
        ctx.orientation().transformInverse(v);
        return v;
    }

    /** 逆 gimbal 滚转（绕 Z，中间层） */
    private Vector3d transformPrimaryInverse(final Vector3d v) {
        v.rotateZ(-this.eulerAngles.x);
        return v;
    }

    /** 逆 compass 俯仰（绕 X，最里层） */
    private Vector3d transformSecondaryInverse(final Vector3d v) {
        v.rotateX(-this.eulerAngles.y);
        return v;
    }

    /** 逆 test 偏航（绕 Y，最外层） */
    private Vector3d transformCompassInverse(final Vector3d v) {
        v.rotateY(-this.eulerAngles.z);
        return v;
    }

    float lerp(final float a, final float b, final float t) {
        return a * (1 - t) + b * t;
    }

    public double getZAngle() {
        return this.ZAngle;
    }

    public double getXAngle() {
        return this.XAngle;
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
