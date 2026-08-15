package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

/**
 * Monitor 的 CC:Tweaked 外设实现。
 * <p>
 * 通过 {@code pe.getPeripheral(ch)} 或 {@code peripheral.wrap(...)} 获取。
 * 提供模块/屏幕查询：{@link #getCellModule(int, int)} / {@link #getModule(int)}，
 * 返回的 {@link ModuleHandle} 即为可在 Lua 侧进一步操作的「模块实例」。
 */
public class MonitorPeripheral implements IPeripheral {

    private final MonitorBlockEntity be;

    public MonitorPeripheral(MonitorBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccpe:monitor";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof MonitorPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public MonitorBlockEntity getBlockEntity() {
        return be;
    }

    // ═══════════════ 模块 / 屏幕查询 ═══════════════

    /**
     * 读取指定格子上的模块实例；若格子被屏幕占用，则返回屏幕实例。
     *
     * <pre>{@code
     * local mod = monitor.getCellModule(3, 4)
     * if mod then print(mod.getId(), mod.getType()) end
     * }</pre>
     *
     * @param x 格子 X 坐标（0..11）
     * @param y 格子 Y 坐标（0..9）
     * @return 该格子上的模块/屏幕实例；空格返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable ModuleHandle getCellModule(int x, int y) {
        GridState grid = be.getGridState();
        int cell = grid.getCell(x, y);
        if (cell == GridState.SCREEN_CELL_MARKER) {
            GridState.ScreenRegion screen = grid.getScreenAt(x, y);
            return screen != null ? new ScreenModuleHandle(be, screen) : null;
        }
        if (cell < 0) return null;
        return toHandle(grid.getModule(cell));
    }

    /**
     * 通过 ID 获取模块实例；屏幕与模块共用 ID 命名空间，屏幕 ID 也会返回屏幕实例。
     *
     * <pre>{@code
     * local mod = monitor.getModule(7)
     * if mod then print(mod.getType()) end
     * }</pre>
     *
     * @param id 模块/屏幕 ID
     * @return 模块/屏幕实例；不存在返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable ModuleHandle getModule(int id) {
        MonitorModule module = be.getGridState().getModule(id);
        if (module != null) return toHandle(module);
        GridState.ScreenRegion screen = be.getGridState().getScreenById(id);
        return screen != null ? new ScreenModuleHandle(be, screen) : null;
    }

    /** 把 Java 侧模块记录包装成 Lua 模块实例（经 {@link ModuleHandleRegistry} 分派到对应类型）。 */
    private @Nullable ModuleHandle toHandle(@Nullable MonitorModule module) {
        if (module == null) return null;
        return ModuleHandleRegistry.create(be, module);
    }
}
