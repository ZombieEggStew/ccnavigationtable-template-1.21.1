package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.zzy205.myfirstmod.client.Monitor2GridOverlay;
import com.zzy205.myfirstmod.client.SeatControlState;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
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
    /** 每个控制台独立的油门2 动画角度（度）：指数逼近追逐目标（绕枢轴 (4,2,8) 旋转，总距杆） */
    private final Map<BlockPos, Float> smoothThrottle2s = new HashMap<>();
    /** 每个控制台独立的油门张力充电状态 {progress(0..1), lastDir, lastGearPx}：帧时间平滑推进 */
    private final Map<BlockPos, float[]> throttleCharge = new HashMap<>();
    /** 每个控制台独立的 monitor_2 模块动画值（外层 key=BlockPos，内层 key=moduleId）：按压/旋钮动画 */
    private final Map<BlockPos, Map<Integer, Float>> monitor2Anims = new HashMap<>();

    /** monitor_2 屏幕面参数（块单位），9 宫格/文字共享渲染用（见 {@link Screen9GridRenderer}）。 */
    private static final Screen9GridRenderer.ScreenPlane MONITOR_2_PLANE = new Screen9GridRenderer.ScreenPlane() {
        @Override public float originX() { return (ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1) / 16f; }
        @Override public float originY() { return (ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1) / 16f; }
        @Override public float z() {
            return (ControlDeskBlockEntity.MONITOR_2_SCREEN_Z - ControlDeskBlockEntity.MONITOR_2_MODULE_PROTRUDE_PX) / 16f;
        }
    };

    public ControlDeskRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(ControlDeskBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource bufferSource, int light, int overlay) {
        Level level = be.getLevel();
        boolean shellInstanced = level != null && VisualizationManager.supportsVisualization(level);
        // Flywheel 可用时控件模型由 ControlDeskVisual 实例化渲染，BER 只补画 monitor_2 屏幕动态内容
        // （屏幕 9 宫格 + 文字无法用 Flywheel 表达）；Flywheel 不可用时全量渲染。

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ControlDeskBlock.FACING);

        // 注意：不提前持有 cutoutMipped 的 VertexConsumer —— BufferSource 的 sharedBuffer 机制下，
        // 中途 getBuffer 其他共享 RenderType（solidBlockSheet/text 等）会把上一个共享 buffer 强制 endBatch，
        // 提前持有的 vb 会变成 not building，renderInto 时抛 "Not building!"（Diagram 图纸界面渲染装配体 BE 时触发）。
        // 与 AicRenderer/MyBearingRenderer 一致：每次 renderInto 前现场 getBuffer、拿到即用。

        // monitor_2 屏幕 9 宫格 + 文字（无论 Flywheel 是否可用都由 BER 画，对齐 MonitorRenderer）
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) {
            renderMonitor2Screens(be, state, facing, ms, bufferSource, light, overlay);
            // Flywheel 可用时模块模型由 ControlDeskVisual 实例化渲染，BER 只补画表面装饰
            // （旋钮角度文字 / 按钮灯带 / 按钮标签，对齐 MonitorRenderer 的 shellInstanced 分支）
            if (shellInstanced) {
                renderMonitor2ModuleDecorations(be, state, facing, ms, bufferSource, light, overlay);
            }
        }

        // 桌顶小模块（monitor 模块 button/knob/toggle）：BER 常驻渲染（Flywheel 不实例化它们）
        renderDeskTopModules(be, state, facing, ms, bufferSource, light, overlay);

        if (shellInstanced) return;

        if (be.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)) {
            renderPart(MyModPartialModels.CONTROL_DESK_PEDAL_BASE, state, facing, ms, bufferSource, light, 0);
            float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
            float[] smooth = smoothPedals.computeIfAbsent(be.getBlockPos(), k -> new float[2]);
            float[] target = PedalMotion.targetPx(be);
            smooth[0] = JoystickTilt.approach(smooth[0], target[0], frameTicks);
            smooth[1] = JoystickTilt.approach(smooth[1], target[1], frameTicks);
            renderPedal(MyModPartialModels.CONTROL_DESK_PEDAL, state, facing, ms, bufferSource, light, smooth[0]);
            renderPedal(MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT, state, facing, ms, bufferSource, light, smooth[1]);
        } else {
            smoothPedals.remove(be.getBlockPos());
        }
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)) {
            renderPart(MyModPartialModels.CONTROL_DESK_JOYSTICK_BASE, state, facing, ms, bufferSource, light, 0);
            renderJoystick(be, state, facing, ms, bufferSource, light);
        } else {
            smoothTilts.remove(be.getBlockPos());
        }
        // monitor_2：已接入棋盘自由放置——模型平移到放置位，不面向玩家（无安装朝向旋转，仅随桌体 FACING）
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) {
            SuperByteBuffer buffer = placedBuffer(MyModPartialModels.CONTROL_DESK_MONITOR_2, state, facing,
                    be.getMonitor2PlaceX(), be.getMonitor2PlaceZ(),
                    ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, 0);
            buffer.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
            renderMonitor2Modules(be, state, facing, ms, bufferSource, light, overlay);
        }
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)) {
            int backRot = be.getBackSlotRotation();
            renderThrottlePart(MyModPartialModels.CONTROL_DESK_THROTTLE_BASE, be, state, facing, ms, bufferSource, light, backRot);
            renderThrottleHandle(be, state, facing, ms, bufferSource, light, backRot);
            // 指示灯：随油门档位大小着色（参考 Create analog lever / Simulated diode）
            SuperByteBuffer indicator = placedBuffer(MyModPartialModels.CONTROL_DESK_THROTTLE_INDICATOR, state, facing,
                    be.getThrottlePlaceX(), be.getThrottlePlaceZ(),
                    ControlDeskBlockEntity.THROTTLE_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
            indicator.light(light)
                    .color(ThrottleMotion.indicatorColor(be.getThrottleGear()))
                    .renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
        } else {
            smoothThrottles.remove(be.getBlockPos());
            throttleCharge.remove(be.getBlockPos());
        }
        // joystick_2：底座静态 + 手柄倾斜动画；模型平移到放置位（预览盒位置），安装朝向旋转绕放置中心
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)) {
            int backRot = be.getBackSlotRotation();
            renderJoystick2Part(MyModPartialModels.CONTROL_DESK_JOYSTICK_2_BASE, be, state, facing, ms, bufferSource, light, backRot);
            renderJoystick2(be, state, facing, ms, bufferSource, light, backRot);
        } else {
            smoothTilt2s.remove(be.getBlockPos());
        }
        // throttle_2：底座静态 + 手柄绕枢轴 (4,2,8) 旋转（总距杆类型，见 Throttle2Motion），放置变换与 throttle 同链
        if (be.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)) {
            int backRot = be.getBackSlotRotation();
            renderThrottle2Part(MyModPartialModels.CONTROL_DESK_THROTTLE_2_BASE, be, state, facing, ms, bufferSource, light, backRot);
            renderThrottle2Handle(be, state, facing, ms, bufferSource, light, backRot);
        } else {
            smoothThrottle2s.remove(be.getBlockPos());
        }
    }

    /** 踏板本体：facing 旋转 + 向模型空间 +z 平移（动画 = 指数逼近追逐压下值 × 1px）。 */
    private static void renderPedal(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                    BlockState state, Direction facing,
                                    PoseStack ms, MultiBufferSource bufferSource, int light, float zPx) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        if (zPx != 0f) {
            buffer.translate(0f, 0f, zPx);
        }
        buffer.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /** 操纵杆本体：facing 旋转 + 绕枢轴 (8,6,3) 倾斜（动画 = 指数逼近追逐模拟轴 × 15°）。 */
    private void renderJoystick(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                PoseStack ms, MultiBufferSource bufferSource, int light) {
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
        stick.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /** 摇杆2 手柄：放置变换（平移到放置位 + 安装朝向旋转绕放置中心）+ 绕枢轴 (8,1,8) 倾斜（动画 = 指数逼近追逐服务端权威轴值 × 15°，逻辑照抄 {@link #renderJoystick}，见 {@link Joystick2Motion}）。 */
    private void renderJoystick2(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                 PoseStack ms, MultiBufferSource bufferSource, int light, int backRot) {
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
        stick.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /** 油门手柄：放置变换（平移到放置位 + 安装朝向旋转绕放置中心）+ 沿模型空间 x 轴平移（档位位置 + 操作者本地张力蠕动，步进突然快速到位）。 */
    private void renderThrottleHandle(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                      PoseStack ms, MultiBufferSource bufferSource, int light, int backRot) {
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
        handle.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /** 静态部件：facing 旋转 + 安装朝向旋转（backRot，0 跳过）。 */
    private static void renderPart(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                   BlockState state, Direction facing, PoseStack ms,
                                   MultiBufferSource bufferSource, int light, int backRot) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        if (backRot != 0) {
            buffer.rotateCenteredDegrees(backRot, Direction.UP);
        }
        buffer.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /** throttle 静态部件：facing 旋转 + 模型平移到放置位 + 安装朝向旋转（只能 0°/180°）绕放置中心。 */
    private static void renderThrottlePart(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                           ControlDeskBlockEntity be, BlockState state, Direction facing,
                                           PoseStack ms, MultiBufferSource bufferSource, int light, int backRot) {
        SuperByteBuffer buffer = placedBuffer(model, state, facing,
                be.getThrottlePlaceX(), be.getThrottlePlaceZ(),
                ControlDeskBlockEntity.THROTTLE_MODEL_CENTER, ControlDeskBlockEntity.THROTTLE_PLACE_Y_BOTTOM, backRot);
        buffer.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /** throttle_2 静态部件：facing 旋转 + 模型平移到放置位 + 安装朝向旋转（只能 0°/180°）绕放置中心。 */
    private static void renderThrottle2Part(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                            ControlDeskBlockEntity be, BlockState state, Direction facing,
                                            PoseStack ms, MultiBufferSource bufferSource, int light, int backRot) {
        SuperByteBuffer buffer = placedBuffer(model, state, facing,
                be.getThrottle2PlaceX(), be.getThrottle2PlaceZ(),
                ControlDeskBlockEntity.THROTTLE_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
        buffer.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /**
     * throttle_2 手柄：放置变换（平移到放置位 + 安装朝向旋转绕放置中心）+ 绕枢轴 (4,2,8) 旋转
     * （总距杆类型：角度 0..+30°，数值 = 服务端权威角度，动画层指数逼近，见 {@link Throttle2Motion}；
     * 与 {@link #renderJoystick2} 同变换结构——tiltX 绕 Z 轴（横向水平轴），tiltY 不参与）。
     */
    private void renderThrottle2Handle(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                       PoseStack ms, MultiBufferSource bufferSource, int light, int backRot) {
        float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
        float target = Throttle2Motion.targetDeg(be);
        float smooth = smoothThrottle2s.computeIfAbsent(be.getBlockPos(), k -> 0f);
        smooth = JoystickTilt.approach(smooth, target, frameTicks);
        smoothThrottle2s.put(be.getBlockPos(), smooth);

        SuperByteBuffer handle = placedBuffer(MyModPartialModels.CONTROL_DESK_THROTTLE_2_HANDLE, state, facing,
                be.getThrottle2PlaceX(), be.getThrottle2PlaceZ(),
                ControlDeskBlockEntity.THROTTLE_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
        if (smooth != 0f) {
            handle.translate(Throttle2Motion.PIVOT_X, Throttle2Motion.PIVOT_Y, Throttle2Motion.PIVOT_Z)
                    .rotate(Mth.DEG_TO_RAD * smooth, Direction.SOUTH)
                    .translate(-Throttle2Motion.PIVOT_X, -Throttle2Motion.PIVOT_Y, -Throttle2Motion.PIVOT_Z);
        }
        handle.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }

    /**
     * 放置部件（joystick_2 / throttle 共用）：facing 旋转 + 模型平移到放置位（默认中心 x/z=8、底座底 y=0 → 放置位底 y=7）+
     * 安装朝向旋转绕放置中心（与预览盒/实物预览一致，三处变换统一，见 {@code memo/control-desk-grid-slot.md}）。
     */
    private static void renderJoystick2Part(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                                            ControlDeskBlockEntity be, BlockState state, Direction facing,
                                            PoseStack ms, MultiBufferSource bufferSource, int light, int backRot) {
        SuperByteBuffer buffer = placedBuffer(model, state, facing,
                be.getJoystick2PlaceX(), be.getJoystick2PlaceZ(),
                ControlDeskBlockEntity.JOYSTICK_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y, backRot);
        buffer.light(light).renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
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

    /**
     * monitor_2 表面小 Monitor 模块渲染（BER 回退，Flywheel 不可用时）：照抄 {@link MonitorRenderer}
     * 的模块渲染（模型 + 额外部件 + 动画），但变换链为 monitor_2 的：放置变换（R_facing · T(shift)）
     * + case 22.5° x 旋转（模型内烘焙）+ 屏幕面定位 + 模块初始旋转 + 按压深度。
     * 模块动画值按 (BlockPos, moduleId) 隔离，与 Flywheel 路径一致。
     */
    private void renderMonitor2Modules(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                       PoseStack ms, MultiBufferSource bufferSource, int light, int overlay) {
        var grid = be.getMonitor2Grid();
        BlockPos pos = be.getBlockPos();

        var beAnims = monitor2Anims.computeIfAbsent(pos, k -> new HashMap<>());
        beAnims.keySet().removeIf(id -> !grid.getAllModules().containsKey(id));
        if (grid.getAllModules().isEmpty()) {
            monitor2Anims.remove(pos);
            return;
        }

        float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
        for (var mod : grid.getAllModules().values()) {
            var bhv = ModuleRenderBehavior.of(mod.type());
            boolean isKnob = mod.type() == ModuleType.KNOB;

            // 屏幕面定位（北向基准模型空间 px，内缩 1px 网格 + 模块微调）；模块向外凸 1px（见 MONITOR_2_MODULE_PROTRUDE_PX）
            float[] anchor = monitor2ModuleAnchor(mod, bhv);
            float px = anchor[0];
            float py = anchor[1];
            float pz = anchor[2];

            float target;
            if (isKnob) {
                Float visual = Monitor2GridOverlay.getActiveKnobVisualAngle(pos, mod.id());
                target = visual != null ? visual : grid.getKnobAngle(mod.id());
            } else {
                target = grid.isPressed(mod.id()) ? 1f : 0f;
            }
            float next = ModuleRenderBehavior.stepAnim(beAnims, mod.id(), isKnob, target,
                    bhv.animPressSpeed(), bhv.animReleaseSpeed());

            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            ms.pushPose();
            applyMonitor2ModuleTransform(ms, be, facing, px, py, pz, mod.type());
            if (bhv.usePressDepth()) {
                ms.translate(0f, 0f, ModuleRenderBehavior.ButtonBehavior.PRESS_DEPTH * next / 16f);
            }
            // 底座
            Screen9GridRenderer.renderModel(ms, bufferSource.getBuffer(Sheets.solidBlockSheet()), model, light, overlay);
            // 额外部件（拉杆/按钮头/旋钮把手/灯带）
            float lightLevel = next;
            if (mod.type() == ModuleType.BUTTON_1X1) {
                lightLevel = grid.isLightCodeControlled(mod.id())
                        ? grid.getLightBrightness(mod.id()) : next;
            }
            bhv.renderExtra(ms, bufferSource, next, lightLevel, light, overlay);
            // 表面装饰：旋钮角度文字 / 按钮标签（灯带已由 renderExtra 绘制）
            if (isKnob) {
                ModuleSurfaceRenderer.renderKnobAngle(ms, bufferSource, pos, mod.id(), light,
                        grid.getKnobAngle(mod.id()), grid.getModuleConfig(mod.id()),
                        ModuleSurfaceRenderer.MONITOR_2);
            }
            if (mod.type() == ModuleType.BUTTON_1X1) {
                ModuleSurfaceRenderer.renderButtonLabel(ms, bufferSource, grid.getButtonLabel(mod.id()), next, light);
            }
            ms.popPose();
        }
    }

    /**
     * monitor_2 表面模块装饰（Flywheel 可用时 BER 补画，对齐 {@link MonitorRenderer} 的
     * shellInstanced 分支）：模块模型由 {@link ControlDeskVisual} 实例化渲染，BER 只画
     * 旋钮表面角度/卡位文字、按钮灯带与按钮标签（Flywheel 无法表达这些文字/面片）。
     */
    private void renderMonitor2ModuleDecorations(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                                 PoseStack ms, MultiBufferSource bufferSource, int light, int overlay) {
        var grid = be.getMonitor2Grid();
        BlockPos pos = be.getBlockPos();

        for (var mod : grid.getAllModules().values()) {
            var bhv = ModuleRenderBehavior.of(mod.type());
            boolean isKnob = mod.type() == ModuleType.KNOB;

            float[] anchor = monitor2ModuleAnchor(mod, bhv);

            ms.pushPose();
            applyMonitor2ModuleTransform(ms, be, facing, anchor[0], anchor[1], anchor[2], mod.type());
            if (isKnob) {
                ModuleSurfaceRenderer.renderKnobAngle(ms, bufferSource, pos, mod.id(), light,
                        grid.getKnobAngle(mod.id()), grid.getModuleConfig(mod.id()),
                        ModuleSurfaceRenderer.MONITOR_2);
            }
            if (mod.type() == ModuleType.BUTTON_1X1) {
                Float visualAnim = ControlDeskVisual.getModuleAnim(pos, mod.id());
                float next = visualAnim != null ? visualAnim : (grid.isPressed(mod.id()) ? 1f : 0f);
                float lightLevel = grid.isLightCodeControlled(mod.id())
                        ? grid.getLightBrightness(mod.id()) : next;
                ((ModuleRenderBehavior.ButtonBehavior) bhv).renderIndicator(ms, bufferSource, next, lightLevel);
                ModuleSurfaceRenderer.renderButtonLabel(ms, bufferSource, grid.getButtonLabel(mod.id()), next, light);
            }
            ms.popPose();
        }
    }

    /**
     * 桌顶小模块（monitor 模块 button/knob/toggle_switch）渲染：BER 常驻（Flywheel 可用时也不实例化它们）。
     * 变换链：桌体 FACING 旋转 → 平移到桌顶格位（格 (gx,gy) ↔ 北向 px (1+gx, 9+gy)，模型坐桌面 y8）→
     * 朝向校正：button 底座原生为竖在 XY 面的贴片（前脸 +Z）→ 绕 X -90° 平躺朝上；toggle/knob 底座原生
     * 平躺（前脸 +Y）→ 直接放桌顶。暂为静态渲染（按钮弹起/开关关/旋钮 0°），交互动画后续接入。
     */
    private void renderDeskTopModules(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                      PoseStack ms, MultiBufferSource bufferSource, int light, int overlay) {
        var grid = be.getDeskTopGrid();
        if (grid.getAllModules().isEmpty()) return;

        for (var mod : grid.getAllModules().values()) {
            var bhv = ModuleRenderBehavior.of(mod.type());
            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            ms.pushPose();
            // 桌体 FACING 旋转（PoseStack 已平移到方块位置；与 applyMonitor2ModuleTransform 同链）
            ms.translate(0.5f, 0.5f, 0.5f);
            ms.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
            ms.translate(-0.5f, -0.5f, -0.5f);
            // 桌顶格位（px → 块，模型坐桌面 y8）；X/Z 微调对齐 monitor_2 锚点公式（offsetX/Z 为块单位）
            float px = (1 + mod.gridX() + bhv.offsetX() * 16f) / 16f;
            float pz = (9 + mod.gridY() + bhv.offsetZ() * 16f) / 16f;
            ms.translate(px, ControlDeskBlockEntity.MODEL_PLACE_Y / 16f, pz);
            // 朝向校正：button 贴片竖放 → 平躺；toggle/knob 底座已平躺
            if (mod.type() == ModuleType.BUTTON_1X1) {
                ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90));
            }
            // 底座 + 额外部件（静态）
            Screen9GridRenderer.renderModel(ms, bufferSource.getBuffer(Sheets.solidBlockSheet()), model, light, overlay);
            bhv.renderExtra(ms, bufferSource, 0f, 0f, light, overlay);
            ms.popPose();
        }
    }

    /** monitor_2 模块屏幕面锚点（北向基准模型空间 px，内缩 1px 网格 + 模块微调 + 凸出 1px）。 */
    private static float[] monitor2ModuleAnchor(MonitorModule mod, ModuleRenderBehavior bhv) {        return new float[]{
                ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1 + mod.gridX() + bhv.offsetX() * 16f,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1 + mod.gridY() + bhv.offsetY() * 16f,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_Z - ControlDeskBlockEntity.MONITOR_2_MODULE_PROTRUDE_PX
                        + bhv.offsetZ() * 16f
        };
    }

    /** monitor_2 模块基底变换（PoseStack 版）：facing → 放置平移 → case 22.5° x 旋转 → 屏幕面定位 → 模块初始旋转。 */
    private static void applyMonitor2ModuleTransform(PoseStack ms, ControlDeskBlockEntity be, Direction facing,
                                                     float px, float py, float pz, ModuleType type) {
        // 0. 桌体 FACING 旋转（绕方块中心 Y，PoseStack 已平移到方块位置；与 BER 控件渲染同链）
        ms.translate(0.5f, 0.5f, 0.5f);
        ms.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.getOpposite().toYRot()));
        ms.translate(-0.5f, -0.5f, -0.5f);
        // 1. 放置变换（平移到放置位，不面向玩家）
        float shiftX = (be.getMonitor2PlaceX() - ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER) / 16f;
        float shiftY = (ControlDeskBlockEntity.MODEL_PLACE_Y - ControlDeskBlockEntity.MONITOR_2_MODEL_BOTTOM_Y) / 16f;
        float shiftZ = (be.getMonitor2PlaceZ() - ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER) / 16f;
        ms.translate(shiftX, shiftY, shiftZ);
        // 2. case 22.5° x 旋转（绕 origin [14,4,3]，px 单位）
        ms.translate(ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_X / 16f,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Y / 16f,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Z / 16f);
        ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_DEG));
        ms.translate(-ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_X / 16f,
                -ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Y / 16f,
                -ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Z / 16f);
        // 3. 屏幕面定位（px → 块）
        ms.translate(px / 16f, py / 16f, pz / 16f);
        // 4. 模块初始旋转（与 ModuleRenderBehavior.applyInitialRotation 一致）
        if (type == ModuleType.TOGGLE_SWITCH || type == ModuleType.KNOB) {
            ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90));
        }
    }

    /**
     * monitor_2 表面屏幕 9 宫格 + 文字渲染（BER，对齐 {@link MonitorRenderer#renderScreen}）。
     * 变换链与模块渲染一致：facing 旋转（PoseStack 已平移到方块位置）→ 放置平移 → case 22.5° x 旋转
     * → 屏幕面网格定位；绘制本身委托 {@link Screen9GridRenderer}（块单位，Monitor 与 monitor_2 共用，
     * 消除「px/块单位只改一半」类 bug）。
     */
    private void renderMonitor2Screens(ControlDeskBlockEntity be, BlockState state, Direction facing,
                                       PoseStack ms, MultiBufferSource bufferSource, int light, int overlay) {
        var grid = be.getMonitor2Grid();
        if (grid.getScreenRegions().isEmpty()) return;

        BakedModel corner = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CORNER);
        BakedModel edge   = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_EDGE);
        BakedModel center = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CENTER);

        for (var scr : grid.getScreenRegions()) {
            ms.pushPose();
            applyMonitor2ModuleTransform(ms, be, facing, 0, 0, 0, null);
            Screen9GridRenderer.renderScreen(ms, bufferSource, corner, edge, center, scr,
                    grid.getScreenText(scr.id()), MONITOR_2_PLANE, light, overlay);
            ms.popPose();
        }
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull ControlDeskBlockEntity blockEntity) {
        // 操纵杆把手最高到 y≈17.4/16，monitor_2 屏幕可到 y≈19/16，超出 1 格默认盒；放大避免 BER 路径被视锥剔除
        return AABB.ofSize(blockEntity.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }
}
