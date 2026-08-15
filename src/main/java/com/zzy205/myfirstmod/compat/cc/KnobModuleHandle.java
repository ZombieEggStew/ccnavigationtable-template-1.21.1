package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 旋钮模块（knob）的 Lua 模块实例。
 * <p>
 * 提供 {@link #getAngle()} / {@link #setAngle(double)}，角度单位为度（0..360）。
 * {@link #setAngle(double)} 遵循旋钮的卡位（detent）配置：开启卡位时自动吸附到最近档位。
 */
public final class KnobModuleHandle extends ModuleHandle {

    public KnobModuleHandle(MonitorBlockEntity be, MonitorModule module) {
        super(be, module.id(), module.type().name, module.gridX(), module.gridY(),
                module.getWidth(), module.getHeight());
    }

    /** 读取当前角度（度，0..360）。 */
    @LuaFunction
    public final double getAngle() {
        return be.getGridState().getKnobAngle(id);
    }

    /**
     * 设置角度（度）。开启卡位（detent）时自动吸附到最近档位。
     *
     * @param angle 目标角度（度），自动归一化到 0..360
     */
    @LuaFunction(mainThread = true)
    public final void setAngle(double angle) {
        be.rotateKnob(id, (float) angle);
    }
}
