package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.zzy205.myfirstmod.client.SeatControlState;
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
 * 操纵杆本体叠加倾斜：绕枢轴 (8,6,3) 倾斜（SuperByteBuffer 变换链，与 Create HarvesterRenderer
 * pivot 模式一致），倾斜 = 模拟轴 × 15°（轴值动力学由服务端 BE tick 推进），见 {@link JoystickTilt}。
 * 摇杆2 手柄叠加倾斜：放置变换后绕枢轴 (8,1,8) 倾斜，逻辑照抄操纵杆（独立轴值/配置），见 {@link Joystick2Motion}。
 * 踏板本体叠加平移：向模型空间 +z 平移压下值 × 1px（踩下 = 前后平移，见 {@link PedalMotion}）。
 */
public class ControlDeskRenderer extends SafeBlockEntityRenderer<ControlDeskBlockEntity> {

    /** 每个控制台独立的操纵杆动画倾斜值（度）{tiltX, tiltY}：指数逼近追逐目标 */
    private final Map<BlockPos, float[]> smoothTilts = new HashMap<>();
    /** 每个控制台独立的摇杆2 动画倾斜值（度）{tiltX, tiltY}：指数逼近追逐目标（独立于 joystick） */
    private final Map<BlockPos, float[]> smoothTilt2s = new HashMap<>();
    /** 每个控制台独立的踏板动画平移量（块单位）{leftPx, rightPx}：指数逼近追逐目标 */
    private final Map<BlockPos, float[]> smoothPedals = new HashMap<>();
    /** 每个控制台独立的油门动画平移量（块单位）：指数逼近追逐目标（沿模型空间 x 轴） */
    private final Map<BlockPos, Float> smoothThrottles = new HashMap<>();
    /** 每个控制台独立的油门张力充电状态 {progress(0..1), lastDir, lastGearPx}：帧时间平滑推进 */
    private final Map<BlockPos, float[]> throttleCharge = new HashMap<>();

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
            renderPart(MyModPartialModels.CONTROL_DESK_PEDAL_BASE, state, facing, ms, vb, light, 0);
            float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
            float[] smooth = smoothPedals.computeIfAbsent(be.getBlockPos(), k -> new float[2]);
            float[] target = PedalMotion.targetPx(be);
            smooth[0] = JoystickTilt.approach(smooth[0], target[0], frameTicks);
            smooth[1] = JoystickTilt.approach(smooth[1], target[1], frameTicks);
            renderPedal(MyModPartialModels.CONTROL_DESK_PEDAL, state, facing, ms, vb, light, smooth[0]);
            renderPedal(MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT, state, facing, ms, vb, light, smooth[1]);
        } else {
            smoothPedals.remove(be.getBlockPos());
        }
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)) {
            renderPart(MyModPartialModels.CONTROL_DESK_JOYSTICK_BASE, state, facing, ms, vb, light, 0);
            renderJoystick(be, state, facing, ms, vb, light);
        } else {
            smoothTilts.remove(be.getBlockPos());
        }
        // monitor_2：已接入棋盘自由放置——模型平移到放置位，不面向玩家（无安装朝向旋转，仅随桌体 FACING）
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) {
            SuperByteBuffer buffer = placedBuffer(MyModPartialModels.CONTROL_DESK_MONITOR_2, state, facing,
                    be.getMonitor2PlaceX(), be.getMonitor2PlaceZ(),
                    ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, 0);
            buffer.light(light).renderInto(ms, vb);
        }
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)) {
            int backRot = be.getBackSlotRotation();
            renderThrottlePart(MyModPartialModels.CONTROL_DESK_THROTTLE_BASE, be, state, facing, ms, vb, light, backRot);
            renderThrottleHandle(be, state, facing, ms, vb, light, backRot);
            // 指示灯：随油门档位大小着色（参考 Create analog lever / Simulated diode）
            SuperByteBuffer indicator = placedBuffer(MyModPartialModels.CONTROL_DESK_THROTTLE_INDICATOR, state, facing,
                    be.getThrottlePlaceX(), be.getThrottlePlaceZ(),
                    ControlDeskBlockEntity.THROTTLE_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
            indicator.light(light)
                    .color(ThrottleMotion.indicatorColor(be.getThrottleGear()))
                    .renderInto(ms, vb);
        } else {
            smoothThrottles.remove(be.getBlockPos());
            throttleCharge.remove(be.getBlockPos());
        }
        // joystick_2：底座静态 + 手柄倾斜动画；模型平移到放置位（预览盒位置），安装朝向旋转绕放置中心
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)) {
            int backRot = be.getBackSlotRotation();
            renderJoystick2Part(MyModPartialModels.CONTROL_DESK_JOYSTICK_2_BASE, be, state, facing, ms, vb, light, backRot);
            renderJoystick2(be, state, facing, ms, vb, light, backRot);
        } else {
            smoothTilt2s.remove(be.getBlockPos());
        }
    }

    /** 踏板本体：facing 旋转 + 向模型空间 +z 平移（动画 = 指数逼近追逐压下值 × 1px）。 */
    private static void renderPedal(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                    BlockState state, Direction facing,
                                    PoseStack ms, VertexConsumer vb, int light, float zPx) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        if (zPx != 0f) {
            buffer.translate(0f, 0f, zPx);
        }
        buffer.light(light).renderInto(ms, vb);
    }

    /** 操纵杆本体：facing 旋转 + 绕枢轴 (8,6,3) 倾斜（动画 = 指数逼近追逐模拟轴 × 15°）。 */
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

    /** 摇杆2 手柄：放置变换（平移到放置位 + 安装朝向旋转绕放置中心）+ 绕枢轴 (8,1,8) 倾斜（动画 = 指数逼近追逐服务端权威轴值 × 15°，逻辑照抄 {@link #renderJoystick}，见 {@link Joystick2Motion}）。 */
    private void renderJoystick2(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                 PoseStack ms, VertexConsumer vb, int light, int backRot) {
        float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
        float[] smooth = smoothTilt2s.computeIfAbsent(be.getBlockPos(), k -> new float[2]);
        float[] target = Joystick2Motion.targetDeg(be);
        smooth[0] = JoystickTilt.approach(smooth[0], target[0], frameTicks);
        smooth[1] = JoystickTilt.approach(smooth[1], target[1], frameTicks);
        float tiltX = smooth[0];
        float tiltY = smooth[1];

        SuperByteBuffer stick = placedBuffer(MyModPartialModels.CONTROL_DESK_JOYSTICK_2_HANDLE, state, facing,
                be.getJoystick2PlaceX(), be.getJoystick2PlaceZ(),
                ControlDeskBlockEntity.JOYSTICK_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
        if (tiltX != 0f || tiltY != 0f) {
            stick.translate(Joystick2Motion.PIVOT_X, Joystick2Motion.PIVOT_Y, Joystick2Motion.PIVOT_Z)
                    .rotate(Mth.DEG_TO_RAD * tiltY, Direction.EAST)
                    .rotate(Mth.DEG_TO_RAD * tiltX, Direction.SOUTH)
                    .translate(-Joystick2Motion.PIVOT_X, -Joystick2Motion.PIVOT_Y, -Joystick2Motion.PIVOT_Z);
        }
        stick.light(light).renderInto(ms, vb);
    }

    /** 油门手柄：放置变换（平移到放置位 + 安装朝向旋转绕放置中心）+ 沿模型空间 x 轴平移（档位位置 + 操作者本地张力蠕动，步进突然快速到位）。 */
    private void renderThrottleHandle(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                      PoseStack ms, VertexConsumer vb, int light, int backRot) {
        float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
        float gearPx = ThrottleMotion.targetPx(be);
        // 张力充电进度 {progress, lastDir, lastGearPx}：帧时间平滑推进（避免游戏时间整 tick 跳变卡顿）
        float[] charge = throttleCharge.computeIfAbsent(be.getBlockPos(), k -> new float[3]);
        if (gearPx != charge[2]) {
            charge[2] = gearPx;
            charge[0] = 0f; // 档位步进：张力清零
        }
        int dir = SeatControlState.isLinkedDesk(be.getBlockPos()) ? SeatControlState.getThrottleDir() : 0;
        if (dir != (int) charge[1]) {
            charge[1] = dir;
            charge[0] = 0f; // 按键按下/松开边沿：张力清零
        }
        if (dir != 0) {
            charge[0] = Math.min(1f, charge[0] + frameTicks / be.getThrottleTicksPerGear());
        }
        float target = gearPx + ThrottleMotion.tensionPx(dir, charge[0], gearPx);
        float smooth = smoothThrottles.computeIfAbsent(be.getBlockPos(), k -> 0f);
        smooth = ThrottleMotion.approachStep(smooth, target, frameTicks);
        smoothThrottles.put(be.getBlockPos(), smooth);

        SuperByteBuffer handle = placedBuffer(MyModPartialModels.CONTROL_DESK_THROTTLE_HANDLE, state, facing,
                be.getThrottlePlaceX(), be.getThrottlePlaceZ(),
                ControlDeskBlockEntity.THROTTLE_MODEL_CENTER, ControlDeskBlockEntity.THROTTLE_PLACE_Y_BOTTOM, backRot);
        if (smooth != 0f) {
            handle.translate(smooth, 0f, 0f);
        }
        handle.light(light).renderInto(ms, vb);
    }

    /** 静态部件：facing 旋转 + 安装朝向旋转（backRot，0 跳过）。 */
    private static void renderPart(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                   BlockState state, Direction facing, PoseStack ms,
                                   VertexConsumer vb, int light, int backRot) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        if (backRot != 0) {
            buffer.rotateCenteredDegrees(backRot, Direction.UP);
        }
        buffer.light(light).renderInto(ms, vb);
    }

    /** throttle 静态部件：facing 旋转 + 模型平移到放置位 + 安装朝向旋转（只能 0°/180°）绕放置中心。 */
    private static void renderThrottlePart(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                           ControlDeskBlockEntity be, BlockState state, Direction facing,
                                           PoseStack ms, VertexConsumer vb, int light, int backRot) {
        SuperByteBuffer buffer = placedBuffer(model, state, facing,
                be.getThrottlePlaceX(), be.getThrottlePlaceZ(),
                ControlDeskBlockEntity.THROTTLE_MODEL_CENTER, ControlDeskBlockEntity.THROTTLE_PLACE_Y_BOTTOM, backRot);
        buffer.light(light).renderInto(ms, vb);
    }

    /**
     * 放置部件（joystick_2 / throttle 共用）：facing 旋转 + 模型平移到放置位（默认中心 x/z=8、底座底 y=0 → 放置位底 y=7）+
     * 安装朝向旋转绕放置中心（与预览盒/实物预览一致，三处变换统一，见 {@code memo/control-desk-grid-slot.md}）。
     */
    private static void renderJoystick2Part(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                            ControlDeskBlockEntity be, BlockState state, Direction facing,
                                            PoseStack ms, VertexConsumer vb, int light, int backRot) {
        SuperByteBuffer buffer = placedBuffer(model, state, facing,
                be.getJoystick2PlaceX(), be.getJoystick2PlaceZ(),
                ControlDeskBlockEntity.JOYSTICK_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
        buffer.light(light).renderInto(ms, vb);
    }

    /** 放置变换公共链：facing 旋转 + 平移到放置位 + 安装朝向旋转绕放置中心（与 Flywheel {@code ControlDeskVisual.applyPlacement} 同链）。 */
    private static SuperByteBuffer placedBuffer(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                                BlockState state, Direction facing,
                                                int placeX, int placeZ, float modelCenter, float placeYBottom, int backRot) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        float px = placeX / 16f;
        float pz = placeZ / 16f;
        if (backRot != 0) {
            buffer.translate(px, 0.5f, pz);
            buffer.rotate(Mth.DEG_TO_RAD * backRot, Direction.UP);
            buffer.translate(-px, -0.5f, -pz);
        }
        buffer.translate((placeX - modelCenter) / 16f, (placeYBottom - ControlDeskBlockEntity.JOYSTICK_2_MODEL_BOTTOM_Y) / 16f,
                (placeZ - modelCenter) / 16f);
        return buffer;
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull ControlDeskBlockEntity blockEntity) {
        // 操纵杆把手最高到 y≈17.4/16，超出 1 格默认盒；放大避免 BER 兜底路径被视锥剔除
        return AABB.ofSize(blockEntity.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }
}
