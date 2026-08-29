package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 */
public class PitotTubeBlock extends Block implements IWrenchable {

    public static final MapCodec<PitotTubeBlock> CODEC = simpleCodec(PitotTubeBlock::new);

    /** 顶面朝向（6 向，放置时 = 点击面） */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 绕顶面法线（模型局部 Y 轴）的滚转索引：0/1/2/3 = 0°/90°/180°/270° */
    public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 3);

    /**
     * 24 个朝向的选择框 = AABB(基准盒绕方块中心按 (facing, roll) 旋转)，
     * 基准盒 = 管口朝北、贴地（facing=up, roll=0 未旋转变体下的实测盒）(5,0,2)-(11,7,12)。
     * roll=0 的 6 项与旧版 6 向选择框逐项一致（进游戏验证过）。
     */
    private static final EnumMap<Direction, VoxelShape[]> SHAPES = buildShapes();

    private static EnumMap<Direction, VoxelShape[]> buildShapes() {
        // 每行 = 一个 facing（up/down/north/south/east/west），每列 = roll 0..3
        // 盒 = (minX, minY, minZ, maxX, maxY, maxZ)
        int[][][] boxes = {
            // up
            { {5, 0, 2, 11, 7, 12}, {2, 0, 5, 12, 7, 11}, {5, 0, 4, 11, 7, 14}, {4, 0, 5, 14, 7, 11} },
            // down
            { {5, 9, 4, 11, 16, 14}, {2, 9, 5, 12, 16, 11}, {5, 9, 2, 11, 16, 12}, {4, 9, 5, 14, 16, 11} },
            // north
            { {5, 2, 9, 11, 12, 16}, {2, 5, 9, 12, 11, 16}, {5, 4, 9, 11, 14, 16}, {4, 5, 9, 14, 11, 16} },
            // south
            { {5, 2, 0, 11, 12, 7}, {4, 5, 0, 14, 11, 7}, {5, 4, 0, 11, 14, 7}, {2, 5, 0, 12, 11, 7} },
            // east
            { {0, 2, 5, 7, 12, 11}, {0, 5, 2, 7, 11, 12}, {0, 4, 5, 7, 14, 11}, {0, 5, 4, 7, 11, 14} },
            // west
            { {9, 2, 5, 16, 12, 11}, {9, 5, 4, 16, 11, 14}, {9, 4, 5, 16, 14, 11}, {9, 5, 2, 16, 11, 12} },
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

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
