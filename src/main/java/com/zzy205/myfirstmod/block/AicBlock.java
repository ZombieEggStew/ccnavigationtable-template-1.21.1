package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 航空集成计算机（AIC，Aviation Integrated Computer）。
 * <p>
 * 6 向朝向方块：blockstate 旋转结构参考 {@code create:display_link}
 * （未旋转变体 = {@code facing=up}；放置时 FACING = 点击面，同 display_link）。
 * <p>
 * 罗盘（{@code my_aero_sensor/aic/compass}）由 {@link AicBlockEntity} 的客户端重力摆模拟驱动，
 * 经 {@link AicVisual} / {@link AicRenderer} 叠加渲染：先平移到局部位置 {@link #COMPASS_POS}，
 * 再绕该点旋转（blockstate facing 旋转在最外层，见 {@link AicBlockEntity#getBaseQuaternion()}）。
 * 外壳/机身（含 gyro 透明外壳）由 blockstate 静态模型渲染。
 */
public class AicBlock extends DirectionalBlock implements IWrenchable, EntityBlock {

    public static final MapCodec<AicBlock> CODEC = simpleCodec(AicBlock::new);

    /**
     * 罗盘旋转中心在方块局部系的位置（块单位，相对方块角），单一来源（渲染/模拟共用）。
     * 当前取 block.json 里 gyro 外壳的中心 (11,5,11)px；调整罗盘位置只改这里。
     */
    public static final Vector3f COMPASS_POS = new Vector3f(11f / 16f, 5f / 16f, 11f / 16f);

    public AicBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    // ── 选择框（与 blockstate 变体旋转一致，结构参考 FmcBlock） ──

    /** 6 向选择框：up 未旋转盒 (1,0,1,15,4,15)；down x180 → (1,12,1,15,16,15)；水平四向 = WALL 基准盒绕 Y 四向 */
    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.UP, Block.box(1, 0, 1, 15, 4, 15));
        shapes.put(Direction.DOWN, Block.box(1, 12, 1, 15, 16, 15));
        // 水平四向基准盒 = facing=north（模型绕 X 90° 竖立后，同 FMC WALL 盒），绕 Y 旋转出四向
        shapes.putAll(buildHorizontalShapes(Block.box(1, 1, 12, 15, 15, 16)));
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

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
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
        // 同 display_link：模型"正面"朝点击面
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    // ── 方块实体（客户端罗盘摆动画 + 服务端 BodySensorRegistry 注册） ──

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new AicBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<AicBlockEntity> TICKER = AicBlockEntity::tick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        // 客户端跑罗盘摆动画；服务端跑 BodySensorRegistry 所在物理体 UUID 复核（AIC = INS + FMC 门控）
        if (type == MyModBlockEntities.aic_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) TICKER;
            return ticker;
        }
        return null;
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
