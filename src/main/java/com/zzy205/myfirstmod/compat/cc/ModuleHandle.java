package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorGridHost;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 显示器「模块实例」在 Lua 侧的抽象基类（普通模块与屏幕共用）。
 * <p>
 * 通过 {@code monitor.getCellModule(x, y)} / {@code monitor.getModule(id)} 获取。
 * 具体类型见 {@link ButtonModuleHandle}、{@link ToggleSwitchModuleHandle}、
 * {@link KnobModuleHandle}（普通模块）以及 {@link ScreenModuleHandle}（屏幕）。
 * <p>
 * 本类只持有不可变快照，通用读取方法无需 mainThread；子类中需要改动方块状态的
 * 控制方法应标注 {@code @LuaFunction(mainThread = true)}。
 * <p>
 * 宿主为 {@link MonitorGridHost}（Monitor 方块实体或 controlDesk 的 monitor_2 模块），
 * 因此同一套 handle 可同时服务 Monitor 与 monitor_2。
 */
public abstract class ModuleHandle {

    protected final MonitorGridHost be;
    /** 模块/屏幕在 monitor 内的唯一 ID。 */
    protected final int id;

    private final String type;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    protected ModuleHandle(MonitorGridHost be, int id, String type, int x, int y, int width, int height) {
        this.be = be;
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    // ═══════════════ 通用只读属性 ═══════════════

    /** 模块/屏幕在本 monitor 内的唯一 ID。 */
    @LuaFunction
    public final int getId() {
        return id;
    }

    /** 类型名：模块为 "button_1"/"toggle_switch"/"knob"，屏幕为 "screen"。 */
    @LuaFunction
    public final String getType() {
        return type;
    }

    /** 左上角所在格子的 X 坐标（0..11）。 */
    @LuaFunction
    public final int getX() {
        return x;
    }

    /** 左上角所在格子的 Y 坐标（0..9）。 */
    @LuaFunction
    public final int getY() {
        return y;
    }

    /** 占用宽度（格）。 */
    @LuaFunction
    public final int getWidth() {
        return width;
    }

    /** 占用高度（格）。 */
    @LuaFunction
    public final int getHeight() {
        return height;
    }

    /**
     * 设置该模块/屏幕的 tooltip（悬浮文本）。
     * <p>
     * 普通模块写入配置的 "text" 键（悬停/配置界面显示）；屏幕写入屏幕文本（悬停显示）。
     *
     * <pre>{@code
     * mod.setTooltip("喂料阀门")
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setTooltip(String tooltip) {
        be.setTooltip(id, tooltip);
    }

    // ═══════════════ Java 侧访问器 ═══════════════

    public final MonitorGridHost getBlockEntity() {
        return be;
    }
}
