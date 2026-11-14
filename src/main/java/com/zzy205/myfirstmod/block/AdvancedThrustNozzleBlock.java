package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

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
     * 选择框 / 碰撞箱：**先用整块正方形**（用户定稿，阶段一）。
     * <p>
     * 用整块而不是按模型抠形的原因：喷口本体会在锥角内连续倾斜，VoxelShape 表达不了连续旋转；
     * 整块盒既简单又保证"任何倾角都挡得住"。正方体绕任何轴 90° 旋转还是自己，所以六个朝向共用同一个盒，
     * 不需要按 blockstate 的 x/y 再生成六份（父类的 {@code rotateX}/{@code rotateY} 仍留给需要精确形的子类）。
     * <p>
     * 阶段二若想贴合模型，可以再做"最大倾角包络"。
     */
    private static final VoxelShape FULL_CUBE_SHAPE = Shapes.block();

    public AdvancedThrustNozzleBlock(final Properties properties) {
        super(properties);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape shapeFor(final Direction facing) {
        // 正方体旋转仍是自己 ⇒ 六向共用一个盒
        return FULL_CUBE_SHAPE;
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
