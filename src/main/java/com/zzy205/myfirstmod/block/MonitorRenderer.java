package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;

/**
 * Monitor BER — 在屏幕表面渲染已放置的模块模型。
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        var grid = be.getGridState();
        if (grid.isEmpty()) return;

        Direction facing = be.getBlockState().getValue(MonitorBlock.FACING);
        var mc = Minecraft.getInstance();
        var modelRenderer = mc.getBlockRenderer().getModelRenderer();

        poseStack.pushPose();

        // 旋转到模型空间（NORTH-facing），与 MonitorGridOverlay.rot() 一致
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        poseStack.translate(c, c, c);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
        poseStack.translate(-c, -c, -c);

        for (var mod : grid.getAllModules().values()) {
            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            poseStack.pushPose();

            // 定位到屏幕表面（模型背部贴在屏幕上，向玩家方向突出）
            float px = (MonitorBlock.SCREEN_X_MIN + mod.gridX()) / 16f;
            float py = (MonitorBlock.SCREEN_Y_MIN + mod.gridY()) / 16f;
            float pz = (MonitorBlock.SCREEN_Z) / 16f;

            poseStack.translate(px, py, pz);

            // 仿 control-panels：用 Sheets.solidBlockSheet() 替代 RenderType.solid()
            modelRenderer.renderModel(
                    poseStack.last(),
                    buffer.getBuffer(Sheets.solidBlockSheet()),
                    null,
                    model,
                    1f, 1f, 1f,
                    light, overlay
            );

            poseStack.popPose();
        }

        poseStack.popPose();
    }
}
