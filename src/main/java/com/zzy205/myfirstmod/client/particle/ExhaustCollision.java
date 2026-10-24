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

import java.util.UUID;

/**
 * 排气粒子的**扫掠碰撞**：球体（半径 = 粒子包围盒半径）沿本 tick 位移扫过，返回最早命中点。
 * <p>
 * 与 {@code ThrustNozzleBlockEntity#clearDistance}（喷口那条一次性射线）分工不同：
 * 那条只管"喷口出膛时前方有没有东西"，这里管"粒子已经飞出去之后撞到什么"。
 * <p>
 * 参考来源（只读）：
 * {@code references/create-propulsion-simulated-main/.../particles/{ParticleCollision,ParticleCollisionMath}.java}
 * —— 同样的"主世界逐方块扫掠 + 逐个物理体把射线反投回它自己的 plot 局部系扫掠"两段式；
 * 这里去掉了参考实现里"用 plot 包围盒裁剪扫描范围"那一步（我们的扫描范围只有几个方块，
 * 不需要裁剪），并改用 {@link BlockPos#betweenClosed} 直接遍历。
 */
public final class ExhaustCollision {

    private ExhaustCollision() {
    }

    /**
     * @param fraction 命中发生在位移的哪一段（0~1）
     * @param movement 实际可走到的位移（= 原位移 × fraction）
     * @param normal   命中面法线（世界系）
     */
    public record Hit(double fraction, Vec3 movement, Vec3 normal) {
    }

    /**
     * @param radius              粒子碰撞半径（块）
     * @param ignoreBodyId        要忽略的物理体（喷管自己所在的那个，否则一出生就撞自己），null/零 UUID = 不忽略
     * @param collideOtherBodies  其它物理体是否参与碰撞
     */
    public static @Nullable Hit sweep(final Level level, final Vec3 start, final Vec3 movement,
                                      final double radius, @Nullable final UUID ignoreBodyId,
                                      final boolean collideOtherBodies) {
        if (movement.lengthSqr() < 1.0E-8D) {
            return null;
        }

        final Vec3 end = start.add(movement);
        final AABB swept = new AABB(start, end).inflate(radius);

        // ① 主世界（含本物理体自己的方块：它们在 level 里的 plot 区块坐标处，而这颗粒子已经是世界坐标，
        //    所以这里只会看到主世界）
        Hit best = scan(level, start, movement, radius, swept);

        if (!collideOtherBodies) {
            return best;
        }

        // ② 其它物理体：把位移反投进它的 plot 局部系扫掠，命中法线再投回世界系
        for (final SubLevel body : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(swept))) {
            if (body.isRemoved()) {
                continue;
            }

            if (ignoreBodyId != null && ignoreBodyId.equals(body.getUniqueId())) {
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
            final Hit localHit = scan(level, localStart, localMove, localRadius, localSwept);

            if (localHit == null || (best != null && best.fraction() <= localHit.fraction())) {
                continue;
            }

            final Vec3 worldNormal = pose.transformNormal(localHit.normal()).normalize();
            best = new Hit(localHit.fraction(), movement.scale(localHit.fraction()), worldNormal);
        }

        return best;
    }

    /** 在给定坐标系（主世界 / 某个物理体的 plot 局部系）里逐方块做球-箱扫掠 */
    private static @Nullable Hit scan(final Level level, final Vec3 start, final Vec3 movement,
                                      final double radius, final AABB region) {
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
                        // 漏了这一步 ⇒ 所有候选盒都堆在原点附近，粒子永远撞不到东西
                        // —— v5 第一版的「撞墙没反应」就是这个。
                        final Hit hit = slab(start, movement, box, radius,
                                pos.getX(), pos.getY(), pos.getZ());

                        if (hit != null && (best == null || hit.fraction() < best.fraction())) {
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
        double entry = 0.0D;
        double exit = 1.0D;
        int normalAxis = -1;
        double normalSign = 0.0D;

        for (int axis = 0; axis < 3; axis++) {
            final double min = (axis == 0 ? box.minX + ox : axis == 1 ? box.minY + oy : box.minZ + oz) - radius;
            final double max = (axis == 0 ? box.maxX + ox : axis == 1 ? box.maxY + oy : box.maxZ + oz) + radius;
            final double origin = axis == 0 ? start.x : axis == 1 ? start.y : start.z;
            final double delta = axis == 0 ? movement.x : axis == 1 ? movement.y : movement.z;

            if (Math.abs(delta) < 1.0E-12D) {
                if (origin <= min || origin >= max) {
                    return null; // 该轴上不移动且在板外 ⇒ 不相交
                }

                continue;
            }

            double near = (min - origin) / delta;
            double far = (max - origin) / delta;

            if (near > far) {
                final double swap = near;
                near = far;
                far = swap;
            }

            if (near >= entry) {
                entry = near;
                normalAxis = axis;
                normalSign = delta > 0.0D ? -1.0D : 1.0D;
            }

            exit = Math.min(exit, far);

            if (entry > exit) {
                return null;
            }
        }

        if (normalAxis < 0 || exit < 0.0D || entry > 1.0D) {
            return null;
        }

        final Vec3 normal = switch (normalAxis) {
            case 0 -> new Vec3(normalSign, 0.0D, 0.0D);
            case 1 -> new Vec3(0.0D, normalSign, 0.0D);
            default -> new Vec3(0.0D, 0.0D, normalSign);
        };

        return new Hit(entry, movement.scale(entry), normal);
    }
}
