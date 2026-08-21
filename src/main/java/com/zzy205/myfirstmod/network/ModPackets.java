package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 全部自定义网络包的注册入口。
 * <p>
 * 按功能域分派给 {@link MonitorPacketHandlers}（Monitor）、
 * {@link SensorPacketHandlers}（传感器）、{@link ReceiverPacketHandlers}（红石收发器）。
 * 由主类 {@link CCPeripheralExtender} 在 {@code RegisterPayloadHandlersEvent} 时调用。
 */
public final class ModPackets {

    private ModPackets() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CCPeripheralExtender.MOD_ID);
        MonitorPacketHandlers.register(registrar);
        SensorPacketHandlers.register(registrar);
        ReceiverPacketHandlers.register(registrar);
    }
}
