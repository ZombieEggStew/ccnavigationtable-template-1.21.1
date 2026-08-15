package com.zzy205.myfirstmod.compat.cc;

import dan200.computercraft.api.ComputerCraftAPI;

/**
 * CC:Tweaked 传感器 API 注册入口。
 * 在 mod 初始化时调用 {@link #register()} 即可。
 */
public final class CCPeripheralExtenderSetup {

    private CCPeripheralExtenderSetup() {}

    /**
     * 向 CC:Tweaked 注册传感器 Lua API 工厂。
     * 每个计算机启动时都会通过该工厂创建一个 {@link PeripheralExtenderAPI} 实例。
     * Lua 端通过 {@code require("ccpe.pe")} 使用。
     */
    public static void register() {
        ComputerCraftAPI.registerAPIFactory(computer -> new PeripheralExtenderAPI());
    }
}
