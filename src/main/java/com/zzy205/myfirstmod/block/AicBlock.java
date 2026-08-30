package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import org.joml.Vector3f;

import org.jetbrains.annotations.NotNull;

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

    // ── 方块实体（客户端罗盘摆动画） ──

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
        // 罗盘摆动画只在客户端跑；服务端无逻辑
        if (!level.isClientSide) return null;
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
