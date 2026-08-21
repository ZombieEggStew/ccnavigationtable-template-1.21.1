package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.block.MyModBlockEntities;
import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import com.zzy205.myfirstmod.block.TransmissionPeripheralBlockEntity;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * CC:Tweaked 外设 capability 注册。
 * <p>
 * 把本 mod 的 BlockEntity 暴露给 {@code peripheral.wrap} / {@code peripheral.find}。
 * 由主类 {@link com.zzy205.myfirstmod.CCPeripheralExtender} 在 {@code RegisterCapabilitiesEvent} 时调用。
 */
public final class CCPeripheralCapabilities {

    private CCPeripheralCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        if (!ModList.get().isLoaded("computercraft")) return;

        // Receiver BlockEntity 作为 CC:T 外设
        event.registerBlockEntity(
                PeripheralCapability.get(),
                MyModBlockEntities.redstone_transceiver_entity.get(),
                (be, side) -> new RedstoneTransceiverPeripheral((RedstoneTransceiverBlockEntity) be)
        );
        event.registerBlockEntity(
                PeripheralCapability.get(),
                MyModBlockEntities.transmission_peripheral_entity.get(),
                (be, side) -> ((TransmissionPeripheralBlockEntity) be).getPeripheral()
        );
        event.registerBlockEntity(
                PeripheralCapability.get(),
                MyModBlockEntities.monitor_entity.get(),
                (be, side) -> ((MonitorBlockEntity) be).getPeripheral()
        );
    }
}
