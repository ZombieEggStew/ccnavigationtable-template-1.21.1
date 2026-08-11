package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.Map;

/**
 * Monitor BER — 在屏幕表面渲染已放置的模块模型。
 * 各模块的微调/动画逻辑见 {@link ModuleRenderBehavior}。
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    private static final RandomSource RANDOM = RandomSource.create(42L);
    private static final float PRESS_DEPTH = 0.6f;

    private final Map<Integer, Float> animProgress = new HashMap<>();

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        var grid = be.getGridState();
        if (grid.isEmpty()) return;

        Direction facing = be.getBlockState().getValue(MonitorBlock.FACING);

        poseStack.pushPose();
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        poseStack.translate(c, c, c);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
        poseStack.translate(-c, -c, -c);

        animProgress.keySet().removeIf(id -> !grid.getAllModules().containsKey(id));

        for (var mod : grid.getAllModules().values()) {
            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            var bhv = ModuleRenderBehavior.of(mod.type());

            // 动画进度：旋钮用角度，普通按钮/钮子用 0/1
            boolean isKnob = mod.type() == com.zzy205.myfirstmod.monitor.ModuleType.KNOB;
            float target = isKnob ? grid.getKnobAngle(mod.id()) : (grid.isPressed(mod.id()) ? 1f : 0f);
            float current = animProgress.getOrDefault(mod.id(), 0f);
            float speed = target > current ? bhv.animPressSpeed() : bhv.animReleaseSpeed();
            float next = current + (target - current) * speed;
            if (Math.abs(next - target) < 0.01f) next = target;
            animProgress.put(mod.id(), next);

            poseStack.pushPose();

            float px = (MonitorBlock.SCREEN_X_MIN + mod.gridX()) / 16f + bhv.offsetX();
            float py = (MonitorBlock.SCREEN_Y_MIN + mod.gridY()) / 16f + bhv.offsetY();
            float pz = MonitorBlock.SCREEN_Z / 16f + bhv.offsetZ();
            if (bhv.usePressDepth()) pz += PRESS_DEPTH * next / 16f;

            poseStack.translate(px, py, pz);
            bhv.applyInitialRotation(poseStack);

            // 底座
            renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), model, light, overlay);
            // 额外部件（拉杆等）
            bhv.renderExtra(poseStack, buffer, next, light, overlay);

            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void renderModel(PoseStack ps, VertexConsumer consumer, BakedModel model, int light, int overlay) {
        var pose = ps.last();
        for (Direction dir : Direction.values()) {
            for (var q : model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null))
                consumer.putBulkData(pose, q, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
        }
        for (var q : model.getQuads(null, null, RANDOM, ModelData.EMPTY, null))
            consumer.putBulkData(pose, q, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
    }
}
