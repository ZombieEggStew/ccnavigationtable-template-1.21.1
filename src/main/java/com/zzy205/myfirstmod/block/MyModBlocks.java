package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.item.MyModItems;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class MyModBlocks {
    public static final DeferredRegister.Blocks MyBlocks =
            DeferredRegister.createBlocks(CCPeripheraExtender.MOD_ID);

//     public static final DeferredBlock<Block> test_block =
//             registerBlocks("test_block" , () -> new Block(BlockBehaviour.Properties.of().
//                     strength(1.0f , 6.0f)
//             ));

    public static final DeferredBlock<Block> micro_peripheral_extender =
            registerBlocks("micro_peripheral_extender" , () -> new PeripheralExtenderBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.WOOD).
                    strength(1.0f , 6.0f).
                    noOcclusion()
            ));

    public static final DeferredBlock<Block> redstone_transceiver =
            registerBlocks("redstone_transceiver" , () -> new RedstoneTransceiverBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.WOOD).
                    strength(1.0f , 6.0f)
            ));

    public static final DeferredBlock<TransmissionPeripheralBlock> transmission_peripheral =
            registerBlocks("transmission_peripheral", () -> new TransmissionPeripheralBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.METAL).
                    strength(2.0f, 6.0f).
                    noOcclusion()
            ));

    public static final DeferredBlock<MonitorBlock> monitor =
            registerBlocks("my_monitor", () -> new MonitorBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.METAL).
                    strength(1.5f, 6.0f).
                    noOcclusion()
            ));



    private static <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier<T> block) {
        DeferredBlock<T> blocks = MyBlocks.register(name , block);
        MyModItems.registerBlockItems(name , blocks);
        return blocks;
    }

    public static void register(IEventBus modEventBus) {
        MyBlocks.register(modEventBus);
    }
}
