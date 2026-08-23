package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

/**
 * 控制台原版 BER 回退渲染（Flywheel 不可用时使用），对齐 simulated 的 ThrottleLeverRenderer：
 * 左右踏板/操纵杆 PartialModel 用 SuperByteBuffer 叠加在底座（blockstate 静态模型）之上。
 * <p>
 * 与 {@link ControlDeskVisual} 共享同一套朝向约定：模型按与底座相同的方块空间（北向）建模，
 * 渲染时绕方块中心 Y 旋转到 FACING（BER 的 PoseStack 已平移到方块位置，无需再平移）。
 */
public class ControlDeskRenderer extends SafeBlockEntityRenderer<ControlDeskBlockEntity> {

    public ControlDeskRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(ControlDeskBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource bufferSource, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null || VisualizationManager.supportsVisualization(level)) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ControlDeskBlock.FACING);

        VertexConsumer vb = bufferSource.getBuffer(RenderType.cutoutMipped());

        SuperByteBuffer pedal = CachedBuffers.partial(MyModPartialModels.CONTROL_DESK_PEDAL, state);
        SuperByteBuffer pedalRight = CachedBuffers.partial(MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT, state);
        SuperByteBuffer joystick = CachedBuffers.partial(MyModPartialModels.CONTROL_DESK_JOYSTICK, state);

        initialTransform(pedal, facing);
        initialTransform(pedalRight, facing);
        initialTransform(joystick, facing);

        pedal.light(light).renderInto(ms, vb);
        pedalRight.light(light).renderInto(ms, vb);
        joystick.light(light).renderInto(ms, vb);
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull ControlDeskBlockEntity blockEntity) {
        // 操纵杆把手最高到 y≈17.4/16，超出 1 格默认盒；放大避免 BER 兜底路径被视锥剔除（同 simulated ThrottleLever）
        return AABB.ofSize(blockEntity.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }

    /** 与 {@link ControlDeskVisual#initialTransform} 的旋转一致：绕方块中心 Y 旋转到 facing。 */
    private static void initialTransform(SuperByteBuffer buffer, Direction facing) {
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
    }
}
