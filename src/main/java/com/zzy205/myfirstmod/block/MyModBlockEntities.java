package com.zzy205.myfirstmod.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.zzy205.myfirstmod.CCNavigationtable.MOD_ID;

public final class MyModBlockEntities {
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralExtenderBlockEntity>> micro_peripheral_extender_entity = BLOCK_ENTITY_TYPES.register("micro_peripheral_extender", () -> BlockEntityType.Builder.of(PeripheralExtenderBlockEntity::new, MyModBlocks.micro_peripheral_extender.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneTransceiverBlockEntity>> redstone_transceiver_entity = BLOCK_ENTITY_TYPES.register("redstone_transceiver", () -> BlockEntityType.Builder.of(RedstoneTransceiverBlockEntity::new, MyModBlocks.redstone_transceiver.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
