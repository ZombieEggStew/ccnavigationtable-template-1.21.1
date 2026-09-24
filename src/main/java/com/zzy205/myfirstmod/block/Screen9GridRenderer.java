package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.client.ScreenTextRenderer;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ScreenText;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
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
        /** 9 宫格基准面 z（块单位）：monitor_2 已含模块凸出（-MONITOR_2_MODULE_PROTRUDE_PX/16）；
         *  水平顶面（{@link #horizontal()}=true）时作为<b>面板高度 y</b>（块单位，translate 用）。 */
        float z();
        /** 1 格的世界单位（Monitor 与 monitor_2 均为 1/16 块） */
        default float cellSize() { return 1f / 16f; }
        /** 屏幕面是否水平（顶面，如 monitor_slab）：true 时整体「平移到 (originX, z, originY) + 绕 X +90° 摊平」，
         *  本地 XY 绘制平面 → 世界 XZ，本地 +Z（文字/正面法线）→ 世界 +Y（朝上）。默认竖面（false），Monitor/monitor_2 零影响。 */
        default boolean horizontal() { return false; }
        /** 水平顶面（{@link #horizontal()}=true）时的<b>平面内旋转</b>（度，正 = 俯视顺时针，与 blockstate y 同向）：
         *  只绕屏幕区域中心旋转<b>内容（文字/图形）</b>，9 宫格外框保持网格对齐——monitor_slab 模块朝向已跟随 FACING，
         *  屏幕内容需同步跟随（外框旋转会宽高互换/错位）。默认 0（Monitor/monitor_2 零影响）。 */
        default float inPlaneRotationDeg() { return 0f; }

        /**
         * 屏幕面变换是否为<b>反射</b>（行列式 −1，如天花板底面的 Rx(90)·scale(1,1,−1)）：
         * true 时 9 宫格模型与字形 quad 的<b>绕序被翻转</b>，需反转顶点顺序补偿，否则背面剔除把正面剔除
         * （模型内外翻转 / 不可见）。内容平面内坐标不受影响（反射只作用在深度轴），文字不镜像。
         * 默认 false（Monitor/monitor_2 / 地板 / 贴墙零影响）。
         */
        default boolean mirrorWinding() { return false; }
    }

    /** 水平面包装后的内部局部平面：原点归零，避免与包装 translate 双重叠加。 */
    private static ScreenPlane localPlane(ScreenPlane plane) {
        float cell = plane.cellSize();
        return new ScreenPlane() {
            @Override public float originX() { return 0; }
            @Override public float originY() { return 0; }
            @Override public float z() { return 0; }
            @Override public float cellSize() { return cell; }
        };
    }

    /** 水平面包装：translate(originX, z, originY) + 绕 X +90°（本地 +Z → 世界 +Y）。返回包装后的局部平面。 */
    private static ScreenPlane wrapHorizontal(PoseStack ps, ScreenPlane plane) {
        ps.pushPose();
        ps.translate(plane.originX(), plane.z(), plane.originY());
        ps.mulPose(Axis.XP.rotationDegrees(90));
        return localPlane(plane);
    }

    /**
     * 屏幕<b>内容</b>绕<b>屏幕区域中心</b>做平面内旋转（正 = 俯视顺时针，与 blockstate y 同向）。
     * 在绘制帧里等价于绕本地 Z（水平面 = 世界 −Y；天花板垂直帧 = 面板法线 −Y）转，内容朝向变化、区域位置不动
     * （仅文字/图形，9 宫格外框不参与——renderScreen 外框保持网格对齐）。
     * 水平面必须紧接 {@link #wrapHorizontal} 之后调用（此时坐标为局部 0 基）；天花板等垂直帧直接在帧内调用。
     * 屏幕中心 = 平面原点 + 区域中心格（含 originX/originY，Monitor/monitor_2 的 inPlane=0 不受影响）。
     */
    private static void applyInPlaneRotation(PoseStack ps, GridState.ScreenRegion scr, ScreenPlane plane, float inPlane) {
        if (inPlane == 0f) return;
        float cell = plane.cellSize();
        float cx = plane.originX() + (scr.minX() + scr.maxX() + 1f) * cell / 2f;
        float cy = plane.originY() + (scr.minY() + scr.maxY() + 1f) * cell / 2f;
        ps.translate(cx, cy, 0f);
        ps.mulPose(Axis.ZP.rotationDegrees(inPlane));
        ps.translate(-cx, -cy, 0f);
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

        boolean horizontal = plane.horizontal();
        float inPlane = plane.inPlaneRotationDeg();
        boolean mirror = plane.mirrorWinding();
        if (horizontal) {
            // 只摊平、不整体旋转：9 宫格外框保持网格对齐（monitor_slab 正方形网格内屏幕区域位置固定，
            // 外框整体旋转会导致宽高互换/错位，如 4×5 → 5×4）；内容单独绕屏幕中心旋转见下方文字部分
            plane = wrapHorizontal(ps, plane);
        }

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
            renderCorner(ps, vc, corner, scrX, scrY, scrZ, 0, mirror, light, overlay);
            renderCorner(ps, vc, corner, scrX + scrW - borderSize, scrY, scrZ, 90, mirror, light, overlay);
            renderCorner(ps, vc, corner, scrX, scrY + scrH - borderSize, scrZ, -90, mirror, light, overlay);
            renderCorner(ps, vc, corner, scrX + scrW - borderSize, scrY + scrH - borderSize, scrZ, 180, mirror, light, overlay);
        }

        // ── 四边（平铺，避免纹理拉伸变形）──
        if (edge != null) {
            int edgeTilesH = Math.max(0, scr.width() - 2);  // 水平边单元数
            int edgeTilesV = Math.max(0, scr.height() - 2); // 垂直边单元数
            for (int i = 0; i < edgeTilesV; i++) {
                renderCorner(ps, vc, edge, scrX + scrW - borderSize, scrY + borderSize + i * cellSize, scrZ, 180, mirror, light, overlay);
                renderCorner(ps, vc, edge, scrX, scrY + borderSize + i * cellSize, scrZ, 0, mirror, light, overlay);
            }
            for (int i = 0; i < edgeTilesH; i++) {
                renderCorner(ps, vc, edge, scrX + borderSize + i * cellSize, scrY, scrZ, 90, mirror, light, overlay);
                renderCorner(ps, vc, edge, scrX + borderSize + i * cellSize, scrY + scrH - borderSize, scrZ, -90, mirror, light, overlay);
            }
        }

        // ── 中央面板（XY 双向拉伸；center 模型 1px 宽 → scale = 格数）──
        if (center != null && innerW > 0.001f && innerH > 0.001f) {
            ps.pushPose();
            ps.translate(scrX + borderSize, scrY + borderSize, scrZ);
            ps.scale(innerW / cellSize, innerH / cellSize, 1);
            renderModel(ps, vc, center, mirror, light, overlay);
            ps.popPose();
        }

        // ── 屏幕字符 / 图形（内容跟随 FACING：绕屏幕区域中心旋转，9 宫格外框不动）──
        // 水平面在 wrap 后的局部帧内旋转；垂直帧（天花板 = 反射帧）直接在帧内旋转，
        // 旋转轴均为本地 Z（水平面 = 世界 −Y；天花板反射帧 = 世界 +Y，inPlane 已取反），与 blockstate y 同向。
        // inPlane=0（Monitor/monitor_2）零影响。
        if (text != null && text.hasContent()) {
            if (inPlane != 0f) {
                ps.pushPose();
                applyInPlaneRotation(ps, scr, plane, inPlane);
                renderTextContent(ps, buffer, scr, text, plane, horizontal, mirror);
                ps.popPose();
            } else {
                renderTextContent(ps, buffer, scr, text, plane, horizontal, mirror);
            }
        }

        if (horizontal) {
            ps.popPose();
        }
    }

    /** 在屏幕内区渲染格子文本缓冲（格子模型：每格字符 + 前景/背景色 + 图形层）。 */
    public static void renderScreenText(PoseStack ps, MultiBufferSource buffer,
                                        GridState.ScreenRegion scr, ScreenText text, ScreenPlane plane) {
        boolean horizontal = plane.horizontal();
        float inPlane = plane.inPlaneRotationDeg();
        boolean mirror = plane.mirrorWinding();
        if (horizontal) {
            plane = wrapHorizontal(ps, plane);
            applyInPlaneRotation(ps, scr, plane, inPlane);
        }

        renderTextContent(ps, buffer, scr, text, plane, horizontal, mirror);

        if (horizontal) {
            ps.popPose();
        }
    }

    /** 格子文本绘制体（调用方已处理水平 wrap / 平面内旋转）；horizontal 决定文字 zBase 方向（水平面文字须在面板上方），
     *  mirror 决定字形绕序是否反转（反射帧补偿）。 */
    private static void renderTextContent(PoseStack ps, MultiBufferSource buffer,
                                          GridState.ScreenRegion scr, ScreenText text, ScreenPlane plane,
                                          boolean horizontal, boolean mirror) {
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
        // 内容基准面 = 屏幕 9 宫格中心面（screen_center 模型 north 面在 z=0.7px）。
        // 水平顶面 wrap 后 world Y = 平移 y(面板 9/16) − 本地z；用户 9.27 指定内容落在 8.3/16
        // （zBase = +0.7px → 世界 y = 9/16 − 0.7/16 = 8.3/16，与竖面同值 0.7px，贴合屏幕面板表面）。
        // 天花板反射帧：zBase = z() + 0.7/16（z()=−1/16 → −0.3/16 → 世界 y = 8/16 − 0.3/16 = 7.7/16 = 9 宫格正面）。
        float zBase = plane.z() + 0.7f / 16f;

        ScreenTextRenderer.drawAll(ps, buffer, text, contentRight, contentTop,
            contentLeft, contentBottom, innerWidthUnits, innerHeightUnits, zBase, mirror);
    }

    /** 渲染一个角模型，绕格子中心 Z 轴旋转（法线安全）。坐标均为块单位。 */
    private static void renderCorner(PoseStack ps, VertexConsumer vc, BakedModel corner,
                                     float cellX, float cellY, float scrZ, float zDegrees,
                                     boolean mirror, int light, int overlay) {
        float halfCell = 0.5f / 16f;
        ps.pushPose();
        ps.translate(cellX + halfCell, cellY + halfCell, scrZ);
        if (zDegrees != 0) ps.mulPose(Axis.ZP.rotationDegrees(zDegrees));
        ps.translate(-halfCell, -halfCell, 0);
        renderModel(ps, vc, corner, mirror, light, overlay);
        ps.popPose();
    }

    /** 渲染一个模型（全部面 + 无 cull 面）；{@code reverseWinding}=true 时反转 quad 绕序（反射帧补偿）。 */
    public static void renderModel(PoseStack ps, VertexConsumer consumer, BakedModel model, int light, int overlay) {
        renderModel(ps, consumer, model, false, light, overlay);
    }

    /** 渲染一个模型（全部面 + 无 cull 面）；{@code reverseWinding}=true 时反转 quad 绕序（反射帧补偿）。 */
    public static void renderModel(PoseStack ps, VertexConsumer consumer, BakedModel model,
                                   boolean reverseWinding, int light, int overlay) {
        var pose = ps.last();
        for (Direction dir : Direction.values()) {
            for (var q : model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null)) {
                BakedQuad quad = reverseWinding ? reverseWinding(q) : q;
                consumer.putBulkData(pose, quad, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
            }
        }
        for (var q : model.getQuads(null, null, RANDOM, ModelData.EMPTY, null)) {
            BakedQuad quad = reverseWinding ? reverseWinding(q) : q;
            consumer.putBulkData(pose, quad, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
        }
    }

    /** 复制 quad 并交换第 2/第 4 个顶点（0,1,2,3 → 0,3,2,1），反转绕序（反射帧的背面剔除补偿）。
     *  顶点记录长度按实际格式取（vertices.length / 4），不硬编码。 */
    private static BakedQuad reverseWinding(BakedQuad quad) {
        int[] v = quad.getVertices().clone();
        int stride = v.length / 4; // 每顶点 int 数（DefaultVertexFormats.BLOCK = 7）
        for (int i = 0; i < stride; i++) {
            int tmp = v[stride + i];
            v[stride + i] = v[3 * stride + i];
            v[3 * stride + i] = tmp;
        }
        return new BakedQuad(v, quad.getTintIndex(), quad.getDirection(), quad.getSprite(),
                quad.isShade(), quad.hasAmbientOcclusion());
    }
}
