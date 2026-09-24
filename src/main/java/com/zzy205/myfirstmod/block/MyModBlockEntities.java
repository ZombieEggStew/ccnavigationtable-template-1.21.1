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
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShortRangeLinkerBlockEntity>> short_range_linker_entity = BLOCK_ENTITY_TYPES.register("short_range_linker", () -> BlockEntityType.Builder.of(ShortRangeLinkerBlockEntity::new, MyModBlocks.short_range_linker.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TransmissionPeripheralBlockEntity>> transmission_peripheral_entity = BLOCK_ENTITY_TYPES.register("transmission_peripheral", () -> BlockEntityType.Builder.of(TransmissionPeripheralBlockEntity::new, MyModBlocks.transmission_peripheral.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>> monitor_entity = BLOCK_ENTITY_TYPES.register("my_monitor", () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, MyModBlocks.monitor.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControlDeskBlockEntity>> control_desk_entity = BLOCK_ENTITY_TYPES.register("my_control_desk", () -> BlockEntityType.Builder.of(ControlDeskBlockEntity::new, MyModBlocks.my_control_desk.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyBearingBlockEntity>> aero_bearing_entity = BLOCK_ENTITY_TYPES.register("aero_bearing", () -> BlockEntityType.Builder.of(MyBearingBlockEntity::new, MyModBlocks.aero_bearing.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyBearingPlateBlockEntity>> aero_bearing_plate_entity = BLOCK_ENTITY_TYPES.register("aero_bearing_plate", () -> BlockEntityType.Builder.of(MyBearingPlateBlockEntity::new, MyModBlocks.aero_bearing_plate.get()).build(null));
    /** Lua 舵机轴承 BE：照抄 mechanical_bearing 旋转逻辑 + 客户端插值修复（无动力/无红石/Lua 目标角控制），渲染见 ServoBearingVisual/ServoBearingRenderer */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ServoBearingBlockEntity>> servo_bearing_entity = BLOCK_ENTITY_TYPES.register("servo_bearing", () -> BlockEntityType.Builder.of(ServoBearingBlockEntity::new, MyModBlocks.servo_bearing.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StaticPortBlockEntity>> static_port_entity = BLOCK_ENTITY_TYPES.register("static_port", () -> BlockEntityType.Builder.of(StaticPortBlockEntity::new, MyModBlocks.static_port.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PitotTubeBlockEntity>> pitot_tube_entity = BLOCK_ENTITY_TYPES.register("pitot_tube", () -> BlockEntityType.Builder.of(PitotTubeBlockEntity::new, MyModBlocks.pitot_tube.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InsBlockEntity>> ins_entity = BLOCK_ENTITY_TYPES.register("ins", () -> BlockEntityType.Builder.of(InsBlockEntity::new, MyModBlocks.ins.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FmcBlockEntity>> fmc_entity = BLOCK_ENTITY_TYPES.register("fmc", () -> BlockEntityType.Builder.of(FmcBlockEntity::new, MyModBlocks.fmc.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AicBlockEntity>> aic_entity = BLOCK_ENTITY_TYPES.register("aic", () -> BlockEntityType.Builder.of(AicBlockEntity::new, MyModBlocks.aic.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidPortBlockEntity>> fluid_port_entity = BLOCK_ENTITY_TYPES.register("fluid_port", () -> BlockEntityType.Builder.of(FluidPortBlockEntity::new, MyModBlocks.fluid_port.get()).build(null));
    /** 从动轮悬架 BE：Sable BlockEntitySubLevelActor（装配进物理体后每 physics substep 施加弹簧支撑力） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrailingWheelMountBlockEntity>> trailing_wheel_mount_entity = BLOCK_ENTITY_TYPES.register("trailing_wheel_mount", () -> BlockEntityType.Builder.of(TrailingWheelMountBlockEntity::new, MyModBlocks.trailing_wheel_mount.get()).build(null));
    /** 发动机核心 BE：Create 动力源骨架（GeneratingKineticBlockEntity，当前不发电，贯通传动杆随网络连通） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EngineCoreBlockEntity>> engine_core_entity = BLOCK_ENTITY_TYPES.register("engine_core", () -> BlockEntityType.Builder.of(EngineCoreBlockEntity::new, MyModBlocks.engine_core.get()).build(null));
    /** 流体燃烧室 BE：轻量 BlockEntity（供 Flywheel/BER 读活塞状态，当前无动画） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidCombustionChamberBlockEntity>> fluid_combustion_chamber_entity = BLOCK_ENTITY_TYPES.register("fluid_combustion_chamber", () -> BlockEntityType.Builder.of(FluidCombustionChamberBlockEntity::new, MyModBlocks.fluid_combustion_chamber.get()).build(null));
    /** 蒸汽动力室 BE：轻量 BlockEntity（同流体燃烧室模式） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SteamPowerChamberBlockEntity>> steam_power_chamber_entity = BLOCK_ENTITY_TYPES.register("steam_power_chamber", () -> BlockEntityType.Builder.of(SteamPowerChamberBlockEntity::new, MyModBlocks.steam_power_chamber.get()).build(null));
    /** 快速装填燃料箱 BE：单物品类型库存（容量 1024，无 GUI）；开盖动画由 Block tick 驱动（对齐 item_hatch/fluid_port） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuickFillFuelVaultBlockEntity>> quick_fill_fuel_vault_entity = BLOCK_ENTITY_TYPES.register("quick_fill_fuel_vault", () -> BlockEntityType.Builder.of(QuickFillFuelVaultBlockEntity::new, MyModBlocks.quick_fill_fuel_vault.get()).build(null));
    /** 快速装填流体储罐 BE：4000mb 单槽流体存储（无 GUI）；开盖动画由 Block tick 驱动（对齐 fluid_port/fuel_vault）；goggle tooltip 同 create:fluid_tank */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuickFillFluidTankBlockEntity>> quick_fill_fluid_tank_entity = BLOCK_ENTITY_TYPES.register("quick_fill_fluid_tank", () -> BlockEntityType.Builder.of(QuickFillFluidTankBlockEntity::new, MyModBlocks.quick_fill_fluid_tank.get()).build(null));
    /** 板式监视器 BE：表面 Monitor 棋盘网格（14×14，MonitorGridHost，见 MonitorSlabBlockEntity；对齐 monitor_2 表面小 Monitor 模式） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorSlabBlockEntity>> monitor_slab_entity = BLOCK_ENTITY_TYPES.register("monitor_slab", () -> BlockEntityType.Builder.of(MonitorSlabBlockEntity::new, MyModBlocks.monitor_slab.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
