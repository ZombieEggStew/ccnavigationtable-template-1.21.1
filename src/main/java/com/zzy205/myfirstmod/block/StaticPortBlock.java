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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 降压孔（静压孔，Static Port）。
 * <p>
 * 贴附式传感器：贴在任意面上，孔朝外（放置时 FACING = 点击面）。
 * 模型绕 Y 轴对称，因此 blockstate 不区分水平 4 向旋转（不绕 Y 轴），
 * 仅需表达 朝上 / 朝下 / 贴墙 三类朝向（绕 X 轴 0/90/180/270）。
 */
public class StaticPortBlock extends Block implements IWrenchable {

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

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
