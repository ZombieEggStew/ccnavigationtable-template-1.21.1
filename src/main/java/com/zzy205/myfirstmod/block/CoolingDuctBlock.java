package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 冷却风道（aero_engine / cooling_duct）：6 面贴附 × 每面 2 旋转 = <b>12 个 blockstate</b>。
 * <p>
 * 朝向由两个属性表达（参考 {@code PitotTubeBlock}）：
 * <ul>
 *   <li>{@link #FACING}（6 向，放置时 = 点击面）：贴附面；</li>
 *   <li>{@link #ROLL}（0/1）：地板/天花板 = 风道南北↔东西（放置时按玩家水平朝向）；<b>墙面不再单独旋转</b>
 *       （roll=0/1 渲染相同）。</li>
 * </ul>
 * <b>双模型</b>：地板/天花板用平模型 {@code block.json}（风道水平，y 旋转出南北/东西）；
 * 墙面用竖模型 {@code block_v.json}（风道竖直贴墙，绕 Y 转 4 次贴东南西北四面墙）。
 * <p>
 * 纯静态方块（无方块实体/无动态渲染，物品模型 = block 模型）；
 * 音效对齐发动机核心（SoundType.NETHERITE_BLOCK）；
 * 扳手 = {@link IWrenchable}：仅地板/天花板（竖直轴贴附面）右键顶面 ROLL 0↔1 切换；墙面不旋转；
 * 潜行右键 = 默认拆除掉包。
 */
public class CoolingDuctBlock extends DirectionalBlock implements IWrenchable {

    public static final MapCodec<CoolingDuctBlock> CODEC = simpleCodec(CoolingDuctBlock::new);

    /** 贴附面（放置时 = 点击面） */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 每面 2 旋转：0 = 默认（地板南北/墙面默认），1 = 转 90°（地板东西；墙面渲染相同） */
    public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 1);

    /**
     * 12 个状态的选择框：
     * 平模型基准盒 (0,0,0,16,8,16)（地板/天花板）；竖模型基准盒 (0,0,8,16,16,16)（贴墙，绕 Y 四向）。
     */
    private static final Map<Direction, VoxelShape[]> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape[]> buildShapes() {
        VoxelShape flat = Block.box(0, 0, 0, 16, 8, 16);              // 平躺（地板/天花板）
        VoxelShape wallN = Block.box(0, 0, 8, 16, 16, 16);            // 竖模型贴北墙（基准）
        Map<Direction, VoxelShape[]> map = new EnumMap<>(Direction.class);
        map.put(Direction.UP, new VoxelShape[]{flat, flat});
        map.put(Direction.DOWN, new VoxelShape[]{Block.box(0, 8, 0, 16, 16, 16), Block.box(0, 8, 0, 16, 16, 16)});
        map.put(Direction.NORTH, new VoxelShape[]{wallN, wallN});
        map.put(Direction.SOUTH, new VoxelShape[]{Block.box(0, 0, 0, 16, 16, 8), Block.box(0, 0, 0, 16, 16, 8)});
        map.put(Direction.EAST, new VoxelShape[]{Block.box(0, 0, 0, 8, 16, 16), Block.box(0, 0, 0, 8, 16, 16)});
        map.put(Direction.WEST, new VoxelShape[]{Block.box(8, 0, 0, 16, 16, 16), Block.box(8, 0, 0, 16, 16, 16)});
        return map;
    }

    public CoolingDuctBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ROLL, 0));
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 {@code FACING} 常量，
     * 不会把属性注册进 stateDefinition（曾因此放置时 {@code setValue(FACING)} 崩溃），
     * 子类必须自己 add。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROLL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        int roll = 0;
        // 地板/天花板（点击面为竖直轴）：风道按玩家水平朝向旋转——玩家面朝南北 → 风道南北（roll=0），
        // 面朝东西 → 风道东西（roll=1），无需再扳手转一次；墙面放置保持 roll=0（竖直风道，扳手切水平）。
        if (facing.getAxis().isVertical() && context.getPlayer() != null) {
            roll = context.getHorizontalDirection().getAxis() == Direction.Axis.X ? 1 : 0;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(ROLL, roll);
    }

    /**
     * 扳手右键 = 仅地板/天花板（竖直轴贴附面）触发：右键顶面（= FACING）ROLL 0↔1（风道南北↔东西）；
     * 墙面不再单独旋转（竖模型贴墙，见类注释），扳手右键不旋转。潜行右键 = IWrenchable 默认拆除掉包。
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        Direction facing = originalState.getValue(FACING);
        if (targetedFace != facing || !facing.getAxis().isVertical())
            return originalState;
        int roll = originalState.getValue(ROLL);
        return originalState.setValue(ROLL, (roll + 1) % 2);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING))[state.getValue(ROLL)];
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
