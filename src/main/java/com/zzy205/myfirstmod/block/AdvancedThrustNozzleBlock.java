package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 矢量喷管（{@code ccpe:advanced_thrust_nozzle}）—— 定向喷管 {@link ThrustNozzleBlock} 的加强版。
 * <p>
 * 与定向喷管的唯一结构差别：<b>固定部分只剩底板</b>，喷口本体（筒身 + 十字臂 + 顶环）可以在一个锥角范围内
 * 绕枢轴 {@code (8,1,8)px} 转动，四个小活塞随动（动态模型见
 * {@code assets/ccpe/models/block/advanced_thrust_nozzle/}，渲染见 {@link AdvancedThrustNozzleRenderer}）。
 * 参考实现：{@code aeronautics:gyroscopic_propeller_bearing}（固定体 + 可倾斜顶盘 + 四个动态小活塞）。
 * <p>
 * 除此之外<b>一切都继承</b> {@link ThrustNozzleBlock}：贴在 {@code create:encased_fan} 出风面
 * （{@link #canSurvive}）、六向 {@code FACING}、染料右键染尾迹、扳手语义、推力/气压/粒子全部同款
 * （推力逻辑在 {@link ThrustNozzleBlockEntity}，矢量喷管的 BE 直接继承它）。
 */
public class AdvancedThrustNozzleBlock extends ThrustNozzleBlock {

    public static final MapCodec<AdvancedThrustNozzleBlock> CODEC = simpleCodec(AdvancedThrustNozzleBlock::new);

    /**
     * 选择框 / 碰撞箱基准（{@code facing=up} 未旋转模型帧，单位 = 1/16 格）。
     * <p>
     * 按未倾斜姿态取一个包住整机的静态包：底板 + 筒身/裙边 + 十字臂带（整宽）+ 顶环。
     * 阶段一先固定；阶段二若要跟倾斜，再改成"最大倾角包络"或按 BE 状态动态生成。
     * 六个朝向由父类 {@link #rotateX}/{@link #rotateY} 按 blockstate 的 x/y 旋转生成。
     */
    private static final VoxelShape BASE_SHAPE = Shapes.or(
            // 固定底板（base.json：x2..14, y0..2, z2..14）
            Block.box(2.0D, 0.0D, 2.0D, 14.0D, 2.0D, 14.0D),
            // 裙边 + 筒身（skirt y1..5 在 x3..13 内，筒身 y4..13 到 x2..14）
            Block.box(2.0D, 2.0D, 2.0D, 14.0D, 13.0D, 14.0D),
            // 四条十字臂（block.json 里 y6..10 伸到方块四边 ⇒ 整宽一圈）
            Block.box(0.0D, 6.0D, 0.0D, 16.0D, 10.0D, 16.0D),
            // 顶环（y13..16 的内缩方框，取整块包围）
            Block.box(1.0D, 13.0D, 1.0D, 15.0D, 16.0D, 15.0D)
    );

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    /** 六向盒：up = 原样；down = x180；north = x90；east/south/west = north 再绕 Y 转 90/180/270（同 {@link ThrustNozzleBlock}） */
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

    public AdvancedThrustNozzleBlock(final Properties properties) {
        super(properties);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape shapeFor(final Direction facing) {
        return SHAPES.get(facing);
    }

    @Override
    protected BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new AdvancedThrustNozzleBlockEntity(pos, state);
    }

    @Override
    protected BlockEntityType<?> entityType() {
        return MyModBlockEntities.advanced_thrust_nozzle_entity.get();
    }
}
