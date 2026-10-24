package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 定向喷管（thrust_nozzle）—— 装在 {@code create:encased_fan} 出风面上的推力装置。
 * <p>
 * 结构照抄 {@code create:nozzle}（{@code com.simibubi.create.content.kinetics.fan.NozzleBlock}）：
 * <ul>
 * <li>{@code FACING} = 喷口朝向，放置时取点击面（喷口背离风扇）；</li>
 * <li>{@link #canSurvive}：背后那格必须是 {@link IAirCurrentSource}，且其
 *     {@code getAirflowOriginSide() == FACING}（即贴在电扇出风面上）；风扇没了就掉落；</li>
 * <li>不是动能方块，不消耗应力，只读风扇转速。</li>
 * </ul>
 * 与 {@code create:nozzle} 的本质区别：原版喷嘴靠射线打到表面产生力（6.4 格射线、依赖距离与
 * 是否命中），本方块是<b>纯轴向恒定推力</b>，任意高度 / 任意距离出力相同，见
 * {@link ThrustNozzleBlockEntity#sable$physicsTick}。
 */
public class ThrustNozzleBlock extends DirectionalBlock implements EntityBlock {

    public static final MapCodec<ThrustNozzleBlock> CODEC = simpleCodec(ThrustNozzleBlock::new);

    /**
     * 选择框 / 碰撞箱基准（{@code facing=up} 未旋转的模型坐标系，单位 = 1/16 格）。
     * <p>
     * 用户定稿：(1,12,1,15,15,15) 顶盖 + (2,0,2,14,12,14) 筒身。
     * 六个朝向的盒子由 {@link #buildShapes()} 按 blockstate 的模型旋转生成，
     * 与 {@code blockstates/thrust_nozzle.json} 的 x/y 旋转严格一致（变换链照抄 {@link AicBlock}）。
     */
    private static final VoxelShape BASE_SHAPE =
            Shapes.or(Block.box(1.0D, 12.0D, 1.0D, 15.0D, 15.0D, 15.0D),
                      Block.box(2.0D, 0.0D, 2.0D, 14.0D, 12.0D, 14.0D));

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    /**
     * 与 blockstate 变体一致的六向盒：up = 原样；down = x180；north = x90；
     * east/south/west = north 再绕 Y 转 90/180/270（{@code blockstates/thrust_nozzle.json}）。
     */
    private static Map<Direction, VoxelShape> buildShapes() {
        final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        final VoxelShape north = rotateX(BASE_SHAPE, 1);

        shapes.put(Direction.UP, BASE_SHAPE);
        shapes.put(Direction.DOWN, rotateX(BASE_SHAPE, 2));
        shapes.put(Direction.NORTH, north);
        shapes.put(Direction.EAST, rotateY(north, 1));
        shapes.put(Direction.SOUTH, rotateY(north, 2));
        shapes.put(Direction.WEST, rotateY(north, 3));
        return shapes;
    }

    /** 绕 X 轴 90° 的整数倍旋转：1 圈映射 (x,y,z) → (x, z, 1−y)（由 x=90 的模型旋转反推，同 {@link AicBlock}） */
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

    /** 绕 Y 轴 90° 的整数倍旋转：1 圈映射 (x,y,z) → (1−z, y, x)（照抄 {@link AicBlock#rotateShapeY}） */
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

    public ThrustNozzleBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 {@code FACING} 常量，
     * 不会把属性注册进 stateDefinition，子类必须自己 add（同 {@link AicBlock}）。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
            @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // ── 只允许贴装在电扇出风面（照抄 create:nozzle） ──

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        final Direction facing = state.getValue(FACING);
        final BlockEntity behind = level.getBlockEntity(pos.relative(facing.getOpposite()));
        return behind instanceof IAirCurrentSource source && source.getAirflowOriginSide() == facing;
    }

    @Override
    protected void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
            @NotNull Block block, @NotNull BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) {
            return;
        }
        // 背后那格（风扇）变化时复核：风扇被拆/被转走 → 喷管掉落
        if (fromPos.equals(pos.relative(state.getValue(FACING).getOpposite())) && !canSurvive(state, level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ThrustNozzleBlockEntity(pos, state);
    }
}
