package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.NotNull;

/**
 * 降压孔（静压孔，Static Port）。
 * <p>
 * 贴附式传感器：贴在任意面上，孔朝外（放置时 FACING = 点击面）。
 * 模型绕 Y 轴对称，因此 blockstate 不区分水平 4 向旋转（不绕 Y 轴），
 * 仅需表达 朝上 / 朝下 / 贴墙 三类朝向（绕 X 轴 0/90/180/270）。
 * <p>
 * 带 {@link StaticPortBlockEntity}：注册进 {@code BodySensorRegistry}，
 * 使 {@code ccpe.sensor_system} 能读到静压孔处的气压/高度。
 */
public class StaticPortBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<StaticPortBlock> CODEC = simpleCodec(StaticPortBlock::new);

    /** 孔朝向（6 向，放置时 = 点击面） */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 形状基准：孔朝上（模型元素 x5-11, y-1~2, z5-11 → 3px 高） */
    private static final VoxelShaper SHAPES = VoxelShaper.forDirectional(
            Block.box(5, 0, 5, 11, 3, 11), Direction.UP);

    public StaticPortBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 孔朝点击面（点北墙 → 孔朝北）
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos supportPos = pos.relative(state.getValue(FACING).getOpposite());
        return !level.getBlockState(supportPos).isAir();
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /**
     * 无碰撞箱（实体可穿过）：保留选择框（{@link #getShape}）供瞄准/交互/扳手旋转，
     * 碰撞盒返回空形状——与皮托管/INS 一致（传感器不阻挡机体内实体与气流）。
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    // ── 方块实体（BodySensorRegistry 注册） ──

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new StaticPortBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<StaticPortBlockEntity> SERVER_TICKER =
            StaticPortBlockEntity::serverTick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type == MyModBlockEntities.static_port_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) SERVER_TICKER;
            return ticker;
        }
        return null;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
