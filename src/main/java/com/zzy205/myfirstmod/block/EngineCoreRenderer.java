package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 原版 BER 回退渲染（Flywheel 不可用时使用）。
 * <p>
 * 在 AXIS 轴的两端各渲染<b>半个传动杆</b>（Create {@code SHAFT_HALF}），合成贯通传动杆；
 * 两段绕同一 AXIS 轴、同一角度旋转（等价于一根整轴旋转）。
 * 方块本体（发动机外壳）由 blockstate 静态模型渲染，不进 BER。
 * <p>
 * 参考来源：{@code MyBearingRenderer}（半轴旋转公式）、Create {@code GearboxRenderer}（轴向 shaft stub 渲染）。
 */
public class EngineCoreRenderer extends KineticBlockEntityRenderer<EngineCoreBlockEntity> {

    public EngineCoreRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(EngineCoreBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        if (be.getLevel() == null || VisualizationManager.supportsVisualization(be.getLevel())) return;

        BlockState state = be.getBlockState();
        Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
        BlockPos pos = be.getBlockPos();
        float time = AnimationTickHolder.getRenderTime(be.getLevel());
        float speed = be.getSpeed();
        float angleDeg = (time * speed * 3f / 10f) % 360;
        angleDeg += getRotationOffsetForPosition(be, pos, axis);
        float angle = angleDeg / 180f * (float) Math.PI;

        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != axis) continue;
            SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, direction);
            kineticRotationTransform(shaft, be, axis, angle, light);
            shaft.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        }
    }
}
