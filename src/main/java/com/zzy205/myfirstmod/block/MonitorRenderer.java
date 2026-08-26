package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.monitor.ScreenText;
import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.client.MonitorBackgrounds;
import com.zzy205.myfirstmod.client.MonitorTransform;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
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
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Monitor BER — 在屏幕表面渲染已放置的模块模型。
 * 各模块的微调/动画逻辑见 {@link ModuleRenderBehavior}；
 * 旋钮角度文字/按钮标签见 {@link ModuleSurfaceRenderer}；屏幕 9 宫格/文字见 {@link Screen9GridRenderer}。
 * 
 * animProgress 使用 (BlockPos, moduleId) 复合 key，
 * 防止不同 Monitor 之间同 moduleId 的动画进度互相污染。
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    private static final float PRESS_DEPTH = 0.6f;

    /** 每个 Monitor 独立的动画进度表，外层 key=BlockPos，内层 key=moduleId */
    private final Map<BlockPos, Map<Integer, Float>> animProgress = new HashMap<>();

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        BlockPos bePos = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(MonitorBlock.FACING);

        // Flywheel 可用时外壳（bearing/case）由 MonitorVisual 实例化渲染，BER 只负责动态内容
        Level level = be.getLevel();
        boolean shellInstanced = level != null && VisualizationManager.supportsVisualization(level);

        poseStack.pushPose();
        // 底座由方块模型（blockstate → my_monitor_base）固定渲染，BER 只负责可动部分。
        // facing → offset → yaw（外层到内层）
        MonitorTransform.applyFacing(poseStack, facing);
        MonitorTransform.applyOffset(poseStack, be.getOffset());
        MonitorTransform.applyYaw(poseStack, be.getYawAngle());

        // bearing：随 facing + offset + yaw，不随 pitch
        if (!shellInstanced) {
            BakedModel bearingModel = MonitorPreloadedModels.getMonitorBearing();
            if (bearingModel != null) {
                Screen9GridRenderer.renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), bearingModel, light, overlay);
            }
        }

        // case 与所有屏幕内容：随 facing + offset + yaw + pitch。
        // case 模型带 render_type=cutout（前脸有屏幕开孔），必须用 cutout 片，否则背景/屏幕文字被不透明前脸遮挡。
        MonitorTransform.applyPitch(poseStack, be.getPitchAngle());
        if (!shellInstanced) {
            BakedModel caseModel = MonitorPreloadedModels.getMonitorCase();
            if (caseModel != null) {
                Screen9GridRenderer.renderModel(poseStack, buffer.getBuffer(Sheets.cutoutBlockSheet()), caseModel, light, overlay);
            }
        }

        // ── 背景面板（始终渲染，覆盖原 screen 元素的面板贴图） ──
        renderBackground(poseStack, buffer, be.getBackground(), light);

        var grid = be.getGridState();

        // ── 渲染模块 ──
        var beAnims = animProgress.computeIfAbsent(bePos, k -> new HashMap<>());
        beAnims.keySet().removeIf(id -> !grid.getAllModules().containsKey(id));

        for (var mod : grid.getAllModules().values()) {
            var bhv = ModuleRenderBehavior.of(mod.type());
            boolean isKnob = mod.type() == ModuleType.KNOB;

            float px = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + mod.gridX()) / 16f + bhv.offsetX();
            float py = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + mod.gridY()) / 16f + bhv.offsetY();
            float pz = MonitorBlock.SCREEN_Z / 16f + bhv.offsetZ();

            if (shellInstanced) {
                // 模块模型由 MonitorVisual（Flywheel）实例化渲染；BER 只画模块上的文字与按钮灯带
                Float visualAnim = MonitorVisual.getModuleAnim(bePos, mod.id());
                float next = visualAnim != null ? visualAnim
                        : (isKnob ? grid.getKnobAngle(mod.id()) : (grid.isPressed(mod.id()) ? 1f : 0f));

                poseStack.pushPose();
                poseStack.translate(px, py, pz);
                bhv.applyInitialRotation(poseStack);
                if (isKnob) {
                    ModuleSurfaceRenderer.renderKnobAngle(poseStack, buffer, bePos, mod.id(), light,
                            grid.getKnobAngle(mod.id()), grid.getModuleConfig(mod.id()),
                            ModuleSurfaceRenderer.MONITOR);
                }
                if (mod.type() == ModuleType.BUTTON_1X1) {
                    float lightLevel = grid.isLightCodeControlled(mod.id())
                            ? grid.getLightBrightness(mod.id()) : next;
                    ((ModuleRenderBehavior.ButtonBehavior) bhv).renderIndicator(poseStack, buffer, next, lightLevel);
                    ModuleSurfaceRenderer.renderButtonLabel(poseStack, buffer, grid.getButtonLabel(mod.id()), next, light);
                }
                poseStack.popPose();
                continue;
            }

            // ── Flywheel 不可用：BER 全量渲染（含模块模型与部件） ──
            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            float target;
            if (isKnob) {
                // 拖拽中优先使用客户端视觉角度（卡位微扭动）；否则跟随服务端角度
                Float visual = MonitorGridOverlay.getActiveKnobVisualAngle(bePos, mod.id());
                target = visual != null ? visual : grid.getKnobAngle(mod.id());
            } else {
                target = grid.isPressed(mod.id()) ? 1f : 0f;
            }
            float next = ModuleRenderBehavior.stepAnim(beAnims, mod.id(), isKnob, target,
                    bhv.animPressSpeed(), bhv.animReleaseSpeed());

            poseStack.pushPose();
            if (bhv.usePressDepth()) pz += PRESS_DEPTH * next / 16f;
            poseStack.translate(px, py, pz);
            bhv.applyInitialRotation(poseStack);

            // 底座
            Screen9GridRenderer.renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), model, light, overlay);
            // 额外部件（拉杆等）。按钮灯带亮度：代码控制时用 Lua 亮度，否则跟随按下动画
            float lightLevel = next;
            if (mod.type() == ModuleType.BUTTON_1X1) {
                lightLevel = grid.isLightCodeControlled(mod.id())
                        ? grid.getLightBrightness(mod.id()) : next;
            }
            bhv.renderExtra(poseStack, buffer, next, lightLevel, light, overlay);
            if (isKnob) {
                ModuleSurfaceRenderer.renderKnobAngle(poseStack, buffer, bePos, mod.id(), light,
                        grid.getKnobAngle(mod.id()), grid.getModuleConfig(mod.id()),
                        ModuleSurfaceRenderer.MONITOR);
            }
            if (mod.type() == ModuleType.BUTTON_1X1) {
                ModuleSurfaceRenderer.renderButtonLabel(poseStack, buffer, grid.getButtonLabel(mod.id()), next, light);
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

    // ── 屏幕 9 宫格渲染 ──

    /** Monitor 屏幕面参数（块单位），9 宫格/文字共享渲染用（见 {@link Screen9GridRenderer}）。 */
    private static final Screen9GridRenderer.ScreenPlane MONITOR_PLANE = new Screen9GridRenderer.ScreenPlane() {
        @Override public float originX() { return (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET) / 16f; }
        @Override public float originY() { return (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET) / 16f; }
        @Override public float z() { return MonitorBlock.SCREEN_Z / 16f; }
    };

    private void renderScreen(PoseStack ps, MultiBufferSource buffer,
                              GridState.ScreenRegion scr, ScreenText text, int light, int overlay) {
        BakedModel corner = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CORNER);
        BakedModel edge   = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_EDGE);
        BakedModel center = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CENTER);

        Screen9GridRenderer.renderScreen(ps, buffer, corner, edge, center, scr, text, MONITOR_PLANE, light, overlay);
    }
}
