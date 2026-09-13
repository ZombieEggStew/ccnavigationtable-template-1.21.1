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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 快速装填流体储罐（aero_engine / quick_fill_fluid_tank）：6 向贴附式纯静态方块。
 * <p>
 * blockstate 旋转结构照抄 {@code fluid_port}（同 display_link / AIC）：
 * 未旋转变体 = {@code facing=up}，模型"开口"（顶面）朝 FACING；放置时 {@code FACING} = 点击面。
 * 共 6 个 state（仅 {@code FACING}）。
 * <p>
 * <b>无方块实体</b>：只有静态模型（blockstate 渲染，无动态部件/无渲染器）；物品模型直接复用 block 模型。
 * 音效对齐 fluid_port（SoundType.COPPER）；扳手 = {@link IWrenchable} 默认处理
 * （潜行右键拆除掉包、右键无旋转，同 AIC/FMC/燃烧室）。
 * 参考来源：{@code FluidPortBlock}（blockstate/形状结构，去掉 OPEN 属性与方块实体）。
 */
public class QuickFillFluidTankBlock extends DirectionalBlock implements IWrenchable {

    public static final MapCodec<QuickFillFluidTankBlock> CODEC = simpleCodec(QuickFillFluidTankBlock::new);

    /** 6 向选择框：up 未旋转盒 (0,0,0,16,8,16)；down x180 → (0,8,0,16,16,16)；水平四向 = north 基准盒绕 Y 四向（同 fluid_port） */
    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.UP, Block.box(0, 0, 0, 16, 8, 16));
        shapes.put(Direction.DOWN, Block.box(0, 8, 0, 16, 16, 16));
        // 水平四向基准盒 = facing=north（模型绕 X 90° 竖立后，8px 厚贴墙），绕 Y 旋转出四向
        shapes.putAll(buildHorizontalShapes(Block.box(0, 0, 8, 16, 16, 16)));
        return shapes;
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

    public QuickFillFluidTankBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 {@code FACING} 常量，
     * 不会把属性注册进 stateDefinition（曾因此放置时 {@code setValue(FACING)} 崩溃），
     * 子类必须自己 add。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 同 fluid_port/display_link：模型"开口"（顶面）朝点击面
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
