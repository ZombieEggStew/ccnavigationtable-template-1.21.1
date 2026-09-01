package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.client.ScreenTextRenderer;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ScreenText;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 屏幕 9 宫格 + 格子文本渲染的共享实现（Monitor 与 monitor_2 共用）。
 * <p>
 * 所有几何量统一为<b>块单位</b>，各宿主通过 {@link ScreenPlane} 提供屏幕面网格起点与基准面 z——
 * 消除移植时「px/块单位只改一半」类 bug（monitor_2 曾把 width 错乘 16f 导致 9 宫格布满天空，
 * 见 {@code memo/control-desk-grid-slot.md}）。
 */
public final class Screen9GridRenderer {

    /** 屏幕面参数（块单位）。 */
    public interface ScreenPlane {
        /** 屏幕面网格起点 x（块单位）：Monitor=(SCREEN_X_MIN+GRID_INSET)/16；monitor_2=(MONITOR_2_SCREEN_X_MIN+1)/16 */
        float originX();
        /** 屏幕面网格起点 y（块单位） */
        float originY();
        /** 9 宫格基准面 z（块单位）：monitor_2 已含模块凸出（-MONITOR_2_MODULE_PROTRUDE_PX/16） */
        float z();
        /** 1 格的世界单位（Monitor 与 monitor_2 均为 1/16 块） */
        default float cellSize() { return 1f / 16f; }
    }

    private static final RandomSource RANDOM = RandomSource.create(42L);

    private Screen9GridRenderer() {}

    /** 渲染一个屏幕 9 宫格（角/边/中心面板）+ 格子文字。 */
    public static void renderScreen(PoseStack ps, MultiBufferSource buffer,
                                    BakedModel corner, BakedModel edge, BakedModel center,
                                    GridState.ScreenRegion scr, ScreenText text, ScreenPlane plane,
                                    int light, int overlay) {
        // 屏幕渲染开关关闭：整个屏幕（9 宫格 + 内容）不绘制
        if (text != null && !text.isVisible()) return;

        float cellSize = plane.cellSize();
        float borderSize = cellSize;

        float scrX = plane.originX() + scr.minX() * cellSize;
        float scrY = plane.originY() + scr.minY() * cellSize;
        float scrW = scr.width() * cellSize;
        float scrH = scr.height() * cellSize;
        float scrZ = plane.z();

        float innerW = scrW - 2 * borderSize;
        float innerH = scrH - 2 * borderSize;

        VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());

        // ── 四个角（绕 Z 轴旋转，法线安全）──
        if (corner != null) {
            renderCorner(ps, vc, corner, scrX, scrY, scrZ, 0, light, overlay);
            renderCorner(ps, vc, corner, scrX + scrW - borderSize, scrY, scrZ, 90, light, overlay);
            renderCorner(ps, vc, corner, scrX, scrY + scrH - borderSize, scrZ, -90, light, overlay);
            renderCorner(ps, vc, corner, scrX + scrW - borderSize, scrY + scrH - borderSize, scrZ, 180, light, overlay);
        }

        // ── 四边（平铺，避免纹理拉伸变形）──
        if (edge != null) {
            int edgeTilesH = Math.max(0, scr.width() - 2);  // 水平边单元数
            int edgeTilesV = Math.max(0, scr.height() - 2); // 垂直边单元数
            for (int i = 0; i < edgeTilesV; i++) {
                renderCorner(ps, vc, edge, scrX + scrW - borderSize, scrY + borderSize + i * cellSize, scrZ, 180, light, overlay);
                renderCorner(ps, vc, edge, scrX, scrY + borderSize + i * cellSize, scrZ, 0, light, overlay);
            }
            for (int i = 0; i < edgeTilesH; i++) {
                renderCorner(ps, vc, edge, scrX + borderSize + i * cellSize, scrY, scrZ, 90, light, overlay);
                renderCorner(ps, vc, edge, scrX + borderSize + i * cellSize, scrY + scrH - borderSize, scrZ, -90, light, overlay);
            }
        }

        // ── 中央面板（XY 双向拉伸；center 模型 1px 宽 → scale = 格数）──
        if (center != null && innerW > 0.001f && innerH > 0.001f) {
            ps.pushPose();
            ps.translate(scrX + borderSize, scrY + borderSize, scrZ);
            ps.scale(innerW / cellSize, innerH / cellSize, 1);
            renderModel(ps, vc, center, light, overlay);
            ps.popPose();
        }

        // ── 屏幕字符 / 图形 ──
        if (text != null && text.hasContent()) {
            renderScreenText(ps, buffer, scr, text, plane);
        }
    }

    /** 在屏幕内区渲染格子文本缓冲（格子模型：每格字符 + 前景/背景色 + 图形层）。 */
    public static void renderScreenText(PoseStack ps, MultiBufferSource buffer,
                                        GridState.ScreenRegion scr, ScreenText text, ScreenPlane plane) {
        float cellSize = plane.cellSize();
        float drawableInset = (float) ScreenText.DRAWABLE_INSET;

        float scrX = plane.originX() + scr.minX() * cellSize;
        float scrY = plane.originY() + scr.minY() * cellSize;
        float scrW = scr.width() * cellSize;
        float scrH = scr.height() * cellSize;

        // 可绘制区域 = 屏幕 9 宫格内区再内缩 DRAWABLE_INSET（1/64 块）。
        // 内容原点：DRAWABLE_INSET 已包含在这里，格子 / drawRect 共用这组边界。
        float contentRight = scrX + scrW - drawableInset;
        float contentTop = scrY + scrH - drawableInset;
        float contentLeft = scrX + drawableInset;
        float contentBottom = scrY + drawableInset;
        float innerWidthUnits = (float) ((scr.width() - 2f * drawableInset * 16f)
            * ScreenText.RECT_UNITS_PER_PX);
        float innerHeightUnits = (float) ((scr.height() - 2f * drawableInset * 16f)
            * ScreenText.RECT_UNITS_PER_PX);
        // 内容基准面 = 屏幕 9 宫格中心面（screen_center 模型 north 面在 z=0.7px）
        float zBase = plane.z() + 0.7f / 16f;

        ScreenTextRenderer.drawAll(ps, buffer, text, contentRight, contentTop,
            contentLeft, contentBottom, innerWidthUnits, innerHeightUnits, zBase);
    }

    /** 渲染一个角模型，绕格子中心 Z 轴旋转（法线安全）。坐标均为块单位。 */
    private static void renderCorner(PoseStack ps, VertexConsumer vc, BakedModel corner,
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

    /** 渲染一个模型（全部面 + 无 cull 面）。 */
    public static void renderModel(PoseStack ps, VertexConsumer consumer, BakedModel model, int light, int overlay) {
        var pose = ps.last();
        for (Direction dir : Direction.values()) {
            for (var q : model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null))
                consumer.putBulkData(pose, q, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
        }
        for (var q : model.getQuads(null, null, RANDOM, ModelData.EMPTY, null))
            consumer.putBulkData(pose, q, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
    }
}
