package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 服务端→客户端：推送 Monitor 棋盘网格状态。
 * <p>
 * NBT 经 gzip 压缩后以 byte 数组传输（{@code NbtIo.writeCompressed} / {@code readCompressed}），
 * 规避 2 MiB 网络包上限；文本层为定长格子数组（int[]），体积固定不再增长。
 */
public record SyncGridPayload(BlockPos pos, CompoundTag gridTag) implements CustomPacketPayload {

    public static final Type<SyncGridPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "sync_grid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncGridPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buf, payload.pos);
                        byte[] compressed = compress(payload.gridTag);
                        buf.writeByteArray(compressed);
                    },
                    buf -> new SyncGridPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            decompress(buf.readByteArray())
                    )
            );

    private static byte[] compress(CompoundTag tag) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(tag, baos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to compress grid NBT", e);
        }
        return baos.toByteArray();
    }

    private static CompoundTag decompress(byte[] data) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress grid NBT", e);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
