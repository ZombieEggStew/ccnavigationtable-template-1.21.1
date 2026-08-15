package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 按钮模块（button_1）的 Lua 模块实例。
 * <p>
 * 按钮为瞬时型：{@link #press()} 按下、{@link #release()} 弹起，{@link #isPressed()} 读取当前按下状态。
 */
public final class ButtonModuleHandle extends ModuleHandle {

    public ButtonModuleHandle(MonitorBlockEntity be, MonitorModule module) {
        super(be, module.id(), module.type().name, module.gridX(), module.gridY(),
                module.getWidth(), module.getHeight());
    }

    /** 按下按钮（瞬时）。 */
    @LuaFunction(mainThread = true)
    public final void press() {
        be.pressModule(id);
    }

    /** 弹起按钮（瞬时）。 */
    @LuaFunction(mainThread = true)
    public final void release() {
        be.releaseModule(id);
    }

    /** 当前是否处于按下状态。 */
    @LuaFunction
    public final boolean isPressed() {
        return be.getGridState().isPressed(id);
    }
}
