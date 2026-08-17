package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Test block for a fixed base with an adjustable (right-click menu) case pitch/yaw.
 * Supports horizontal rotation (north/south/east/west) like the static Monitor.
 */
public class PitchMonitorTestBlock extends BaseEntityBlock {

    public static final MapCodec<PitchMonitorTestBlock> CODEC = simpleCodec(PitchMonitorTestBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 旋转原点（Y=8, 水平中心=8），与模型 y 旋转 / VoxelShaper 的中心一致 */
    public static final float ROT_ORIGIN = 8f;

    private static final VoxelShape BASE_SHAPE = Block.box(0, 0, 0, 16, 2, 13);
    private static final VoxelShape CASE_FLAT_SHAPE = Block.box(1, 2, 4, 15, 14, 9);

    /** 碰撞用：仅底座（静态，不随角度变化） */
    private static final VoxelShaper BASE_SHAPER = VoxelShaper.forHorizontal(BASE_SHAPE, Direction.NORTH);

    /** getShape 缓存：key = (facing, pitch)，角度变化时才重算（角度只在玩家手动改时变） */
    private static final ConcurrentHashMap<Long, VoxelShape> SHAPE_CACHE = new ConcurrentHashMap<>();

    public PitchMonitorTestBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        float pitch = level.getBlockEntity(pos) instanceof PitchMonitorTestBlockEntity monitor
                ? monitor.getPitchAngle() : 0f;
        return cachedOutline(state.getValue(FACING), Math.round(pitch));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BASE_SHAPER.get(state.getValue(FACING));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PitchMonitorTestBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        // 菜单由客户端事件处理器（CCPeripheralExtenderClient）打开，这里仅消费右键。
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static VoxelShape cachedOutline(Direction facing, int pitch) {
        long key = ((long) facing.ordinal() << 32) | (pitch + 90);
        return SHAPE_CACHE.computeIfAbsent(key, k -> {
            VoxelShape north = pitch == 0
                    ? Shapes.or(BASE_SHAPE, CASE_FLAT_SHAPE)
                    : Shapes.or(BASE_SHAPE, createPitchedCaseShape(pitch));
            return VoxelShaper.forHorizontal(north, Direction.NORTH).get(facing);
        });
    }

    private static VoxelShape createPitchedCaseShape(int pitchDegrees) {
        VoxelShape shape = Shapes.empty();
        double radians = Math.toRadians(pitchDegrees);
        for (int slice = 0; slice < 12; slice++) {
            double y0 = 2.0 + slice;
            double y1 = y0 + 1.0;
            double z0 = 4.0;
            double z1 = 9.0;
            double pivotY = 2.0;
            double pivotZ = 6.0;
            double rotatedY0 = pivotY + (y0 - pivotY) * Math.cos(radians) - (z1 - pivotZ) * Math.sin(radians);
            double rotatedY1 = pivotY + (y1 - pivotY) * Math.cos(radians) - (z0 - pivotZ) * Math.sin(radians);
            double rotatedZ0 = pivotZ + (y0 - pivotY) * Math.sin(radians) + (z0 - pivotZ) * Math.cos(radians);
            double rotatedZ1 = pivotZ + (y1 - pivotY) * Math.sin(radians) + (z1 - pivotZ) * Math.cos(radians);
            shape = Shapes.or(shape, Block.box(1, Math.min(rotatedY0, rotatedY1),
                    Math.min(rotatedZ0, rotatedZ1), 15, Math.max(rotatedY0, rotatedY1),
                    Math.max(rotatedZ0, rotatedZ1)));
        }
        return shape;
    }
}
