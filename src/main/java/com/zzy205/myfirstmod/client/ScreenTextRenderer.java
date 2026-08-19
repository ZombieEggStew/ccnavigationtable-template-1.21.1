package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.monitor.ScreenText;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 屏幕字符渲染器（方案 A：位图字体图集 + quad）。
 * <p>
 * 字形源使用 vanilla {@code minecraft:textures/font/ascii.png}（16×16 网格、每格 8×8 像素），
 * 字符 c 映射到第 c 格（col = c%16，row = c/16），用最近邻采样（无 mipmap）绘制，
 * 思路仿 CC:T 的 FixedWidthFontRenderer。
 * <p>
 * 背景用独立的纯色 quad（POSITION_COLOR）绘制，前景字形用带 UV 的 quad（RenderType.text）。
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

    /** 纯色 quad（矩形/描边，无纹理）。 */
    private static final RenderType SOLID_BG = RenderType.create(
            CCPeripheraExtender.MOD_ID + ":screen_text_bg",
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

    /** 层级 z 每 +1 向前移动的距离（块，0.01px）。 */
    private static final float Z_STEP = 0.01f / 16f;
    /** z=0 时字形在面板中心面前方 0.01px。 */
    private static final float GLYPH_FRONT = 0.01f / 16f;
    /** 同一 z 下矩形相对字形后退 0.005px，保证默认「文字在矩形上」。 */
    private static final float RECT_BACK = 0.005f / 16f;

    private ScreenTextRenderer() {}

    /** 在同一平面绘制缓冲中的全部字符和图形。 */
    public static void drawAll(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                               float fullRight, float fullTop, float zBase) {
        draw(ps, buffer, text, fullRight, fullTop, zBase);
        drawRects(ps, buffer, text, fullRight, fullTop, zBase);
        drawLines(ps, buffer, text, fullRight, fullTop, zBase);
        drawCircles(ps, buffer, text, fullRight, fullTop, zBase);
    }

    /** 字形深度（块）。z 越大越靠前。 */
    private static float glyphDepth(float zBase, double z) {
        return zBase - GLYPH_FRONT - (float) z * Z_STEP;
    }

    /** 矩形深度（块）。同一 z 下比字形略靠后。 */
    private static float rectDepth(float zBase, double z) {
        return glyphDepth(zBase, z) + RECT_BACK;
    }

    /**
     * 在屏幕内区绘制文本缓冲。
     * <p>
     * 每个字符用 drawRect 的局部坐标定位（{@code x,y} 为字符左上角，1/128 块，原点在内区左上角），
     * 与 {@link #drawRects} 使用同一套映射：逻辑左 ↔ 物理右、逻辑上 ↔ 物理上。
     * 深度由每个字符的 {@code z} 层级决定（越大越靠前）。
     *
     * @param ps        PoseStack（已在 monitor 面朝北的坐标空间）
     * @param buffer    MultiBufferSource
     * @param text      屏幕文本缓冲
     * @param fullRight 屏幕区物理右边缘（世界坐标，块）
     * @param fullTop   屏幕区物理上边缘（世界坐标，块）
     * @param zBase     内容基准面（面板中心面，世界坐标，块）
     */
    public static void draw(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                            float fullRight, float fullTop, float zBase) {
        int fg = text.getTextColour();

        float px = 1f / 128f;                        // 1 drawRect 单位 = 1/128 块
        float glyphBlocks = (float) text.getTextScale() / 16f; // 字形为正方形
        Matrix4f pose = ps.last().pose();

        for (ScreenText.TextChar ch : text.getChars()) {
            float xRight = fullRight - (float) ch.x() * px;   // 物理右
            float xLeft = xRight - glyphBlocks;               // 物理左
            float yTop = fullTop - (float) ch.y() * px;       // 物理上
            float yBottom = yTop - glyphBlocks;               // 物理下

            if (ch.ch() != ' ') {
                glyphQuad(buffer, pose, ch.ch(), xLeft, yBottom, xRight, yTop,
                        glyphDepth(zBase, ch.z()), fg);
            }
        }
    }

    /**
     * 绘制矩形指令。矩形使用屏幕局部像素坐标（原点左上，X 向右、Y 向下），
     * X 轴与文本同向翻转（逻辑左 ↔ 物理右），Y 轴逻辑上 ↔ 物理上。
     * 深度由每个矩形的 {@code z} 层级决定（越大越靠前）。
     *
     * @param fullRight 屏幕区物理右边缘（世界坐标，块）
     * @param fullTop   屏幕区物理上边缘（世界坐标，块）
     * @param zBase     内容基准面（面板中心面，世界坐标，块）
     */
    public static void drawRects(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                 float fullRight, float fullTop, float zBase) {
        Matrix4f pose = ps.last().pose();
        float px = 1f / 128f; // 1 Lua 单位 = 1/128 块

        for (ScreenText.Rect rect : text.getRects()) {
            float x0 = fullRight - (float) (rect.x() + rect.width()) * px;  // 物理左
            float x1 = fullRight - (float) rect.x() * px;                   // 物理右
            float y0 = fullTop - (float) (rect.y() + rect.height()) * px;   // 物理下
            float y1 = fullTop - (float) rect.y() * px;                     // 物理上

            float depth = rectDepth(zBase, rect.z());

            if (rect.solid()) {
                solidQuad(buffer, pose, x0, y0, x1, y1, depth, rect.colour());
                continue;
            }

            float lw = Math.max(0.001f, (float) rect.lineWidth()) * px; // 线宽（块）
            // 上边
            solidQuad(buffer, pose, x0, y1 - lw, x1, y1, depth, rect.colour());
            // 下边
            solidQuad(buffer, pose, x0, y0, x1, y0 + lw, depth, rect.colour());
            // 左边
            solidQuad(buffer, pose, x0, y0, x0 + lw, y1, depth, rect.colour());
            // 右边
            solidQuad(buffer, pose, x1 - lw, y0, x1, y1, depth, rect.colour());
        }
    }

    /** 绘制线段（1/128 块逻辑坐标，原点内区左上角）。 */
    public static void drawLines(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                 float fullRight, float fullTop, float zBase) {
        Matrix4f pose = ps.last().pose();
        float px = 1f / 128f;

        for (ScreenText.Line line : text.getLines()) {
            float depth = rectDepth(zBase, line.z());
            lineQuad(buffer, pose, fullRight, fullTop, px,
                    line.x1(), line.y1(), line.x2(), line.y2(),
                    Math.max(0, line.lineWidth()) / 2.0, depth, line.colour());
        }
    }

    /** 绘制圆（1/128 块逻辑坐标；正多边形逼近）。 */
    public static void drawCircles(PoseStack ps, MultiBufferSource buffer, ScreenText text,
                                   float fullRight, float fullTop, float zBase) {
        Matrix4f pose = ps.last().pose();
        float px = 1f / 128f;

        for (ScreenText.Circle c : text.getCircles()) {
            float depth = rectDepth(zBase, c.z());
            int n = Math.max(3, c.segments());
            double r = c.radius();

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
                    quad(buffer, pose, ccx, ccy, p0x, p0y, p1x, p1y, ccx, ccy, depth, c.colour());
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
                    quad(buffer, pose, o0x, o0y, i0x, i0y, i1x, i1y, o1x, o1y, depth, c.colour());
                }
            }
        }
    }

    /** 绘制一个字形（带 UV 的 quad，采样 ascii.png 对应格）。 */
    private static void glyphQuad(MultiBufferSource buffer, Matrix4f pose, char ch,
                                  float x0, float y0, float x1, float y1, float z, int colour) {
        int code = ch & 0xFF; // 仅支持 ASCII / Latin-1
        int col = code % GLYPHS_PER_ROW;
        int row = code / GLYPHS_PER_ROW;

        float uLeft = col * GLYPH_UV_SIZE + UV_INSET;
        float uRight = (col + 1) * GLYPH_UV_SIZE - UV_INSET;
        float vTop = row * GLYPH_UV_SIZE + UV_INSET;
        float vBottom = (row + 1) * GLYPH_UV_SIZE - UV_INSET;

        VertexConsumer vc = buffer.getBuffer(RenderType.text(FONT_TEXTURE));
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;

        // 顶点环绕顺序与旋钮角度文字（font.drawInBatch 经 -Y 缩放）一致：左下→左上→右上→右下，
        // 正面朝向玩家（此顺序才能通过 CULL 被看到）。北面局部 X 轴与字形纹理方向相反，故翻转 U。
        // RenderType.text 使用 POSITION_COLOR_TEX_LIGHTMAP 格式，必须补 UV2（fullbright 发光）
        vc.addVertex(pose, x0, y0, z).setColor(r, g, b, 1f).setUv(uRight, vBottom).setLight(LightTexture.FULL_BRIGHT);
        vc.addVertex(pose, x0, y1, z).setColor(r, g, b, 1f).setUv(uRight, vTop).setLight(LightTexture.FULL_BRIGHT);
        vc.addVertex(pose, x1, y1, z).setColor(r, g, b, 1f).setUv(uLeft, vTop).setLight(LightTexture.FULL_BRIGHT);
        vc.addVertex(pose, x1, y0, z).setColor(r, g, b, 1f).setUv(uLeft, vBottom).setLight(LightTexture.FULL_BRIGHT);
    }

    /** 绘制一个纯色轴对齐 quad。 */
    private static void solidQuad(MultiBufferSource buffer, Matrix4f pose,
                                  float x0, float y0, float x1, float y1, float z, int colour) {
        quad(buffer, pose, x0, y0, x0, y1, x1, y1, x1, y0, z, colour);
    }

    /** 绘制一个任意四边形的纯色 quad（SOLID_BG 为 NO_CULL，无需管绕序）。 */
    private static void quad(MultiBufferSource buffer, Matrix4f pose,
                             float x0, float y0, float x1, float y1,
                             float x2, float y2, float x3, float y3,
                             float z, int colour) {
        VertexConsumer vc = buffer.getBuffer(SOLID_BG);
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
                                 float z, int colour) {
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

        quad(buffer, pose, ax, ay, bx, by, cx, cy, dx2, dy2, z, colour);
    }
}
