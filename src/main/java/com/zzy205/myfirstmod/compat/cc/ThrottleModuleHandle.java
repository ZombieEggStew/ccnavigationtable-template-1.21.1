package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * 油门杆模块实例（经 {@link ControlDeskPeripheral#getModule(String)} 的 {@code "throttle"} 获取）。
 * <p>
 * 直接读 BE 数值层（服务端权威档位/输入租约）：原始值 = 前进/后退键按住态，
 * 档位 = 0..{@code MAX_TRAVEL_PX}（11）离散整数（锁存不回正），轴值 = 档位 / MAX（0..1，满前进 = 1）。
 * 全部 {@code mainThread=false}，Lua 侧高频轮询直接跑在 CC worker 线程，不占游戏主线程。
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
     * 当前档位（0..11 整数，锁存不回正）：0 = 最低档（底端，-x 端）/ 11 = 满前进（+x 端）。
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
     * 油门轴值（0..1）= 档位 / 最大行程：0 = 最低档 / 1 = 满前进。
     */
    @LuaFunction
    public final double getAxis() {
        return be.getThrottleAxis();
    }
}
