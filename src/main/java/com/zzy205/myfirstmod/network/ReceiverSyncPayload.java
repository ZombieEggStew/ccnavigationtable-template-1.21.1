package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 receiver 的全部 banner 数据（频道 + 幽灵物品）和加载模式。
 */
public record ReceiverSyncPayload(BlockPos pos, CompoundTag data, int loadMode) implements CustomPacketPayload {

    public static final Type<ReceiverSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "redstone_transceiver_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReceiverSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ReceiverSyncPayload::pos,
                    ByteBufCodecs.COMPOUND_TAG, ReceiverSyncPayload::data,
                    net.minecraft.network.codec.ByteBufCodecs.INT, ReceiverSyncPayload::loadMode,
                    ReceiverSyncPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
