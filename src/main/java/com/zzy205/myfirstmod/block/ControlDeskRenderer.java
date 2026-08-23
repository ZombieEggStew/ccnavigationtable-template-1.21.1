package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

/**
 * 控制台原版 BER 回退渲染（Flywheel 不可用时使用）。
 * <p>
 * 底座由 blockstate 静态模型渲染；踏板/操纵杆已改为可安装控件物品（pedal/joystick），
 * 控件安装系统接入后，在此按已安装状态叠加对应 PartialModel。
 */
public class ControlDeskRenderer extends SafeBlockEntityRenderer<ControlDeskBlockEntity> {

    public ControlDeskRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(ControlDeskBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource bufferSource, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null || VisualizationManager.supportsVisualization(level)) return;
        // 暂无叠加内容
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull ControlDeskBlockEntity blockEntity) {
        return AABB.ofSize(blockEntity.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }
}
