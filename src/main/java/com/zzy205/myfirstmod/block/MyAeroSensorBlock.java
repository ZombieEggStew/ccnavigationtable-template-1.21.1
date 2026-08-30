package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * 惯性导航系统（INS）：可动的罗盘/万向环姿态指示器，照抄 {@code simulated:gimbal_sensor}。
 * <p>
 * 简化差异：
 * <ul>
 * <li><b>无 blockstate 旋转</b>：去掉 gimbal_sensor 的 {@code HORIZONTAL_AXIS} 属性，
 *     所有朝向渲染一致（模型保持默认朝向，base 恒为单位旋转）；</li>
 * <li><b>无红石逻辑</b>：不是红石源，不输出信号；</li>
 * <li>扳手旋转时给 BE 一个随机扰动（{@link MyAeroSensorBlockEntity#randomNudge()}）。</li>
 * </ul>
 * 可动部件（万向环 / 罗盘盘）由 BER（{@link MyAeroSensorRenderer}）或
 * Flywheel（{@link MyAeroSensorVisual}）叠加渲染，不参与 blockstate 模型。
 */
public class MyAeroSensorBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<MyAeroSensorBlock> CODEC = simpleCodec(MyAeroSensorBlock::new);

    /** 选择框/碰撞盒：中心 4×6×4 盒体（6,0,6 → 10,6,10），不随朝向旋转 */
    private static final VoxelShape SHAPE = Block.box(6, 0, 6, 10, 6, 10);

    public MyAeroSensorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // ── 方块实体 ──

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new MyAeroSensorBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<MyAeroSensorBlockEntity> TICKER =
            MyAeroSensorBlockEntity::tick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (type == MyModBlockEntities.ins_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) TICKER;
            return ticker;
        }
        return null;
    }

    private MyAeroSensorBlockEntity getBlockEntity(BlockGetter level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof MyAeroSensorBlockEntity myAeroSensorBlockEntity ? myAeroSensorBlockEntity : null;
    }

    /** 空手右键：与扳手相同的随机扰动（客户端直接生效，服务端无副作用） */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof MyAeroSensorBlockEntity be) {
            be.randomNudge();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (result == InteractionResult.SUCCESS) {
            if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MyAeroSensorBlockEntity be) {
                be.randomNudge();
            }
        }
        return result;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
