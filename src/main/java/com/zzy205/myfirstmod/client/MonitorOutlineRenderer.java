package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlock;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * 为可动 Monitor（正式 + 测试）绘制精确跟随 offset/yaw/pitch 的外壳描边，
 * 因为原版 VoxelShape 只能表示轴对齐盒，无法表示连续旋转。碰撞体仍由静态底座承担。
 */
public final class MonitorOutlineRenderer {

    private MonitorOutlineRenderer() {}

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        BlockHitResult target = event.getTarget();
        BlockPos pos = target.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        BlockEntity be = minecraft.level.getBlockEntity(pos);

        Direction facing;
        float pitch, yaw;
        int offset;
        if (state.getBlock() instanceof MonitorBlock) {
            facing = state.getValue(MonitorBlock.FACING);
            pitch = be instanceof MonitorBlockEntity m ? m.getPitchAngle() : 0f;
            yaw = be instanceof MonitorBlockEntity m ? m.getYawAngle() : 0f;
            offset = be instanceof MonitorBlockEntity m ? m.getOffset() : 0;
        } else if (state.getBlock() instanceof PitchMonitorTestBlock) {
            facing = state.getValue(PitchMonitorTestBlock.FACING);
            pitch = be instanceof PitchMonitorTestBlockEntity m ? m.getPitchAngle() : 0f;
            yaw = be instanceof PitchMonitorTestBlockEntity m ? m.getYawAngle() : 0f;
            offset = be instanceof PitchMonitorTestBlockEntity m ? m.getOffset() : 0;
        } else {
            return;
        }

        event.setCanceled(true);

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        MonitorTransform.applyFacing(poseStack, facing);

        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());

        // 底座：固定，不随 yaw/pitch
        drawBox(poseStack.last(), lines, 0f, 0f, 0f, 1f, 2f / 16f, 1f, 0f);

        // bearing + case：都随 offset + yaw；case 额外随 pitch
        poseStack.pushPose();
        MonitorTransform.applyOffset(poseStack, offset);
        MonitorTransform.applyYaw(poseStack, yaw);
        PoseStack.Pose yawedPose = poseStack.last();

        // bearing（不随 pitch）
        drawBox(yawedPose, lines, 0f, 2f / 16f, 6f / 16f, 1f, 11f / 16f, 10f / 16f, 0f);
        // case（随 pitch）
        drawBox(yawedPose, lines, 1f / 16f, 3f / 16f, 4f / 16f,
                15f / 16f, 15f / 16f, 9f / 16f, pitch);

        poseStack.popPose();
        poseStack.popPose();
    }

    private static void drawBox(PoseStack.Pose pose, VertexConsumer lines,
                                float x0, float y0, float z0, float x1, float y1, float z1,
                                float pitchDegrees) {
        Vec3[] corners = new Vec3[8];
        for (int index = 0; index < corners.length; index++) {
            float x = (index & 1) == 0 ? x0 : x1;
            float y = (index & 2) == 0 ? y0 : y1;
            float z = (index & 4) == 0 ? z0 : z1;
            corners[index] = pitchDegrees != 0f ? rotatePitch(x, y, z, pitchDegrees) : new Vec3(x, y, z);
        }

        for (int index = 0; index < corners.length; index++) {
            for (int bit : new int[]{1, 2, 4}) {
                int adjacent = index | bit;
                if (adjacent > index) line(pose, lines, corners[index], corners[adjacent]);
            }
        }
    }

    private static Vec3 rotatePitch(float x, float y, float z, float pitchDegrees) {
        double radians = Math.toRadians(pitchDegrees);
        float hingeY = MonitorBlock.HINGE_Y / 16f;
        float hingeZ = MonitorBlock.HINGE_Z / 16f;
        float localY = y - hingeY;
        float localZ = z - hingeZ;
        float rotatedY = hingeY + localY * (float) Math.cos(radians)
                - localZ * (float) Math.sin(radians);
        float rotatedZ = hingeZ + localY * (float) Math.sin(radians)
                + localZ * (float) Math.cos(radians);
        return new Vec3(x, rotatedY, rotatedZ);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer lines, Vec3 from, Vec3 to) {
        Vec3 normal = to.subtract(from).normalize();
        lines.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(0f, 0f, 0f, 0.4f)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
        lines.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(0f, 0f, 0f, 0.4f)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
