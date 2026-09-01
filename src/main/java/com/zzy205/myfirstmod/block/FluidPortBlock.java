package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 流体端口（fluid_port）：6 向台阶状方块（普通方块，无附着存活检查，拆掉贴附方块不会掉落）。
 * <p>
 * blockstate 旋转结构参考 {@code create:display_link} / {@code aic.json}：
 * 未旋转变体 = {@code facing=up}，模型"开口"（顶面）朝 FACING。
 * 放置时 {@code FACING} = 点击面（同 AIC/display_link），即开口朝点击面（玩家），
 * 模型背面朝 {@code pos.relative(FACING.getOpposite())}。
 * <p>
 * 交互时从<b>除开口面（FACING）外的 5 个方向</b>查找储罐（背面优先，其余按固定顺序），
 * 任何提供 {@code FluidHandler.BLOCK} 能力的方块都可作为储罐（如 Create 流体储罐）。
 * <p>
 * 交互（对齐参考 mod CreateFluidLogistics 的 fluid_hatch，参考来源：
 * {@code references/CreateFluidLogistics-master/.../content/fluids/fluidHatch/FluidHatchBlock.java}）：
 * <ul>
 *   <li>右键手持流体容器 → 物品 → 罐（Shift 右键则优先罐 → 物品）；</li>
 *   <li>传输成功后 {@code pulseOpen()}：OPEN=true（模型切到 block_open）+ 播放 Item Hatch 开启音效，
 *       调度 tick 在 OPEN_TICKS 后自动关闭（模型切回 block_close）。</li>
 * </ul>
 */
public class FluidPortBlock extends DirectionalBlock implements EntityBlock {

    public static final MapCodec<FluidPortBlock> CODEC = simpleCodec(FluidPortBlock::new);

    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    private static final int OPEN_TICKS = 10;

    /** 6 向选择框：up 未旋转盒 (0,0,0,16,8,16)（当前模型 16×8×16 占位 slab）；down x180 → (0,8,0,16,16,16)；水平四向 = north 基准盒绕 Y 四向 */
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

    public FluidPortBlock(BlockBehaviour.Properties properties) {
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
        // 同 AIC/display_link：模型"开口"（顶面）朝点击面
        return defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(OPEN, false);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    // ── 开盖动画（对齐参考 FluidHatchBlock.pulseOpen/tick/scheduleCloseTick） ──

    public static void pulseOpen(Level level, BlockPos pos) {
        pulseOpen(level, pos, OPEN_TICKS);
    }

    static void pulseOpen(Level level, BlockPos pos, int ticks) {
        if (!(level instanceof ServerLevel serverLevel))
            return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FluidPortBlock))
            return;
        FluidPortBlockEntity port = level.getBlockEntity(pos) instanceof FluidPortBlockEntity fluidPort
                ? fluidPort
                : null;
        if (port != null) {
            port.extendOpen(serverLevel, ticks);
        }
        boolean wasOpen = state.getValue(OPEN);
        if (!wasOpen) {
            level.setBlockAndUpdate(pos, state.setValue(OPEN, true));
            AllSoundEvents.ITEM_HATCH.playOnServer(level, pos);
        }
        if (port != null) {
            if (!wasOpen || !port.hasScheduledCloseTick(serverLevel)) {
                scheduleCloseTick(serverLevel, pos, state.getBlock(), port);
            }
            return;
        }
        serverLevel.scheduleTick(pos, state.getBlock(), ticks);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(OPEN))
            return;
        FluidPortBlockEntity port = level.getBlockEntity(pos) instanceof FluidPortBlockEntity fluidPort
                ? fluidPort
                : null;
        if (port != null && port.shouldRemainOpen(level)) {
            if (!port.hasScheduledCloseTick(level)) {
                scheduleCloseTick(level, pos, this, port);
            }
            return;
        }
        if (port != null) {
            port.clearOpenPulse();
        }
        level.setBlockAndUpdate(pos, state.setValue(OPEN, false));
    }

    private static void scheduleCloseTick(ServerLevel level, BlockPos pos, Block block, FluidPortBlockEntity port) {
        int delay = port.getRemainingOpenTicks(level);
        port.markCloseTickScheduled(level, delay);
        level.scheduleTick(pos, block, delay);
    }

    // ── 交互：手持流体容器 ↔ 储罐（对齐参考 FluidHatchBlock.useItemOn，去 FilteringBehaviour） ──
    // 候选罐方向 = 除开口面（FACING，模型顶面旋转后指向）外的 5 个方向，背面（FACING 反向）优先；
    // 对每个方向先试主操作（右键 = 物品→罐，Shift = 罐→物品），失败再试副操作，仍失败才换下一个方向。

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide())
            return ItemInteractionResult.SUCCESS;

        if (player instanceof FakePlayer)
            return ItemInteractionResult.SUCCESS;

        boolean primaryToTank = !player.isSecondaryUseActive();
        for (Direction dir : candidateTankDirections(state)) {
            BlockPos tankPos = pos.relative(dir);
            BlockEntity blockEntity = level.getBlockEntity(tankPos);
            if (blockEntity == null)
                continue;

            IFluidHandler tankCapability = level.getCapability(Capabilities.FluidHandler.BLOCK, tankPos, null);
            if (tankCapability == null)
                continue;

            boolean tankIsCreative = blockEntity instanceof CreativeFluidTankBlockEntity;
            Runnable onChanged = () -> {
                blockEntity.setChanged();
                if (level instanceof ServerLevel serverLevel)
                    serverLevel.getChunkSource().blockChanged(blockEntity.getBlockPos());
            };

            FluidExchange exchange;
            FluidStack fluidStack;
            if (primaryToTank) {
                if (!(fluidStack = FluidPortItemTransfer.tryEmptyItem(level, player, hand, stack, tankCapability, tankIsCreative, onChanged)).isEmpty()) {
                    exchange = FluidExchange.ITEM_TO_TANK;
                } else if (!(fluidStack = FluidPortItemTransfer.tryFillItem(level, player, hand, stack, tankCapability, tankIsCreative, onChanged)).isEmpty()) {
                    exchange = FluidExchange.TANK_TO_ITEM;
                } else {
                    exchange = null;
                }
            } else {
                if (!(fluidStack = FluidPortItemTransfer.tryFillItem(level, player, hand, stack, tankCapability, tankIsCreative, onChanged)).isEmpty()) {
                    exchange = FluidExchange.TANK_TO_ITEM;
                } else if (!(fluidStack = FluidPortItemTransfer.tryEmptyItem(level, player, hand, stack, tankCapability, tankIsCreative, onChanged)).isEmpty()) {
                    exchange = FluidExchange.ITEM_TO_TANK;
                } else {
                    exchange = null;
                }
            }
            if (exchange == null)
                continue;

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

        if (FluidPortItemTransfer.canItemBeEmptied(level, stack) || FluidPortItemTransfer.canItemBeFilled(level, stack))
            return ItemInteractionResult.SUCCESS;
        return ItemInteractionResult.FAIL;
    }

    /** 候选储罐方向：除开口面（FACING）外的 5 个方向，背面（FACING 反向，原附着方向）优先，其余按 Direction 固定顺序 */
    private static Direction[] candidateTankDirections(BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction back = facing.getOpposite();
        Direction[] result = new Direction[5];
        int i = 0;
        result[i++] = back;
        for (Direction dir : Direction.values()) {
            if (dir != facing && dir != back)
                result[i++] = dir;
        }
        return result;
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter worldIn, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // ── 方块实体 ──

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new FluidPortBlockEntity(pos, state);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
