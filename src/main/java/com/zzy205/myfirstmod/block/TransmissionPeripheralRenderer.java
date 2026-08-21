package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import static com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock.AXIS;

/**
 * 原版 BER 回退渲染（Flywheel 不可用时使用）。
 * 两端 shaft 分别以输入端/输出端速度渲染。
 */
public class TransmissionPeripheralRenderer extends KineticBlockEntityRenderer<TransmissionPeripheralBlockEntity> {

    public TransmissionPeripheralRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(TransmissionPeripheralBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null || VisualizationManager.supportsVisualization(level)) return;

        BlockState state = be.getBlockState();
        Direction.Axis axis = state.getValue(AXIS);
        BlockPos pos = be.getBlockPos();
        float time = AnimationTickHolder.getRenderTime(level);

        for (Direction direction : Iterate.directionsInAxis(axis)) {
            float angleDeg;
            if (be.isServoMode() && be.isServoOutputFace(direction)) {
                // 舵机模式：输出端按服务器权威角度渲染（真实定位语义，不加随机 offset）
                angleDeg = be.getServoDisplayAngle(partialTicks);
            } else {
                float speed = getDirectionalSpeed(be, direction);
                angleDeg = (time * speed * 3f / 10f) % 360;
                angleDeg += getRotationOffsetForPosition(be, pos, axis);
            }
            float angle = angleDeg / 180f * (float) Math.PI;

            SuperByteBuffer shaftBuffer = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, direction);
            kineticRotationTransform(shaftBuffer, be, axis, angle, light);
            shaftBuffer.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        }
    }

    private static float getDirectionalSpeed(TransmissionPeripheralBlockEntity be, Direction direction) {
        if (!be.hasSource() || be.getSourceFacing() == direction) {
            return be.getSpeed();
        }
        return be.getSpeed() * be.getRotationSpeedModifier(direction);
    }
}
