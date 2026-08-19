package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.NonNull;

import java.util.List;

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

    // ═══════════════ 掉落（含扳手拆卸）保留 banner 配置 ═══════════════
    // 扳手拆卸走 Create 默认的 IWrenchable.onSneakWrenched，它会：
    //   1. 调用本 getDrops 拿掉落物（带配置）
    //   2. spawnAfterBreak + destroyBlock
    //   3. 播放 WRENCH_REMOVE 拆卸音效
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        BlockEntity be = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof RedstoneTransceiverBlockEntity receiverBE
                && receiverBE.getLevel() != null && !receiverBE.getBannerData().isEmpty()) {
            CompoundTag tag = new CompoundTag();
            receiverBE.saveAdditional(tag, receiverBE.getLevel().registryAccess());
            BlockEntity.addEntityType(tag, MyModBlockEntities.redstone_transceiver_entity.get());
            ItemStack stack = new ItemStack(this);
            stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag.copy()));
            return List.of(stack);
        }
        return super.getDrops(state, builder);
    }

    // ────────────────────────────────────────
    //  右键 GUI
    // ────────────────────────────────────────

    private static final Component RECEIVER_GUI_TITLE =
            Component.translatable("gui.ccpe.redstone_transceiver");

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        // 蹲下右键时放行，交给扳手处理拆卸（与 create:redstone_requester 的 be.use(player) 一致）
        if (player.isCrouching()) {
            return InteractionResult.PASS;
        }
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
