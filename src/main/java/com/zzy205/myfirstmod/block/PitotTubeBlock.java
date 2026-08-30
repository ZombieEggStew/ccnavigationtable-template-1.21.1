package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.EnumMap;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.NotNull;

/**
 * 皮托管（Pitot Tube）。
 * <p>
 * 方向性传感器，朝向由两个属性表达：
 * <ul>
 * <li>{@link #FACING}（6 向，放置时 = 点击面）：模型局部 +Y（顶面）的世界方向，也是模型顶面所在的面；</li>
 * <li>{@link #ROLL}（0/90/180/270）：绕顶面法线（模型局部 Y 轴）的滚转角。</li>
 * </ul>
 * 共 24 个朝向。Create 扳手右键<b>模型顶面</b>（= FACING 方向的面）时，绕该面旋转：ROLL +1
 * （FACING 不变，管子绕点击面转）；右键侧面 / 前面 / 底面等其它面不旋转。
 * <p>
 * 带 {@link PitotTubeBlockEntity}：注册进 {@code BodySensorRegistry}（SPEED），
 * 使 {@code ccpe.sensor_system} 能读到沿管口朝向的速度分量（见 {@link #axisOf}）。
 */
public class PitotTubeBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<PitotTubeBlock> CODEC = simpleCodec(PitotTubeBlock::new);

    /** 顶面朝向（6 向，放置时 = 点击面） */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 绕顶面法线（模型局部 Y 轴）的滚转索引：0/1/2/3 = 0°/90°/180°/270° */
    public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 3);

    /**
     * 24 个朝向的选择框 = AABB(基准盒绕方块中心按 (facing, roll) 旋转)，
     * 基准盒 = 管口朝北、贴地（facing=up, roll=0 未旋转变体下的实测盒）(5,0,2)-(11,6,11)。
     * 24 项由 tools/pitot-24state-gen.js 从基准盒生成（脚本校验自洽，进游戏复核）。
     */
    private static final EnumMap<Direction, VoxelShape[]> SHAPES = buildShapes();

    private static EnumMap<Direction, VoxelShape[]> buildShapes() {
        // 每行 = 一个 facing（up/down/north/south/east/west），每列 = roll 0..3
        // 盒 = (minX, minY, minZ, maxX, maxY, maxZ)
        int[][][] boxes = {
            // up
            { {5, 0, 2, 11, 6, 11}, {2, 0, 5, 11, 6, 11}, {5, 0, 5, 11, 6, 14}, {5, 0, 5, 14, 6, 11} },
            // down
            { {5, 10, 5, 11, 16, 14}, {2, 10, 5, 11, 16, 11}, {5, 10, 2, 11, 16, 11}, {5, 10, 5, 14, 16, 11} },
            // north
            { {5, 2, 10, 11, 11, 16}, {2, 5, 10, 11, 11, 16}, {5, 5, 10, 11, 14, 16}, {5, 5, 10, 14, 11, 16} },
            // south
            { {5, 2, 0, 11, 11, 6}, {5, 5, 0, 14, 11, 6}, {5, 5, 0, 11, 14, 6}, {2, 5, 0, 11, 11, 6} },
            // east
            { {0, 2, 5, 6, 11, 11}, {0, 5, 2, 6, 11, 11}, {0, 5, 5, 6, 14, 11}, {0, 5, 5, 6, 11, 14} },
            // west
            { {10, 2, 5, 16, 11, 11}, {10, 5, 5, 16, 11, 14}, {10, 5, 5, 16, 14, 11}, {10, 5, 2, 16, 11, 11} },
        };
        Direction[] dirs = { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        EnumMap<Direction, VoxelShape[]> map = new EnumMap<>(Direction.class);
        for (int i = 0; i < dirs.length; i++) {
            VoxelShape[] rolls = new VoxelShape[4];
            for (int r = 0; r < 4; r++) {
                int[] b = boxes[i][r];
                rolls[r] = Block.box(b[0], b[1], b[2], b[3], b[4], b[5]);
            }
            map.put(dirs[i], rolls);
        }
        return map;
    }

    private static VoxelShape shapeFor(Direction facing, int roll) {
        return SHAPES.get(facing)[roll];
    }

    /**
     * 24 个朝向的<b>感应轴线（管口朝向）</b>（plot 帧轴方向）：facing → roll 0..3。
     * <p>
     * 推导：模型管口向量 = 未旋转模型局部 (0,0,−1)（管口朝北/−Z，含 z90 烘焙模型——
     * 绕 Z 旋转不改轴方向），经 blockstate 旋转 {@code Ry(−θy)·Rx(−θx)} 逐项计算，
     * 并与已进游戏验证的 24 个选择框逐项核对（管口端 = 盒长轴端）一致。
     */
    private static final EnumMap<Direction, Direction[]> AXES = buildAxes();

    private static EnumMap<Direction, Direction[]> buildAxes() {
        // 每行 = 一个 facing，每列 = roll 0..3；值 = 管口朝向（Direction）
        Direction[][] axes = {
            // up:    roll0 −Z(NORTH)  roll1 −X(WEST)  roll2 +Z(SOUTH)  roll3 +X(EAST)
            { Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST },
            // down:  roll0 +Z(SOUTH)  roll1 −X(WEST)  roll2 −Z(NORTH)  roll3 +X(EAST)
            { Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST },
            // north: roll0 −Y(DOWN)   roll1 −X(WEST)  roll2 +Y(UP)     roll3 +X(EAST)
            { Direction.DOWN, Direction.WEST, Direction.UP, Direction.EAST },
            // south: roll0 −Y(DOWN)   roll1 +X(EAST)  roll2 +Y(UP)     roll3 −X(WEST)
            { Direction.DOWN, Direction.EAST, Direction.UP, Direction.WEST },
            // east:  roll0 −Y(DOWN)   roll1 −Z(NORTH) roll2 +Y(UP)     roll3 +Z(SOUTH)
            { Direction.DOWN, Direction.NORTH, Direction.UP, Direction.SOUTH },
            // west:  roll0 −Y(DOWN)   roll1 +Z(SOUTH) roll2 +Y(UP)     roll3 −Z(NORTH)
            { Direction.DOWN, Direction.SOUTH, Direction.UP, Direction.NORTH },
        };
        Direction[] dirs = { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        EnumMap<Direction, Direction[]> map = new EnumMap<>(Direction.class);
        for (int i = 0; i < dirs.length; i++)
            map.put(dirs[i], axes[i]);
        return map;
    }

    /** 该状态皮托管的感应轴线（管口朝向，plot 帧轴方向）；供 {@code ccpe.sensor_system} 计算沿轴速度 */
    public static Direction axisOf(BlockState state) {
        return AXES.get(state.getValue(FACING))[state.getValue(ROLL)];
    }

    public PitotTubeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ROLL, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROLL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 管口朝点击面（点北墙 → facing=north），滚转角 0
        return defaultBlockState().setValue(FACING, context.getClickedFace()).setValue(ROLL, 0);
    }

    /**
     * 贴附式传感器：FACING = 点击面（管口朝外），附着面在反方向。
     * 支撑方块被破坏/移走后无法存活 → {@link #neighborChanged} 掉落。
     * 与 {@link StaticPortBlock} / Peripheral Extender 同款。
     */
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

    /**
     * Create 扳手右键旋转：
     * <ul>
     * <li>顶面 = 模型相对顶面（模型选择框局部 x-z 平面所在的世界面）= 当前 FACING 方向的面；
     *     仅右键该面触发旋转；</li>
     * <li>旋转 = 绕该面（模型局部 Y 轴）旋转：ROLL +1（FACING 不变，管子绕点击面转）；</li>
     * <li>右键侧面 / 前面 / 底面等其它面一律<b>不旋转</b>（原样返回：无状态变化、无声效）。</li>
     * </ul>
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        Direction facing = originalState.getValue(FACING);
        if (targetedFace != facing)
            return originalState;
        int roll = originalState.getValue(ROLL);
        return originalState.setValue(ROLL, (roll + 1) % 4);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor(state.getValue(FACING), state.getValue(ROLL));
    }

    // ── 方块实体（BodySensorRegistry 注册） ──

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new PitotTubeBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<PitotTubeBlockEntity> SERVER_TICKER =
            PitotTubeBlockEntity::serverTick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type == MyModBlockEntities.pitot_tube_entity.get()) {
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
