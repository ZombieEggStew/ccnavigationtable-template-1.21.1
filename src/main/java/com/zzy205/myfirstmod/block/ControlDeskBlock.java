package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 控制台方块 — 纯装饰方块，水平四向朝向（北/东/南/西）。
 * 模型：models/block/control_desk_1/my_control_desk_base.json
 */
public class ControlDeskBlock extends Block implements IWrenchable {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 北向基准形状：桌面主体 + 两侧立柱（对应模型元素 from/to） */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Shapes.or(
                    Block.box(5, 0, 0, 11, 8, 8),
                    Block.box(0, 0, 8, 16, 8, 16)
            ),
            Direction.NORTH
    );

    public ControlDeskBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE.get(state.getValue(FACING));
    }

    // ═══════════════ Create 扳手旋转（顺时针 90°） ═══════════════
    // Create 默认 getRotatedBlockState 只识别 Create 自己的朝向属性，
    // 这里把原版 HORIZONTAL_FACING 顺时针转 90°，实现四向旋转。

    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState.setValue(FACING, originalState.getValue(FACING).getClockWise());
    }
}
