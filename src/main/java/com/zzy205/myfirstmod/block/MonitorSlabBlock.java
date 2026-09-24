package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 板式监视器（monitor_slab）：贴附式台阶状方块（slab 形状，表面后续可放置 Monitor 模块）。
 * <p>
 * blockstate 结构参考 {@code create:stock_link}（FACE + 水平 FACING，实现照抄项目内已验证的
 * {@code fmc.json} / FmcBlock 模式，而非 vanilla DirectionalBlock 的 6 向 FACING）：
 * <ul>
 *   <li>附着在地板 / 天花板（{@code FACE}=FLOOR/CEILING）：{@code FACING} 随玩家水平朝向，四向可旋转（4×2 态）；</li>
 *   <li>附着在墙面（{@code FACE}=WALL）：{@code FACING} = 点击面，每个方向固定一个 state（4 态）。</li>
 * </ul>
 * 共 12 态。放置时 {@code FACE} = 点击面、{@code FACING} = 玩家水平朝向反向（地板/天花板）或点击面（墙面）。
 * <p>
 * 选择框 = 16×8×16 台阶盒（照抄 quick_fill_fuel_vault：地板底半 y0..8 / 天花板顶半 y8..16 / 墙面 8px 厚贴墙）；
 * 音效对齐 quick_fill_fuel_vault（SoundType.COPPER）；扳手 = {@link IWrenchable} 默认处理
 * （潜行右键拆除掉包；顶/底面右键旋转水平朝向，墙面态不可旋转）。
 * <p>
 * 当前为纯放置逻辑（无方块实体）；表面 Monitor 模块的放置/交互接入见后续步骤。
 */
public class MonitorSlabBlock extends DirectionalBlock implements IWrenchable {

    public static final MapCodec<MonitorSlabBlock> CODEC = simpleCodec(MonitorSlabBlock::new);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 选择框按 FACE 分组、每组按 FACING 四向旋转：16×8×16 台阶盒（同 quick_fill_fuel_vault） */
    private static final Map<AttachFace, Map<Direction, VoxelShape>> SHAPES;

    static {
        Map<AttachFace, Map<Direction, VoxelShape>> shapes = new EnumMap<>(AttachFace.class);
        // 地板 = 底半 y0..8；天花板 = 顶半 y8..16；墙面 = 8px 厚贴墙（facing=north 基准 z8..16，绕 Y 四向）
        shapes.put(AttachFace.FLOOR, buildHorizontalShapes(Block.box(0, 0, 0, 16, 8, 16)));
        shapes.put(AttachFace.CEILING, buildHorizontalShapes(Block.box(0, 8, 0, 16, 16, 16)));
        shapes.put(AttachFace.WALL, buildHorizontalShapes(Block.box(0, 0, 8, 16, 16, 16)));
        SHAPES = shapes;
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

    public MonitorSlabBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
        );
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 FACING 常量，
     * 不会把属性注册进 stateDefinition（曾因此放置时 {@code setValue(FACING)} 崩溃），
     * 子类必须自己 add。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
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

    /** monitor_slab 的支撑方块方向（FACE/FACING → 支撑方向）；附着方块 = {@code pos.relative(supportDirection)} */
    public static Direction supportDirectionOf(BlockState state) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.getValue(FACING).getOpposite();
        };
    }

    @Override
    protected boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        BlockPos supportPos = pos.relative(supportDirectionOf(state));
        return !level.getBlockState(supportPos).isAir();
    }

    @Override
    public void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Block block, @NotNull BlockPos fromPos, boolean isMoving) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACE)).get(state.getValue(FACING));
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
