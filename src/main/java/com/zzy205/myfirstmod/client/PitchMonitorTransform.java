package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlock;
import net.minecraft.core.Direction;

/**
 * 测试 Monitor 三层旋转（facing → yaw → pitch）的单一实现来源。
 * <p>
 * 渲染 / 描边走正向（applyXxx 到 PoseStack），命中检测走逆向（inverseToModel）。
 * 所有枢轴常量集中在此，模型微调时只改这里。
 * <p>
 * 旋转顺序（PoseStack 后调为内层、先作用于顶点）：
 * facing（方块中心，Y）→ yaw（颈部，Y）→ pitch（铰链，X）。
 */
public final class PitchMonitorTransform {

    /** 俯仰铰链（绕 X 轴） */
    public static final float HINGE_Y = 2f / 16f;
    public static final float HINGE_Z = 6f / 16f;
    /** 偏航颈部（绕 Y 轴），case 水平中心；z=6.5 供模型微调 */
    public static final float NECK_X = 8f / 16f;
    public static final float NECK_Z = 6.5f / 16f;

    private PitchMonitorTransform() {}

    // ───────────────────────── 正向（渲染 / 描边） ─────────────────────────

    /** facing：绕方块中心 Y 轴（外层，90° 步进朝向）。 */
    public static void applyFacing(PoseStack pose, Direction facing) {
        float c = PitchMonitorTestBlock.ROT_ORIGIN / 16f;
        pose.translate(c, c, c);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
        pose.translate(-c, -c, -c);
    }

    /** yaw：绕颈部 Y 轴（中层，连续水平转动）。 */
    public static void applyYaw(PoseStack pose, float yaw) {
        if (yaw == 0f) return;
        pose.translate(NECK_X, 0f, NECK_Z);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-NECK_X, 0f, -NECK_Z);
    }

    /** pitch：绕铰链 X 轴（内层，连续俯仰）。 */
    public static void applyPitch(PoseStack pose, float pitch) {
        if (pitch == 0f) return;
        pose.translate(0f, HINGE_Y, HINGE_Z);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.translate(0f, -HINGE_Y, -HINGE_Z);
    }

    // ───────────────────────── 逆向（命中检测） ─────────────────────────

    /**
     * 把「块局部空间」的射线反变换回「模型空间」（平铺、朝北）。
     * 逆变换顺序与正向相反：facing逆 → yaw逆 → pitch逆，每个轴取负。
     *
     * @param origin 块局部坐标（world - blockPos），长度 3 数组，就地修改
     * @param dir    视线方向，长度 3 数组，就地修改
     */
    public static void inverseToModel(double[] origin, double[] dir, Direction facing, float yaw, float pitch) {
        // facing 逆：绕方块中心 Y 轴 +facing.getOpposite().toYRot()
        double center = PitchMonitorTestBlock.ROT_ORIGIN / 16.0;
        rotateYPoint(origin, center, center, Math.toRadians(facing.getOpposite().toYRot()));
        rotateYDir(dir, Math.toRadians(facing.getOpposite().toYRot()));

        // yaw 逆：绕颈部 Y 轴 -yaw
        rotateYPoint(origin, NECK_X, NECK_Z, Math.toRadians(-yaw));
        rotateYDir(dir, Math.toRadians(-yaw));

        // pitch 逆：绕铰链 X 轴 -pitch（y/z 分量）
        double pr = Math.toRadians(pitch);
        double cos = Math.cos(pr), sin = Math.sin(pr);
        double ly = origin[1] - HINGE_Y, lz = origin[2] - HINGE_Z;
        origin[1] = HINGE_Y + ly * cos + lz * sin;
        origin[2] = HINGE_Z - ly * sin + lz * cos;
        double dy = dir[1], dz = dir[2];
        dir[1] = dy * cos + dz * sin;
        dir[2] = -dy * sin + dz * cos;
    }

    /** 绕水平中心 (cx, cz) 的 Y 轴旋转点（x/z 分量，y 不变）。 */
    private static void rotateYPoint(double[] p, double cx, double cz, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double rx = p[0] - cx, rz = p[2] - cz;
        p[0] = cx + (rx * cos + rz * sin);
        p[2] = cz + (-rx * sin + rz * cos);
    }

    /** 绕 Y 轴旋转方向（x/z 分量，y 不变）。 */
    private static void rotateYDir(double[] d, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double dx = d[0], dz = d[2];
        d[0] = dx * cos + dz * sin;
        d[2] = -dx * sin + dz * cos;
    }
}
