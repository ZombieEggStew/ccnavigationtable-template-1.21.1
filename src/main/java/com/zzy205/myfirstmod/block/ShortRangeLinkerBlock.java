package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.screen.ShortRangeLinkerMenu;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * 短程信号链接器方块：贴附式（FACE 地面/天花板/墙面 + 水平 FACING，仅墙面参与旋转），
 * 结构照抄 {@link PeripheralExtenderBlock}；blockstate 只有 6 种形态
 * （地面 1 + 天花板 1 + 墙壁 4：floor/ceiling 不随 facing 旋转，wall 四向旋转）。
 * <p>
 * 本阶段：右键打开链接器 GUI（当前只显示背景，频道滚轮 / 加载开关后续阶段接入）；红石输出通道已就绪
 * （BE {@code setRedstoneOutput} 经 Lua API 调用，下一阶段接入）。
 */
public class ShortRangeLinkerBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<ShortRangeLinkerBlock> CODEC = ShortRangeLinkerBlock.simpleCodec(ShortRangeLinkerBlock::new);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /**
     * 选择框与 {@link StaticPortBlock} 同款（用户确认与链接器模型贴合）：基准盒 [5,0,5]-[11,2,11]（y 轴比原 [11,3,11] 压低 1px），
     * 用 Catnip VoxelShaper 旋转（照 static_port 已验证模式，不手写旋转）：
     * FLOOR → UP（贴地原样）、CEILING → DOWN（x:180）、WALL → 水平四向（x:90 + y，与 blockstate 旋转一致）。
     */
    private static final VoxelShaper SHAPES =
            VoxelShaper.forDirectional(Block.box(5, 0, 5, 11, 2, 11), Direction.UP);

    public ShortRangeLinkerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        AttachFace face = clickedFace == Direction.DOWN ? AttachFace.CEILING
                : clickedFace == Direction.UP ? AttachFace.FLOOR
                : AttachFace.WALL;
        Direction facing = face == AttachFace.WALL ? clickedFace : context.getHorizontalDirection().getOpposite();

        return defaultBlockState()
                .setValue(FACE, face)
                .setValue(FACING, facing);
    }

    @Override
    public @NotNull BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        Direction supportDirection = switch (state.getValue(FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.getValue(FACING).getOpposite();
        };
        BlockPos supportPos = pos.relative(supportDirection);
        return !level.getBlockState(supportPos).isAir();
    }

    @Override
    public void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Block block, @NotNull BlockPos fromPos, boolean isMoving) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    // ── 红石输出 ──

    @Override
    protected int getSignal(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull Direction side) {
        if (level.getBlockEntity(pos) instanceof ShortRangeLinkerBlockEntity be)
            return be.getRedstoneOutput();
        return 0;
    }

    @Override
    protected boolean isSignalSource(@NotNull BlockState state) {
        return state.getValue(POWERED);
    }

    /**
     * 更新链接器的红石输出信号并通知相邻方块。
     * 由 {@link ShortRangeLinkerBlockEntity#setRedstoneOutput} 调用。
     */
    public static void updateRedstoneOutput(Level level, BlockPos pos, int signal) {
        // 防止在区块卸载/世界保存期间产生 block update 死锁
        if (level.isClientSide || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.is(MyModBlocks.short_range_linker.get())) return; // 方块已被替换则跳过
        boolean shouldPower = signal > 0;
        if (state.hasProperty(POWERED) && state.getValue(POWERED) != shouldPower) {
            level.setBlock(pos, state.setValue(POWERED, shouldPower), Block.UPDATE_ALL);
        }
        level.updateNeighborsAt(pos, state.getBlock());
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter worldIn, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> SHAPES.get(Direction.UP);
            case CEILING -> SHAPES.get(Direction.DOWN);
            case WALL -> SHAPES.get(state.getValue(FACING));
        };
    }

    /** 无碰撞：玩家/实体可穿过；保留 {@link #getShape} 用于选中框与交互 */
    @Override
    protected @NotNull VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter worldIn, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ShortRangeLinkerBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<ShortRangeLinkerBlockEntity> SERVER_TICKER =
            ShortRangeLinkerBlockEntity::serverTick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type == MyModBlockEntities.short_range_linker_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) SERVER_TICKER;
            return ticker;
        }
        return null;
    }

    /** 计算链接器所附着的方块坐标 */
    public static BlockPos getAttachedPos(BlockState state, BlockPos linkerPos) {
        Direction supportDir = switch (state.getValue(FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.getValue(FACING).getOpposite();
        };
        return linkerPos.relative(supportDir);
    }

    // ────────────────────────────────────────
    //  右键 GUI
    // ────────────────────────────────────────

    private static final Component LINKER_GUI_TITLE =
            Component.translatable("block.ccpe.short_range_linker");

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return useWithoutItem(state, context.getLevel(), context.getClickedPos(), context.getPlayer(),
                new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), true));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        // 蹲下右键时放行，交给扳手处理拆除（与 redstone_transceiver 一致）
        if (player.isCrouching()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // 是否在物理体上：决定 GUI 显示提示还是控件区
            final boolean onPhysicsBody;
            if (level.getBlockEntity(pos) instanceof ShortRangeLinkerBlockEntity linkerBE) {
                onPhysicsBody = linkerBE.isOnPhysicsBody();
            } else {
                onPhysicsBody = false;
            }

            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inv, p) -> new ShortRangeLinkerMenu(containerId, pos, inv, onPhysicsBody),
                            LINKER_GUI_TITLE
                    ),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeBoolean(onPhysicsBody);
                    }
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
