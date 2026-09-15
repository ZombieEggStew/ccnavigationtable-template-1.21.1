package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.fluid.FluidHelper.FluidExchange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
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
 * 快速装填流体储罐（aero_engine / quick_fill_fluid_tank）：6 向贴附式方块，自带 4000mb 单槽流体存储。
 * <p>
 * blockstate 旋转结构照抄 {@code fluid_port}（同 display_link / AIC）：
 * 未旋转变体 = {@code facing=up}，模型"开口"（顶面）朝 FACING；放置时 {@code FACING} = 点击面。
 * 共 12 态（FACING × OPEN），<b>默认 {@code OPEN=false}</b>（closed.json，闭盖状态），
 * {@code OPEN=true} 时切 open.json（开盖动画，10 tick 后自动关闭）。
 * <p>
 * 灵感来源 = {@code fluid_port}（交互/开盖动画）+ {@code quick_fill_fuel_vault}（自带内部存储）：
 * <ul>
 *   <li>内部流体存储 {@link QuickFillFluidTankBlockEntity#CAPACITY} mb（单槽，存于 BE）；</li>
 *   <li>手持流体容器右键 → 存入罐内（物品 → 罐）；</li>
 *   <li>手持空桶右键 → 从罐装满 1 桶（罐 → 物品）；</li>
 *   <li>存取成功 → 开盖动画（OPEN=true）+ {@code create:item_hatch} 音效 + 流体音效，
 *       调度 tick 在 OPEN_TICKS 后自动关闭（动画结构对齐 create:item_hatch / fluid_port / fuel_vault）。</li>
 * </ul>
 * <p>
 * 流体存放在 {@link QuickFillFluidTankBlockEntity}；goggle tooltip 同 create:fluid_tank
 * （BE 实现 {@code IHaveGoggleInformation}，见 {@link QuickFillFluidTankBlockEntity#addToGoggleTooltip}）。
 * 选择框照抄 fluid_port（6 向 16×8×16 台阶盒）；音效对齐 fluid_port（SoundType.COPPER）；
 * 扳手 = {@link IWrenchable} 默认处理（潜行右键拆除、右键无旋转）。
 */
public class QuickFillFluidTankBlock extends DirectionalBlock implements IWrenchable, EntityBlock {

    public static final MapCodec<QuickFillFluidTankBlock> CODEC = simpleCodec(QuickFillFluidTankBlock::new);

    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    private static final int OPEN_TICKS = 10;

    /** 6 向选择框：up 未旋转盒 (0,0,0,16,8,16)；down x180 → (0,8,0,16,16,16)；水平四向 = north 基准盒绕 Y 四向（同 fluid_port） */
    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.UP, Block.box(0, 0, 0, 16, 8, 16));
        shapes.put(Direction.DOWN, Block.box(0, 8, 0, 16, 16, 16));
        // 水平四向基准盒 = facing=north（模型绕 X 90° 竖立后，8px 厚贴墙），绕 Y 旋转出四向
        shapes.putAll(buildHorizontalShapes(Block.box(0, 0, 8, 16, 16, 16)));
        return shapes;
    }

    private static Map<Direction, VoxelShape> buildHorizontalShapes(VoxelShape northShape) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, northShape);
        shapes.put(Direction.EAST, rotateShapeY(northShape, 1));
        shapes.put(Direction.SOUTH, rotateShapeY(northShape, 2));
        shapes.put(Direction.WEST, rotateShapeY(northShape, 3));
        return shapes;
    }

    private static VoxelShape rotateShapeY(VoxelShape shape, int quarterTurns) {
        VoxelShape[] result = new VoxelShape[]{Shapes.empty()};
        int turns = ((quarterTurns % 4) + 4) % 4;

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            switch (turns) {
                case 0 -> result[0] = Shapes.or(result[0], Shapes.box(minX, minY, minZ, maxX, maxY, maxZ));
                case 1 -> result[0] = Shapes.or(result[0], Shapes.box(1.0D - maxZ, minY, minX, 1.0D - minZ, maxY, maxX));
                case 2 -> result[0] = Shapes.or(result[0], Shapes.box(1.0D - maxX, minY, 1.0D - maxZ, 1.0D - minX, maxY, 1.0D - minZ));
                case 3 -> result[0] = Shapes.or(result[0], Shapes.box(minZ, minY, 1.0D - maxX, maxZ, maxY, 1.0D - minX));
            }
        });

        return result[0].optimize();
    }

    public QuickFillFluidTankBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false));
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 {@code FACING} 常量，
     * 不会把属性注册进 stateDefinition（曾因此放置时 {@code setValue(FACING)} 崩溃），
     * 子类必须自己 add。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 同 fluid_port/display_link：模型"开口"（顶面）朝点击面；默认闭盖（OPEN=false）
        return defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(OPEN, false);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    // ── 交互：手持流体容器存入 / 空桶装满 1 桶（对齐 FluidPortBlock.useItemOn，tank = 自身 BE） ──

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide())
            return ItemInteractionResult.SUCCESS;
        if (player instanceof FakePlayer)
            return ItemInteractionResult.SUCCESS;

        QuickFillFluidTankBlockEntity tankBE = getTank(level, pos);
        if (tankBE == null)
            return ItemInteractionResult.FAIL;

        IFluidHandler tank = tankBE.getTank();
        boolean primaryToTank = !player.isSecondaryUseActive();
        Runnable onChanged = () -> {
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
            // 手持可清空/可填充物品但传输失败（如罐满/罐空）→ 吞右键不换手
            if (FluidPortItemTransfer.canItemBeEmptied(level, stack) || FluidPortItemTransfer.canItemBeFilled(level, stack))
                return ItemInteractionResult.SUCCESS;
            return ItemInteractionResult.FAIL;
        }

        // 流体传输音效（对齐 fluid_port）
        SoundEvent soundevent = switch (exchange) {
            case ITEM_TO_TANK -> FluidHelper.getEmptySound(fluidStack);
            case TANK_TO_ITEM -> FluidHelper.getFillSound(fluidStack);
        };
        if (soundevent != null) {
            float pitch = Mth.clamp(1 - (fluidStack.getAmount() / (FluidTankBlockEntity.getCapacityMultiplier() * 16f)), 0, 1);
            pitch /= 1.5f;
            pitch += .5f;
            pitch += (level.random.nextFloat() - .5f) / 4f;
            level.playSound(null, pos, soundevent, SoundSource.BLOCKS, .5f, pitch);
        }

        pulseOpen(level, pos);
        return ItemInteractionResult.SUCCESS;
    }

    @Nullable
    private static QuickFillFluidTankBlockEntity getTank(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof QuickFillFluidTankBlockEntity tankBE ? tankBE : null;
    }

    // ── 开盖动画（对齐 create:item_hatch / fluid_port / fuel_vault：OPEN 10 tick 后自动关闭） ──

    public static void pulseOpen(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof QuickFillFluidTankBlock))
            return;
        if (!state.getValue(OPEN)) {
            level.setBlockAndUpdate(pos, state.setValue(OPEN, true));
            AllSoundEvents.ITEM_HATCH.playOnServer(level, pos);
        }
        level.scheduleTick(pos, state.getBlock(), OPEN_TICKS);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(OPEN))
            level.setBlockAndUpdate(pos, state.setValue(OPEN, false));
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // ── 方块实体 ──

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new QuickFillFluidTankBlockEntity(pos, state);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
