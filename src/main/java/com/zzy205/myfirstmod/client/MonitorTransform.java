package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.block.MonitorBlock;
import net.minecraft.core.Direction;

/**
 * 可动 Monitor 渲染正向变换（facing → offset → yaw → pitch），供正式 Monitor 与测试 Monitor 共用。
 * <p>
 * 枢轴常量统一定义在 {@link MonitorBlock}（单位：模型像素）；逆向变换（射线求交）与
 * 正向点变换的纯数学实现也都在 {@link MonitorBlock}，保证渲染/检测严格互逆、单一来源。
 * <p>
 * 变换顺序（PoseStack 后调为内层、先作用于顶点）：
 * facing（方块中心，Y）→ offset（前后平移，Z）→ yaw（颈部，Y）→ pitch（铰链，X）。
 */
public final class MonitorTransform {

    private MonitorTransform() {}

    /** facing：绕方块中心 Y 轴（外层，90° 步进朝向）。 */
    public static void applyFacing(PoseStack pose, Direction facing) {
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        pose.translate(c, c, c);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
        pose.translate(-c, -c, -c);
    }

    /** offset：沿 facing 方向前后平移（像素，正值向前 = 朝屏幕 -Z），影响 bearing + case。 */
    public static void applyOffset(PoseStack pose, int offset) {
        if (offset == 0) return;
        pose.translate(0f, 0f, -offset / 16f);
    }

    /** yaw：绕颈部 Y 轴（中层，连续水平转动，影响 bearing + case）。 */
    public static void applyYaw(PoseStack pose, float yaw) {
        if (yaw == 0f) return;
        pose.translate(MonitorBlock.NECK_X / 16f, 0f, MonitorBlock.NECK_Z / 16f);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-MonitorBlock.NECK_X / 16f, 0f, -MonitorBlock.NECK_Z / 16f);
    }

    /** pitch：绕铰链 X 轴（内层，连续俯仰，仅影响 case）。 */
    public static void applyPitch(PoseStack pose, float pitch) {
        if (pitch == 0f) return;
        pose.translate(0f, MonitorBlock.HINGE_Y / 16f, MonitorBlock.HINGE_Z / 16f);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.translate(0f, -MonitorBlock.HINGE_Y / 16f, -MonitorBlock.HINGE_Z / 16f);
    }
}
