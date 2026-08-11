package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：推送 Monitor 棋盘网格状态。
 */
public record SyncGridPayload(BlockPos pos, CompoundTag gridTag) implements CustomPacketPayload {

    public static final Type<SyncGridPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "sync_grid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncGridPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buf, payload.pos);
                        buf.writeNbt(payload.gridTag);
                    },
                    buf -> new SyncGridPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readNbt()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
