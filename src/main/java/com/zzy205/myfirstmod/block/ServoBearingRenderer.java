package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/**
 * 原版 BER 回退渲染（Flywheel 不可用时使用）：顶部转盘（Create {@code BEARING_TOP} partial）
 * 绕 FACING 轴按 {@link ServoBearingBlockEntity#getInterpolatedAngle} 旋转。
 * <p>
 * 旋转/定向逻辑照抄 {@code BearingRenderer}；插值角度为插值式（修复版），传 {@code partialTicks}。
 * <p>
 * 参考来源：{@code references/Create-mc1.21.1-dev/.../bearing/BearingRenderer.java}。
 */
public class ServoBearingRenderer extends SafeBlockEntityRenderer<ServoBearingBlockEntity> {

    public ServoBearingRenderer(final BlockEntityRendererProvider.Context context) {
        // SafeBlockEntityRenderer 为无参构造；保留 Context 参数以匹配 BlockEntityRendererProvider 工厂
    }

    @Override
    protected void renderSafe(final ServoBearingBlockEntity be, final float partialTicks, final PoseStack ms,
                              final MultiBufferSource buffer, final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) return;

        final Direction facing = be.getBlockState().getValue(ServoBearingBlock.FACING);
        final float interpolatedAngle = be.getInterpolatedAngle(partialTicks);

        final SuperByteBuffer top = CachedBuffers.partial(MyModPartialModels.SERVO_BEARING_TOP, be.getBlockState());
        KineticBlockEntityRenderer.kineticRotationTransform(top, be, facing.getAxis(),
                (float) (interpolatedAngle / 180 * Math.PI), light);

        if (facing.getAxis().isHorizontal())
            top.rotateCentered(AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite())), Direction.UP);
        top.rotateCentered(AngleHelper.rad(-90 - AngleHelper.verticalAngle(facing)), Direction.EAST);

        top.renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }
}
