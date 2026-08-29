package com.zzy205.myfirstmod.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.zzy205.myfirstmod.CCPeripheralExtender.MOD_ID;

public final class MyModBlockEntities {
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralExtenderBlockEntity>> micro_peripheral_extender_entity = BLOCK_ENTITY_TYPES.register("micro_peripheral_extender", () -> BlockEntityType.Builder.of(PeripheralExtenderBlockEntity::new, MyModBlocks.micro_peripheral_extender.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneTransceiverBlockEntity>> redstone_transceiver_entity = BLOCK_ENTITY_TYPES.register("redstone_transceiver", () -> BlockEntityType.Builder.of(RedstoneTransceiverBlockEntity::new, MyModBlocks.redstone_transceiver.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TransmissionPeripheralBlockEntity>> transmission_peripheral_entity = BLOCK_ENTITY_TYPES.register("transmission_peripheral", () -> BlockEntityType.Builder.of(TransmissionPeripheralBlockEntity::new, MyModBlocks.transmission_peripheral.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>> monitor_entity = BLOCK_ENTITY_TYPES.register("my_monitor", () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, MyModBlocks.monitor.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControlDeskBlockEntity>> control_desk_entity = BLOCK_ENTITY_TYPES.register("my_control_desk", () -> BlockEntityType.Builder.of(ControlDeskBlockEntity::new, MyModBlocks.my_control_desk.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyBearingBlockEntity>> aero_bearing_entity = BLOCK_ENTITY_TYPES.register("aero_bearing", () -> BlockEntityType.Builder.of(MyBearingBlockEntity::new, MyModBlocks.aero_bearing.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyBearingPlateBlockEntity>> aero_bearing_plate_entity = BLOCK_ENTITY_TYPES.register("aero_bearing_plate", () -> BlockEntityType.Builder.of(MyBearingPlateBlockEntity::new, MyModBlocks.aero_bearing_plate.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
