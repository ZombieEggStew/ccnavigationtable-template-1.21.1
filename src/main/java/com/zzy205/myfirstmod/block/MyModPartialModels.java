package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * CCPE 自定义 PartialModel 注册（参考 Create 的 AllPartialModels 与 simulated 的 SimPartialModels）。
 */
public class MyModPartialModels {

    /** 控制台：踏板（东侧地面） */
    public static final PartialModel CONTROL_DESK_PEDAL = block("control_desk_1/pedal");
    /** 控制台：操纵杆（桌面北缘） */
    public static final PartialModel CONTROL_DESK_JOYSTICK = block("control_desk_1/joystick");
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
