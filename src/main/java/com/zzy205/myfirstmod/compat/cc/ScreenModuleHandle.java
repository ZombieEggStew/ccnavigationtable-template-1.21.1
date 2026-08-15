package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 屏幕（screen）的 Lua 模块实例。
 * <p>
 * 通过 {@code monitor.getCellModule(x, y)}（屏幕占用的格子）或 {@code monitor.getModule(id)}
 * 获取。屏幕与普通模块共用同一 ID 命名空间，{@link #getType()} 返回 "screen"。
 * 提供 {@link #getTooltip()} / {@link ModuleHandle#setTooltip(String)}（读写悬停说明文字）。
 */
public final class ScreenModuleHandle extends ModuleHandle {

    public ScreenModuleHandle(MonitorBlockEntity be, GridState.ScreenRegion screen) {
        super(be, screen.id(), "screen", screen.minX(), screen.minY(),
                screen.width(), screen.height());
    }

    /** 读取屏幕的悬停说明文字（tooltip）。 */
    @LuaFunction
    public final String getTooltip() {
        GridState.ScreenRegion screen = be.getGridState().getScreenById(id);
        return screen != null ? screen.tooltipText() : "";
    }
}
