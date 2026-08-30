package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 惯性导航系统（INS）：可动的罗盘/万向环姿态指示器，照抄 {@code simulated:gimbal_sensor}。
 * <p>
 * 与 gimbal_sensor 一致：
 * <ul>
 * <li>放置时 {@code HORIZONTAL_AXIS} = 放置朝向顺时针 90° 的轴（仅 x/z 两个变体）；</li>
 * <li>是红石源：四个水平方向按倾角输出 0–15 信号（强信号，不连弱电源检查）；</li>
 * <li>红石粉可连接（四个面都连）；</li>
 * <li>扳手旋转时给 BE 一个随机扰动（{@link MyAeroSensorBlockEntity#randomNudge()}）。</li>
 * </ul>
 * 可动部件（万向环 / 罗盘盘）由 BER（{@link MyAeroSensorRenderer}）或
 * Flywheel（{@link MyAeroSensorVisual}）叠加渲染，不参与 blockstate 模型。
 */
public class MyAeroSensorBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<MyAeroSensorBlock> CODEC = simpleCodec(MyAeroSensorBlock::new);

    /** 主旋转轴（放置时 = 朝向顺时针 90° 的轴），与 simulated:gimbal_sensor 一致 */
    public static final Property<Direction.Axis> HORIZONTAL_AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    /** 碰撞盒照抄 simulated:gimbal_sensor（0,0,0 → 16,10,16） */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 10, 16);

    public MyAeroSensorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HORIZONTAL_AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HORIZONTAL_AXIS,
                context.getHorizontalDirection().getClockWise().getAxis());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        Direction.Axis axis = state.getValue(HORIZONTAL_AXIS);
        return state.setValue(HORIZONTAL_AXIS,
                rot.rotate(Direction.get(Direction.AxisDirection.POSITIVE, axis)).getAxis());
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirrorIn) {
        return state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // ── 红石输出（照抄 gimbal_sensor：四向按倾角输出 0–15） ──

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        MyAeroSensorBlockEntity be = getBlockEntity(level, pos);
        if (be == null)
            return 0;
        return be.getPower(side.getOpposite());
    }

    /** 四个面都连接红石粉（与 simulated CommonRedstoneBlock.commonConnectRedstone=true 一致） */
    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction) {
        return direction != null;
    }

    // ── 方块实体 ──

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new MyAeroSensorBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<MyAeroSensorBlockEntity> TICKER =
            MyAeroSensorBlockEntity::tick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (type == MyModBlockEntities.my_aero_sensor_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) TICKER;
            return ticker;
        }
        return null;
    }

    private MyAeroSensorBlockEntity getBlockEntity(BlockGetter level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof MyAeroSensorBlockEntity myAeroSensorBlockEntity ? myAeroSensorBlockEntity : null;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (result == InteractionResult.SUCCESS) {
            if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MyAeroSensorBlockEntity be) {
                be.randomNudge();
            }
        }
        return result;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
