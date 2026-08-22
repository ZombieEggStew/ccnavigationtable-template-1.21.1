package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.monitor.ScreenText;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 屏幕渲染器（方案三：格子模型）。
 * <p>
 * 文本层为定长格子数组：每格 = 字符 + 前景色 + 背景色，同位置永远只有一个值（无重叠面片）。
 * 每格最多 2 个 quad —— 背景格（纯色）+ 字形（带 UV），字符画在填充色之上。
 * <p>
 * 深度防 z-fighting 策略（参考原版告示牌 {@code SignRenderer}，已与作者确认）：
 * <b>渲染平面 = screen 模块外边面</b>（screen 9 宫格模型 north 面，{@code zBase = (SCREEN_Z + 0.7)/16}）。
 * 内容只做<b>极小贴面前移</b>（1/2048 块级，肉眼不可见，量级对齐原版 {@code TEXT_OFFSET}），
 * 同平面的字形 / 背景格深度区分交给 {@link RenderType#textPolygonOffset}
 * （其 {@code POLYGON_OFFSET_LAYERING} state shard 在 RenderType 切换时自动
 * {@code polygonOffset(-1,-10)} + enable/disable，适配 MultiBufferSource 延迟批处理）。
 * <ul>
 *   <li>背景格：{@code zBase - 1/2048}（贴面，无 polygon offset，最底）</li>
 *   <li>字形：{@code zBase - 1/2048 - 1/2048} + textPolygonOffset（polygonOffset 前移，盖在背景格上）</li>
 *   <li>图形层（rect/line/circle）：{@code zBase - 1/2048 - 1/4096 - z * 1/2048}，
 *       默认（z=0）在字形之下、背景格之上；z 越大越靠前（保留层级语义）</li>
 * </ul>
 * <p>
 * 字形源使用 vanilla {@code minecraft:textures/font/ascii.png}（16×16 网格、每格 8×8 像素），
 * 字符 c 映射到第 c 格（col = c%16，row = c/16），用最近邻采样（无 mipmap）绘制。
 * 背景用独立的纯色 quad（POSITION_COLOR）绘制。
 */
public final class ScreenTextRenderer {

    /** vanilla 位图字体图集。 */
    private static final ResourceLocation FONT_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/font/ascii.png");

    /** 图集为 16×16 网格。 */
    private static final int GLYPHS_PER_ROW = 16;
    private static final float GLYPH_UV_SIZE = 1f / GLYPHS_PER_ROW;
    /** 半像素内缩，避免最近邻采样时相邻字形串色。 */
    private static final float UV_INSET = 0.001f;

    /** 纯色 quad（背景格 / 矩形 / 描边 / 圆，无纹理）。 */
    private static final RenderType SOLID_BG = RenderType.create(
            CCPeripheralExtender.MOD_ID + ":screen_text_bg",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    /** 内容相对 screen 模块外边面的极小贴面前移（块，1/2048），量级对齐原版 SignRenderer TEXT_OFFSET。 */
    private static final float SURFACE_FRONT = 1f / 2048f;
    /** 图形层相对背景格的固定前移（块，1/4096），默认位于字形之下、背景格之上。 */
    private static final float SHAPE_FRONT = 1f / 4096f;
    /** 字形相对图形层默认位置的固定前移（块，1/2048），配合 textPolygonOffset 盖在最上。 */
    private static final float GLYPH_FRONT = 1f / 2048f;
    /** 图形层 z 每 +1 向前移动的距离（块，1/2048 级）。 */
    private static final float Z_STEP = 1f / 2048f;

    private ScreenTextRenderer() {}

    /**
     * 在同一平面绘制缓冲中的全部格子与图形。
     *
     * @param ps                PoseStack（已在 monitor 面朝北的坐标空间）
     * @param buffer            MultiBufferSource
     * @param text              屏幕文本缓冲（格子模型）
     * @param fullRight         可绘制区物理右边缘（世界坐标，块）
     * @param fullTop           可绘制区物理上边缘（世界坐标，块）
     * @param left              可绘制区物理左边缘（世界坐标，块）
     * @param bottom            可绘制区物理下边缘（世界坐标，块）
     * @param innerWidthUnits   可绘制区宽（drawRect 单位，图形层裁剪用）
     * @param innerHeightUnits  可绘制区高（drawRect 单位，图形层裁剪用）
     * @param zBase             内容基准面（屏幕 9 宫格中心面，世界坐标，块）
     */
    public static void drawAll(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                               float fullRight, float fullTop, float left, float bottom,
                               float innerWidthUnits, float innerHeightUnits, float zBase) {
        drawCellBackgrounds(ps, buffer, text, fullRight, fullTop, left, bottom, zBase);
        drawCells(ps, buffer, text, fullRight, fullTop, left, bottom, zBase);
        drawRects(ps, buffer, text, fullRight, fullTop, left, bottom, zBase);
        drawLines(ps, buffer, text, fullRight, fullTop, left, bottom, zBase);
        drawCircles(ps, buffer, text, fullRight, fullTop, left, bottom, zBase);
    }

    // ── 格子几何 ──

    /** 单格宽度（块）。 */
    private static float cellWidth(float fullRight, float left, int cols) {
        return Math.max(1e-6f, (fullRight - left) / Math.max(1, cols));
    }

    /** 单格高度（块）。 */
    private static float cellHeight(float fullTop, float bottom, int rows) {
        return Math.max(1e-6f, (fullTop - bottom) / Math.max(1, rows));
    }

    /**
     * 格子 (col,row)（1 起）的物理边界：物理左/右/上/下。
     * 北面视图翻转：逻辑列向右 → 物理向左（col=1 贴物理右缘）。
     */
    private static float cellLeft(float fullRight, float cellW, int col) {
        return fullRight - col * cellW;
    }

    private static float cellRight(float fullRight, float cellW, int col) {
        return fullRight - (col - 1) * cellW;
    }

    private static float cellBottom(float fullTop, float cellH, int row) {
        return fullTop - row * cellH;
    }

    private static float cellTop(float fullTop, float cellH, int row) {
        return fullTop - (row - 1) * cellH;
    }

    // ── 背景格 ──

    /** 绘制全部非透明背景格（fill 填充色），字符画在其上。 */
    private static void drawCellBackgrounds(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                            float fullRight, float fullTop, float left, float bottom, float zBase) {
        int cols = text.getCols();
        int rows = text.getRows();
        float cellW = cellWidth(fullRight, left, cols);
        float cellH = cellHeight(fullTop, bottom, rows);
        float pad = (float) text.getFillPadding();
        float padX = cellW * pad;
        float padY = cellH * pad;
        Matrix4f pose = ps.last().pose();
        VertexConsumer vc = buffer.getBuffer(SOLID_BG);

        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                int bg = text.getCellBg(col, row);
                if (bg == ScreenText.TRANSPARENT_BG) continue;
                float x0 = cellLeft(fullRight, cellW, col) + padX;
                float x1 = cellRight(fullRight, cellW, col) - padX;
                float y0 = cellBottom(fullTop, cellH, row) + padY;
                float y1 = cellTop(fullTop, cellH, row) - padY;
                if (x0 >= x1 || y0 >= y1) continue;
                colorQuad(vc, pose, x0, y0, x1, y1, bgDepth(zBase), bg);
            }
        }
    }

    // ── 字形（格子文本层） ──

    /** 绘制全部非空格子的字形。 */
    private static void drawCells(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                  float fullRight, float fullTop, float left, float bottom, float zBase) {
        int cols = text.getCols();
        int rows = text.getRows();
        float cellW = cellWidth(fullRight, left, cols);
        float cellH = cellHeight(fullTop, bottom, rows);
        // 字形为正方形，贴合格子宽；格子比字形高时垂直居中
        float glyph = Math.min(cellW, cellH);
        float yOffset = (cellH - glyph) / 2f;
        Matrix4f pose = ps.last().pose();
        float z = glyphDepth(zBase);
        VertexConsumer vc = buffer.getBuffer(RenderType.textPolygonOffset(FONT_TEXTURE));

        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                char ch = text.getCellChar(col, row);
                if (ch == ' ' || ch == '\0') continue;
                float xRight = cellRight(fullRight, cellW, col);
                float xLeft = xRight - glyph;
                float yTop = cellTop(fullTop, cellH, row) - yOffset;
                float yBottom = yTop - glyph;
                glyphQuad(vc, pose, ch, xLeft, yBottom, xRight, yTop,
                        text.getCellFg(col, row), z);
            }
        }
    }

    /**
     * 绘制一个字形（带 UV 的 quad，采样 ascii.png 对应格，整格无裁剪）。
     * <p>
     * 顶点环绕顺序与旋钮角度文字（font.drawInBatch 经 -Y 缩放）一致：左下→左上→右上→右下，
     * 正面朝向玩家（此顺序才能通过 CULL 被看到）。
     * <p>
     * UV 必须<b>水平翻转</b>（左顶点采 {@code uRight}、右顶点采 {@code uLeft}）：
     * 「北面局部 X 轴」与「屏幕逻辑 X 轴」相反，不翻转则每个字符左右镜像
     * （见 memo/record_screen_text.md 踩坑记录）。
     * RenderType.textPolygonOffset 使用 POSITION_COLOR_TEX_LIGHTMAP 格式，必须补 UV2（fullbright 发光）。
     */
    private static void glyphQuad(VertexConsumer vc, Matrix4f pose, char ch,
                                  float x0, float y0, float x1, float y1, int colour, float z) {
        int code = ch & 0xFF; // 仅支持 ASCII / Latin-1
        int col = code % GLYPHS_PER_ROW;
        int row = code / GLYPHS_PER_ROW;

        float uLeft = col * GLYPH_UV_SIZE + UV_INSET;
        float uRight = (col + 1) * GLYPH_UV_SIZE - UV_INSET;
        float vTop = row * GLYPH_UV_SIZE + UV_INSET;
        float vBottom = (row + 1) * GLYPH_UV_SIZE - UV_INSET;

        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;

        // 左顶点采 uRight、右顶点采 uLeft（水平翻转，纠正北面镜像）
        vc.addVertex(pose, x0, y0, z).setColor(r, g, b, 1f).setUv(uRight, vBottom).setLight(LightTexture.FULL_BRIGHT);
        vc.addVertex(pose, x0, y1, z).setColor(r, g, b, 1f).setUv(uRight, vTop).setLight(LightTexture.FULL_BRIGHT);
        vc.addVertex(pose, x1, y1, z).setColor(r, g, b, 1f).setUv(uLeft, vTop).setLight(LightTexture.FULL_BRIGHT);
        vc.addVertex(pose, x1, y0, z).setColor(r, g, b, 1f).setUv(uLeft, vBottom).setLight(LightTexture.FULL_BRIGHT);
    }

    // ── 图形层（自由定位 + z 层级，仅限可绘制区域内） ──

    /** 背景格深度（块）：贴 screen 模块外边面（仅极小前移避免与面板同面）。 */
    private static float bgDepth(float zBase) {
        return zBase - SURFACE_FRONT;
    }

    /** 字形深度（块）：背景格之上，配合 textPolygonOffset 的 polygonOffset 前移盖在最上。 */
    private static float glyphDepth(float zBase) {
        return zBase - SURFACE_FRONT - GLYPH_FRONT;
    }

    /** 图形深度（块）。z 越大越靠前；默认位于字形之下、背景格之上。 */
    private static float shapeDepth(float zBase, double z) {
        return zBase - SURFACE_FRONT - SHAPE_FRONT - (float) z * Z_STEP;
    }

    /**
     * 绘制矩形指令。矩形使用屏幕局部像素坐标（原点左上，X 向右、Y 向下），
     * X 轴与文本同向翻转（逻辑左 ↔ 物理右），Y 轴逻辑上 ↔ 物理上。
     * 深度由每个矩形的 {@code z} 层级决定（越大越靠前）。
     */
    public static void drawRects(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                 float fullRight, float fullTop, float left, float bottom, float zBase) {
        Matrix4f pose = ps.last().pose();
        float px = 1f / 128f; // 1 Lua 单位 = 1/128 块

        for (ScreenText.Rect rect : text.getRects()) {
            float x0 = fullRight - (float) (rect.x() + rect.width()) * px;  // 物理左
            float x1 = fullRight - (float) rect.x() * px;                   // 物理右
            float y0 = fullTop - (float) (rect.y() + rect.height()) * px;   // 物理下
            float y1 = fullTop - (float) rect.y() * px;                     // 物理上

            float depth = shapeDepth(zBase, rect.z());
            VertexConsumer vc = buffer.getBuffer(SOLID_BG);

            if (rect.solid()) {
                solidQuad(vc, pose, x0, y0, x1, y1, fullRight, left, fullTop, bottom,
                        depth, rect.colour());
                continue;
            }

            float lw = Math.max(0.001f, (float) rect.lineWidth()) * px; // 线宽（块）
            // 上边
                solidQuad(vc, pose, x0, y1 - lw, x1, y1, fullRight, left, fullTop, bottom,
                    depth, rect.colour());
            // 下边
                solidQuad(vc, pose, x0, y0, x1, y0 + lw, fullRight, left, fullTop, bottom,
                    depth, rect.colour());
            // 左边
                solidQuad(vc, pose, x0, y0, x0 + lw, y1, fullRight, left, fullTop, bottom,
                    depth, rect.colour());
            // 右边
                solidQuad(vc, pose, x1 - lw, y0, x1, y1, fullRight, left, fullTop, bottom,
                    depth, rect.colour());
        }
    }

    /** 绘制线段（1/128 块逻辑坐标，原点内区左上角）。 */
    public static void drawLines(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                 float fullRight, float fullTop, float left, float bottom, float zBase) {
        Matrix4f pose = ps.last().pose();
        float px = 1f / 128f;

        for (ScreenText.Line line : text.getLines()) {
            float depth = shapeDepth(zBase, line.z());
            lineQuad(buffer, pose, fullRight, fullTop, px,
                    line.x1(), line.y1(), line.x2(), line.y2(),
                    Math.max(0, line.lineWidth()) / 2.0, fullRight, left, fullTop, bottom, depth, line.colour());
        }
    }

    /** 绘制圆（1/128 块逻辑坐标；正多边形逼近）。 */
    public static void drawCircles(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                   float fullRight, float fullTop, float left, float bottom, float zBase) {
        Matrix4f pose = ps.last().pose();
        float px = 1f / 128f;

        for (ScreenText.Circle c : text.getCircles()) {
            float depth = shapeDepth(zBase, c.z());
            int n = Math.max(3, c.segments());
            double r = c.radius();
            VertexConsumer vc = buffer.getBuffer(SOLID_BG);

            if (c.solid()) {
                // 三角扇：中心 + 相邻两个圆周点
                float ccx = fullRight - (float) c.cx() * px;
                float ccy = fullTop - (float) c.cy() * px;
                for (int i = 0; i < n; i++) {
                    double a0 = 2 * Math.PI * i / n;
                    double a1 = 2 * Math.PI * (i + 1) / n;
                    float p0x = fullRight - (float) (c.cx() + r * Math.cos(a0)) * px;
                    float p0y = fullTop - (float) (c.cy() + r * Math.sin(a0)) * px;
                    float p1x = fullRight - (float) (c.cx() + r * Math.cos(a1)) * px;
                    float p1y = fullTop - (float) (c.cy() + r * Math.sin(a1)) * px;
                        quad(vc, pose, ccx, ccy, p0x, p0y, p1x, p1y, ccx, ccy,
                            fullRight, left, fullTop, bottom, depth, c.colour());
                }
            } else {
                // 圆环：内外两个同心多边形，逐段画梯形 quad（顶点共享，无缝隙/无重叠）
                double halfW = Math.max(0, c.lineWidth()) / 2.0;
                double rOuter = r + halfW;
                double rInner = Math.max(0, r - halfW);

                for (int i = 0; i < n; i++) {
                    double a0 = 2 * Math.PI * i / n;
                    double a1 = 2 * Math.PI * (i + 1) / n;

                    float o0x = fullRight - (float) (c.cx() + rOuter * Math.cos(a0)) * px;
                    float o0y = fullTop - (float) (c.cy() + rOuter * Math.sin(a0)) * px;
                    float o1x = fullRight - (float) (c.cx() + rOuter * Math.cos(a1)) * px;
                    float o1y = fullTop - (float) (c.cy() + rOuter * Math.sin(a1)) * px;

                    float i0x = fullRight - (float) (c.cx() + rInner * Math.cos(a0)) * px;
                    float i0y = fullTop - (float) (c.cy() + rInner * Math.sin(a0)) * px;
                    float i1x = fullRight - (float) (c.cx() + rInner * Math.cos(a1)) * px;
                    float i1y = fullTop - (float) (c.cy() + rInner * Math.sin(a1)) * px;

                    // 梯形：外[i] → 内[i] → 内[i+1] → 外[i+1]
                        quad(vc, pose, o0x, o0y, i0x, i0y, i1x, i1y, o1x, o1y,
                            fullRight, left, fullTop, bottom, depth, c.colour());
                }
            }
        }
    }

    /** 绘制一个纯色轴对齐 quad。 */
    private static void solidQuad(VertexConsumer vc, Matrix4f pose,
                                  float x0, float y0, float x1, float y1,
                                  float fullRight, float left, float fullTop, float bottom, float z, int colour) {
        quad(vc, pose, x0, y0, x0, y1, x1, y1, x1, y0, fullRight, left, fullTop, bottom, z, colour);
    }

    /** 绘制一个任意四边形的纯色 quad（SOLID_BG 为 NO_CULL，无需管绕序）。 */
    private static void quad(VertexConsumer vc, Matrix4f pose,
                             float x0, float y0, float x1, float y1,
                             float x2, float y2, float x3, float y3,
                             float fullRight, float left, float fullTop, float bottom, float z, int colour) {
        x0 = Math.max(left, Math.min(fullRight, x0));
        x1 = Math.max(left, Math.min(fullRight, x1));
        x2 = Math.max(left, Math.min(fullRight, x2));
        x3 = Math.max(left, Math.min(fullRight, x3));
        y0 = Math.max(bottom, Math.min(fullTop, y0));
        y1 = Math.max(bottom, Math.min(fullTop, y1));
        y2 = Math.max(bottom, Math.min(fullTop, y2));
        y3 = Math.max(bottom, Math.min(fullTop, y3));
        colorQuad(vc, pose, x0, y0, x1, y1, x2, y2, x3, y3, z, colour);
    }

    /** 纯色 quad（任意四边形）。 */
    private static void colorQuad(VertexConsumer vc, Matrix4f pose,
                                  float x0, float y0, float x1, float y1, float z, int colour) {
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        vc.addVertex(pose, x0, y0, z).setColor(r, g, b, 1f);
        vc.addVertex(pose, x0, y1, z).setColor(r, g, b, 1f);
        vc.addVertex(pose, x1, y1, z).setColor(r, g, b, 1f);
        vc.addVertex(pose, x1, y0, z).setColor(r, g, b, 1f);
    }

    /** 纯色 quad（任意四边形）。 */
    private static void colorQuad(VertexConsumer vc, Matrix4f pose,
                                  float x0, float y0, float x1, float y1,
                                  float x2, float y2, float x3, float y3, float z, int colour) {
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        vc.addVertex(pose, x0, y0, z).setColor(r, g, b, 1f);
        vc.addVertex(pose, x1, y1, z).setColor(r, g, b, 1f);
        vc.addVertex(pose, x2, y2, z).setColor(r, g, b, 1f);
        vc.addVertex(pose, x3, y3, z).setColor(r, g, b, 1f);
    }

    /**
     * 画一条有宽度的线段：由方向向量 + 垂直方向 ±halfW 得到 4 个角。
     * 输入为 drawRect 逻辑坐标（1/128 块），内部映射到世界坐标。
     */
    private static void lineQuad(MultiBufferSource buffer, Matrix4f pose,
                                 float fullRight, float fullTop, float px,
                                 double x1, double y1, double x2, double y2, double halfW,
                                 float right, float left, float top, float bottom, float z, int colour) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-9) return;

        double nx = -dy / len * halfW;
        double ny = dx / len * halfW;

        float ax = fullRight - (float) (x1 - nx) * px;
        float ay = fullTop - (float) (y1 - ny) * px;
        float bx = fullRight - (float) (x1 + nx) * px;
        float by = fullTop - (float) (y1 + ny) * px;
        float cx = fullRight - (float) (x2 + nx) * px;
        float cy = fullTop - (float) (y2 + ny) * px;
        float dx2 = fullRight - (float) (x2 - nx) * px;
        float dy2 = fullTop - (float) (y2 - ny) * px;

        quad(buffer.getBuffer(SOLID_BG), pose, ax, ay, bx, by, cx, cy, dx2, dy2,
            right, left, top, bottom, z, colour);
    }
}
