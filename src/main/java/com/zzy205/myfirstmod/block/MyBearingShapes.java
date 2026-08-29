package com.zzy205.myfirstmod.block;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

/**
 * 轴承 / plate 的选择框与碰撞框。
 * <p>
 * 数值完全对齐 simulated 的 {@code SimBlockShapes}（swivel_bearing）：
 * <ul>
 *   <li>{@link #BEARING_ASSEMBLED}：装配后基座（轴承本体）选择框，y 0-11.9，顶部让给 plate；</li>
 *   <li>{@link #PLATE}：plate 选中框，y 12.1-16；</li>
 *   <li>{@link #PLATE_COLLISION}：plate 碰撞框，y 12-16、xz 3-13。</li>
 * </ul>
 * 装配后基座与 plate 是分离的两个物理体，各自有独立选择框，可分别点选。
 * <p>
 * 参考来源：{@code references/Simulated-Project-main/.../index/SimBlockShapes.java}（46-56 行）。
 */
public final class MyBearingShapes {

    /** 装配后基座（轴承本体）选择框：y 0-11.9（以 UP 为基准，forDirectional 生成 6 向） */
    public static final VoxelShaper BEARING_ASSEMBLED = VoxelShaper.forDirectional(
            Block.box(0, 0, 0, 16, 11.9, 16), Direction.UP);

    /** plate 选中框：y 12.1-16 */
    public static final VoxelShaper PLATE = VoxelShaper.forDirectional(
            Block.box(0, 12.1, 0, 16, 16, 16), Direction.UP);

    /** plate 碰撞框：y 12-16，xz 3-13 */
    public static final VoxelShaper PLATE_COLLISION = VoxelShaper.forDirectional(
            Block.box(3, 12, 3, 13, 16, 13), Direction.UP);

    private MyBearingShapes() {
    }
}
