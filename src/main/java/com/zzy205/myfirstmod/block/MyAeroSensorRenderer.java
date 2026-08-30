package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.joml.Quaternionf;

/**
 * 惯性导航系统原版 BER 回退渲染（Flywheel 不可用时使用），照抄
 * {@code simulated:gimbal_sensor} 的 GimbalSensorRenderer。
 * 层级（外→内）：test 绕 Y 偏航指北 → gimbal 绕 Z 滚转 → compass 绕 X 俯仰
 * （四元数 Y / Y·Z / Y·Z·X，各自独立实例），partialTick 插值。
 * 外壳由 blockstate 静态模型渲染。
 */
public class MyAeroSensorRenderer extends SafeBlockEntityRenderer<MyAeroSensorBlockEntity> {

    /** 转动部件（万向环/罗盘盘/偏航标记）整体下移量（块单位）：模型与旋转中心同步下移 3.5px */
    private static final float PIVOT_DROP = 3.5f / 16f;

    public MyAeroSensorRenderer(final BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(final MyAeroSensorBlockEntity be, final float partialTicks, final PoseStack ms,
                              final MultiBufferSource buffer, final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        final VertexConsumer vb = buffer.getBuffer(RenderType.cutout());

        // 层级（外→内）：test(Y 偏航) → gimbal(Z 滚转) → compass(X 俯仰)，每个部件独立四元数
        // test（最外层）：只绕 Y
        final Quaternionf testQ = be.getBaseQuaternion();
        be.applyCompassQuaternion(testQ, partialTicks);
        this.apply(MyModPartialModels.MY_AERO_SENSOR_YAW, be, testQ, light, ms, vb);

        // gimbal（中间层）：Y·Z
        final Quaternionf gimbalQ = be.getBaseQuaternion();
        be.applyCompassQuaternion(gimbalQ, partialTicks);
        be.applyPrimaryQuaternion(gimbalQ, partialTicks);
        this.apply(MyModPartialModels.MY_AERO_SENSOR_GIMBAL, be, gimbalQ, light, ms, vb);

        // compass（最里层）：Y·Z·X
        final Quaternionf compassQ = be.getBaseQuaternion();
        be.applyCompassQuaternion(compassQ, partialTicks);
        be.applyPrimaryQuaternion(compassQ, partialTicks);
        be.applySecondaryQuaternion(compassQ, partialTicks);
        this.apply(MyModPartialModels.MY_AERO_SENSOR_COMPASS, be, compassQ, light, ms, vb);
    }

    private void apply(final PartialModel model, final MyAeroSensorBlockEntity te, final Quaternionf Q,
                       final int light, final PoseStack ms, final VertexConsumer vb) {
        final SuperByteBuffer buf = CachedBuffers.partial(model, te.getBlockState());
        // 模型以方块中心为原点建模：平移到下移后的中心，再绕该点旋转（T(C')·R，旋转中心与模型同步下移）
        buf.translate(0.5f, 0.5f - PIVOT_DROP, 0.5f);
        buf.rotate(Q);
        buf.light(light).renderInto(ms, vb);
    }
}
