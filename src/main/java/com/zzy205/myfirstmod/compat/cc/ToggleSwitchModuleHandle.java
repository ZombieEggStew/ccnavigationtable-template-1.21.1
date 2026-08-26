package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorGridHost;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 钮子开关模块（toggle_switch）的 Lua 模块实例。
 * <p>
 * 提供 {@link #getToggleState()} / {@link #setToggleState(boolean)} / {@link #toggle()}；
 * 状态亦可由玩家点击拉杆改变，脚本可在循环中轮询 {@link #getToggleState()}。
 */
public final class ToggleSwitchModuleHandle extends ModuleHandle {

    public ToggleSwitchModuleHandle(MonitorGridHost be, MonitorModule module) {
        super(be, module.id(), module.type().name, module.gridX(), module.gridY(),
                module.getWidth(), module.getHeight());
    }

    /** 读取当前锁存状态。 */
    @LuaFunction
    public final boolean getToggleState() {
        return be.getGridState().isPressed(id);
    }

    /**
     * 设置锁存状态。
     *
     * @param state true = 打开（按下），false = 关闭（弹起）
     */
    @LuaFunction(mainThread = true)
    public final void setToggleState(boolean state) {
        be.setToggleState(id, state);
    }

    /** 反转锁存状态（等价于玩家点击拉杆）。 */
    @LuaFunction(mainThread = true)
    public final void toggle() {
        be.toggleModule(id);
    }
}
