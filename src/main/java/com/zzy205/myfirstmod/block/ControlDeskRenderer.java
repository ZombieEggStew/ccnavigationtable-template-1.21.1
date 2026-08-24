package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 控制台原版 BER 回退渲染（Flywheel 不可用时使用）：按 BE 已安装控件状态叠加踏板/操纵杆。
 * 与 {@link ControlDeskVisual} 共享同一套朝向约定：模型按与底座相同的方块空间（北向）建模，
 * 渲染时绕方块中心 Y 旋转到 FACING（BER 的 PoseStack 已平移到方块位置，无需再平移）。
 * 操纵杆本体叠加倾斜：绕枢轴 (8,3,3) 倾斜（SuperByteBuffer 变换链，与 Create HarvesterRenderer
 * pivot 模式一致），倾斜 = 模拟轴 × 15°（轴值动力学由 SeatControlListener 推进），见 {@link JoystickTilt}。
 */
public class ControlDeskRenderer extends SafeBlockEntityRenderer<ControlDeskBlockEntity> {

    /** 每个控制台独立的操纵杆动画倾斜值（度）{tiltX, tiltY}：指数逼近追逐目标 */
    private final Map<BlockPos, float[]> smoothTilts = new HashMap<>();

    public ControlDeskRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(ControlDeskBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource bufferSource, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null || VisualizationManager.supportsVisualization(level)) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ControlDeskBlock.FACING);

        VertexConsumer vb = bufferSource.getBuffer(RenderType.cutoutMipped());

        if (be.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)) {
            renderPart(MyModPartialModels.CONTROL_DESK_PEDAL_BASE, state, facing, ms, vb, light);
            renderPart(MyModPartialModels.CONTROL_DESK_PEDAL, state, facing, ms, vb, light);
            renderPart(MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT, state, facing, ms, vb, light);
        }
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)) {
            renderPart(MyModPartialModels.CONTROL_DESK_JOYSTICK_BASE, state, facing, ms, vb, light);
            renderJoystick(be, state, facing, ms, vb, light);
        } else {
            smoothTilts.remove(be.getBlockPos());
        }
    }

    /** 操纵杆本体：facing 旋转 + 绕枢轴 (8,3,3) 倾斜（动画 = 指数逼近追逐模拟轴 × 15°）。 */
    private void renderJoystick(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                PoseStack ms, VertexConsumer vb, int light) {
        float[] smooth = smoothTilts.computeIfAbsent(be.getBlockPos(), k -> new float[2]);
        float[] target = JoystickTilt.targetDeg(be);
        float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
        smooth[0] = JoystickTilt.approach(smooth[0], target[0], frameTicks);
        smooth[1] = JoystickTilt.approach(smooth[1], target[1], frameTicks);
        float tiltX = smooth[0];
        float tiltY = smooth[1];

        SuperByteBuffer stick = CachedBuffers.partial(MyModPartialModels.CONTROL_DESK_JOYSTICK, state);
        stick.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        if (tiltX != 0f || tiltY != 0f) {
            stick.translate(JoystickTilt.PIVOT_X, JoystickTilt.PIVOT_Y, JoystickTilt.PIVOT_Z)
                    .rotate(Mth.DEG_TO_RAD * tiltY, Direction.EAST)
                    .rotate(Mth.DEG_TO_RAD * tiltX, Direction.SOUTH)
                    .translate(-JoystickTilt.PIVOT_X, -JoystickTilt.PIVOT_Y, -JoystickTilt.PIVOT_Z);
        }
        stick.light(light).renderInto(ms, vb);
    }

    private static void renderPart(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                   BlockState state, Direction facing, PoseStack ms,
                                   VertexConsumer vb, int light) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        buffer.light(light).renderInto(ms, vb);
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull ControlDeskBlockEntity blockEntity) {
        // 操纵杆把手最高到 y≈17.4/16，超出 1 格默认盒；放大避免 BER 兜底路径被视锥剔除
        return AABB.ofSize(blockEntity.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }
}
