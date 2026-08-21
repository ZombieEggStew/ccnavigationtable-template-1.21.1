package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：移除 Monitor 上指定格子的屏幕。
 */
public record RemoveScreenPayload(BlockPos pos, int gridX, int gridY) implements CustomPacketPayload {

    public static final Type<RemoveScreenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "remove_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveScreenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RemoveScreenPayload::pos,
                    ByteBufCodecs.VAR_INT, RemoveScreenPayload::gridX,
                    ByteBufCodecs.VAR_INT, RemoveScreenPayload::gridY,
                    RemoveScreenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
