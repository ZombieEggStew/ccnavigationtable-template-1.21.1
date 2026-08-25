package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * CCPE 自定义 PartialModel 注册（参考 Create 的 AllPartialModels 与 simulated 的 SimPartialModels）。
 */
public class MyModPartialModels {

    /** 控制台：底座由 blockstate 静态模型渲染；以下为可安装控件模型（安装后叠加渲染） */
    public static final PartialModel CONTROL_DESK_PEDAL = block("pedal/pedal");
    public static final PartialModel CONTROL_DESK_PEDAL_RIGHT = block("pedal/pedal_right");
    public static final PartialModel CONTROL_DESK_PEDAL_BASE = block("pedal/pedal_base");
    public static final PartialModel CONTROL_DESK_JOYSTICK = block("joystick/joystick");
    public static final PartialModel CONTROL_DESK_JOYSTICK_BASE = block("joystick/joystick_base");
    /** 控制台：monitor_2 / throttle / joystick_2（桌体后缘上方插槽，静态渲染；throttle 拆 base/handle/indicator 三部件，joystick_2 拆 base/handle） */
    public static final PartialModel CONTROL_DESK_MONITOR_2 = block("control_desk_1/monitor_2/monitor_2");
    public static final PartialModel CONTROL_DESK_THROTTLE_BASE = block("control_desk_1/throttle/throttle_base");
    public static final PartialModel CONTROL_DESK_THROTTLE_HANDLE = block("control_desk_1/throttle/throttle_handle");
    public static final PartialModel CONTROL_DESK_THROTTLE_INDICATOR = block("control_desk_1/throttle/throttle_indicator");
    public static final PartialModel CONTROL_DESK_JOYSTICK_2_BASE = block("control_desk_1/joystick_2/joystick_2_base");
    public static final PartialModel CONTROL_DESK_JOYSTICK_2_HANDLE = block("control_desk_1/joystick_2/joystick_2_handle");
    /** Monitor：偏航支架（bearing，随 facing+offset+yaw） */
    public static final PartialModel MONITOR_BEARING = block("monitor/my_monitor_bearing");
    /** Monitor：屏幕外壳（case，随 facing+offset+yaw+pitch；模型带 cutout 开孔） */
    public static final PartialModel MONITOR_CASE = block("monitor/my_monitor_case");
    /** Monitor 模块：按钮底座 / 按钮头（按下凹陷） */
    public static final PartialModel MODULE_BUTTON_BASE = block("button_1/button_1_base");
    public static final PartialModel MODULE_BUTTON_HEAD = block("button_1/button_1_head");
    /** Monitor 模块：钮子开关底座 / 拉杆 */
    public static final PartialModel MODULE_TOGGLE_BASE = block("toggle/toggle_base");
    public static final PartialModel MODULE_TOGGLE_LEVER = block("toggle/toggle");
    /** Monitor 模块：旋钮底座 / 旋转把手 */
    public static final PartialModel MODULE_KNOB_BASE = block("knob_1/knob_1_base");
    public static final PartialModel MODULE_KNOB_HANDLE = block("knob_1/knob_1");

    private static PartialModel block(String path) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "block/" + path));
    }

    /** 触发 static 字段初始化 */
    public static void init() {
    }
}
