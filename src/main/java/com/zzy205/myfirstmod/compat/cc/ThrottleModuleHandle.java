package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 油门杆模块实例（经 {@link ControlDeskPeripheral#getModule(String)} 的 {@code "throttle"} 获取）。
 * <p>
 * 直接读 BE 数值层（服务端权威位置/输入租约）：原始值 = 前进/后退键按住态；
 * <b>档位模式</b>（默认）：位置 = 离散档位 0..{@code MAX_TRAVEL_PX}（11）整数（锁存不回正）；
 * <b>自由模式</b>（{@link #setFreeMode(boolean)} 开启，无 GUI）：位置连续 0..11，可停在档位之间。
 * 轴值 = 位置 / MAX（0..1，满前进 = 1）。
 * <p>
 * 读取全部 {@code mainThread=false}（Lua 侧高频轮询直接跑 CC worker 线程，不占游戏主线程）；
 * 控制方法 {@link #setFreeMode(boolean)} / {@link #setAxis(double)} 为 {@code mainThread=true}
 * （服务端权威写 BE 并广播，玩家坐垫输入有效时每 tick 模拟覆盖）。
 */
public class ThrottleModuleHandle {

    private final ControlDeskBlockEntity be;

    public ThrottleModuleHandle(ControlDeskBlockEntity be) {
        this.be = be;
    }

    /**
     * 前进键是否按住（原始输入，读服务端输入租约）。
     *
     * <pre>{@code
     * local th = desk.getModule("throttle")
     * print(th.isForwardActive(), th.getThrottleGear())
     * }</pre>
     */
    @LuaFunction
    public final boolean isForwardActive() {
        return be.isThrottleForwardActive();
    }

    /**
     * 后退键是否按住（原始输入，读服务端输入租约）。
     */
    @LuaFunction
    public final boolean isBackActive() {
        return be.isThrottleBackActive();
    }

    /**
     * 是否有人在操作这台油门（前进或后退任一键按住，读服务端输入租约）。
     *
     * <pre>{@code
     * print(th.isActive())  -- true / false
     * }</pre>
     */
    @LuaFunction
    public final boolean isActive() {
        return be.isThrottleForwardActive() || be.isThrottleBackActive();
    }

    /**
     * 当前档位（0..11 整数，锁存不回正）：档位模式下 = 当前档位；自由模式下 = 就近档位（位置四舍五入，精确值用 {@link #getAxis()}）。
     *
     * <pre>{@code
     * print(th.getThrottleGear())  -- 0 .. 11
     * }</pre>
     */
    @LuaFunction
    public final int getThrottleGear() {
        return be.getThrottleGear();
    }

    /**
     * 油门轴值（0..1）= 位置 / 最大行程：0 = 底端 / 1 = 满前进。
     * 档位模式下为离散值（档位/MAX），自由模式下为连续值（可停在档位之间）。
     */
    @LuaFunction
    public final double getAxis() {
        return be.getThrottleAxis();
    }

    /**
     * 当前是否自由模式（false = 档位模式/卡位，默认）。模式开关无 GUI，由本方法控制并持久化（存档/蓝图保留）。
     *
     * <pre>{@code
     * print(th.isFreeMode())  -- false（默认档位模式）
     * }</pre>
     */
    @LuaFunction
    public final boolean isFreeMode() {
        return be.isThrottleFreeMode();
    }

    /**
     * 设置油门自由/档位模式（服务端权威，持久化到方块 NBT）。
     * <ul>
     *   <li>{@code true}（自由模式，不卡位）：按住前进/后退平滑连续移动（无卡位音效/段落感），松开锁存；</li>
     *   <li>{@code false}（档位模式，卡位，默认）：按住满档位切换节奏 tick 进/退一档，带卡位音效。</li>
     * </ul>
     * 切回档位模式时位置吸附到最近档位（自由位置不能停在档位之间）。
     *
     * <pre>{@code
     * th.setFreeMode(true)   -- 切到自由模式
     * th.setFreeMode(false)  -- 切回档位模式
     * }</pre>
     *
     * @param free true = 自由模式 / false = 档位模式
     * @return 始终 true（设置成功）
     */
    @LuaFunction(mainThread = true)
    public final boolean setFreeMode(boolean free) {
        be.setThrottleFreeMode(free);
        return true;
    }

    /**
     * 直接设置油门位置（轴值 0..1，服务端权威，越界钳位）：自由模式下连续写；档位模式吸附到最近档位。
     * <p>
     * 注意：玩家坐在联动坐垫上操作（输入租约有效）时，服务端每 tick 模拟会按按键继续推进位置，
     * 覆盖本设置；无玩家输入（锁存）时本设置保持到下次按键/模式切换。
     *
     * <pre>{@code
     * th.setFreeMode(true)
     * th.setAxis(0.37)  -- 油门手柄移到 37% 位置
     * }</pre>
     *
     * @param axis 目标轴值（0..1，越界自动钳位）
     * @return 始终 true（设置成功；非法输入钳位后仍成功）
     */
    @LuaFunction(mainThread = true)
    public final boolean setAxis(double axis) {
        if (!Double.isFinite(axis)) return false;
        be.setThrottleAxis((float) axis);
        return true;
    }
}
