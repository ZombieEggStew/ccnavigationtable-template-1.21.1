package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCNavigationtable;
import com.zzy205.myfirstmod.item.MyModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class MyModBlocks {
    public static final DeferredRegister.Blocks MyBlocks =
            DeferredRegister.createBlocks(CCNavigationtable.MOD_ID);

    public static final DeferredBlock<Block> test_block =
            registerBlocks("test_block" , () -> new Block(BlockBehaviour.Properties.of().
                    strength(1.0f , 6.0f)
            ));

    public static final DeferredBlock<Block> my_sensor =
            registerBlocks("my_sensor" , () -> new MySensorBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.WOOD).
                    strength(1.0f , 6.0f).
                    noOcclusion()
            ));

    public static final DeferredBlock<Block> my_receiver =
            registerBlocks("my_receiver" , () -> new MyReceiverBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.WOOD).
                    strength(1.0f , 6.0f)
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
