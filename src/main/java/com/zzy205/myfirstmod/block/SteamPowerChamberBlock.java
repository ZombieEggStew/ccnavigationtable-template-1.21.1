package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 蒸汽动力室（aero_engine / steam_power_chamber）：6 向贴附式方块，模式照抄 {@code FluidCombustionChamberBlock}。
 * <p>
 * blockstate 旋转结构照抄 {@code fluid_port}（同 display_link / AIC）：
 * 未旋转变体 = {@code facing=up}，模型"开口"（顶面）朝 FACING；放置时 {@code FACING} = 点击面。
 * 共 6 个 state（仅 {@code FACING}）。
 * <p>
 * 方块本体（腔体 + 顶沿）由 blockstate 静态模型渲染；<b>活塞</b>（{@code piston.json}）是唯一动态部件，
 * 由 {@link SteamPowerChamberVisual}（Flywheel）/ {@link SteamPowerChamberRenderer}（BER 回退）叠加渲染。
 * 音效对齐发动机核心（SoundType.NETHERITE_BLOCK）；扳手 = {@link IWrenchable} 默认处理
 * （潜行右键拆除掉包、右键无旋转，同 AIC/FMC）。
 */
public class SteamPowerChamberBlock extends DirectionalBlock implements IWrenchable, EntityBlock {

    public static final MapCodec<SteamPowerChamberBlock> CODEC = simpleCodec(SteamPowerChamberBlock::new);

    /** 6 向选择框：up 未旋转盒 = 腔体 (0,0,0,16,8,16) + 顶沿 collar (2,8,2,14,10,14)；down x180；水平四向 = north 基准盒绕 Y 四向 */
    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.UP, Shapes.or(Block.box(0, 0, 0, 16, 8, 16), Block.box(2, 8, 2, 14, 10, 14)));
        shapes.put(Direction.DOWN, Shapes.or(Block.box(0, 8, 0, 16, 16, 16), Block.box(2, 6, 2, 14, 14, 14)));
        // 水平四向基准盒 = facing=north（模型绕 X 90° 竖立后：腔体 z8..16 + collar z6..8），绕 Y 旋转出四向
        shapes.putAll(buildHorizontalShapes(Shapes.or(Block.box(0, 0, 8, 16, 16, 16), Block.box(2, 2, 6, 14, 14, 8))));
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

    public SteamPowerChamberBlock(BlockBehaviour.Properties properties) {
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

    // ── 方块实体 ──

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new SteamPowerChamberBlockEntity(pos, state);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
