package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
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
 * 独立的动态 Monitor 命中检测器。
 * <p>
 * 交互入口不再依赖原版 {@code Minecraft#getPickResult()}（其静态 AABB 无法表达连续旋转），
 * 而是直接用玩家视线射线 + 每个候选 Monitor 的实时变换（facing / yaw / pitch / offset）
 * 做屏幕面板正面求交，取最近命中。屏幕旋出方块范围后仍可正常交互，同时做遮挡检测，
 * 避免穿透实体方块命中其后的 Monitor。
 */
public final class MonitorHitDetector {

    /** 遮挡检测容差：命中点与遮挡物距离差小于该值视为同一位置（避免浮点误判）。 */
    private static final double OCCLUSION_EPSILON = 1e-3;

    private MonitorHitDetector() {}

    /** 一次命中的完整信息：候选 Monitor 位置 + 实时变换 + 网格坐标 + 距离。 */
    public record MonitorHit(
            BlockPos pos,
            Direction facing,
            float yaw,
            float pitch,
            int offset,
            @Nullable int[] grid,
            double distance) {}

    /**
     * 求玩家准心视线命中的最近 Monitor 屏幕；未命中返回 null。
     *
     * @param level       玩家所在 Level（Sable 子次元下 BE 以 plot 坐标存于此 Level）
     * @param player      玩家
     * @param partialTick 渲染插值刻度（用于 Sable 子次元姿态插值）
     */
    @Nullable
    public static MonitorHit find(Level level, Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 view = player.getViewVector(partialTick);
        double reach = player.blockInteractionRange() + 1.0; // 屏幕可旋出方块，多留余量

        MonitorHit best = null;
        for (BlockPos pos : MonitorClientRegistry.loaded()) {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof MonitorBlockEntity monitor)) continue;

            float yaw = monitor.getYawAngle();
            float pitch = monitor.getPitchAngle();
            int offset = monitor.getOffset();
            Direction facing = monitor.getBlockState().getValue(MonitorBlock.FACING);

            SubLevel sub = SableCompat.getContainingSubLevel(level, pos);
            Vec3 o = sub != null ? SableCompat.toLocalPosition(sub, partialTick, eye) : eye;
            Vec3 d = sub != null ? SableCompat.toLocalDirection(sub, partialTick, view) : view;

            double[] hit = MonitorBlock.intersectScreen(pos, facing, yaw, pitch, offset, o, d, reach);
            if (hit == null) continue;

            float sx = (float) hit[1];
            float sy = (float) hit[2];
            // 射线虽穿过面板平面，但落点在屏幕表面之外（面板四周空白/侧面）→ 不算命中
            if (!MonitorBlock.isOnPanel(sx, sy)) continue;

            double t = hit[0];

            // 遮挡检测：命中点从 plot 空间投影回世界空间，检查眼→命中点之间是否有其它方块碰撞体遮挡
            Vec3 worldHit = o.add(d.scale(t));
            if (sub != null) worldHit = SableCompat.toWorldPosition(sub, partialTick, worldHit);
            if (isOccluded(level, player, eye, worldHit, t)) continue;

            int[] grid = MonitorBlock.localToGrid(sx, sy);
            if (best == null || t < best.distance()) {
                best = new MonitorHit(pos, facing, yaw, pitch, offset, grid, t);
            }
        }
        return best;
    }

    /**
     * 判断眼到命中点之间是否被其它方块（碰撞体）遮挡。
     * <p>
     * 使用 COLLIDER（碰撞形状）而非 OUTLINE：Monitor 自身的 outline 是完整 case，
     * 若用 OUTLINE 会误判为“被自己遮挡”；而其碰撞形状仅底座（y:0..2），不覆盖屏幕
     * （y:3..15），因此不会自遮挡。eye 与 worldHit 均为世界空间。
     */
    private static boolean isOccluded(Level level, Player player, Vec3 eye, Vec3 worldHit, double hitDistance) {
        ClipContext ctx = new ClipContext(eye, worldHit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult blockHit = level.clip(ctx);
        if (blockHit.getType() == HitResult.Type.MISS) return false;
        double blockDist = eye.distanceTo(blockHit.getLocation());
        return blockDist < hitDistance - OCCLUSION_EPSILON;
    }
}
