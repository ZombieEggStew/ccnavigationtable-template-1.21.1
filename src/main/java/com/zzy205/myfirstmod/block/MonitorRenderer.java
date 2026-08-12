package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.monitor.GridState;
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
        if (grid.isEmpty() && !grid.hasScreen()) return;

        Direction facing = be.getBlockState().getValue(MonitorBlock.FACING);

        poseStack.pushPose();
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        poseStack.translate(c, c, c);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
        poseStack.translate(-c, -c, -c);

        // ── 渲染模块 ──
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

        // ── 渲染所有屏幕 9 宫格 ──
        for (var screen : grid.getScreenRegions()) {
            renderScreen(poseStack, buffer, screen, light, overlay);
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

    // ── 屏幕 9 宫格渲染 ──

    private void renderScreen(PoseStack ps, MultiBufferSource buffer,
                              GridState.ScreenRegion scr, int light, int overlay) {
        BakedModel corner = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CORNER);
        BakedModel edge   = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_EDGE);
        BakedModel center = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CENTER);

        VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());

        float cellSize   = 1f / 16f;
        float borderSize = cellSize;   // 角模型占 1 格

        float scrX = (MonitorBlock.SCREEN_X_MIN + scr.minX()) / 16f;
        float scrY = (MonitorBlock.SCREEN_Y_MIN + scr.minY()) / 16f;
        float scrW = scr.width()  * cellSize;
        float scrH = scr.height() * cellSize;
        float scrZ = MonitorBlock.SCREEN_Z / 16f;

        float innerW = scrW - 2 * borderSize;
        float innerH = scrH - 2 * borderSize;

        // ── 四个角（绕 Z 轴旋转，法线安全）──
        if (corner != null) {
            // 左上：0°（模型自带方向即左上）
            renderCorner(ps, vc, corner, scrX, scrY, scrZ, 0, light, overlay);
            // 右上：Z 90°
            renderCorner(ps, vc, corner, scrX + scrW - borderSize, scrY, scrZ, 90, light, overlay);
            // 左下：Z -90°
            renderCorner(ps, vc, corner, scrX, scrY + scrH - borderSize, scrZ, -90, light, overlay);
            // 右下：Z 180°
            renderCorner(ps, vc, corner, scrX + scrW - borderSize, scrY + scrH - borderSize, scrZ, 180, light, overlay);
        }

        // ── 四边（平铺，避免纹理拉伸变形）──
        if (edge != null) {
            int edgeTilesH = Math.max(0, scr.width() - 2);  // 水平边单元数
            int edgeTilesV = Math.max(0, scr.height() - 2); // 垂直边单元数
            for (int i = 0; i < edgeTilesV; i++) {
                // 右边：180°
                renderCorner(ps, vc, edge, scrX + scrW - borderSize, scrY + borderSize + i * cellSize, scrZ, 180, light, overlay);
                // 左边：0°
                renderCorner(ps, vc, edge, scrX, scrY + borderSize + i * cellSize, scrZ, 0, light, overlay);
            }
            for (int i = 0; i < edgeTilesH; i++) {
                // 上边：90°
                renderCorner(ps, vc, edge, scrX + borderSize + i * cellSize, scrY, scrZ, 90, light, overlay);
                // 下边：-90°
                renderCorner(ps, vc, edge, scrX + borderSize + i * cellSize, scrY + scrH - borderSize, scrZ, -90, light, overlay);
            }
        }

        // ── 中央面板（XY 双向拉伸）──
        if (center != null && innerW > 0.001f && innerH > 0.001f) {
            ps.pushPose();
            ps.translate(scrX + borderSize, scrY + borderSize, scrZ);
            ps.scale(innerW / cellSize, innerH / cellSize, 1);
            renderModel(ps, vc, center, light, overlay);
            ps.popPose();
        }
    }

    /** 渲染一个角模型，绕格子中心 Z 轴旋转（法线安全） */
    private void renderCorner(PoseStack ps, VertexConsumer vc, BakedModel corner,
                              float cellX, float cellY, float scrZ, float zDegrees,
                              int light, int overlay) {
        float halfCell = 0.5f / 16f;
        ps.pushPose();
        ps.translate(cellX + halfCell, cellY + halfCell, scrZ);
        if (zDegrees != 0) ps.mulPose(Axis.ZP.rotationDegrees(zDegrees));
        ps.translate(-halfCell, -halfCell, 0);
        renderModel(ps, vc, corner, light, overlay);
        ps.popPose();
    }
}
