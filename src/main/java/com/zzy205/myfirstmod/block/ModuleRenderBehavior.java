package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
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

    /** 灯带纯色面片渲染类型：POSITION_COLOR（无纹理、无光照贴图），与 screen 文字背景 SOLID_BG 完全同款 */
    private static final RenderType INDICATOR_RENDER_TYPE = RenderType.create(
            CCPeripheralExtender.MOD_ID + ":module_indicator",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    static {
        REGISTRY.put(ModuleType.BUTTON_1X1, new ButtonBehavior(MonitorPreloadedModels.BUTTON_1_HEAD, MonitorPreloadedModels.BUTTON_1_INDICATOR));
        REGISTRY.put(ModuleType.TOGGLE_SWITCH, new ToggleBehavior());
        REGISTRY.put(ModuleType.KNOB, new KnobBehavior());
    }

    public static ModuleRenderBehavior of(ModuleType type) {
        return REGISTRY.getOrDefault(type, new ButtonBehavior());
    }

    /**
     * 单步动画逼近（BER 与 Flywheel Visual 共用的单一实现）：20 TPS 下每 tick 逼近 speed 比例，
     * 按实际帧时间推进，避免重绘频率改变动画观感。返回下一帧动画值（已写入 anims）。
     *
     * @param anims       动画值表（按 moduleId 存放）
     * @param isKnob      旋钮用角度语义（360° 取最短路径），按钮/开关用 0..1 按压语义
     * @param target      目标值（旋钮为累计角度，其余为 0/1）
     */
    public static float stepAnim(Map<Integer, Float> anims, int moduleId, boolean isKnob, float target,
                                 float pressSpeed, float releaseSpeed) {
        float current = anims.computeIfAbsent(moduleId, ignored -> target);
        float delta = target - current;
        if (isKnob) {
            delta %= 360f;
            if (delta > 180f) delta -= 360f;
            else if (delta < -180f) delta += 360f;
        }
        float speed = delta >= 0f ? pressSpeed : releaseSpeed;
        float frameTime = Math.min(Minecraft.getInstance().getTimer().getGameTimeDeltaTicks(), 2f);
        float next = current + delta * (1f - (float) Math.pow(1f - speed, frameTime));
        if (!isKnob && Math.abs(next - target) < 0.01f) next = target;
        if (isKnob && Math.abs(delta) < 0.01f) next = current;
        anims.put(moduleId, next);
        return next;
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

    /** 按下动画速度（20 TPS 下每 tick 的逼近比例）。 */
    public float animPressSpeed() { return 0.25f; }
    /** 弹起动画速度（20 TPS 下每 tick 的逼近比例）。 */
    public float animReleaseSpeed() { return 0.25f; }

    /**
     * 渲染额外部件（如拉杆）。anim 0=弹起, 1=按下；lightLevel 为灯带亮度（0=灭, 1=最亮），
     * 普通模块可忽略该参数。
     */
    public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, float lightLevel,
                            int light, int overlay) {}

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

    /** 绘制一个纯色平面（POSITION_COLOR：无纹理、不受光照贴图影响，支持透明度）。坐标均为块单位。 */
    protected static void renderFlatQuad(PoseStack ps, MultiBufferSource buffer,
                                         float x0, float y0, float x1, float y1, float z,
                                         float r, float g, float b, float a) {
        VertexConsumer vc = buffer.getBuffer(INDICATOR_RENDER_TYPE);
        var pose = ps.last().pose();
        vc.addVertex(pose, x0, y0, z).setColor(r, g, b, a);
        vc.addVertex(pose, x0, y1, z).setColor(r, g, b, a);
        vc.addVertex(pose, x1, y1, z).setColor(r, g, b, a);
        vc.addVertex(pose, x1, y0, z).setColor(r, g, b, a);
    }

    // ── 默认：按钮行为 ──

    public static class ButtonBehavior extends ModuleRenderBehavior {
        /** 按钮 head 按压凹陷深度（模型像素），Flywheel 实例化与 BER 共用 */
        public static final float PRESS_DEPTH = 0.2f;

        /** 灯带平面范围（模型像素，1px=1/16 块），与 button_1_indicator 模型一致 */
        private static final float INDICATOR_X0 = 0.1875f, INDICATOR_X1 = 0.8125f;
        private static final float INDICATOR_Y0 = 0.6875f, INDICATOR_Y1 = 0.8125f;
        /** 灯带基准 z（模型像素）：head 前脸 0.625 前方 0.011px，避免 z-fighting */
        private static final float INDICATOR_Z_PX = 0.614f;

        private final String headKey;      // 可空：独立按钮主体（按下凹陷）
        private final String indicatorKey; // 非空即绘制灯带

        public ButtonBehavior() { this(MonitorPreloadedModels.BUTTON_1_HEAD, MonitorPreloadedModels.BUTTON_1_INDICATOR); }

        public ButtonBehavior(String headKey, String indicatorKey) {
            this.headKey = headKey;
            this.indicatorKey = indicatorKey;
        }

        /** 有独立 head 时主模型（底座）不凹陷，凹陷由 head/indicator 在 renderExtra 里自行处理 */
        @Override public boolean usePressDepth() { return headKey == null; }

        /** 按下动画速度：值越大越快。 */
        @Override public float animPressSpeed() { return 0.6f; }

        @Override public float animReleaseSpeed() { return 0.6f; }

        @Override
        public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, float lightLevel,
                                int light, int overlay) {
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

            // ② 灯带：常显不透明面片，颜色随 lightLevel 从灰(0x666666)渐变到纯绿，避免半透明透出背后世界
            renderIndicator(ps, buffer, anim, lightLevel);
        }

        /** 仅绘制灯带面片（模块模型由 Flywheel 实例化时，BER 只画灯带与文字）。 */
        public void renderIndicator(PoseStack ps, MultiBufferSource buffer, float anim, float lightLevel) {
            if (indicatorKey == null) return;
            ps.pushPose();
            float iz = (INDICATOR_Z_PX + PRESS_DEPTH * anim) / 16f;
            float r = Mth.lerp(lightLevel, 0.2f, 0.0f);
            float g = Mth.lerp(lightLevel, 0.2f, 1.0f);
            float b = Mth.lerp(lightLevel, 0.2f, 0.0f);
            renderFlatQuad(ps, buffer,
                    INDICATOR_X0 / 16f, INDICATOR_Y0 / 16f, INDICATOR_X1 / 16f, INDICATOR_Y1 / 16f, iz,
                    r, g, b, 1f);
            ps.popPose();
        }
    }

    // ── 钮子开关 ──

    public static class ToggleBehavior extends ModuleRenderBehavior {
        @Override public float offsetZ() { return 1f / 16f; }       // z 微调
        @Override public boolean usePressDepth() { return false; }   // 底座不动
        @Override public float animPressSpeed() { return 0.95f; }
        @Override public float animReleaseSpeed() { return 0.95f; }
        @Override
        public void applyInitialRotation(PoseStack ps) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));               // 竖→横
        }
        @Override
        public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, float lightLevel,
                                int light, int overlay) {
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
        @Override public float animPressSpeed() { return 0.5f; }
        @Override public float animReleaseSpeed() { return 0.5f; }
        @Override public float offsetX() { return 1f / 16f; }       // x 微调
        @Override public float offsetY() { return 1f / 16f; }       // y 微调
        @Override public float offsetZ() { return 1f / 16f; }       // z 微调
        @Override
        public void applyInitialRotation(PoseStack ps) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));
        }
        @Override
        public void renderExtra(PoseStack ps, MultiBufferSource buffer, float anim, float lightLevel,
                                int light, int overlay) {
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
