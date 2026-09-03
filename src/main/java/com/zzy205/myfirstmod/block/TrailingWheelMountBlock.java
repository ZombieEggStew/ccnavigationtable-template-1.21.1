package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/**
 * 从动轮悬架（Trailing Wheel Mount）——单轮、无动力输入（完全从动）。
 * <p>
 * 结构/模型/物理参考 offroad 的 {@code wheel_mount}
 * （{@code references/Simulated-Project-main/offroad/.../wheel_mount/}），但<b>不是 Create 动力方块</b>：
 * <ul>
 * <li>无传动轴：不继承 {@code HorizontalKineticBlock}，无应力/无轴输入——轮子只随车身地面运动自由滚转；</li>
 * <li>模型直接复用 offroad 资产（blockstate → {@code offroad:block/wheel_mount/block}，
 *     BE renderer 的 tele/spring 部件 → {@code offroad:block/wheel_mount/...} partial），零拷贝；</li>
 * <li>轮胎体系复用 offroad：槽位接受任意带 {@code offroad:TIRE} 数据组件的物品
 *     （offroad 轮胎 / Create 轮子，后者由 offroad 运行时批量挂组件）；</li>
 * <li>悬挂物理（弹簧支撑/地形检测/施力）移植 offroad {@code WheelMountBlockEntity} 的
 *     {@code sable$physicsTick}，删除驱动项——只支撑车体，不推车。</li>
 * </ul>
 * 前置条件与项目其他传感器一致：方块必须随车体结构被装配进 Sable sub-level（物理体 plot）
 * 后，Sable 才会调用其 {@code sable$physicsTick} 施加弹簧力；未装配时仅作静态方块。
 * <p>
 * 首版极简：无红石转向 / 无驻车刹车 / 无悬挂强度滚轮 UI（强度用常量，见
 * {@code TrailingWheelMountBlockEntity.SUSPENSION_STRENGTH}）。
 */
public class TrailingWheelMountBlock extends BaseEntityBlock {

    public static final MapCodec<TrailingWheelMountBlock> CODEC = simpleCodec(TrailingWheelMountBlock::new);

    /** 轮子伸出方向（facing = 轮胎所在侧，facing 的下一格是轮心） */
    public static final DirectionProperty HORIZONTAL_FACING = BlockStateProperties.HORIZONTAL_FACING;

    public TrailingWheelMountBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 轮子朝放置时面对的侧面伸出；点击面为上/下（如从上方装到车架）时退回玩家视线反方向
        Direction facing = context.getClickedFace();
        if (!facing.getAxis().isHorizontal()) {
            facing = context.getHorizontalDirection().getOpposite();
        }
        return defaultBlockState().setValue(HORIZONTAL_FACING, facing);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.hasBlockEntity() && state.getBlock() != newState.getBlock()) {
            if (level.getBlockEntity(pos) instanceof final TrailingWheelMountBlockEntity be
                    && !be.getHeldItem().isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), be.getHeldItem());
            }
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 与 offroad wheel_mount 一致：只允许在轮子朝向面（facing）或底面装卸轮胎
        final Direction hitDirection = hitResult.getDirection();
        if (hitDirection != state.getValue(HORIZONTAL_FACING) && hitDirection != Direction.DOWN) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!(level.getBlockEntity(pos) instanceof final TrailingWheelMountBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (level.isClientSide) {
            // 客户端预判允许性，避免二次触发（照 offroad WheelMountBlock.useItemOn）
            final ItemStack potentialTire = be.getHeldItem();
            if ((heldItem.isEmpty() && potentialTire.has(OffroadDataComponents.TIRE))
                    || (heldItem.has(OffroadDataComponents.TIRE) && potentialTire.has(OffroadDataComponents.TIRE))
                    || (heldItem.has(OffroadDataComponents.TIRE) && potentialTire.isEmpty())) {
                return ItemInteractionResult.SUCCESS;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (switchStacks(level, pos, player, hand, be)) {
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** 与槽位对换手中物品：空手取回、带 TIRE 组件物品装入/替换；返回是否发生了交换 */
    private boolean switchStacks(Level level, BlockPos pos, Player player, InteractionHand hand, TrailingWheelMountBlockEntity be) {
        final ItemStack heldItem = player.getItemInHand(hand);
        final boolean canTake = heldItem.isEmpty() && !be.getHeldItem().isEmpty();
        final boolean canPut = heldItem.has(OffroadDataComponents.TIRE) && be.getHeldItem().isEmpty();
        final boolean canSwap = heldItem.has(OffroadDataComponents.TIRE) && be.getHeldItem().has(OffroadDataComponents.TIRE);
        if (!canTake && !canPut && !canSwap) {
            return false;
        }

        final ItemStack oldSlotItem = be.getHeldItem().copy();
        be.setHeldItem(heldItem.copyWithCount(1));
        if (!player.hasInfiniteMaterials()) {
            heldItem.shrink(1);
        }
        player.getInventory().placeItemBackInInventory(oldSlotItem);

        final boolean wasEmpty = oldSlotItem.isEmpty();
        final boolean nowEmpty = be.getHeldItem().isEmpty();
        final float pitch = 0.8f + level.random.nextFloat() * 0.4f;
        if (!wasEmpty && nowEmpty) {
            // 轮胎被取回
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.75f, pitch);
        } else if (!nowEmpty) {
            // 装入/替换
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.PLAYERS, 0.75f, pitch);
        }
        return true;
    }

    // ── 方块实体 ──

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new TrailingWheelMountBlockEntity(pos, state);
    }

    private static final BlockEntityTicker<TrailingWheelMountBlockEntity> TICKER =
            TrailingWheelMountBlockEntity::tick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state,
                                                                  @NotNull BlockEntityType<T> type) {
        if (type == MyModBlockEntities.trailing_wheel_mount_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) TICKER;
            return ticker;
        }
        return null;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
