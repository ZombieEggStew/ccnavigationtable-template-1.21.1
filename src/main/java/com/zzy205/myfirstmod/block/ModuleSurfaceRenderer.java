package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.client.Monitor2GridOverlay;
import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.monitor.ButtonLabel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 模块表面装饰渲染（旋钮角度/卡位文字、按钮标签）的共享实现（Monitor 与 monitor_2 共用）。
 * <p>
 * 调用方需先把 PoseStack 定位到模块锚点并施加初始旋转
 * （{@link ModuleRenderBehavior#applyInitialRotation}），然后直接调用本类方法。
 * 旋钮悬停/拖拽状态经 {@link KnobDisplaySource} 抽象，由 {@link MonitorGridOverlay}（Monitor）
 * 与 {@link Monitor2GridOverlay}（monitor_2）各自实现——monitor_2 移植时不再需要复制
 * {@code renderKnobAngle}/{@code renderButtonLabel} 的文字变换数学。
 */
public final class ModuleSurfaceRenderer {

    /** 旋钮角度显示的状态来源。 */
    public interface KnobDisplaySource {
        /** 正在拖动的旋钮角度；未拖动该模块时返回 null。 */
        Float activeAngle(BlockPos pos, int moduleId);
        /** 当前准星悬浮的旋钮模块 ID；未悬浮旋钮时返回 -1。 */
        int hoveredModuleId(BlockPos pos);
    }

    /** Monitor 宿主（MonitorGridOverlay）。 */
    public static final KnobDisplaySource MONITOR = new KnobDisplaySource() {
        @Override public Float activeAngle(BlockPos pos, int moduleId) {
            return MonitorGridOverlay.getActiveKnobAngle(pos, moduleId);
        }
        @Override public int hoveredModuleId(BlockPos pos) {
            return MonitorGridOverlay.getHoveredKnobModuleId(pos);
        }
    };

    /** monitor_2 宿主（Monitor2GridOverlay）。 */
    public static final KnobDisplaySource MONITOR_2 = new KnobDisplaySource() {
        @Override public Float activeAngle(BlockPos pos, int moduleId) {
            return Monitor2GridOverlay.getActiveKnobAngle(pos, moduleId);
        }
        @Override public int hoveredModuleId(BlockPos pos) {
            return Monitor2GridOverlay.getHoveredKnobModuleId(pos);
        }
    };

    /** 按钮标签相对 head 前脸（z=0.625px）向前 0.01px 的 z 坐标（模型像素），避免 z-fighting。 */
    private static final float BUTTON_LABEL_FRONT_Z_PX = 0.615f;
    /** 按钮 head 按压凹陷深度（模型像素），与 ModuleRenderBehavior.ButtonBehavior.PRESS_DEPTH 一致。 */
    private static final float BUTTON_PRESS_DEPTH_PX = 0.2f;
    /** 标签坐标原点 X（模型像素）：按钮 head 的水平中心。 */
    private static final float BUTTON_LABEL_ORIGIN_X_PX = 0.5f;
    /** 标签坐标原点 Y（模型像素）：按钮 head 的视觉垂直中心。 */
    private static final float BUTTON_LABEL_ORIGIN_Y_PX = 0.35f;

    private ModuleSurfaceRenderer() {}

    /**
     * 渲染旋钮表面角度/卡位/百分比文字（从 MonitorRenderer 迁出，Monitor 与 monitor_2 共用）。
     * 拖拽中显示客户端视觉角度；否则仅在准心悬浮于该旋钮上时显示服务端当前角度。
     */
    public static void renderKnobAngle(PoseStack poseStack, MultiBufferSource buffer,
                                       BlockPos modulePos, int moduleId, int light, float serverAngle,
                                       CompoundTag config, KnobDisplaySource source) {
        Float angle = source.activeAngle(modulePos, moduleId);
        if (angle == null) {
            if (source.hoveredModuleId(modulePos) == moduleId) {
                angle = config.getBoolean("physical_limit")
                        ? serverAngle : normalizeKnobDisplayAngle(serverAngle);
            } else {
                return;
            }
        }

        int detentAngle = config.getInt("angle");
        boolean showDetent = config.getBoolean("detent_display") && detentAngle > 0;
        String text;
        if (config.getBoolean("percent_display")) {
            float maxAngle = config.getBoolean("physical_limit")
                ? Math.max(1, config.getInt("angle_limit")) : 360f;
            int percent = Math.round(Math.max(0f, Math.min(maxAngle, angle)) / maxAngle * 100f);
            text = percent + "%";
        } else {
            text = showDetent
                ? String.valueOf(Math.round(angle / detentAngle))
                : Math.round(angle) + "°";
        }
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

    public static float normalizeKnobDisplayAngle(float angle) {
        float normalized = angle % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    /**
     * 在按钮表面渲染标签文字（从 MonitorRenderer 迁出，Monitor 与 monitor_2 共用），
     * 朝向与旋钮角度文字（{@link #renderKnobAngle}）完全一致：
     * 按钮没有旋钮的初始 XP-90，故在此补齐一次 XP-90，再走旋钮相同的 XP-90 + ZP-180 变换。
     * <p>
     * 文字落在按钮 head 前脸（north 面，z=0.625px）稍前方，并随按压凹陷动画一起移动。
     */
    public static void renderButtonLabel(PoseStack poseStack, MultiBufferSource buffer,
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
}
