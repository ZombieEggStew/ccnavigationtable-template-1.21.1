package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.block.MonitorSlabBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * monitor_slab 表面 Monitor 的独立命中检测器（仅地板 FLOOR 放置）。
 * <p>
 * 与 {@link Monitor2HitDetector} 同一思路但不依赖原版 {@code mc.hitResult}，而是遍历
 * {@link MonitorSlabClientRegistry} 的候选 slab，用玩家视线射线与<b>水平面板平面</b>
 * （世界 y = pos.y + 0.5，slab 顶面）求交。slab 无 yaw/pitch/tilt，面板轴对齐——
 * 命中退化为「射线 vs 水平平面」，比 monitor_2 的 22.5° 倾斜变换简单得多。
 * <p>
 * 背面剔除：面板朝上，视线必须从上往下（射线 y 分量 < 0）才能命中。
 * 遮挡检测：COLLIDER 排除 slab 自身（面板与 slab 碰撞体顶面重合，不排除会自遮挡，
 * 对齐 monitor_2 的踩坑修复）。
 */
public final class MonitorSlabHitDetector {

    /** 遮挡检测容差：命中点与遮挡物距离差小于该值视为同一位置（避免浮点误判）。 */
    private static final double OCCLUSION_EPSILON = 1e-3;

    /** DEBUG：求交过程日志（候选枚举 / 失败原因 / 遮挡判定）。进游戏收集信息用，定位后置 false 或删除。 */
    private static final boolean DEBUG_TRACE = false;

    /** DEBUG_TRACE 的节流计数器（每 N 次调用打一次，防刷屏）。 */
    private static int debugTraceTick = 0;

    private MonitorSlabHitDetector() {}

    /** 一次命中的完整信息：slab 位置 + 命中距离 + 面板局部坐标（模型空间 px，北向基准：px→模型 x / pz→模型 z）+ 网格坐标。 */
    public record SlabHit(
            BlockPos pos,
            double distance,
            float px,
            float pz,
            @Nullable int[] grid) {}

    /**
     * 求玩家准心视线命中的最近 monitor_slab 面板；未命中返回 null。
     *
     * @param level       玩家所在 Level（Sable 子次元下 BE 以 plot 坐标存于此 Level）
     * @param player      玩家
     * @param partialTick 渲染插值刻度（用于 Sable 子次元姿态插值）
     */
    @Nullable
    public static SlabHit find(Level level, Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 view = player.getViewVector(partialTick);
        double reach = player.blockInteractionRange() + 1.0;

        SlabHit best = null;
        boolean trace = DEBUG_TRACE && (++debugTraceTick & 19) == 0;
        int candidates = 0;
        int occluded = 0;
        for (BlockPos pos : MonitorSlabClientRegistry.loaded()) {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof MonitorSlabBlockEntity)) continue;
            candidates++;

            SubLevel sub = SableCompat.getContainingSubLevel(level, pos);
            Vec3 o = sub != null ? SableCompat.toLocalPosition(sub, partialTick, eye) : eye;
            Vec3 d = sub != null ? SableCompat.toLocalDirection(sub, partialTick, view) : view;

            double[] hit = intersectPanel(pos, o, d, reach);
            if (hit == null) {
                if (trace) {
                    CCPeripheralExtender.LOGGER.info("[SlabTrace] {} 求交失败（背面/平行/超距）", pos.toShortString());
                }
                continue;
            }

            double t = hit[0];
            float px = (float) hit[1];
            float pz = (float) hit[2];

            // 射线虽穿过面板平面，但落点在面板（顶面 0..16）之外 → 不算命中
            // （对齐 Monitor2HitDetector 的 isOnScreen 检查；缺此检查时判定蔓延到远处）
            if (!isOnPanel(px, pz)) {
                if (trace) {
                    CCPeripheralExtender.LOGGER.info("[SlabTrace] {} 落点在面板外 (px={}, pz={})", pos.toShortString(),
                            String.format("%.2f", px), String.format("%.2f", pz));
                }
                continue;
            }

            // 遮挡检测：命中点从 plot 空间投影回世界空间，检查眼→命中点之间是否有其它方块碰撞体遮挡
            Vec3 worldHit = o.add(d.scale(t));
            if (sub != null) worldHit = SableCompat.toWorldPosition(sub, partialTick, worldHit);
            if (isOccluded(level, player, eye, worldHit, t, pos)) {
                occluded++;
                if (trace) {
                    CCPeripheralExtender.LOGGER.info("[SlabTrace] {} 命中 (px={}, pz={}) 但被遮挡", pos.toShortString(),
                            String.format("%.2f", px), String.format("%.2f", pz));
                }
                continue;
            }

            int[] grid = localToGrid(px, pz);
            if (trace) {
                CCPeripheralExtender.LOGGER.info("[SlabTrace] {} 命中 (px={}, pz={}) grid={}",
                        pos.toShortString(),
                        String.format("%.2f", px), String.format("%.2f", pz),
                        grid == null ? "null(网格外)" : "[" + grid[0] + "," + grid[1] + "]");
            }
            if (best == null || t < best.distance()) {
                best = new SlabHit(pos, t, px, pz, grid);
            }
        }
        if (trace) {
            CCPeripheralExtender.LOGGER.info("[SlabTrace] 本轮候选={} 被遮挡={} 最近命中={}",
                    candidates, occluded, best != null ? best.pos().toShortString() : "无");
        }
        return best;
    }

    /** 面板局部坐标 [px, pz]（北向基准模型空间 px）→ 网格坐标 [gridX, gridY]（14×14，内缩 1px）。不在网格区域时返回 null。 */
    @Nullable
    public static int[] localToGrid(float px, float pz) {
        float inset = MonitorSlabBlockEntity.GRID_INSET_PX;
        float ox = MonitorSlabBlockEntity.GRID_ORIGIN_X_PX;
        float oz = MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX;
        if (px < ox || px > ox + MonitorSlabBlockEntity.GRID_WIDTH) return null;
        if (pz < oz || pz > oz + MonitorSlabBlockEntity.GRID_HEIGHT) return null;

        int gx = (int) Math.floor(px - ox);
        int gy = (int) Math.floor(pz - oz);
        if (gx < 0 || gx >= MonitorSlabBlockEntity.GRID_WIDTH
                || gy < 0 || gy >= MonitorSlabBlockEntity.GRID_HEIGHT) return null;
        return new int[]{ gx, gy };
    }

    /** 面板局部坐标 [px, pz]（模型空间 px）是否落在面板（顶面 x0..16 / z0..16）内。 */
    public static boolean isOnPanel(float px, float pz) {
        return px >= 0f && px <= 16f && pz >= 0f && pz <= 16f;
    }

    /**
     * 视线射线 → 面板平面求交（返回 {@code [t, px, pz]}：t 射线参数块单位，px/pz 落点模型空间 px；
     * 未命中返回 null）。
     * <p>
     * 面板 = 水平面 世界 y = pos.y + 0.5（slab 顶面 y8/16）；背面剔除：面板法线 +Y，
     * 视线 y 分量必须为负（从上往下看）才能命中。
     */
    @Nullable
    private static double[] intersectPanel(BlockPos pos, Vec3 origin, Vec3 dir, double maxDistance) {
        Vec3 block = Vec3.atLowerCornerOf(pos);
        double[] o = { origin.x - block.x, origin.y - block.y, origin.z - block.z };
        double[] d = { dir.x, dir.y, dir.z };

        double planeY = MonitorSlabBlockEntity.PANEL_Y_PX / 16.0;
        if (d[1] >= -1e-6) return null;   // 平行或从背面（下方）看 → 剔除
        double t = (planeY - o[1]) / d[1];
        if (t < 0 || t > maxDistance) return null;

        double px = (o[0] + t * d[0]) * 16.0;
        double pz = (o[2] + t * d[2]) * 16.0;
        return new double[]{ t, px, pz };
    }

    /**
     * 判断眼到命中点之间是否被其它方块（碰撞体）遮挡。
     * <p>
     * 使用 COLLIDER（碰撞形状）而非 OUTLINE。面板与 slab 自身碰撞体顶面重合
     * （射线从上方看面板时会先穿过 slab 自身碰撞体），因此**必须排除 slab 自身方块**，
     * 否则误判自遮挡（monitor_2 踩过的坑）；其它方块（墙/箱子等）仍正常遮挡。
     * eye 与 worldHit 均为世界空间。
     */
    private static boolean isOccluded(Level level, Player player, Vec3 eye, Vec3 worldHit,
                                      double hitDistance, BlockPos slabPos) {
        ClipContext ctx = new ClipContext(eye, worldHit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult blockHit = level.clip(ctx);
        if (blockHit.getType() == HitResult.Type.MISS) return false;
        // slab 载体（自身）不算遮挡
        if (blockHit.getBlockPos().equals(slabPos)) return false;
        double blockDist = eye.distanceTo(blockHit.getLocation());
        return blockDist < hitDistance - OCCLUSION_EPSILON;
    }
}
