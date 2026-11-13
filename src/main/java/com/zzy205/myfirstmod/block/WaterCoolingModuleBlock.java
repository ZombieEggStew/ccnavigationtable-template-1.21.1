package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.fluid.FluidHelper.FluidExchange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 水冷模块（water_cooling_module）：<b>6 个 blockstate</b>（只有 {@link #FACING}），
 * blockstate 结构照 {@code create:encased_fan}（6 向单模型 + x/y 旋转，无第二属性）。
 * <p>
 * 定位（见 memo/engine-module.md）：低速 / 静态引擎的散热模块，贴着引擎核心（或燃烧室）的外表面摆放。
 * <b>当前进度</b>：方块接入（放置 / 渲染 / 扳手）+ <b>8000mb 单槽冷却液存储</b>
 * （{@link WaterCoolingModuleBlockEntity}，只接受 {@link EngineCoolants} 表内流体）；引擎侧逻辑
 * （模块枚举、散热计数、温度模型消费冷却效率）<b>还没接</b>——不参与
 * {@code EngineCoreBlockEntity#scanModule} 的模块枚举、不计数散热、不代理 goggle。
 * <p>
 * 朝向语义：<b>{@code FACING} = 模块朝外的方向</b>（= 模型那块 16×16 薄板的外法线），
 * 即薄板（散热面）朝外、机身与鳍朝里贴住被放置的方块。
 * <p>
 * <b>放置方向照 {@code create:encased_fan}（不贴附、不看点击面，只看玩家视线）</b>：
 * {@code
 *   preferred = context.getNearestLookingDirection();
 *   FACING    = 玩家潜行 ? preferred : preferred.getOpposite();
 * }
 * 即：不潜行时薄板朝玩家（正面贴墙放置时薄板朝外、机身贴墙）；潜行时朝视线方向转 180°。
 * （encased_fan 里额外的「对轴 / 溜槽」分支本方块没有，故不抄。）
 * <p>
 * 扳手（<b>完全照 {@code create:encased_fan}</b>，即只用 {@link IWrenchable} 的默认实现、本类不覆写任何扳手方法）：
 * <ul>
 *   <li>参照依据：{@code EncasedFanBlock} 自身也没有覆写 {@code onWrenched / onSneakWrenched / getRotatedBlockState}
 *       （它经 {@code KineticBlock → IRotate extends IWrenchable} 拿到默认实现）；唯一覆写的
 *       {@code updateAfterWrenched} 只是通知它的 BE「前方方块变了」，本方块没有 BE，无事可做；</li>
 *   <li><b>潜行 + 右键</b> = 快速拆除（{@code onSneakWrenched} 默认实现：post {@code BreakEvent} →
 *       非创造模式把 {@code Block#getDrops} 直接塞回背包 → {@code destroyBlock} → WRENCH_REMOVE 音效）；</li>
 *   <li><b>普通右键</b> = 旋转（{@code onWrenched} 默认实现 → {@code getRotatedBlockState}：绕点击面所在轴
 *       顺时针转到「下一个不在该轴上的朝向」；点击面与 {@code FACING} 同轴（如右键薄板那一侧）时状态不变，
 *       这是 Create 的通用规则）。</li>
 * </ul>
 * 掉落表：{@code data/ccpe/loot_table/blocks/water_cooling_module.json}。
 * <p>
 * <b>冷却液存储</b>（{@link WaterCoolingModuleBlockEntity}，8000mb 单槽，照 {@code create:fluid_tank}）：
 * <ul>
 *   <li><b>管道 / 泵</b>：BE 暴露 {@code Capabilities.FluidHandler.BLOCK}
 *       （在 {@code CCPeripheralExtender#registerBlockCapabilities} 注册），桶/罐直接灌进去；</li>
 *   <li><b>手持容器</b>：{@link #useItemOn} 复用 {@link FluidPortItemTransfer}
 *       （同 {@code quick_fill_fluid_tank}：右键 物品→罐 优先、潜行右键 罐→物品 优先）；</li>
 *   <li><b>白名单</b>：两条途径最终都落到 {@code FluidTank#fill} → {@code isFluidValid} =
 *       {@link EngineCoolants#isCoolant}（数据包 {@code data/<ns>/coolant/*.json} 表），
 *       <b>表外流体装不进去</b>，已装一种冷却液后也装不进第二种。</li>
 * </ul>
 * <p>
 * 模型（{@code assets/ccpe/models/block/water_cooling_module/water_cooling_module.json}）在 Blockbench 里
 * 画成「薄板在 <b>北侧</b>（z = 0~6）、机身与鳍朝 <b>+Z（南）</b> 伸出、小凸块在顶上」，
 * 即<b>未旋转变体 = {@code facing=north}</b>——所以 blockstate 的 6 个变体不是 end_rod 风格
 * （{@code facing=up} 无旋转），而是按 {@code BlockModelRotation = Ry(−θy)·Rx(−θx)}
 * （X 先转、Y 后转、绕方块中心，见 memo/pitot-selection-box.md 坑 3）反推「模型 −Z → 目标朝向」的旋转表，
 * 见 {@code blockstates/water_cooling_module.json}：north 无旋转 / south y180 / east y90 / west y270 /
 * down x90 / up x270+y180。
 * <p>
 * 选择框：用户定稿 = 模型里两块实体 {@code [0,0,0,16,16,6]}（薄板）+ {@code [1,1,6,15,15,15]}（机身），
 * 按<b>与 blockstate 完全同一套旋转</b>生成 6 个盒子（{@link #buildShapes()}），
 * 不写死 6 份手算坐标。碰撞箱仍用默认整格（同 {@code thrust_nozzle}）。
 */
public class WaterCoolingModuleBlock extends DirectionalBlock implements IWrenchable, EntityBlock {

    public static final MapCodec<WaterCoolingModuleBlock> CODEC = simpleCodec(WaterCoolingModuleBlock::new);

    /** 朝外方向（薄板外法线）；贴附面 = {@code FACING.getOpposite()} */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /**
     * 选择框基准（{@code facing=north} 未旋转变体的模型坐标系，单位 = 1/16 格）：
     * 薄板 (0,0,0)-(16,16,6) + 机身 (1,1,6)-(15,15,15)。
     */
    private static final VoxelShape BASE_SHAPE =
            Shapes.or(Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 6.0D),
                      Block.box(1.0D, 1.0D, 6.0D, 15.0D, 15.0D, 15.0D));

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    /**
     * 六个朝向的选择框，旋转与 {@code blockstates/water_cooling_module.json} 的变体逐一对应：
     * north 原样；south 绕 Y 180°；east 绕 Y 90°；west 绕 Y 270°；down 绕 X 90°；up 绕 X 270° 再绕 Y 180°。
     * <p>
     * 注意旋转顺序按原版 blockstate 约定（X 先转、Y 后转），做法的模板见 {@link ThrustNozzleBlock}。
     */
    private static Map<Direction, VoxelShape> buildShapes() {
        final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

        shapes.put(Direction.NORTH, BASE_SHAPE);
        shapes.put(Direction.SOUTH, rotateY(BASE_SHAPE, 2));
        shapes.put(Direction.EAST, rotateY(BASE_SHAPE, 1));
        shapes.put(Direction.WEST, rotateY(BASE_SHAPE, 3));
        shapes.put(Direction.DOWN, rotateX(BASE_SHAPE, 1));
        shapes.put(Direction.UP, rotateY(rotateX(BASE_SHAPE, 3), 2));
        return shapes;
    }

    /** 绕 X 轴 90° 的整数倍旋转：1 圈映射 (x,y,z) → (x, z, 1−y)（由 x=90 的模型旋转反推，同 {@link ThrustNozzleBlock}） */
    private static VoxelShape rotateX(VoxelShape shape, int quarterTurns) {
        final int turns = ((quarterTurns % 4) + 4) % 4;

        if (turns == 0) {
            return shape;
        }

        final VoxelShape[] result = new VoxelShape[]{Shapes.empty()};

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double y1 = minY;
            double y2 = maxY;
            double z1 = minZ;
            double z2 = maxZ;

            for (int i = 0; i < turns; i++) {
                final double nextY1 = z1;
                final double nextY2 = z2;
                final double nextZ1 = 1.0D - y2;
                final double nextZ2 = 1.0D - y1;
                y1 = nextY1;
                y2 = nextY2;
                z1 = nextZ1;
                z2 = nextZ2;
            }

            result[0] = Shapes.or(result[0], Shapes.box(minX, y1, z1, maxX, y2, z2));
        });

        return result[0].optimize();
    }

    /** 绕 Y 轴 90° 的整数倍旋转：1 圈映射 (x,y,z) → (1−z, y, x)（照抄 {@link ThrustNozzleBlock#rotateY}） */
    private static VoxelShape rotateY(VoxelShape shape, int quarterTurns) {
        final int turns = ((quarterTurns % 4) + 4) % 4;

        if (turns == 0) {
            return shape;
        }

        final VoxelShape[] result = new VoxelShape[]{Shapes.empty()};

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double x1 = minX;
            double x2 = maxX;
            double z1 = minZ;
            double z2 = maxZ;

            for (int i = 0; i < turns; i++) {
                final double nextX1 = 1.0D - z2;
                final double nextX2 = 1.0D - z1;
                final double nextZ1 = x1;
                final double nextZ2 = x2;
                x1 = nextX1;
                x2 = nextX2;
                z1 = nextZ1;
                z2 = nextZ2;
            }

            result[0] = Shapes.or(result[0], Shapes.box(x1, minY, z1, x2, maxY, z2));
        });

        return result[0].optimize();
    }

    public WaterCoolingModuleBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 {@code FACING} 常量，
     * 不会把属性注册进 stateDefinition，子类必须自己 add（同 {@link CoolingAirDuctBlock}）。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * 放置方向照 {@code create:encased_fan}：<b>不看点击面</b>（不是贴附式），只看玩家视线——
     * 不潜行时薄板朝玩家，潜行时朝视线方向。
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        final Direction preferred = context.getNearestLookingDirection();
        final Direction facing = context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                ? preferred
                : preferred.getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
            @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // ── 冷却液填充：手持流体容器（对齐 quick_fill_fluid_tank / FluidPortBlock 的交互手感） ──
    //
    // 只接受冷却液这件事不在这里做：储罐的 isFluidValid 已经是白名单（EngineCoolants），
    // 所以 FluidPortItemTransfer 的每次 fill/drain 试探都会自动被拒，非冷却液既装不进去、桶也不会被消耗。

    /**
     * 右键 = 物品→储罐 优先，失败再 储罐→物品；潜行右键 = 反向优先（同 {@code quick_fill_fluid_tank}）。
     * 传输成功播流体音效（{@link FluidHelper#getEmptySound}/{@link FluidHelper#getFillSound}）。
     * 管道 / 泵走 BE 的 {@code FluidHandler} 能力，不经过这里。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide())
            return ItemInteractionResult.SUCCESS;
        if (player instanceof FakePlayer)
            return ItemInteractionResult.SUCCESS;

        if (!(level.getBlockEntity(pos) instanceof final WaterCoolingModuleBlockEntity tankBE))
            return ItemInteractionResult.FAIL;

        final IFluidHandler tank = tankBE.getTank();
        final boolean primaryToTank = !player.isSecondaryUseActive();
        final Runnable onChanged = () -> {
            tankBE.setChanged();
            // blockChanged：通知相邻方块（管道等）；sendBlockUpdated：推送 BE 数据到客户端
            // （goggle tooltip 在客户端读 TankContent，不推则显示旧量）
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.getChunkSource().blockChanged(tankBE.getBlockPos());
                level.sendBlockUpdated(pos, state, state, 3);
            }
        };

        FluidExchange exchange;
        FluidStack fluidStack;
        if (primaryToTank) {
            // 右键：物品 → 罐（存入）优先，失败再罐 → 物品（空桶装满）
            if (!(fluidStack = FluidPortItemTransfer.tryEmptyItem(level, player, hand, stack, tank, false, onChanged)).isEmpty()) {
                exchange = FluidExchange.ITEM_TO_TANK;
            } else if (!(fluidStack = FluidPortItemTransfer.tryFillItem(level, player, hand, stack, tank, false, onChanged)).isEmpty()) {
                exchange = FluidExchange.TANK_TO_ITEM;
            } else {
                exchange = null;
            }
        } else {
            // 潜行右键：罐 → 物品优先，失败再物品 → 罐
            if (!(fluidStack = FluidPortItemTransfer.tryFillItem(level, player, hand, stack, tank, false, onChanged)).isEmpty()) {
                exchange = FluidExchange.TANK_TO_ITEM;
            } else if (!(fluidStack = FluidPortItemTransfer.tryEmptyItem(level, player, hand, stack, tank, false, onChanged)).isEmpty()) {
                exchange = FluidExchange.ITEM_TO_TANK;
            } else {
                exchange = null;
            }
        }

        if (exchange == null) {
            // 手持可清空/可填充物品但传输失败（如罐满、或流体不在冷却液表里被拒）→ 吞右键不换手
            if (FluidPortItemTransfer.canItemBeEmptied(level, stack)
                    || FluidPortItemTransfer.canItemBeFilled(level, stack))
                return ItemInteractionResult.SUCCESS;
            return ItemInteractionResult.FAIL;
        }

        SoundEvent soundevent = switch (exchange) {
            case ITEM_TO_TANK -> FluidHelper.getEmptySound(fluidStack);
            case TANK_TO_ITEM -> FluidHelper.getFillSound(fluidStack);
        };
        if (soundevent != null)
            level.playSound(null, pos, soundevent, SoundSource.BLOCKS, .5f, 1f);

        return ItemInteractionResult.SUCCESS;
    }

    // ── 方块实体 ──

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new WaterCoolingModuleBlockEntity(pos, state);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
