package com.zzy205.myfirstmod.client.particle;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 排气粒子的**扫掠碰撞**：球体（半径见 ExhaustParticle）沿本 tick 位移扫过，返回最早命中点。
 * <p>
 * 与 {@code ThrustNozzleBlockEntity#clearDistance}（喷口那条一次性射线）分工不同：
 * 那条只管"喷口出膛时前方有没有东西"，这里管"粒子已经飞出去之后撞到什么"。
 * <p>
 * **出生点保护（grace）**：距离出生点 {@code graceRadius} 以内的命中全部忽略。
 * 早期版本是"整个喷管所在物理体都不算"，结果<b>车体/舱壁自己的方块也挡不住粒子</b>，
 * 一堆粒子直接穿到墙后面（用户实测反馈）。现在只保护喷口周围一小圈，车身其它结构照常挡。
 * <p>
 * 参考来源（只读）：
 * {@code references/create-propulsion-simulated-main/.../particles/{ParticleCollision,ParticleCollisionMath}.java}
 * —— 同样的"主世界逐方块扫掠 + 逐个物理体把射线反投回它自己的 plot 局部系扫掠"两段式。
 */
public final class ExhaustCollision {

    private ExhaustCollision() {
    }

    /**
     * @param fraction 命中发生在位移的哪一段（0~1）
     * @param movement 实际可走到的位移（= 原位移 × fraction）
     * @param normal   命中面法线（世界系，已归一化）
     * @param push     额外的**推出距离**（沿 {@code normal}，与 {@code normal} 同一坐标系）。
     *                 普通扫掠命中为 0；只有「起点已经嵌在膨胀盒内」那种情况才 &gt; 0，
     *                 见 {@link #slab} 的①分支与 {@code ExhaustParticle#move}
     */
    public record Hit(double fraction, Vec3 movement, Vec3 normal, double push) {
    }

    /**
     * @param radius             粒子碰撞半径（块）
     * @param graceCenter        出生点（世界系）：离它 {@code graceRadius} 以内的命中一律忽略
     * @param graceRadius        出生点保护半径（块）
     * @param collideOtherBodies 其它物理体是否参与碰撞
     */
    public static @Nullable Hit sweep(final Level level, final Vec3 start, final Vec3 movement,
                                      final double radius, @Nullable final Vec3 graceCenter,
                                      final double graceRadius, final boolean collideOtherBodies) {
        if (movement.lengthSqr() < 1.0E-8D) {
            return null;
        }

        // 把"离出生点多远"换算成"位移走到哪一段"：出生点保护以内一律不算命中
        final double moveLength = movement.length();
        final double distanceFromSpawn = graceCenter == null ? Double.MAX_VALUE : start.distanceTo(graceCenter);
        final double minFraction = graceCenter == null
                ? 0.0D
                : Mth.clamp((graceRadius - distanceFromSpawn) / moveLength, 0.0D, 1.0D);

        final Vec3 end = start.add(movement);
        final AABB swept = new AABB(start, end).inflate(radius);

        // ① 主世界（含本物理体自己的方块：它们在 level 里的 plot 区块坐标处，而这颗粒子已经是世界坐标，
        //    所以这里只会看到主世界）
        Hit best = scan(level, start, movement, radius, swept, minFraction);

        if (!collideOtherBodies) {
            return best;
        }

        // ② 其它物理体（包括喷管所在的物理体本身，只是出生点附近被 grace 挡掉）：
        //    把位移反投进它的 plot 局部系扫掠，命中法线再投回世界系
        for (final SubLevel body : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(swept))) {
            if (body.isRemoved()) {
                continue;
            }

            final Pose3dc pose = body.logicalPose();
            final double bodyScale = Math.max(0.01D, Math.abs(pose.scale().x()));
            final Vec3 localStart = pose.transformPositionInverse(start);
            final Vec3 localEnd = pose.transformPositionInverse(end);
            final Vec3 localMove = localEnd.subtract(localStart);

            if (localMove.lengthSqr() < 1.0E-8D) {
                continue;
            }

            final double localRadius = radius / bodyScale;
            final AABB localSwept = new AABB(localStart, localEnd).inflate(localRadius);
            final Hit localHit = scan(level, localStart, localMove, localRadius, localSwept, minFraction);

            if (localHit == null || (best != null && best.fraction() <= localHit.fraction())) {
                continue;
            }

            final Vec3 worldNormal = pose.transformNormal(localHit.normal()).normalize();
            // 推出距离是在该物理体的 plot 局部系里量的，换回世界系要乘 bodyScale（刚体变换保长度也保比例）
            best = new Hit(localHit.fraction(), movement.scale(localHit.fraction()), worldNormal,
                    localHit.push() * bodyScale);
        }

        return best;
    }

    /**
     * 在给定坐标系（主世界 / 某个物理体的 plot 局部系）里逐方块做球-箱扫掠。
     *
     * @param minFraction 命中比例低于它的忽略（出生点保护）
     */
    private static @Nullable Hit scan(final Level level, final Vec3 start, final Vec3 movement,
                                      final double radius, final AABB region, final double minFraction) {
        if (minFraction >= 1.0D) {
            return null;
        }

        final int minY = Math.max(level.getMinBuildHeight(), Mth.floor(region.minY));
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, Mth.floor(region.maxY));

        if (minY > maxY) {
            return null;
        }

        final int minX = Mth.floor(region.minX);
        final int maxX = Mth.floor(region.maxX);
        final int minZ = Mth.floor(region.minZ);
        final int maxZ = Mth.floor(region.maxZ);

        Hit best = null;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunkAt(pos.set(x, minY, z))) {
                    continue;
                }

                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    final BlockState state = level.getBlockState(pos);

                    if (state.isAir()) {
                        continue;
                    }

                    final VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());

                    if (shape.isEmpty()) {
                        continue;
                    }

                    for (final AABB box : shape.toAabbs()) {
                        // 注意：getCollisionShape 返回的 AABB 是**方块局部坐标**（0~1），
                        // 必须加上方块坐标才是世界/plot 坐标（这里直接把偏移传给 slab，不新建 AABB）。
                        // 漏了这一步 ⇒ 所有候选盒都堆在原点附近，粒子永远撞不到东西。
                        final Hit hit = slab(start, movement, box, radius,
                                pos.getX(), pos.getY(), pos.getZ());

                        if (hit == null || hit.fraction() < minFraction) {
                            continue;
                        }

                        // fraction 打平时（多个方块同时"嵌入"）取推出距离最小的那个，避免一次被推很远
                        if (best == null
                                || hit.fraction() < best.fraction()
                                || (hit.fraction() == best.fraction() && hit.push() < best.push())) {
                            best = hit;
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 球体（按半径膨胀 AABB）与一段位移的 slab 相交测试；命中返回进入点比例与面法线。
     *
     * @param box      方块局部坐标的碰撞盒（0~1）
     * @param ox/oy/oz 该方块的位置偏移（box + 偏移 = 当前坐标系里的实际碰撞盒）
     */
    private static @Nullable Hit slab(final Vec3 start, final Vec3 movement, final AABB box, final double radius,
                                      final int ox, final int oy, final int oz) {
        // 膨胀半径后的包围盒（球-箱扫掠的常见近似：把箱子三轴各膨胀 r，等价于"球 vs 箱"的立方体近似）
        final double[] min = {
                box.minX + ox - radius, box.minY + oy - radius, box.minZ + oz - radius};
        final double[] max = {
                box.maxX + ox + radius, box.maxY + oy + radius, box.maxZ + oz + radius};
        final double[] origin = {start.x, start.y, start.z};
        final double[] delta = {movement.x, movement.y, movement.z};

        // ① 起点已经嵌在膨胀盒里 ⇒ 必须**推出**，不能返回 null。
        //
        //    这是"喷口与平面成角度时粒子穿墙/漏到平面下方"的根因：
        //    起点在盒内时三轴 near 全是负数，而 entry 初值 0 只会被 near >= entry 更新
        //    ⇒ normalAxis 保持 -1 ⇒ 走到下面的"没撞到"分支。于是粒子一旦进入半径为厚的壳层
        //    就再也收不到任何碰撞响应，只有斜擦邻块侧面时才拿到 ±X/±Z 法线，而那种响应不会
        //    消掉朝里的速度 ⇒ 一路沉进方块内部。
        //
        //    最容易把粒子放进壳层的是"撞墙就变大"（IMPACT_GROWTH=2 让半径翻倍，而接触点仍停在
        //    旧半径的壳面上）；浅角度入射时法向速度只有几个百分点，也会让粒子贴着壳层滑不掉。
        //
        //    处理方式：返回 f=0 的接触（法线见 {@link #depenetrationNormal}：优先"从哪进来就从哪出去"），
        //    并把推出深度交给调用方（{@link Hit#push}）真正把粒子挪出壳层。
        boolean embedded = true;

        for (int axis = 0; axis < 3; axis++) {
            if (origin[axis] <= min[axis] || origin[axis] >= max[axis]) {
                embedded = false;
                break;
            }
        }

        if (embedded) {
            final Vec3 normal = depenetrationNormal(origin, min, max, delta);
            final int axis = normal.x != 0.0D ? 0 : normal.y != 0.0D ? 1 : 2;
            final double sign = axis == 0 ? normal.x : axis == 1 ? normal.y : normal.z;
            final double depth = sign > 0.0D ? max[axis] - origin[axis] : origin[axis] - min[axis];

            return new Hit(0.0D, Vec3.ZERO, normal, depth);
        }

        // ② 正常扫掠：取进入时间最晚的那个轴作为命中面（= 最先碰到的面）
        double entry = 0.0D;
        double exit = 1.0D;
        int normalAxis = -1;
        double normalSign = 0.0D;

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < 1.0E-12D) {
                if (origin[axis] <= min[axis] || origin[axis] >= max[axis]) {
                    return null; // 该轴上不移动且在板外 ⇒ 不相交
                }

                continue;
            }

            double near = (min[axis] - origin[axis]) / delta[axis];
            double far = (max[axis] - origin[axis]) / delta[axis];

            if (near > far) {
                final double swap = near;
                near = far;
                far = swap;
            }

            if (near >= entry) {
                entry = near;
                normalAxis = axis;
                normalSign = delta[axis] > 0.0D ? -1.0D : 1.0D;
            }

            exit = Math.min(exit, far);

            if (entry > exit) {
                return null;
            }
        }

        if (normalAxis < 0 || exit < 0.0D || entry > 1.0D) {
            return null;
        }

        return new Hit(entry, movement.scale(entry), axisNormal(normalAxis, normalSign), 0.0D);
    }

    /**
     * 选出"嵌进去之后该从哪一面推出来"。
     * <p>
     * <b>主判据只有一条：推出距离最小（最小平移向量，MTV）</b>——位移最小、最不可能穿到隔壁，
     * 也保证"刚嵌进去一点点"的粒子沿最近的面被弹回表面。
     * <p>
     * ⚠️ <b>不能把"法线与运动方向相反"当主判据</b>（踩过）：粒子撞完是<b>向上</b>弹的，
     * 那时"与运动相反的面"恰好是方块<b>底面</b>，推出深度约一整格 ⇒ 粒子被<b>往下推穿方块</b>，
     * 于是每一个角度都漏、撞一次往下跳一格，比修之前更糟。
     * <p>
     * 所以运动方向只用来做 <b>tie-break</b>：深度几乎相等（粒子卡在方块正中，前后对称）时，
     * 取"法线更偏向运动反侧"的那个面 —— 也就是它是从哪边进来的就往哪边出去，
     * 避免对称时被随机地往<b>下</b>推。
     */
    private static Vec3 depenetrationNormal(final double[] origin, final double[] min, final double[] max,
                                            final double[] delta) {
        final double tieEpsilon = 1.0E-6D;
        Vec3 best = null;
        double bestDepth = Double.MAX_VALUE;
        double bestAlong = Double.MAX_VALUE;

        for (int axis = 0; axis < 3; axis++) {
            for (int s = 0; s < 2; s++) {
                final double sign = s == 0 ? 1.0D : -1.0D;
                final double depth = sign > 0.0D ? max[axis] - origin[axis] : origin[axis] - min[axis];

                if (depth <= 0.0D) {
                    continue;
                }

                final Vec3 normal = axisNormal(axis, sign);
                // 法线朝外 ⇒ 与运动点乘 < 0 表示"这一面在运动的反侧"（更可能是进来的那一面）
                final double along = normal.x * delta[0] + normal.y * delta[1] + normal.z * delta[2];

                if (best == null || depth < bestDepth - tieEpsilon) {
                    best = normal;
                    bestDepth = depth;
                    bestAlong = along;
                } else if (depth <= bestDepth + tieEpsilon && along < bestAlong) {
                    // 深度打平：取更偏向"运动反侧"的面
                    best = normal;
                    bestDepth = Math.min(bestDepth, depth);
                    bestAlong = along;
                }
            }
        }

        return best != null ? best : new Vec3(0.0D, 1.0D, 0.0D);
    }

    /** 轴对齐面法线（{@code sign} 指向盒外） */
    private static Vec3 axisNormal(final int axis, final double sign) {
        return switch (axis) {
            case 0 -> new Vec3(sign, 0.0D, 0.0D);
            case 1 -> new Vec3(0.0D, sign, 0.0D);
            default -> new Vec3(0.0D, 0.0D, sign);
        };
    }
}
