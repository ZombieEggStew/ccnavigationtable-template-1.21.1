package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.item.MyModItems;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class MyModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CCPeripheralExtender.MOD_ID);

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

    public static final DeferredBlock<ControlDeskBlock> my_control_desk =
            registerBlocks("my_control_desk", () -> new ControlDeskBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.WOOD).
                    strength(1.5f, 6.0f).
                    noOcclusion()
            ));

    /** 自研风帆轴承（轴向动力输入，无齿轮）；音效对齐 swivel_bearing（SharedProperties.netheriteMetal → SoundType.NETHERITE_BLOCK） */
    public static final DeferredBlock<MyBearingBlock> my_bearing =
            registerBlocks("my_bearing", () -> new MyBearingBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.NETHERITE_BLOCK).
                    strength(5.0f, 6.0f).
                    noOcclusion()
            ));

    /**
     * 风帆轴承 plate（link block）。
     * <p>
     * 与 swivel 的 {@code swivel_bearing_link_block} 一致：<b>不注册物品</b>，
     * 玩家无法直接放置；plate 在装配时由 {@code MyBearingBlockEntity.assemble()}
     * 自动放置到 sub-level plot 内，作为轴承与从动物理体的连接点。
     */
    public static final DeferredBlock<MyBearingPlateBlock> my_bearing_plate =
            BLOCKS.register("my_bearing_plate", () -> new MyBearingPlateBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.NETHERITE_BLOCK).
                    strength(5.0f, 6.0f).
                    noOcclusion()
            ));

    private static <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier<T> block) {
        DeferredBlock<T> blocks = BLOCKS.register(name , block);
        MyModItems.registerBlockItems(name , blocks);
        return blocks;
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
