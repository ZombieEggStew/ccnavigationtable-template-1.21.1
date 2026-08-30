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
import org.joml.Quaternionf;

/**
 * 航空集成计算机（AIC）原版 BER 回退渲染（Flywheel 不可用时使用），与
 * {@link AicVisual} 同一变换链：
 * {@code T(0.5)·R(facing)·T(P−0.5)·R(q)}——罗盘平移到局部位置
 * {@link AicBlock#COMPASS_POS} 再绕该点旋转姿态，blockstate facing 旋转在最外层。
 * 外壳/机身（含 gyro 透明外壳）由 blockstate 静态模型渲染。
 */
public class AicRenderer extends SafeBlockEntityRenderer<AicBlockEntity> {

    private static final float HALF = 0.5f;

    public AicRenderer(final BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(final AicBlockEntity be, final float partialTicks, final PoseStack ms,
                              final MultiBufferSource buffer, final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        final Quaternionf base = be.getBaseQuaternion(); // blockstate facing（绕方块中心），下面单独应用
        // 姿态 q 只含 Y·Z·X（不含 facing）：facing 已由 buf.rotate(base) 应用，
        // 若 q 再含 base 会导致 facing 重复旋转（Flywheel 路径 position 已吸收 facing，旋转需含 base；
        // BER 路径按矩阵链 T(c)·R(facing)·T(P−c)·R(q)，q 必须不含 facing）
        final Quaternionf q = new Quaternionf();
        be.applyCompassQuaternion(q, partialTicks);
        be.applyPrimaryQuaternion(q, partialTicks);
        be.applySecondaryQuaternion(q, partialTicks);

        final VertexConsumer vb = buffer.getBuffer(RenderType.translucent());
        final SuperByteBuffer buf = CachedBuffers.partial(MyModPartialModels.AIC_COMPASS, be.getBlockState());
        // 变换链（先调用 = 外层）：T(0.5)·R(facing)·T(P−0.5)·R(q)
        buf.translate(HALF, HALF, HALF);
        buf.rotate(base);
        buf.translate(AicBlock.COMPASS_POS.x() - HALF, AicBlock.COMPASS_POS.y() - HALF, AicBlock.COMPASS_POS.z() - HALF);
        buf.rotate(q);
        buf.light(light).renderInto(ms, vb);
    }
}
