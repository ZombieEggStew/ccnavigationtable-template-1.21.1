package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * monitor_2 表面小 Monitor 的独立命中检测器。
 * <p>
 * 与 {@link MonitorHitDetector} 同一思路：不依赖原版 {@code mc.hitResult}（monitor_2 屏幕在
 * controlDesk 底座碰撞体上方，准星瞄准屏幕时原版可能命中不到任何方块），而是遍历
 * {@link ControlDeskClientRegistry} 的候选控制台，用玩家视线射线 + monitor_2 屏幕面实时变换
 * （case 22.5° x 旋转 + 放置平移 + 桌体 FACING 旋转）做屏幕面板正面求交，取最近命中。
 * <p>
 * 变换链与渲染/网格显示一致（见 {@code ControlDeskPlacementOverlay.monitor2World}）：
 * {@code 世界 = pos + R_facing · (shift + R_tilt · p_model)}，命中检测为其严格逆变换。
 */
public final class Monitor2HitDetector {

    /** 遮挡检测容差：命中点与遮挡物距离差小于该值视为同一位置（避免浮点误判）。 */
    private static final double OCCLUSION_EPSILON = 1e-3;

    /** DEBUG：求交过程日志（候选枚举 / 失败原因 / 遮挡判定）。进游戏收集信息用，定位后置 false 或删除。 */
    private static final boolean DEBUG_TRACE = false;

    /** DEBUG_TRACE 的节流计数器（每 N 次调用打一次，防刷屏）。 */
    private static int debugTraceTick = 0;

    private Monitor2HitDetector() {}

    /** 一次命中的完整信息：控制台位置 + 桌体 FACING + 命中距离 + 屏幕局部坐标（模型空间 px，北向基准）+ 网格坐标。 */
    public record Monitor2Hit(
            BlockPos pos,
            Direction facing,
            double distance,
            float screenX,
            float screenY,
            @Nullable int[] grid) {}

    /**
     * 求玩家准心视线命中的最近 monitor_2 屏幕；未命中返回 null。
     *
     * @param level       玩家所在 Level（Sable 子次元下 BE 以 plot 坐标存于此 Level）
     * @param player      玩家
     * @param partialTick 渲染插值刻度（用于 Sable 子次元姿态插值）
     */
    @Nullable
    public static Monitor2Hit find(Level level, Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 view = player.getViewVector(partialTick);
        double reach = player.blockInteractionRange() + 1.0;

        Monitor2Hit best = null;
        boolean trace = DEBUG_TRACE && (++debugTraceTick & 19) == 0;
        int candidates = 0;
        int occluded = 0;
        for (BlockPos pos : ControlDeskClientRegistry.loaded()) {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof ControlDeskBlockEntity desk)) continue;
            if (!desk.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) continue;
            candidates++;

            Direction facing = be.getBlockState().getValue(ControlDeskBlock.FACING);

            SubLevel sub = SableCompat.getContainingSubLevel(level, pos);
            Vec3 o = sub != null ? SableCompat.toLocalPosition(sub, partialTick, eye) : eye;
            Vec3 d = sub != null ? SableCompat.toLocalDirection(sub, partialTick, view) : view;

            double[] hit = intersectScreen(pos, facing, o, d, reach);
            if (hit == null) {
                if (trace) {
                    CCPeripheralExtender.LOGGER.info("[Monitor2Trace] {} 求交失败（背面/平行/超距）", pos.toShortString());
                }
                continue;
            }

            double t = hit[0];
            float sx = (float) hit[1];
            float sy = (float) hit[2];

            // 射线虽穿过屏幕面平面，但落点在屏幕面（x2..14 / y1..11）之外 → 不算命中
            // （对齐 MonitorHitDetector 的 MonitorBlock.isOnPanel 检查；缺此检查时视角移开屏幕
            //   射线仍与无限平面相交于屏幕外远处，判定蔓延到天空）
            if (!isOnScreen(sx, sy)) {
                if (trace) {
                    CCPeripheralExtender.LOGGER.info("[Monitor2Trace] {} 落点在屏幕面外 (sx={}, sy={})", pos.toShortString(),
                            String.format("%.2f", sx), String.format("%.2f", sy));
                }
                continue;
            }

            // 遮挡检测：命中点从 plot 空间投影回世界空间，检查眼→命中点之间是否有其它方块碰撞体遮挡
            Vec3 worldHit = o.add(d.scale(t));
            if (sub != null) worldHit = SableCompat.toWorldPosition(sub, partialTick, worldHit);
            if (isOccluded(level, player, eye, worldHit, t, pos)) {
                occluded++;
                if (trace) {
                    CCPeripheralExtender.LOGGER.info("[Monitor2Trace] {} 命中 (sx={}, sy={}) 但被遮挡", pos.toShortString(),
                            String.format("%.2f", sx), String.format("%.2f", sy));
                }
                continue;
            }

            int[] grid = localToGrid(sx, sy);
            if (trace) {
                CCPeripheralExtender.LOGGER.info("[Monitor2Trace] {} facing={} 命中 (sx={}, sy={}) grid={}",
                        pos.toShortString(), facing,
                        String.format("%.2f", sx), String.format("%.2f", sy),
                        grid == null ? "null(屏幕面外)" : "[" + grid[0] + "," + grid[1] + "]");
            }
            if (best == null || t < best.distance()) {
                best = new Monitor2Hit(pos, facing, t, sx, sy, grid);
            }
        }
        if (trace) {
            CCPeripheralExtender.LOGGER.info("[Monitor2Trace] 本轮候选={} 被遮挡={} 最近命中={}",
                    candidates, occluded, best != null ? best.pos().toShortString() : "无");
        }
        return best;
    }

    /**
     * 屏幕局部坐标 [sx, sy]（北向基准模型空间 px）→ 网格坐标 [gridX, gridY]（10×8，内缩 1px）。
     * 不在网格区域（内缩后的 10×8 格）时返回 null。
     */
    @Nullable
    public static int[] localToGrid(float sx, float sy) {
        float inset = 1f;
        if (sx < ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + inset
                || sx > ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MAX - inset) return null;
        if (sy < ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + inset
                || sy > ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MAX - inset) return null;

        int gx = (int) Math.floor(sx - ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN - inset);
        int gy = (int) Math.floor(sy - ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN - inset);
        if (gx < 0 || gx >= ControlDeskBlockEntity.MONITOR_2_GRID_WIDTH
                || gy < 0 || gy >= ControlDeskBlockEntity.MONITOR_2_GRID_HEIGHT) return null;
        return new int[]{ gx, gy };
    }

    /**
     * 屏幕局部坐标 [sx, sy]（北向基准模型空间 px）是否落在屏幕面（case 前脸 x2..14 / y1..11）内。
     * 射线与屏幕面平面求交后必须通过本检查，否则视角移开屏幕时仍会命中平面外远处。
     */
    public static boolean isOnScreen(float sx, float sy) {
        return sx >= ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN
                && sx <= ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MAX
                && sy >= ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN
                && sy <= ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MAX;
    }

    /**
     * 视线射线 → monitor_2 屏幕面求交（返回 {@code [t, sx, sy]}：t 射线参数块单位，
     * sx/sy 落点北向基准模型空间 px；未命中返回 null）。
     * <p>
     * 逆变换顺序与渲染正向相反：facing逆 → shift逆 → case 22.5° 逆，旋转取负、平移取反，
     * 然后在北向基准模型空间与 z={@code MONITOR_2_SCREEN_Z} 平面求交（背面剔除：屏幕法线 -Z，
     * 视线 z 分量必须为正才能从正面命中）。
     */
    @Nullable
    private static double[] intersectScreen(BlockPos pos, Direction facing, Vec3 origin, Vec3 dir, double maxDistance) {
        Vec3 block = Vec3.atLowerCornerOf(pos);
        double[] o = { origin.x - block.x, origin.y - block.y, origin.z - block.z };
        double[] d = { dir.x, dir.y, dir.z };

        // 1. facing 逆（绕方块中心 Y，与 gridWorld 的 switch 映射互逆）
        inverseFacing(o, d, facing);

        // 2. shift 逆（放置平移取反）
        o[0] -= (ControlDeskBlockEntity.MONITOR_2_PLACE_X - ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER) / 16.0;
        o[1] -= (ControlDeskBlockEntity.MODEL_PLACE_Y - ControlDeskBlockEntity.MONITOR_2_MODEL_BOTTOM_Y) / 16.0;
        o[2] -= (ControlDeskBlockEntity.MONITOR_2_PLACE_Z - ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER) / 16.0;

        // 3. case 22.5° x 旋转逆（绕 origin [14,4,3]，角度取负）
        inverseCaseTilt(o, d);

        // 4. 北向基准模型空间 z=SCREEN_Z 平面求交（屏幕法线 -Z，从正面看 d_z > 0）
        double planeZ = ControlDeskBlockEntity.MONITOR_2_SCREEN_Z / 16.0;
        if (d[2] <= 1e-6) return null;   // 平行或从背面看 → 剔除
        double t = (planeZ - o[2]) / d[2];
        if (t < 0 || t > maxDistance) return null;

        // 5. 落点（块单位 → px）
        double sx = (o[0] + t * d[0]) * 16.0;
        double sy = (o[1] + t * d[1]) * 16.0;
        return new double[]{ t, sx, sy };
    }

    /** 绕方块中心 (0.5, 0.5, 0.5) 的 Y 旋转逆变换（世界/plot → 北向基准块局部；点与方向就地修改）。 */
    private static void inverseFacing(double[] o, double[] d, Direction facing) {
        // gridWorld 正向映射的逆：NORTH 恒等、SOUTH 两次取反、WEST/EAST 对调并取反
        double x = o[0], z = o[2];
        double dx = d[0], dz = d[2];
        switch (facing) {
            case SOUTH -> { o[0] = 1 - x; o[2] = 1 - z; d[0] = -dx; d[2] = -dz; }
            case WEST  -> { o[0] = 1 - z; o[2] = x;     d[0] = -dz; d[2] = dx; }
            case EAST  -> { o[0] = z;     o[2] = 1 - x; d[0] = dz;  d[2] = -dx; }
            default    -> { /* NORTH：恒等 */ }
        }
    }

    /** case 22.5° x 轴旋转逆变换（绕 origin [14,4,3]，角度取负；点与方向就地修改，块单位）。 */
    private static void inverseCaseTilt(double[] o, double[] d) {
        double rad = Math.toRadians(ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_DEG);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double oy = ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Y / 16.0;
        double oz = ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Z / 16.0;

        double dy = o[1] - oy, dz = o[2] - oz;
        o[1] = oy + dy * cos + dz * sin;
        o[2] = oz - dy * sin + dz * cos;

        double ddy = d[1], ddz = d[2];
        d[1] = ddy * cos + ddz * sin;
        d[2] = -ddy * sin + ddz * cos;
    }

    /**
     * 判断眼到命中点之间是否被其它方块（碰撞体）遮挡。
     * <p>
     * 使用 COLLIDER（碰撞形状）而非 OUTLINE。与 Monitor 不同：monitor_2 的屏幕是 controlDesk
     * 桌体的一部分（屏幕面 z2..6 与桌体碰撞体 z8..16 同处一个方块内），因此**必须排除
     * controlDesk 自身方块**（射线从正面看屏幕时会先穿过桌体碰撞体，若不排除会误判自遮挡）；
     * 其它方块（墙/箱子等）仍正常遮挡。eye 与 worldHit 均为世界空间。
     */
    private static boolean isOccluded(Level level, Player player, Vec3 eye, Vec3 worldHit,
                                      double hitDistance, BlockPos deskPos) {
        ClipContext ctx = new ClipContext(eye, worldHit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult blockHit = level.clip(ctx);
        if (blockHit.getType() == HitResult.Type.MISS) return false;
        // monitor_2 载体（controlDesk 桌体自身）不算遮挡
        if (blockHit.getBlockPos().equals(deskPos)) return false;
        double blockDist = eye.distanceTo(blockHit.getLocation());
        return blockDist < hitDistance - OCCLUSION_EPSILON;
    }
}
