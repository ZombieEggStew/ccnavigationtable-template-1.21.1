package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlock;
import com.zzy205.myfirstmod.block.PitchMonitorTestBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/** Draws an exact rotated case outline because vanilla VoxelShapes only support axis-aligned boxes. */
public final class PitchMonitorTestOutlineRenderer {

    private PitchMonitorTestOutlineRenderer() {}

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        BlockHitResult target = event.getTarget();
        BlockPos pos = target.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof PitchMonitorTestBlock)) return;

        event.setCanceled(true);
        float pitch = minecraft.level.getBlockEntity(pos) instanceof PitchMonitorTestBlockEntity monitor
                ? monitor.getPitchAngle() : 0f;
        float yaw = minecraft.level.getBlockEntity(pos) instanceof PitchMonitorTestBlockEntity monitor
                ? monitor.getYawAngle() : 0f;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        PitchMonitorTransform.applyFacing(poseStack, state.getValue(PitchMonitorTestBlock.FACING));

        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());

        // 底座：固定，不随 yaw/pitch
        drawBox(poseStack.last(), lines, 0f, 0f, 0f, 1f, 2f / 16f, 13f / 16f, 0f);

        // case：先绕颈部 yaw（PoseStack），pitch 烘焙进角点
        poseStack.pushPose();
        PitchMonitorTransform.applyYaw(poseStack, yaw);
        drawBox(poseStack.last(), lines, 1f / 16f, 2f / 16f, 4f / 16f,
                15f / 16f, 14f / 16f, 9f / 16f, pitch);
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
        float localY = y - PitchMonitorTransform.HINGE_Y;
        float localZ = z - PitchMonitorTransform.HINGE_Z;
        float rotatedY = PitchMonitorTransform.HINGE_Y + localY * (float) Math.cos(radians)
                - localZ * (float) Math.sin(radians);
        float rotatedZ = PitchMonitorTransform.HINGE_Z + localY * (float) Math.sin(radians)
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
