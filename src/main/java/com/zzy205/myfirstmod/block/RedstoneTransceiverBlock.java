package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.NonNull;

public class RedstoneTransceiverBlock
extends BaseEntityBlock
implements IWrenchable {
    public static final MapCodec<RedstoneTransceiverBlock> CODEC = RedstoneTransceiverBlock.simpleCodec(RedstoneTransceiverBlock::new);

    protected RedstoneTransceiverBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected @NonNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(@NonNull BlockPos pos, @NonNull BlockState state) {
        return new RedstoneTransceiverBlockEntity(pos, state);
    }
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NonNull Level level, @NonNull BlockState state, @NonNull BlockEntityType<T> type) {
        return null;
    }
    @Override
    public @NonNull RenderShape getRenderShape(@NonNull BlockState state) {
        return RenderShape.MODEL;
    }
    

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!level.isClientSide) {
            ItemStack stack = new ItemStack(this);
            level.destroyBlock(pos, false, player);
            if (player == null || !player.getInventory().add(stack)) {
                Block.popResource(level, pos, stack);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ────────────────────────────────────────
    //  右键 GUI
    // ────────────────────────────────────────

    private static final Component RECEIVER_GUI_TITLE =
            Component.translatable("gui.ccnavigationtable.redstone_transceiver");

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = level.getBlockEntity(pos);
            CompoundTag bannerData = be instanceof RedstoneTransceiverBlockEntity receiverBE
                    ? receiverBE.getBannerData() : new CompoundTag();
            int[] occupiedChannels = be instanceof RedstoneTransceiverBlockEntity receiverBE
                    ? receiverBE.getOccupiedChannels() : new int[0];
            int loadMode = be instanceof RedstoneTransceiverBlockEntity receiverBE
                    ? receiverBE.getLoadMode() : 0;
            boolean onPhysicsBody = be instanceof RedstoneTransceiverBlockEntity receiverBE
                    ? receiverBE.isOnPhysicsBody() : false;

            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inv, p) ->
                                    new RedstoneTransceiverMenu(
                                            containerId, pos, bannerData, occupiedChannels, loadMode, onPhysicsBody, inv),
                            RECEIVER_GUI_TITLE
                    ),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeNbt(bannerData);
                        buf.writeVarInt(occupiedChannels.length);
                        for (int ch : occupiedChannels) {
                            buf.writeVarInt(ch);
                        }
                        buf.writeVarInt(loadMode);
                        buf.writeBoolean(onPhysicsBody);
                    }
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
