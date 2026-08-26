package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

/**
 * 控制台（controlDesk）的 CC:Tweaked 外设实现。
 * <p>
 * 通过 {@code pe.getPeripheral(ch)}（{@link PeripheralExtenderAPI#getPeripheral} 按全局频道查找）
 * 或 {@code peripheral.wrap(...)}（经 {@link CCPeripheralCapabilities} 能力注册）获取。
 * 与传感器、显示器共享同一全局频道命名空间（频道全局唯一，见 {@link ControlDeskRegistry}）。
 * <p>
 * 用法（对齐 Monitor 的「外设 → 模块实例」模式）：先 {@link #getModule(String)} 拿到对应控件的
 * 模块实例，再在实例上调用状态读取方法（全部 {@code mainThread=false}，Lua 侧高频轮询不占主线程）：
 * <pre>{@code
 * local d = pe.getPeripheral(0)
 * local pedal = d.getModule("pedal")        -- 未安装返回 nil
 * print(pedal.isLeftPedalDown())
 * local joy = d.getModule("joystick")
 * print(joy.getAxisXSigned())
 * local joy2 = d.getModule("joystick_2")    -- 摇杆2（独立轴值，照抄 joystick）
 * print(joy2.getAxisXSigned())
 * local th = d.getModule("throttle")
 * print(th.getThrottleGear())
 * }</pre>
 * <p>
 * monitor_2 表面小 Monitor（已安装 monitor_2 时可用）：{@link #getMonitor2Module(int)} /
 * {@link #getMonitor2CellModule(int, int)} 返回与 Monitor 完全相同的模块/屏幕实例
 * （按钮/钮子/旋钮/屏幕的 Lua API 见对应 handle，如 {@link ButtonModuleHandle}）。
 * <b>注意</b>：CC:Tweaked 把 {@code @LuaFunction} 方法收集成 Lua 表返回；没有任何 Lua 方法的对象
 * 会被判为 unknown type 返回 nil（CobaltLuaMachine#toValue），因此外设至少要有一个 Lua 方法。
 */
public class ControlDeskPeripheral implements IPeripheral {

    private final ControlDeskBlockEntity be;

    public ControlDeskPeripheral(ControlDeskBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccpe:control_desk";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof ControlDeskPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public ControlDeskBlockEntity getBlockEntity() {
        return be;
    }

    /**
     * 获取指定控件的模块实例（Lua API 在此实例上调用）：
     * <ul>
     *   <li>{@code "pedal"} → {@link PedalModuleHandle}（左右踏板踩下判断）</li>
     *   <li>{@code "joystick"} → {@link JoystickModuleHandle}（操纵杆原始值/轴值/带符号）</li>
     *   <li>{@code "joystick_2"} → {@link Joystick2ModuleHandle}（摇杆2 原始值/轴值/带符号，独立于 joystick，照抄）</li>
     *   <li>{@code "throttle"} → {@link ThrottleModuleHandle}（油门杆前进/后退按住态 + 档位/轴值）</li>
     *   <li>{@code "throttle_2"} → {@link Throttle2ModuleHandle}（油门2 总距杆轴值 0..1 + 回正模式轴值 -1..1 + 角度控制）</li>
     *   <li>{@code "monitor"} → {@link MonitorPeripheral}（type = "ccpe:monitor_2"）：monitor_2 表面小 Monitor
     *       的模块/屏幕查询入口，方法与 Monitor 外设完全同款（{@code getCellModule(x,y)} / {@code getModule(id)}，
     *       返回的 handle 与 Monitor 相同）；monitor_2 未安装返回 nil</li>
     * </ul>
     * 未安装对应控件或名称未知返回 nil。
     *
     * <pre>{@code
     * local pedal = desk.getModule("pedal")
     * if pedal then print(pedal.isLeftPedalDown()) end
     * local m = desk.getModule("monitor")      -- monitor_2 表面小 Monitor（方法同 Monitor 外设）
     * if m then print(m.getCellModule(3, 4).getType()) end
     * }</pre>
     *
     * @param name 控件名（"pedal" / "joystick" / "joystick_2" / "throttle" / "throttle_2" / "monitor"，大小写不敏感）
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Object getModule(String name) {
        if (name == null) return null;
        return switch (name.toLowerCase()) {
            case "pedal" -> be.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)
                    ? new PedalModuleHandle(be) : null;
            case "joystick" -> be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)
                    ? new JoystickModuleHandle(be) : null;
            case "joystick_2" -> be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)
                    ? new Joystick2ModuleHandle(be) : null;
            case "throttle" -> be.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)
                    ? new ThrottleModuleHandle(be) : null;
            case "throttle_2" -> be.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)
                    ? new Throttle2ModuleHandle(be) : null;
            case "monitor" -> be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)
                    ? new MonitorPeripheral(be, "ccpe:monitor_2") : null;
            default -> null;
        };
    }

    // ═══════════════ monitor_2 表面小 Monitor 模块 / 屏幕查询 ═══════════════

    /**
     * 读取 monitor_2 表面指定格子上的模块实例；若格子被屏幕占用，则返回屏幕实例。
     * 与 {@code monitor.getCellModule(x, y)} 完全同构，只是作用在 monitor_2 的 10×8 网格上。
     *
     * <pre>{@code
     * local mod = desk.getMonitor2CellModule(3, 4)
     * if mod then print(mod.getId(), mod.getType()) end
     * }</pre>
     *
     * @param x 格子 X 坐标（0..9）
     * @param y 格子 Y 坐标（0..7）
     * @return 该格子上的模块/屏幕实例；空格或 monitor_2 未安装返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable ModuleHandle getMonitor2CellModule(int x, int y) {
        if (!be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) return null;
        GridState grid = be.getMonitor2Grid();
        int cell = grid.getCell(x, y);
        if (cell == GridState.SCREEN_CELL_MARKER) {
            GridState.ScreenRegion screen = grid.getScreenAt(x, y);
            return screen != null ? new ScreenModuleHandle(be, screen) : null;
        }
        if (cell < 0) return null;
        return toMonitor2Handle(grid.getModule(cell));
    }

    /**
     * 通过 ID 获取 monitor_2 表面模块实例；屏幕与模块共用 ID 命名空间，屏幕 ID 也会返回屏幕实例。
     *
     * <pre>{@code
     * local mod = desk.getMonitor2Module(7)
     * if mod then print(mod.getType()) end
     * }</pre>
     *
     * @param id 模块/屏幕 ID
     * @return 模块/屏幕实例；monitor_2 未安装或不存在返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable ModuleHandle getMonitor2Module(int id) {
        if (!be.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) return null;
        GridState grid = be.getMonitor2Grid();
        MonitorModule module = grid.getModule(id);
        if (module != null) return toMonitor2Handle(module);
        GridState.ScreenRegion screen = grid.getScreenById(id);
        return screen != null ? new ScreenModuleHandle(be, screen) : null;
    }

    /** 把 monitor_2 网格的 Java 侧模块记录包装成 Lua 模块实例（经 {@link ModuleHandleRegistry} 分派）。 */
    private @Nullable ModuleHandle toMonitor2Handle(@Nullable MonitorModule module) {
        if (module == null) return null;
        return ModuleHandleRegistry.create(be, module);
    }
}
