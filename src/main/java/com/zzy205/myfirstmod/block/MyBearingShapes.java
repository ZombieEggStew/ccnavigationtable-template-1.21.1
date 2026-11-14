package com.zzy205.myfirstmod.block;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * 轴承 / plate 的选择框与碰撞框（两者一致：选择框即碰撞框，便于准星命中和物理碰撞对齐）。
 * <p>
 * 三个形状均以 <b>FACING=UP</b> 为基准定义，用 {@link VoxelShaper#forDirectional} 生成 6 向，
 * 与 {@code blockstates/aero_bearing.json} 的 x/y 旋转变体严格对应：
 * <ul>
 *   <li>{@link #BEARING}：未装配轴承 —— 十字梁（y 0-12）+ 顶部满块（y 12-16）；</li>
 *   <li>{@link #BEARING_ASSEMBLED}：装配后底座 —— 只剩十字梁（y 0-12），顶部让给顶盘；</li>
 *   <li>{@link #PLATE}：装配后顶盘 —— 顶部满块（y 12-16）。</li>
 * </ul>
 * 底座（0-12）与顶盘（12-16）上下相接不重叠，装配后两者是分离的物理体，可分别点选。
 * <p>
 * 盒子（x1,y1,z1,x2,y2,z2，单位 1/16 方块）：
 * <pre>
 *   十字梁 A = [5, 0, 0, 11, 12, 16]   （沿 Z 的梁）
 *   十字梁 B = [0, 0, 5, 16, 12, 11]   （沿 X 的梁）
 *   顶部满块 = [0, 12, 0, 16, 16, 16]
 * </pre>
 */
public final class MyBearingShapes {

    /** 未装配轴承：十字梁（y 0-12）+ 顶部满块（y 12-16） */
    public static final VoxelShaper BEARING = VoxelShaper.forDirectional(
            Shapes.or(
                    Block.box(5, 0, 0, 11, 12, 16),
                    Block.box(0, 0, 5, 16, 12, 11),
                    Block.box(0, 12, 0, 16, 16, 16)
            ),
            Direction.UP);

    /** 装配后底座（轴承本体）：十字梁（y 0-12），顶部让给顶盘 */
    public static final VoxelShaper BEARING_ASSEMBLED = VoxelShaper.forDirectional(
            Shapes.or(
                    Block.box(5, 0, 0, 11, 12, 16),
                    Block.box(0, 0, 5, 16, 12, 11)
            ),
            Direction.UP);

    /** 装配后顶盘：顶部满块（y 12-16） */
    public static final VoxelShaper PLATE = VoxelShaper.forDirectional(
            Block.box(0, 12, 0, 16, 16, 16), Direction.UP);

    private MyBearingShapes() {
    }
}
