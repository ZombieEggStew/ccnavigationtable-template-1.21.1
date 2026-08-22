package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.monitor.ButtonLabel;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.monitor.ScreenText;
import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.client.MonitorBackgrounds;
import com.zzy205.myfirstmod.client.MonitorTransform;
import com.zzy205.myfirstmod.client.ScreenTextRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.Map;

/**
 * Monitor BER — 在屏幕表面渲染已放置的模块模型。
 * 各模块的微调/动画逻辑见 {@link ModuleRenderBehavior}。
 * 
 * animProgress 使用 (BlockPos, moduleId) 复合 key，
 * 防止不同 Monitor 之间同 moduleId 的动画进度互相污染。
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    private static final RandomSource RANDOM = RandomSource.create(42L);
    private static final float PRESS_DEPTH = 0.6f;

    /** 按钮标签相对 head 前脸（z=0.625px）向前 0.01px 的 z 坐标（模型像素），避免 z-fighting。 */
    private static final float BUTTON_LABEL_FRONT_Z_PX = 0.615f;
    /** 按钮 head 按压凹陷深度（模型像素），与 ModuleRenderBehavior.ButtonBehavior.PRESS_DEPTH 一致。 */
    private static final float BUTTON_PRESS_DEPTH_PX = 0.2f;
    /** 标签坐标原点 X（模型像素）：按钮 head 的水平中心。 */
    private static final float BUTTON_LABEL_ORIGIN_X_PX = 0.5f;
    /** 标签坐标原点 Y（模型像素）：按钮 head 的视觉垂直中心。 */
    private static final float BUTTON_LABEL_ORIGIN_Y_PX = 0.35f;

    /** 每个 Monitor 独立的动画进度表，外层 key=BlockPos，内层 key=moduleId */
    private final Map<BlockPos, Map<Integer, Float>> animProgress = new HashMap<>();

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        BlockPos bePos = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(MonitorBlock.FACING);

        poseStack.pushPose();
        // 底座由方块模型（blockstate → my_monitor_base）固定渲染，BER 只负责可动部分。
        // facing → offset → yaw（外层到内层）
        MonitorTransform.applyFacing(poseStack, facing);
        MonitorTransform.applyOffset(poseStack, be.getOffset());
        MonitorTransform.applyYaw(poseStack, be.getYawAngle());

        // bearing：随 facing + offset + yaw，不随 pitch
        BakedModel bearingModel = MonitorPreloadedModels.getMonitorBearing();
        if (bearingModel != null) {
            renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), bearingModel, light, overlay);
        }

        // case 与所有屏幕内容：随 facing + offset + yaw + pitch。
        // case 模型带 render_type=cutout（前脸有屏幕开孔），必须用 cutout 片，否则背景/屏幕文字被不透明前脸遮挡。
        MonitorTransform.applyPitch(poseStack, be.getPitchAngle());
        BakedModel caseModel = MonitorPreloadedModels.getMonitorCase();
        if (caseModel != null) {
            renderModel(poseStack, buffer.getBuffer(Sheets.cutoutBlockSheet()), caseModel, light, overlay);
        }

        // ── 背景面板（始终渲染，覆盖原 screen 元素的面板贴图） ──
        renderBackground(poseStack, buffer, be.getBackground(), light);

        var grid = be.getGridState();

        // ── 渲染模块 ──
        var beAnims = animProgress.computeIfAbsent(bePos, k -> new HashMap<>());
        beAnims.keySet().removeIf(id -> !grid.getAllModules().containsKey(id));

        for (var mod : grid.getAllModules().values()) {
            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            var bhv = ModuleRenderBehavior.of(mod.type());

            boolean isKnob = mod.type() == ModuleType.KNOB;
            float target;
            if (isKnob) {
                // 拖拽中优先使用客户端视觉角度（卡位微扭动）；否则跟随服务端角度
                Float visual = MonitorGridOverlay.getActiveKnobVisualAngle(bePos, mod.id());
                target = visual != null ? visual : grid.getKnobAngle(mod.id());
            } else {
                target = grid.isPressed(mod.id()) ? 1f : 0f;
            }
            float current = beAnims.computeIfAbsent(mod.id(), ignored -> target);
            float delta = target - current;
            if (isKnob) {
                delta %= 360f;
                if (delta > 180f) delta -= 360f;
                else if (delta < -180f) delta += 360f;
            }
            float speed = delta >= 0f ? bhv.animPressSpeed() : bhv.animReleaseSpeed();
            // 动画速度定义为 20 TPS 下的每 tick 逼近比例，按实际帧时间推进，避免重绘频率改变动画观感。
            float frameTime = Math.min(Minecraft.getInstance().getTimer().getGameTimeDeltaTicks(), 2f);
            float next = current + delta * (1f - (float) Math.pow(1f - speed, frameTime));
            if (!isKnob && Math.abs(next - target) < 0.01f) next = target;
            if (isKnob && Math.abs(delta) < 0.01f) next = current;
            beAnims.put(mod.id(), next);

            poseStack.pushPose();

            float px = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + mod.gridX()) / 16f + bhv.offsetX();
            float py = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + mod.gridY()) / 16f + bhv.offsetY();
            float pz = MonitorBlock.SCREEN_Z / 16f + bhv.offsetZ();
            if (bhv.usePressDepth()) pz += PRESS_DEPTH * next / 16f;

            poseStack.translate(px, py, pz);
            bhv.applyInitialRotation(poseStack);

            // 底座
            renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), model, light, overlay);
            // 额外部件（拉杆等）。按钮灯带亮度：代码控制时用 Lua 亮度，否则跟随按下动画
            float lightLevel = next;
            if (mod.type() == ModuleType.BUTTON_1X1) {
                lightLevel = grid.isLightCodeControlled(mod.id())
                        ? grid.getLightBrightness(mod.id()) : next;
            }
            bhv.renderExtra(poseStack, buffer, next, lightLevel, light, overlay);
            if (isKnob) {
                renderKnobAngle(poseStack, buffer, bePos, mod.id(), light,
                        grid.getKnobAngle(mod.id()), grid.getModuleConfig(mod.id()));
            }
            if (mod.type() == ModuleType.BUTTON_1X1) {
                renderButtonLabel(poseStack, buffer, grid.getButtonLabel(mod.id()), next, light);
            }

            poseStack.popPose();
        }

        // ── 渲染所有屏幕 9 宫格 ──
        for (var screen : grid.getScreenRegions()) {
            renderScreen(poseStack, buffer, screen, grid.getScreenText(screen.id()), light, overlay);
        }

        poseStack.popPose();
    }

    /**
     * 渲染可更换的背景面板：在 screen 元素（z=5）前方 0.01px 处画一个 14×12 的朝北 quad，
     * 用当前背景贴图覆盖原面板。模块（z=4 平面）仍在其前方，不受影响。
     */
    private static void renderBackground(PoseStack ps, MultiBufferSource buffer, String backgroundKey, int light) {
        var externalTexture = MonitorBackgrounds.getTexture(backgroundKey);
        if (externalTexture != null) {
            renderBackgroundQuad(ps, buffer.getBuffer(RenderType.entitySolid(externalTexture)), 0f, 1f, 0f, 1f, light);
            return;
        }

        TextureAtlasSprite sprite = MonitorPreloadedModels.getBackgroundSprite(MonitorBackground.indexOf(backgroundKey));
        if (sprite == null) return;
        renderBackgroundQuad(ps, buffer.getBuffer(Sheets.solidBlockSheet()),
                sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), light);
    }

    private static void renderBackgroundQuad(PoseStack ps, VertexConsumer vc,
                                             float u0, float u1, float v0, float v1, int light) {

        float x0 = MonitorBlock.SCREEN_X_MIN / 16f;
        float x1 = MonitorBlock.SCREEN_X_MAX / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f;
        float y1 = MonitorBlock.SCREEN_Y_MAX / 16f;
        float z = (MonitorBlock.PANEL_Z - MonitorBlock.BACKGROUND_Z_OFFSET) / 16f;

        var pose = ps.last();

        // 朝北的面（法线 -Z），顶点顺序与 MC 北面一致；v0=贴图顶部 → 面板顶部
        vc.addVertex(pose.pose(), x0, y0, z).setColor(1f, 1f, 1f, 1f).setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
        vc.addVertex(pose.pose(), x0, y1, z).setColor(1f, 1f, 1f, 1f).setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
        vc.addVertex(pose.pose(), x1, y1, z).setColor(1f, 1f, 1f, 1f).setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
        vc.addVertex(pose.pose(), x1, y0, z).setColor(1f, 1f, 1f, 1f).setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
    }

    private static void renderKnobAngle(PoseStack poseStack, MultiBufferSource buffer,
                                        BlockPos monitorPos, int moduleId, int light, float serverAngle,
                                        CompoundTag config) {
        // 拖拽中优先显示客户端视觉角度；否则仅在准心悬浮于该旋钮上时显示服务端当前角度
        Float angle = MonitorGridOverlay.getActiveKnobAngle(monitorPos, moduleId);
        if (angle == null) {
            if (MonitorGridOverlay.getHoveredKnobModuleId(monitorPos) == moduleId) {
                angle = serverAngle;
            } else {
                return;
            }
        }

        int detentAngle = config.getInt("angle");
        boolean showDetent = config.getBoolean("detent_display") && detentAngle > 0;
        String text = showDetent
            ? String.valueOf(Math.round(angle / detentAngle))
            : Math.round(angle) + "°";
        var font = Minecraft.getInstance().font;
        float scale = 1f / 512f;
        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180));
        poseStack.translate(0.0f, 0.0F, 1f / 16f);
        poseStack.scale(scale, -scale, scale);
        font.drawInBatch(Component.literal(text), -font.width(text) / 2f, -font.lineHeight / 2f,
                0xFFFFFFFF, true, poseStack.last().pose(), buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, light);
        poseStack.popPose();
    }

    /**
     * 在按钮表面渲染标签文字，朝向与旋钮角度文字（{@link #renderKnobAngle}）完全一致：
     * 按钮没有旋钮的初始 XP-90，故在此补齐一次 XP-90，再走旋钮相同的 XP-90 + ZP-180 变换。
     * <p>
     * 文字落在按钮 head 前脸（north 面，z=0.625px）稍前方，并随按压凹陷动画一起移动。
     */
    private static void renderButtonLabel(PoseStack poseStack, MultiBufferSource buffer,
                                          ButtonLabel label, float anim, int light) {
        String text = label.text();
        if (text == null || text.isEmpty()) return;

        var font = Minecraft.getInstance().font;
        double scale = ButtonLabel.clampScale(label.scale());
        float s = (float) scale;
        int color = 0xFF000000 | (label.color() & 0xFFFFFF);

        // 标签 z：head 前脸(0.625)向前 0.01px，再叠加按压凹陷 0.2px*anim；
        // 变换后文字落在模块局部 z = -t，故 t 取负值。
        float t = -(BUTTON_LABEL_FRONT_Z_PX + BUTTON_PRESS_DEPTH_PX * anim) / 16f;

        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180));
        poseStack.translate(0.0f, 0.0F, t);
        poseStack.scale(s, -s, s);

        // 位置偏移（MC 像素 → 块 → 字体像素）：变换把字体 x/y 映射为世界 -x/-y，
        // 故 +x 右、+y 上需要取负换算；坐标原点为按钮 head 视觉中心（0.5, 0.35）。
        float effX = (float) label.x() + BUTTON_LABEL_ORIGIN_X_PX;
        float effY = (float) label.y() + BUTTON_LABEL_ORIGIN_Y_PX;
        float fontX = (float) (-effX / 16.0 / scale) - font.width(text) / 2f;
        float fontY = (float) (-effY / 16.0 / scale) - font.lineHeight / 2f;

        font.drawInBatch(Component.literal(text), fontX, fontY, color, label.dropShadow(),
                poseStack.last().pose(), buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, light);
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
                              GridState.ScreenRegion scr, ScreenText text, int light, int overlay) {
        BakedModel corner = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CORNER);
        BakedModel edge   = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_EDGE);
        BakedModel center = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CENTER);

        VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());

        float cellSize   = 1f / 16f;
        float borderSize = cellSize;   // 角模型占 1 格

        float scrX = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + scr.minX()) / 16f;
        float scrY = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + scr.minY()) / 16f;
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

        // ── 屏幕字符 / 图形 ──
        if (text != null && text.hasContent()) {
            renderScreenText(ps, buffer, scr, text);
        }
    }

    /** 在屏幕内区渲染格子文本缓冲（格子模型：每格字符 + 前景/背景色 + 图形层）。 */
    private void renderScreenText(PoseStack ps, MultiBufferSource buffer,
                                  GridState.ScreenRegion scr, ScreenText text) {
        float cellSize = 1f / 16f;
        float drawableInset = (float) ScreenText.DRAWABLE_INSET;

        float scrX = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + scr.minX()) / 16f;
        float scrY = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + scr.minY()) / 16f;
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
        float zBase = (MonitorBlock.SCREEN_Z + 0.7f) / 16f;

        ScreenTextRenderer.drawAll(ps, buffer, text, contentRight, contentTop,
            contentLeft, contentBottom, innerWidthUnits, innerHeightUnits, zBase);
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
