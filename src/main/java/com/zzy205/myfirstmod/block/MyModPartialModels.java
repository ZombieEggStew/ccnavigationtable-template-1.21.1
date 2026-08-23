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

    private static PartialModel block(String path) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "block/" + path));
    }

    /** 触发 static 字段初始化 */
    public static void init() {
    }
}
