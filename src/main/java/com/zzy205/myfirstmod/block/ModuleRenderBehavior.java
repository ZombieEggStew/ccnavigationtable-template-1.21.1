package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.render.RenderTypes;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.EnumMap;
import java.util.Map;

/**
 * 各模块的渲染微调和动画逻辑。
 * 新增元件时只需添加一个 Behavior 子类并注册。
 */
public abstract class ModuleRenderBehavior {

    private static final RandomSource RANDOM = RandomSource.create(42L);
    private static final Map<ModuleType, ModuleRenderBehavior> REGISTRY = new EnumMap<>(ModuleType.class);

    static {
        REGISTRY.put(ModuleType.BUTTON_1X1, new ButtonBehavior(MonitorPreloadedModels.BUTTON_1_HEAD, MonitorPreloadedModels.BUTTON_1_INDICATOR));
        REGISTRY.put(ModuleType.TOGGLE_SWITCH, new ToggleBehavior());
        REGISTRY.put(ModuleType.KNOB, new KnobBehavior());
    }

    public static ModuleRenderBehavior of(ModuleType type) {
        return REGISTRY.getOrDefault(type, new ButtonBehavior());
    }

    // ── 子类覆写 ──

    /** 模型空间中的额外偏移（块单位），用于微调位置 */
    public float offsetX() { return 0; }
    public float offsetY() { return 0; }
    public float offsetZ() { return 0; }

    /** 初始旋转（pos 已定位到屏幕表面后调用） */
    public void applyInitialRotation(PoseStack ps) {}

    /** 是否对底座施加按下深度动画（按钮需要，钮子开关不需要） */
    public boolean usePressDepth() { return true; }

    /** 按下动画速度（每帧），默认 0.1 */
    public float animPressSpeed() { return 0.1f; }
    /** 弹起动画速度（每帧），默认 0.1 */
    public float animReleaseSpeed() { return 0.1f; }

    /** 渲染额外部件（如拉杆）。anim 0=弹起, 1=按下 */
    public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, int light, int overlay) {}

    // ── 渲染工具 ──

    protected static void renderModel(PoseStack ps, VertexConsumer consumer, BakedModel model, int light, int overlay) {
        var pose = ps.last();
        for (Direction dir : Direction.values()) {
            for (var q : model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null))
                consumer.putBulkData(pose, q, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
        }
        for (var q : model.getQuads(null, null, RANDOM, ModelData.EMPTY, null))
            consumer.putBulkData(pose, q, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY);
    }

    /** 带顶点色与自定义光照的模型渲染（用于发光部件：可指定颜色 + FULL_BRIGHT） */
    protected static void renderModelColored(PoseStack ps, VertexConsumer consumer, BakedModel model,
                                             float r, float g, float b, float a, int light, int overlay) {
        var pose = ps.last();
        for (Direction dir : Direction.values()) {
            for (var q : model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null))
                consumer.putBulkData(pose, q, r, g, b, a, light, overlay);
        }
        for (var q : model.getQuads(null, null, RANDOM, ModelData.EMPTY, null))
            consumer.putBulkData(pose, q, r, g, b, a, light, overlay);
    }

    // ── 默认：按钮行为 ──

    public static class ButtonBehavior extends ModuleRenderBehavior {
        private static final float PRESS_DEPTH = 0.2f;

        private final String headKey;      // 可空：独立按钮主体（按下凹陷）
        private final String indicatorKey; // 可空：发光灯带

        public ButtonBehavior() { this(MonitorPreloadedModels.BUTTON_1_HEAD, MonitorPreloadedModels.BUTTON_1_INDICATOR); }

        public ButtonBehavior(String headKey, String indicatorKey) {
            this.headKey = headKey;
            this.indicatorKey = indicatorKey;
        }

        /** 有独立 head 时主模型（底座）不凹陷，凹陷由 head/indicator 在 renderExtra 里自行处理 */
        @Override public boolean usePressDepth() { return headKey == null; }

        /** 按下动画速度：每帧逼近目标的比例，值越大越快（默认 0.1） */
        @Override public float animPressSpeed() { return 0.3f; }

        @Override public float animReleaseSpeed() { return 0.3f; }

        @Override
        public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, int light, int overlay) {
            // ① 按钮主体：按下沿 z 轴凹陷
            if (headKey != null) {
                BakedModel head = MonitorPreloadedModels.getExtra(headKey);
                if (head != null) {
                    ps.pushPose();
                    ps.translate(0, 0, PRESS_DEPTH * anim / 16f);
                    renderModel(ps, buffer.getBuffer(Sheets.solidBlockSheet()), head, light, overlay);
                    ps.popPose();
                }
            }

            // ② 灯带：绿色渐变荧光，跟随按钮主体一起凹陷
            if (indicatorKey != null && anim > 0.01f) {
                BakedModel indicator = MonitorPreloadedModels.getExtra(indicatorKey);
                if (indicator != null) {
                    ps.pushPose();
                    if (headKey != null) ps.translate(0, 0, PRESS_DEPTH * anim / 16f);
                    // 绿色渐变：暗绿 → 亮绿（仿电源指示灯），anim 已在 MonitorRenderer 平滑插值
                    float r = Mth.lerp(anim, 0.03f, 0.22f);
                    float g = Mth.lerp(anim, 0.18f, 1.00f);
                    float b = Mth.lerp(anim, 0.05f, 0.36f);
                    // FULL_BRIGHT 不受环境光；additive 加法混合 = 荧光；顶点色随按下变亮
                    renderModelColored(ps, buffer.getBuffer(RenderTypes.additive()), indicator,
                            r, g, b, 1f, LightTexture.FULL_BRIGHT, overlay);
                    ps.popPose();
                }
            }
        }
    }

    // ── 钮子开关 ──

    public static class ToggleBehavior extends ModuleRenderBehavior {
        @Override public float offsetZ() { return 1f / 16f; }       // z 微调
        @Override public boolean usePressDepth() { return false; }   // 底座不动
        @Override public float animPressSpeed() { return 0.4f; }
        @Override public float animReleaseSpeed() { return 0.4f; }
        @Override
        public void applyInitialRotation(PoseStack ps) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));               // 竖→横
        }
        @Override
        public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, int light, int overlay) {
            BakedModel lever = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.TOGGLE_LEVER);
            if (lever == null) return;
            ps.pushPose();
            ps.translate(1 / 32f, 0, 1 / 32f);
            ps.mulPose(Axis.XP.rotationDegrees(-30 + anim * 60));  // 关=-30°, 开=+30°
            renderModel(ps, buffer.getBuffer(Sheets.solidBlockSheet()), lever, light, overlay);
            ps.popPose();
        }
    }

    // ── 旋钮（2×2，底座+旋转把手）──

    public static class KnobBehavior extends ModuleRenderBehavior {
        @Override public boolean usePressDepth() { return false; }
        @Override public float offsetX() { return 1f / 16f; }       // x 微调
        @Override public float offsetY() { return 1f / 16f; }       // y 微调
        @Override public float offsetZ() { return 1f / 16f; }       // z 微调
        @Override
        public void applyInitialRotation(PoseStack ps) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));
        }
        @Override
        public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, int light, int overlay) {
            BakedModel handle = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.KNOB_HANDLE);
            if (handle == null) return;
            ps.pushPose();
            ps.translate(0f, 0.0f, 0f);
            ps.mulPose(Axis.YP.rotationDegrees(-anim));  // anim = 累计旋转角度（度）
            renderModel(ps, buffer.getBuffer(Sheets.solidBlockSheet()), handle, light, overlay);
            ps.popPose();
        }
    }
}
