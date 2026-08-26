package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.Throttle2Motion;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 油门2（总距杆）模块实例（经 {@link ControlDeskPeripheral#getModule(String)} 的 {@code "throttle_2"} 获取）。
 * <p>
 * 直接读 BE 数值层（服务端权威角度 0..+30°）：轴值 = 角度 / 满偏（0..1，0 = 最底端 / 1 = 满偏上抬），
 * 回正模式专用轴值 = (角度 − 中位 15°) / 15°（-1..1，-1 = 底端 / 0 = 中位 / +1 = 满偏）。
 * 读取全部 {@code mainThread=false}（Lua 侧高频轮询直接跑 CC worker 线程，不占游戏主线程）；
 * 控制方法 {@link #setAngle(double)} 为 {@code mainThread=true}（服务端权威写 BE 角度并广播）。
 */
public class Throttle2ModuleHandle {

    private final ControlDeskBlockEntity be;

    public Throttle2ModuleHandle(ControlDeskBlockEntity be) {
        this.be = be;
    }

    /**
     * 油门2 轴值（0..1）= 角度 / 满偏角（+30°）：0 = 最底端（放置默认），1 = 满偏上抬。
     *
     * <pre>{@code
     * local th2 = desk.getModule("throttle_2")
     * print(th2.getAxis())  -- 0.0 .. 1.0
     * }</pre>
     */
    @LuaFunction
    public final double getAxis() {
        return be.getThrottle2Angle() / Throttle2Motion.MAX_DEG;
    }

    /**
     * 回正模式专用轴值（-1..1）= (角度 − 中位 15°) / 15°：
     * -1 = 最底端（0°），0 = 中位（15°，回正模式松开后的落点），+1 = 满偏上抬（30°）。
     *
     * <pre>{@code
     * print(th2.getCenterAxis())  -- -1.0 .. 1.0
     * }</pre>
     */
    @LuaFunction
    public final double getCenterAxis() {
        return (be.getThrottle2Angle() - Throttle2Motion.NEUTRAL_DEG) / Throttle2Motion.NEUTRAL_DEG;
    }

    /**
     * 直接设置油门2 把手角度（度，0..+30°，服务端权威，越界钳位）。
     * <p>
     * 注意：玩家坐在联动坐垫上操作（输入租约有效）时，服务端每 tick 模拟会按按键继续推进角度，
     * 覆盖本设置；无玩家输入（回正关闭 = 锁存）时本设置保持到下次按键/回正。
     *
     * <pre>{@code
     * th2.setAngle(20)  -- 把手转到 20°
     * }</pre>
     *
     * @param degrees 目标角度（度，0..30，越界自动钳位）
     * @return 始终 true（设置成功；非法输入钳位后仍成功）
     */
    @LuaFunction(mainThread = true)
    public final boolean setAngle(double degrees) {
        if (!Double.isFinite(degrees)) return false;
        be.setThrottle2Angle((float) degrees);
        return true;
    }
}
