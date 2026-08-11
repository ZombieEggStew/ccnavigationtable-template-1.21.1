package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
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
        var button = new ButtonBehavior();
        REGISTRY.put(ModuleType.BUTTON_1X1, button);
        REGISTRY.put(ModuleType.BUTTON_2X2, button);
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

    // ── 默认：按钮行为 ──

    public static class ButtonBehavior extends ModuleRenderBehavior {}

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
