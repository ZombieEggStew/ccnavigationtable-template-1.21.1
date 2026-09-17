package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

/**
 * 流体燃烧室原版 BER 回退渲染（Flywheel 不可用时使用），与
 * {@link FluidCombustionChamberVisual} 同一变换链：
 * {@code T(0.5)·R(facing)·T(−0.5)·T(slide)}——活塞绕方块中心做 blockstate facing 旋转，
 * 再沿 FACING 方向平移（当前 offset = 0）。
 * 腔体/顶沿由 blockstate 静态模型渲染，不进 BER。
 */
public class FluidCombustionChamberRenderer extends SafeBlockEntityRenderer<FluidCombustionChamberBlockEntity> {

    private static final float HALF = 0.5f;

    public FluidCombustionChamberRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(FluidCombustionChamberBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        final Direction facing = be.getBlockState().getValue(FluidCombustionChamberBlock.FACING);
        final Quaternionf base = FluidCombustionChamberVisual.pistonOrientation(facing);

        // 活塞滑动（沿 FACING 方向，最外层；引擎运行时 ±2/16 正弦往复，见 FluidCombustionChamberBlockEntity#getPistonOffset）
        final float offset = be.getPistonOffset(partialTicks);
        final VertexConsumer vb = buffer.getBuffer(RenderType.solid());
        final SuperByteBuffer buf = CachedBuffers.partial(MyModPartialModels.FLUID_COMBUSTION_CHAMBER_PISTON,
                be.getBlockState());
        if (offset != 0) {
            buf.translate(facing.getStepX() * offset, facing.getStepY() * offset, facing.getStepZ() * offset);
        }
        // 变换链（先调用 = 外层）：T(0.5)·R(facing)·T(−0.5)
        buf.translate(HALF, HALF, HALF);
        buf.rotate(base);
        buf.translate(-HALF, -HALF, -HALF);
        buf.light(light).renderInto(ms, vb);
    }
}
