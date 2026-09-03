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

    /** 短程信号链接器：贴附式方块（结构照抄 micro_peripheral_extender），频道作用域 = 单个物理体（Sable 约束链）；选择框/音效对齐 static_port（SoundType.COPPER） */
    public static final DeferredBlock<ShortRangeLinkerBlock> short_range_linker =
            registerBlocks("short_range_linker" , () -> new ShortRangeLinkerBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f , 6.0f).
                    noOcclusion()
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
                    // forceSolidOn：桌体碰撞盒是半高块（16×8×8），默认 calculateSolid 判为“非实心”→ blocksMotion()=false
                    // → FlowingFluid.canHoldFluid()=!blocksMotion() 为 true → 水流会把控制台冲掉（1.21.1 水破坏方块的判定）。
                    // 强制实心只影响 isSolid/blocksMotion 标志（水不能流入该格），不改变碰撞盒，也不会新增窒息
                    // （Entity.isInWall 仍需碰撞形状相交，对齐原版墙/栅栏/锁链的写法）。
                    forceSolidOn().
                    noOcclusion()
            ));

    /** 自研风帆轴承（轴向动力输入，无齿轮）；音效对齐 swivel_bearing（SharedProperties.netheriteMetal → SoundType.NETHERITE_BLOCK） */
    public static final DeferredBlock<MyBearingBlock> aero_bearing =
            registerBlocks("aero_bearing", () -> new MyBearingBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.NETHERITE_BLOCK).
                    strength(5.0f, 6.0f).
                    noOcclusion()
            ));

    /** 降压孔（静压孔）：贴附式气压传感器（模型绕 Y 轴对称，不区分水平旋转）；音效对齐 simulated:iron_handle（SoundType.COPPER） */
    public static final DeferredBlock<StaticPortBlock> static_port =
            registerBlocks("static_port", () -> new StaticPortBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f, 6.0f).
                    noOcclusion()
            ));

    /** 皮托管：方向性速度传感器（管口朝向可绕 x/y/z 轴旋转）；音效对齐 simulated:iron_handle（SoundType.COPPER） */
    public static final DeferredBlock<PitotTubeBlock> pitot_tube =
            registerBlocks("pitot_tube", () -> new PitotTubeBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f, 6.0f).
                    noOcclusion()
            ));

    /** 惯性导航系统（INS）：可动罗盘/万向环姿态指示器，照抄 simulated:gimbal_sensor（自定义部件层级，见 memo/my_aero_sensor.md） */
    public static final DeferredBlock<InsBlock> ins =
            registerBlocks("ins", () -> new InsBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f, 6.0f).
                    noOcclusion()
            ));

    /** 飞行管理计算机（FMC）：贴附式方块（blockstate 结构参考 micro_peripheral_extender，见 FmcBlock）；带 BE 注册进 BodySensorRegistry，作物理数据门控 */
    public static final DeferredBlock<FmcBlock> fmc =
            registerBlocks("fmc", () -> new FmcBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f, 6.0f).
                    noOcclusion()
            ));

    /** 航空集成计算机（AIC）：6 向朝向方块（blockstate 旋转参考 create:display_link，见 AicBlock）；带 BE 注册进 BodySensorRegistry（ATTITUDE + FMC 双门控，等同 INS + FMC）；音效对齐 FMC（SoundType.COPPER） */
    public static final DeferredBlock<AicBlock> aic =
            registerBlocks("aic", () -> new AicBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f, 6.0f).
                    noOcclusion()
            ));

    /** 流体端口（fluid_port）：6 向附着式方块（blockstate 旋转参考 aic/display_link，见 FluidPortBlock）；当前为纯放置逻辑，无方块实体、无流体逻辑、无 OPEN 状态 */
    public static final DeferredBlock<FluidPortBlock> fluid_port =
            registerBlocks("fluid_port", () -> new FluidPortBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.COPPER).
                    strength(1.0f, 6.0f).
                    noOcclusion()
            ));

    /**
     * 风帆轴承 plate（link block）。
     * <p>
     * 与 swivel 的 {@code swivel_bearing_link_block} 一致：<b>不注册物品</b>，
     * 玩家无法直接放置；plate 在装配时由 {@code MyBearingBlockEntity.assemble()}
     * 自动放置到 sub-level plot 内，作为轴承与从动物理体的连接点。
     */
    public static final DeferredBlock<MyBearingPlateBlock> aero_bearing_plate =
            BLOCKS.register("aero_bearing_plate", () -> new MyBearingPlateBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.NETHERITE_BLOCK).
                    strength(5.0f, 6.0f).
                    noOcclusion()
            ));

    /** 从动轮悬架（trailing_wheel_mount）：单轮无动力输入（完全从动），模型直接复用 offroad wheel_mount 资产；结构/物理参考 offroad，见 memo/wheel-axle-design.md；IWrenchable 扳手旋转/拆除见 TrailingWheelMountBlock，硬度 1.5 对齐 offroad wheel_mount（stone 级，便于拆装） */
    public static final DeferredBlock<TrailingWheelMountBlock> trailing_wheel_mount =
            registerBlocks("trailing_wheel_mount", () -> new TrailingWheelMountBlock(BlockBehaviour.Properties.of().
                    sound(SoundType.NETHERITE_BLOCK).
                    strength(1.5f, 6.0f).
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
