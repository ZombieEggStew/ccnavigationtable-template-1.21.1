package com.zzy205.myfirstmod.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.zzy205.myfirstmod.CCNavigationtable.MOD_ID;

public final class MyModBlockEntities {
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MySensorBlockEntity>> my_sensor_entity = BLOCK_ENTITY_TYPES.register("my_sensor", () -> BlockEntityType.Builder.of(MySensorBlockEntity::new, MyModBlocks.my_sensor.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyReceiverBlockEntity>> my_receiver_entity = BLOCK_ENTITY_TYPES.register("my_receiver", () -> BlockEntityType.Builder.of(MyReceiverBlockEntity::new, MyModBlocks.my_receiver.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
