package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.AbstractEncasedShaftBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.NotNull;

public class TransmissionPeripheralBlock extends AbstractEncasedShaftBlock
        implements IBE<TransmissionPeripheralBlockEntity>, IWrenchable {

    public TransmissionPeripheralBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<TransmissionPeripheralBlockEntity> getBlockEntityClass() {
        return TransmissionPeripheralBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TransmissionPeripheralBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.transmission_peripheral_entity.get();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TransmissionPeripheralBlockEntity(pos, state);
    }

    // ═══════════════ 放置与旋转 ═══════════════

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);  // 注册 AXIS 属性
    }

    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return super.getRotatedBlockState(originalState, targetedFace);
    }

    @Override
    public @NotNull BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction.Axis preferredAxis = RotatedPillarKineticBlock.getPreferredAxis(context);
        Direction.Axis axis;

        Player player = context.getPlayer();
        if (preferredAxis != null && (player == null || !player.isShiftKeyDown())) {
            axis = preferredAxis;
        } else {
            // 蹲下放置时反转：水平→垂直、垂直→水平
            if (context.getNearestLookingDirection().getAxis().isVertical()) {
                axis = context.getHorizontalDirection().getAxis();
            } else {
                axis = Direction.Axis.Y;
            }
        }
        return this.defaultBlockState().setValue(AXIS, axis);
    }

    // ═══════════════ 扳手快速拆除 ═══════════════

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!level.isClientSide) {
            ItemStack stack = new ItemStack(this);
            level.destroyBlock(pos, false, player);
            if (player == null || !player.getInventory().add(stack)) {
                net.minecraft.world.level.block.Block.popResource(level, pos, stack);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
