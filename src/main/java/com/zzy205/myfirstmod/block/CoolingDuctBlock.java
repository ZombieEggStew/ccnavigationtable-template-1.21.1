package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 冷却风道（aero_engine / cooling_duct）：6 面贴附 × 每面 2 旋转 = <b>12 个 blockstate</b>。
 * <p>
 * 朝向由两个属性表达（结构参考 {@code PitotTubeBlock} / simulated:rope_connector 的
 * {@code AbstractDirectionalAxisBlock}）：
 * <ul>
 *   <li>{@link #FACING}（6 向，放置时 = 点击面）：贴附面；</li>
 *   <li>{@link #AXIS_ALONG_FIRST}（布尔）：地板/天花板 = 风道南北↔东西（放置时按玩家水平朝向）；
 *       墙面 = 风道竖直↔平躺贴墙（false 用竖模型 {@code block_v.json}，true 用平模型 {@code block.json} 叠 x:90 贴墙）。</li>
 * </ul>
 * <b>双模型</b>：地板/天花板/墙面 axis=true 用平模型 {@code block.json}（x 旋转贴墙）；
 * 墙面 axis=false 用竖模型 {@code block_v.json}（风道竖直贴墙，绕 Y 转 4 次贴东南西北四面墙）。
 * <p>
 * 纯静态方块（无方块实体/无动态渲染，物品模型 = block 模型）；
 * 音效对齐发动机核心（SoundType.NETHERITE_BLOCK）；
 * 扳手 = {@link IWrenchable}（照 rope_connector）：右键贴附面（= FACING）翻转 {@link #AXIS_ALONG_FIRST}
 * （任何朝向都响应，地板/天花板/墙面通用）；右键其他面走 Create 默认（换贴面/转朝向）；
 * 潜行右键 = 默认拆除掉包。
 */
public class CoolingDuctBlock extends DirectionalBlock implements IWrenchable, IProxyHoveringInformation {

    public static final MapCodec<CoolingDuctBlock> CODEC = simpleCodec(CoolingDuctBlock::new);

    /** 贴附面（放置时 = 点击面） */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 每面 2 旋转：false = 默认（地板南北/墙面竖贴），true = 转 90°（地板东西/墙面平贴，平模型叠 x:90） */
    public static final BooleanProperty AXIS_ALONG_FIRST = BooleanProperty.create("axis_along_first");

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
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(AXIS_ALONG_FIRST, false));
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 {@code FACING} 常量，
     * 不会把属性注册进 stateDefinition（曾因此放置时 {@code setValue(FACING)} 崩溃），
     * 子类必须自己 add。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, AXIS_ALONG_FIRST);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        boolean axis = false;
        // 地板/天花板（点击面为竖直轴）：风道按玩家水平朝向旋转——玩家面朝南北 → 风道南北（axis=false），
        // 面朝东西 → 风道东西（axis=true），无需再扳手转一次；墙面放置保持 axis=false（竖贴墙，扳手切平贴）。
        if (facing.getAxis().isVertical() && context.getPlayer() != null) {
            axis = context.getHorizontalDirection().getAxis() == Direction.Axis.X;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(AXIS_ALONG_FIRST, axis);
    }

    /**
     * 扳手右键（照 simulated:rope_connector）：右键贴附面（= FACING）翻转 {@link #AXIS_ALONG_FIRST}
     * （地板/天花板/墙面通用，任何朝向都响应）；右键其他面走 {@link IWrenchable} 默认（换贴面/转朝向）。
     * 潜行右键 = IWrenchable 默认拆除掉包。
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        if (targetedFace == originalState.getValue(FACING)) {
            return IWrenchable.super.getRotatedBlockState(originalState, targetedFace)
                    .setValue(AXIS_ALONG_FIRST, !originalState.getValue(AXIS_ALONG_FIRST));
        }
        return IWrenchable.super.getRotatedBlockState(originalState, targetedFace);
    }

    /**
     * goggle tooltip 代理：冷却风道贴附在引擎模块（核心成员 / 燃烧室）上时，共享整条引擎的 tooltip
     * （与看核心完全相同，含无护目镜悬停的传动信息）；未连接任何核心时返回自身（无 BE → 不显示）。
     */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        BlockPos controller = EngineCoreBlockEntity.engineControllerPos(level, pos);
        return controller != null ? controller : pos;
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING))[state.getValue(AXIS_ALONG_FIRST) ? 1 : 0];
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
