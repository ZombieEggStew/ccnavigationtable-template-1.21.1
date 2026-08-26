package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.client.Monitor2GridOverlay;
import com.zzy205.myfirstmod.client.SeatControlState;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 控制台 Flywheel Visual：按 BE 已安装控件状态叠加渲染控件（底座 + 本体）。
 * 安装状态变化时动态创建/删除实例；实例存在期间每帧重置变换并刷新 facing 旋转
 * （必须 setIdentityTransform，translate 为累加语义，否则模型每帧漂移）。
 * 操纵杆本体（joystick）叠加倾斜：绕枢轴 (8,6,3)（见 {@link JoystickTilt}）倾斜，
 * 目标 = 模拟轴（每 tick 线性累加，{@link com.zzy205.myfirstmod.client.SeatControlState}）× 15°；
 * 摇杆2 手柄（joystick_2_handle）叠加倾斜：绕枢轴 (8,1,8)（见 {@link Joystick2Motion}）倾斜，
 * 目标 = 服务端权威轴值 × 15°（独立配置/轴值，逻辑照抄 joystick）；
 * 踏板本体（pedal / pedal_right）叠加平移：向模型空间 +z 平移压下值 × 1px（见 {@link PedalMotion}）；
 * 油门手柄（throttle_handle）叠加平移：向模型空间 +x 平移档位位置（× 11px，段落感，见 {@link ThrottleMotion}）；
 * 动画均用指数逼近追逐目标（aeroworks SMOOTHED 模式，帧时间修正），本实例持有平滑值。
 * 模型按与底座相同的方块空间（北向）建模，渲染时平移到方块位置 + 绕方块中心 Y 旋转到 FACING。
 */
public class ControlDeskVisual extends AbstractBlockEntityVisual<ControlDeskBlockEntity>
        implements SimpleDynamicVisual {

    private TransformedInstance pedal;
    private TransformedInstance pedalRight;
    private TransformedInstance pedalBase;
    private TransformedInstance joystick;
    private TransformedInstance joystickBase;
    /** monitor_2 / throttle / joystick_2（桌体后缘上方插槽，静态渲染） */
    private TransformedInstance monitor2;
    private TransformedInstance throttleBase;
    private TransformedInstance throttleHandle;
    private TransformedInstance throttleIndicator;
    private TransformedInstance joystick2Base;
    private TransformedInstance joystick2Handle;

    /** 操纵杆动画倾斜值（度）：指数逼近追逐 {@link JoystickTilt#targetDeg} */
    private float smoothTiltX;
    private float smoothTiltY;
    /** 摇杆2 动画倾斜值（度）：指数逼近追逐 {@link Joystick2Motion#targetDeg} */
    private float smoothTilt2X;
    private float smoothTilt2Y;
    /** 踏板动画平移量（块单位）：指数逼近追逐 {@link PedalMotion#targetPx}（左/右） */
    private float smoothPedalLeft;
    private float smoothPedalRight;
    /** 油门动画平移量（块单位）：档位切换快速逼近追逐 {@link ThrottleMotion#targetPx}（沿模型空间 x 轴，段落感） */
    private float smoothThrottle;
    /** 油门张力状态：客户端观察到的上一档位位置（块单位）、张力充电进度（0..1，帧时间平滑推进）、上一操作方向 */
    private float lastThrottleGearPx;
    private float throttleChargeProgress;
    private int lastThrottleDir;

    // ── monitor_2 表面小 Monitor 模块（复用 Monitor 的模块模型与动画）──
    /** moduleId → 模块实例（底座 + 额外部件） */
    private final Map<Integer, ModuleVisual2> monitor2Modules = new HashMap<>();
    /** monitor_2 模块按压/旋钮动画值（单一实现 {@link ModuleRenderBehavior#stepAnim}） */
    private final Map<Integer, Float> monitor2Anims = new HashMap<>();

    /** 供 ControlDeskRenderer（BER）读取的 monitor_2 模块动画值，外层 key=BlockPos，内层 key=moduleId */
    private static final Map<BlockPos, Map<Integer, Float>> ACTIVE_ANIMS = new HashMap<>();

    /** BER 读取模块动画值（按钮灯带/标签深度用）；无 Visual 时返回 null。 */
    public static Float getModuleAnim(BlockPos pos, int moduleId) {
        Map<Integer, Float> anims = ACTIVE_ANIMS.get(pos);
        return anims == null ? null : anims.get(moduleId);
    }

    /** monitor_2 表面单模块的两个实例（底座 + 额外部件：按钮头/钮子拉杆/旋钮把手）。 */
    private static final class ModuleVisual2 {
        final ModuleType type;
        final TransformedInstance base;
        final TransformedInstance extra;

        ModuleVisual2(ModuleType type, TransformedInstance base, TransformedInstance extra) {
            this.type = type;
            this.base = base;
            this.extra = extra;
        }

        void delete() {
            base.delete();
            if (extra != null) extra.delete();
        }
    }

    public ControlDeskVisual(VisualizationContext ctx, ControlDeskBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);
        // 初始化油门张力状态（首帧不误判步进）
        this.lastThrottleGearPx = ThrottleMotion.targetPx(blockEntity);
        this.throttleChargeProgress = 0f;
        this.lastThrottleDir = 0;
        this.transformAll();
    }

    @Override
    public void beginFrame(Context context) {
        this.transformAll();
    }

    private void transformAll() {
        final ControlDeskBlockEntity be = this.blockEntity;
        final BlockState state = be.getBlockState();
        final Direction facing = state.getValue(ControlDeskBlock.FACING);

        boolean pedalWanted = be.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL);
        boolean joystickWanted = be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK);
        boolean monitor2Wanted = be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2);
        boolean throttleWanted = be.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE);
        boolean joystick2Wanted = be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2);

        this.pedalBase = syncInstance(this.pedalBase, pedalWanted, MyModPartialModels.CONTROL_DESK_PEDAL_BASE, facing, null);

        // 踏板本体：动画 = 指数逼近追逐目标（数值层线性累加，动画层指数），目标平移量 = 压下值 × 1px
        float frameTicks = Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
        float[] pedalTarget = PedalMotion.targetPx(be);
        this.smoothPedalLeft = JoystickTilt.approach(this.smoothPedalLeft, pedalTarget[0], frameTicks);
        this.smoothPedalRight = JoystickTilt.approach(this.smoothPedalRight, pedalTarget[1], frameTicks);
        final float pedalLeftPx = this.smoothPedalLeft;
        final float pedalRightPx = this.smoothPedalRight;
        this.pedal = syncInstance(this.pedal, pedalWanted, MyModPartialModels.CONTROL_DESK_PEDAL, facing,
                inst -> inst.translate(0f, 0f, pedalLeftPx));
        this.pedalRight = syncInstance(this.pedalRight, pedalWanted, MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT, facing,
                inst -> inst.translate(0f, 0f, pedalRightPx));
        this.joystickBase = syncInstance(this.joystickBase, joystickWanted, MyModPartialModels.CONTROL_DESK_JOYSTICK_BASE, facing, null);

        // 操纵杆本体：动画 = 指数逼近追逐目标（数值层线性累加，动画层指数）
        float[] target = JoystickTilt.targetDeg(be);
        this.smoothTiltX = JoystickTilt.approach(this.smoothTiltX, target[0], frameTicks);
        this.smoothTiltY = JoystickTilt.approach(this.smoothTiltY, target[1], frameTicks);
        final float tiltX = this.smoothTiltX;
        final float tiltY = this.smoothTiltY;
        this.joystick = syncInstance(this.joystick, joystickWanted, MyModPartialModels.CONTROL_DESK_JOYSTICK, facing,
                inst -> applyTilt(inst, tiltX, tiltY, JoystickTilt.PIVOT_X, JoystickTilt.PIVOT_Y, JoystickTilt.PIVOT_Z));

        // monitor_2：已接入棋盘自由放置——模型平移到放置位，不面向玩家（无安装朝向旋转，仅随桌体 FACING）
        this.monitor2 = syncInstance(this.monitor2, monitor2Wanted, MyModPartialModels.CONTROL_DESK_MONITOR_2, facing,
                inst -> applyMonitor2Placement(inst, be));
        // monitor_2 表面小 Monitor：渲染表面模块（复用 Monitor 模块模型与动画，变换 = 放置 + case 22.5° 旋转 + 屏幕面定位）
        this.transformMonitor2Modules(be, facing, frameTicks);
        this.throttleBase = syncInstance(this.throttleBase, throttleWanted, MyModPartialModels.CONTROL_DESK_THROTTLE_BASE, facing,
                inst -> applyThrottlePlacement(inst, be));

        // 油门手柄：档位位置（服务端权威）+ 操作者本地"张力蠕动"（按住向下一档稍微移动，
        // 满 TICKS_PER_GEAR tick 档位步进后张力清零 → 突然快速到位，参考 knob 卡位）。
        // 张力充电进度用帧时间平滑推进（避免游戏时间按整 tick 跳变导致渲染卡顿）
        float gearPx = ThrottleMotion.targetPx(be);
        if (gearPx != this.lastThrottleGearPx) {
            this.lastThrottleGearPx = gearPx;
            this.throttleChargeProgress = 0f; // 档位步进：张力清零
        }
        int throttleDir = SeatControlState.isLinkedDesk(be.getBlockPos())
                ? SeatControlState.getThrottleDir() : 0;
        if (throttleDir != this.lastThrottleDir) {
            this.lastThrottleDir = throttleDir;
            this.throttleChargeProgress = 0f; // 按键按下/松开边沿：张力清零
        }
        if (throttleDir != 0) {
            this.throttleChargeProgress = Math.min(1f,
                    this.throttleChargeProgress + frameTicks / be.getThrottleTicksPerGear());
        }
        float throttleTarget = gearPx + ThrottleMotion.tensionPx(throttleDir, this.throttleChargeProgress, gearPx);
        this.smoothThrottle = ThrottleMotion.approachStep(this.smoothThrottle, throttleTarget, frameTicks);
        final float throttlePx = this.smoothThrottle;
        this.throttleHandle = syncInstance(this.throttleHandle, throttleWanted, MyModPartialModels.CONTROL_DESK_THROTTLE_HANDLE, facing,
                inst -> {
                    applyThrottlePlacement(inst, be);
                    // 档位平移（模型空间 x 轴，最后调用 = 最内层，先于 facing/放置旋转作用于模型）
                    inst.translate(throttlePx, 0f, 0f);
                });
        // 指示灯：随油门档位大小从暗红（熄灭）→ 亮红（满油门）着色（参考 Create analog lever / Simulated diode）
        this.throttleIndicator = syncInstance(this.throttleIndicator, throttleWanted, MyModPartialModels.CONTROL_DESK_THROTTLE_INDICATOR, facing,
                inst -> applyThrottlePlacement(inst, be));
        if (this.throttleIndicator != null) {
            this.throttleIndicator.colorArgb(ThrottleMotion.indicatorColor(be.getThrottleGear()));
            this.throttleIndicator.setChanged();
        }

        // joystick_2：底座静态 + 手柄倾斜动画；模型平移到放置位（预览盒位置 y7..16），安装朝向旋转绕放置中心，
        // 手柄叠加倾斜：指数逼近追逐目标（数值层线性累加，动画层指数），绕枢轴 (8,1,8) 倾斜（见 Joystick2Motion）
        this.joystick2Base = syncInstance(this.joystick2Base, joystick2Wanted, MyModPartialModels.CONTROL_DESK_JOYSTICK_2_BASE, facing,
                inst -> applyJoystick2Placement(inst, be));
        float[] target2 = Joystick2Motion.targetDeg(be);
        this.smoothTilt2X = JoystickTilt.approach(this.smoothTilt2X, target2[0], frameTicks);
        this.smoothTilt2Y = JoystickTilt.approach(this.smoothTilt2Y, target2[1], frameTicks);
        final float tilt2X = this.smoothTilt2X;
        final float tilt2Y = this.smoothTilt2Y;
        this.joystick2Handle = syncInstance(this.joystick2Handle, joystick2Wanted, MyModPartialModels.CONTROL_DESK_JOYSTICK_2_HANDLE, facing,
                inst -> {
                    applyJoystick2Placement(inst, be);
                    applyTilt(inst, tilt2X, tilt2Y, Joystick2Motion.PIVOT_X, Joystick2Motion.PIVOT_Y, Joystick2Motion.PIVOT_Z);
                });
    }

    /**
     * joystick_2 放置变换：模型平移到放置位（默认中心 x/z=8、底座底 y=0 → 模型坐桌面 y8，见
     * {@link ControlDeskBlockEntity#JOYSTICK_2_MODEL_CENTER} / {@link ControlDeskBlockEntity#MODEL_PLACE_Y}），
     * 安装朝向旋转绕放置中心（Y 旋转，枢轴 y 不影响）。
     */
    private static void applyJoystick2Placement(TransformedInstance inst, ControlDeskBlockEntity be) {
        applyPlacement(inst, be.getBackSlotRotation(),
                be.getJoystick2PlaceX(), be.getJoystick2PlaceZ(),
                ControlDeskBlockEntity.JOYSTICK_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y,
                ControlDeskBlockEntity.JOYSTICK_2_MODEL_BOTTOM_Y);
    }

    /**
     * throttle 放置变换（唯一合法位 (8,12)，见 {@link ControlDeskBlockEntity#THROTTLE_PLACE_X}）：
     * 与 joystick_2 同链——模型平移到放置位 + 安装朝向旋转（只能 0°/180°）绕放置中心。
     */
    private static void applyThrottlePlacement(TransformedInstance inst, ControlDeskBlockEntity be) {
        applyPlacement(inst, be.getBackSlotRotation(),
                be.getThrottlePlaceX(), be.getThrottlePlaceZ(),
                ControlDeskBlockEntity.THROTTLE_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y,
                ControlDeskBlockEntity.THROTTLE_MODEL_BOTTOM_Y);
    }

    /**
     * monitor_2 放置变换（唯一合法位 (8,12)，见 {@link ControlDeskBlockEntity#MONITOR_2_PLACE_X}）：
     * 不面向玩家（backRot 恒 0）——仅平移到放置位，随桌体 FACING 旋转。
     */
    private static void applyMonitor2Placement(TransformedInstance inst, ControlDeskBlockEntity be) {
        applyPlacement(inst, 0,
                be.getMonitor2PlaceX(), be.getMonitor2PlaceZ(),
                ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y,
                ControlDeskBlockEntity.MONITOR_2_MODEL_BOTTOM_Y);
    }

    /**
     * monitor_2 表面小 Monitor 模块渲染（Flywheel）：按网格状态动态创建/删除模块实例。
     * 变换链 = monitor_2 放置变换（R_facing · T(shift)）+ case 22.5° x 旋转（模型内烘焙）
     * + 屏幕面定位 + 模块初始旋转 + 动画。与 {@link ControlDeskPlacementOverlay#monitor2World}
     * 的放置/旋转约定一致，模块定位点同 monitor_2 屏幕面网格坐标（北向基准 px）。
     */
    private void transformMonitor2Modules(ControlDeskBlockEntity be, Direction facing, float frameTicks) {
        final BlockPos pos = be.getBlockPos();
        // 未安装 monitor_2：删除全部模块实例
        if (!be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) {
            ACTIVE_ANIMS.remove(pos);
            if (!monitor2Modules.isEmpty()) {
                monitor2Modules.values().forEach(ModuleVisual2::delete);
                monitor2Modules.clear();
                monitor2Anims.clear();
            }
            return;
        }

        GridState grid = be.getMonitor2Grid();

        // 发布本帧动画值（供 BER 按钮灯带/标签深度读取，对齐 MonitorVisual）
        ACTIVE_ANIMS.put(pos, this.monitor2Anims);

        // 删除已移除的模块实例
        monitor2Modules.keySet().removeIf(id -> {
            if (!grid.getAllModules().containsKey(id)) {
                monitor2Modules.get(id).delete();
                return true;
            }
            return false;
        });

        for (MonitorModule mod : grid.getAllModules().values()) {
            ModuleVisual2 mv = monitor2Modules.get(mod.id());
            if (mv == null) {
                mv = createMonitor2ModuleVisual(mod.type());
                monitor2Modules.put(mod.id(), mv);
                this.relight(mv);
            }

            var bhv = ModuleRenderBehavior.of(mod.type());
            boolean isKnob = mod.type() == ModuleType.KNOB;

            // 屏幕面定位（北向基准模型空间 px，内缩 1px 网格 + 模块微调）；模块向外凸 1px（见 MONITOR_2_MODULE_PROTRUDE_PX）
            float px = ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1 + mod.gridX() + bhv.offsetX() * 16f;
            float py = ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1 + mod.gridY() + bhv.offsetY() * 16f;
            float pz = ControlDeskBlockEntity.MONITOR_2_SCREEN_Z - ControlDeskBlockEntity.MONITOR_2_MODULE_PROTRUDE_PX
                    + bhv.offsetZ() * 16f;

            float target;
            if (isKnob) {
                Float visual = Monitor2GridOverlay.getActiveKnobVisualAngle(pos, mod.id());
                target = visual != null ? visual : grid.getKnobAngle(mod.id());
            } else {
                target = grid.isPressed(mod.id()) ? 1f : 0f;
            }
            float next = ModuleRenderBehavior.stepAnim(monitor2Anims, mod.id(), isKnob, target,
                    bhv.animPressSpeed(), bhv.animReleaseSpeed());

            // 底座：放置变换 + case 22.5° 旋转 + 屏幕面定位 + 初始旋转 + 按压深度
            mv.base.setIdentityTransform();
            applyMonitor2Base(mv.base, be, facing, px, py, pz, mod.type(), bhv.usePressDepth() ? next : 0f);
            mv.base.setChanged();

            // 额外部件：底座变换 + 部件动画（与 ModuleRenderBehavior.renderExtra 一致）
            mv.extra.setIdentityTransform();
            applyMonitor2Base(mv.extra, be, facing, px, py, pz, mod.type(), 0f);
            switch (mod.type()) {
                case BUTTON_1X1 -> mv.extra.translate(0f, 0f,
                        ModuleRenderBehavior.ButtonBehavior.PRESS_DEPTH * next / 16f);
                case TOGGLE_SWITCH -> {
                    mv.extra.translate(1 / 32f, 0f, 1 / 32f);
                    mv.extra.rotateX((float) Math.toRadians(-30 + next * 60));
                }
                case KNOB -> mv.extra.rotateY((float) Math.toRadians(-next));
            }
            mv.extra.setChanged();
        }
    }

    /**
     * monitor_2 模块基底变换（底座与额外部件共用，需先经 setIdentityTransform）：
     * {@code T(pos) · R_facing · T(shift) · Tilt22.5°(case) · T(px,py,pz) · R_initial · T(pressDepth)}。
     * 与 {@link ControlDeskPlacementOverlay#monitor2World} 的放置/旋转约定一致（北向基准模型空间 px）；
     * facing/方块位置链与 {@link #syncInstance} 中 monitor_2 本体一致（translate(pos) → rotateCenteredDegrees → applyPlacement）。
     */
    private void applyMonitor2Base(TransformedInstance inst, ControlDeskBlockEntity be, Direction facing,
                                   float px, float py, float pz, ModuleType type, float pressDepth) {
        // 0. 平移到方块位置 + 桌体 FACING 旋转（与 syncInstance 同链）
        inst.translate(this.getVisualPosition());
        inst.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        // 1. 放置变换（平移到放置位，不面向玩家）
        applyPlacement(inst, 0,
                be.getMonitor2PlaceX(), be.getMonitor2PlaceZ(),
                ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER, ControlDeskBlockEntity.MODEL_PLACE_Y,
                ControlDeskBlockEntity.MONITOR_2_MODEL_BOTTOM_Y);
        // 2. case 22.5° x 旋转（模型内烘焙，绕 origin [14,4,3]；px 单位 → 除以 16 转块）
        double rad = Math.toRadians(ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_DEG);
        inst.translate(ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_X / 16f,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Y / 16f,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Z / 16f);
        inst.rotateX((float) rad);
        inst.translate(-ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_X / 16f,
                -ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Y / 16f,
                -ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Z / 16f);
        // 3. 屏幕面定位（px → 块）
        inst.translate(px / 16f, py / 16f, pz / 16f);
        // 4. 模块初始旋转（与 ModuleRenderBehavior.applyInitialRotation 一致）
        if (type == ModuleType.TOGGLE_SWITCH || type == ModuleType.KNOB) {
            inst.rotateX((float) Math.toRadians(-90));
        }
        // 5. 按压深度（按钮：沿 z 凹陷，模块局部）
        if (pressDepth > 0f) {
            inst.translate(0f, 0f, ModuleRenderBehavior.ButtonBehavior.PRESS_DEPTH * pressDepth / 16f);
        }
    }

    private ModuleVisual2 createMonitor2ModuleVisual(ModuleType type) {
        PartialModel base = switch (type) {
            case BUTTON_1X1 -> MyModPartialModels.MODULE_BUTTON_BASE;
            case TOGGLE_SWITCH -> MyModPartialModels.MODULE_TOGGLE_BASE;
            case KNOB -> MyModPartialModels.MODULE_KNOB_BASE;
        };
        PartialModel extra = switch (type) {
            case BUTTON_1X1 -> MyModPartialModels.MODULE_BUTTON_HEAD;
            case TOGGLE_SWITCH -> MyModPartialModels.MODULE_TOGGLE_LEVER;
            case KNOB -> MyModPartialModels.MODULE_KNOB_HANDLE;
        };
        TransformedInstance baseInst = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(base)).createInstance();
        TransformedInstance extraInst = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(extra)).createInstance();
        return new ModuleVisual2(type, baseInst, extraInst);
    }

    private void relight(ModuleVisual2 mv) {
        this.relight(mv.base);
        if (mv.extra != null) this.relight(mv.extra);
    }

    /**
     * 放置变换公共链（三处渲染统一，见 {@code memo/control-desk-grid-slot.md} 变换链）：
     * {@code R_facing · [T(px,0.5,pz)·R_backRot·T(-px,-0.5,-pz)] · T(shift)}，
     * {@code shift = ((px-modelCenter)/16, (placeYBottom-modelBottomY)/16, (pz-modelCenter)/16)}。
     */
    private static void applyPlacement(TransformedInstance inst, int backRot,
                                       int placeX, int placeZ, float modelCenter, float placeYBottom, float modelBottomY) {
        float px = placeX / 16f;
        float pz = placeZ / 16f;
        if (backRot != 0) {
            inst.translate(px, 0.5f, pz);
            inst.rotate((float) Math.toRadians(backRot), Direction.UP);
            inst.translate(-px, -0.5f, -pz);
        }
        inst.translate((placeX - modelCenter) / 16f, (placeYBottom - modelBottomY) / 16f, (placeZ - modelCenter) / 16f);
    }

    /** 按安装状态创建/删除实例；存在的实例每帧重置变换 + 平移到位 + 旋转到 facing + 追加额外变换并标记更新。 */
    private TransformedInstance syncInstance(TransformedInstance instance, boolean wanted,
                                             PartialModel model, Direction facing,
                                             Consumer<TransformedInstance> extra) {
        if (wanted && instance == null) {
            instance = this.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
                    .createInstance();
        } else if (!wanted && instance != null) {
            instance.delete();
            return null;
        }
        if (instance != null) {
            instance.setIdentityTransform();
            instance.translate(this.getVisualPosition());
            instance.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
            if (extra != null) {
                extra.accept(instance);
            }
            instance.setChanged();
        }
        return instance;
    }

    /** 绕枢轴倾斜（模型空间变换，枢轴由调用方传入：joystick (8,6,3) / 摇杆2 (8,1,8)）：tiltY 绕 X 轴（W/S 前后），tiltX 绕 Z 轴（A/D 左右）。 */
    private static void applyTilt(TransformedInstance inst, float tiltX, float tiltY,
                                  float pivotX, float pivotY, float pivotZ) {
        if (tiltX == 0f && tiltY == 0f) return;
        inst.translate(pivotX, pivotY, pivotZ);
        inst.rotateX((float) Math.toRadians(tiltY));
        inst.rotateZ((float) Math.toRadians(tiltX));
        inst.translate(-pivotX, -pivotY, -pivotZ);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        if (this.pedal != null) consumer.accept(this.pedal);
        if (this.pedalRight != null) consumer.accept(this.pedalRight);
        if (this.pedalBase != null) consumer.accept(this.pedalBase);
        if (this.joystick != null) consumer.accept(this.joystick);
        if (this.joystickBase != null) consumer.accept(this.joystickBase);
        if (this.monitor2 != null) consumer.accept(this.monitor2);
        for (ModuleVisual2 mv : monitor2Modules.values()) {
            consumer.accept(mv.base);
            if (mv.extra != null) consumer.accept(mv.extra);
        }
        if (this.throttleBase != null) consumer.accept(this.throttleBase);
        if (this.throttleHandle != null) consumer.accept(this.throttleHandle);
        if (this.throttleIndicator != null) consumer.accept(this.throttleIndicator);
        if (this.joystick2Base != null) consumer.accept(this.joystick2Base);
        if (this.joystick2Handle != null) consumer.accept(this.joystick2Handle);
    }

    @Override
    public void updateLight(float v) {
        if (this.pedal != null) this.relight(this.pedal);
        if (this.pedalRight != null) this.relight(this.pedalRight);
        if (this.pedalBase != null) this.relight(this.pedalBase);
        if (this.joystick != null) this.relight(this.joystick);
        if (this.joystickBase != null) this.relight(this.joystickBase);
        if (this.monitor2 != null) this.relight(this.monitor2);
        for (ModuleVisual2 mv : monitor2Modules.values()) this.relight(mv);
        if (this.throttleBase != null) this.relight(this.throttleBase);
        if (this.throttleHandle != null) this.relight(this.throttleHandle);
        if (this.throttleIndicator != null) this.relight(this.throttleIndicator);
        if (this.joystick2Base != null) this.relight(this.joystick2Base);
        if (this.joystick2Handle != null) this.relight(this.joystick2Handle);
    }

    @Override
    protected void _delete() {
        if (this.pedal != null) this.pedal.delete();
        if (this.pedalRight != null) this.pedalRight.delete();
        if (this.pedalBase != null) this.pedalBase.delete();
        if (this.joystick != null) this.joystick.delete();
        if (this.joystickBase != null) this.joystickBase.delete();
        if (this.monitor2 != null) this.monitor2.delete();
        monitor2Modules.values().forEach(ModuleVisual2::delete);
        monitor2Modules.clear();
        monitor2Anims.clear();
        ACTIVE_ANIMS.remove(this.blockEntity.getBlockPos());
        if (this.throttleBase != null) this.throttleBase.delete();
        if (this.throttleHandle != null) this.throttleHandle.delete();
        if (this.throttleIndicator != null) this.throttleIndicator.delete();
        if (this.joystick2Base != null) this.joystick2Base.delete();
        if (this.joystick2Handle != null) this.joystick2Handle.delete();
    }
}
