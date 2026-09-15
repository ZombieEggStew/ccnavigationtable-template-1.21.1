package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 快速装填燃料箱（aero_engine / quick_fill_fuel_vault）：6 向贴附式台阶状方块。
 * <p>
 * blockstate 旋转结构照抄 {@code fluid_port}（同 display_link / AIC）：
 * 未旋转变体 = {@code facing=up}，模型"开口"（顶面）朝 FACING；放置时 {@code FACING} = 点击面。
 * 共 12 态（FACING × OPEN），<b>默认 {@code OPEN=false}</b>（closed.json，闭盖状态），
 * {@code OPEN=true} 时切 open.json（开盖动画，10 tick 后自动关闭）。
 * <p>
 * 灵感来源 = {@code create:item_hatch}（Create ItemHatchBlock），差异是自带库存而非存入相邻容器：
 * <ul>
 *   <li>手持物品右键 → 整组存入内部库存（整个燃料箱只能存一种物品，最多
 *       {@link QuickFillFuelVaultBlockEntity#CAPACITY} 个 = 16 组 × 64，无 GUI）；</li>
 *   <li>空手蹲下右键 → 取出一组（该物品最大堆叠数，不足取全部）；</li>
 *   <li>存取成功 → 开盖动画（OPEN=true）+ {@code create:item_hatch} 音效，
 *       调度 tick 在 OPEN_TICKS 后自动关闭（动画结构对齐 create:item_hatch / fluid_port）。</li>
 * </ul>
 * <p>
 * 库存存放在 {@link QuickFillFuelVaultBlockEntity}；方块移除（挖掘/爆炸/扳手拆除）时内容物掉包（{@link #onRemove}）。
 * 选择框照抄 fluid_port（6 向 16×8×16 台阶盒）；音效对齐 fluid_port（SoundType.COPPER）；
 * 扳手 = {@link IWrenchable} 默认处理（潜行右键拆除掉包）。
 */
public class QuickFillFuelVaultBlock extends DirectionalBlock implements IWrenchable, EntityBlock {

    public static final MapCodec<QuickFillFuelVaultBlock> CODEC = simpleCodec(QuickFillFuelVaultBlock::new);

    private static final Logger LOGGER = LoggerFactory.getLogger("ccpe:QuickFillFuelVault");

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

    public QuickFillFuelVaultBlock(BlockBehaviour.Properties properties) {
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

    // ── 交互：手持整组存入 / 空手蹲下取出一组 ──

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide())
            return ItemInteractionResult.SUCCESS;
        if (player instanceof FakePlayer)
            return ItemInteractionResult.SUCCESS;

        if (!stack.isEmpty()) {
            // 手持物品右键：整组存入（同种叠加；空仓开新类型；满/异种则吞右键不操作）
            QuickFillFuelVaultBlockEntity vault = getVault(level, pos);
            if (vault != null && vault.canDeposit(stack)) {
                int deposited = vault.deposit(stack);
                if (!player.isCreative())
                    stack.shrink(deposited);
                pulseOpen(level, pos);
            }
            return ItemInteractionResult.SUCCESS;
        }

        if (player.isSecondaryUseActive()) {
            // 空手蹲下右键：取出一组（最大堆叠数，不足取全部）
            QuickFillFuelVaultBlockEntity vault = getVault(level, pos);
            if (vault != null && !vault.isEmpty()) {
                ItemStack withdrawn = vault.withdrawGroup();
                player.getInventory().placeItemBackInInventory(withdrawn);
                pulseOpen(level, pos);
                return ItemInteractionResult.SUCCESS;
            }
            return ItemInteractionResult.FAIL;
        }

        return ItemInteractionResult.FAIL;
    }

    @Nullable
    private static QuickFillFuelVaultBlockEntity getVault(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof QuickFillFuelVaultBlockEntity vault ? vault : null;
    }

    // ── 扳手拆除（调试日志，确认拆除调用链；定位后移除） ──

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        boolean server = level instanceof ServerLevel;
        LOGGER.info("onSneakWrenched enter: server={}, pos={}, state={}, be={}",
                server, context.getClickedPos(), state, level.getBlockEntity(context.getClickedPos()));
        InteractionResult result = IWrenchable.super.onSneakWrenched(state, context);
        LOGGER.info("onSneakWrenched exit: result={}, blockAfter={}, beAfter={}",
                result, level.getBlockState(context.getClickedPos()),
                level.getBlockEntity(context.getClickedPos()));
        return result;
    }

    // ── 开盖动画（对齐 create:item_hatch / fluid_port：OPEN 10 tick 后自动关闭） ──

    public static void pulseOpen(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof QuickFillFuelVaultBlock))
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

    // ── 掉包：库存内容物随方块移除掉落（覆盖挖掘/爆炸/扳手拆除所有路径） ──

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        LOGGER.info("onRemove: pos={}, {} -> {}, isMoving={}, be={}",
                pos, state, newState, isMoving, level.getBlockEntity(pos));
        if (state.getBlock() != newState.getBlock()) {
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof QuickFillFuelVaultBlockEntity vault) {
                ItemStack stored = vault.takeAllForDrop();
                // 库存可能 >64（最多 1024），拆成最大堆叠数的小组掉落（单个 >64 的 ItemStack
                // 会在掉落物实体存档时触发 count byte 序列化崩溃）
                while (!stored.isEmpty()) {
                    ItemStack part = stored.split(stored.getMaxStackSize());
                    popResource(level, pos, part);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // ── 方块实体 ──

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new QuickFillFuelVaultBlockEntity(pos, state);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
