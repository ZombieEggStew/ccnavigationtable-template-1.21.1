package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：在 Monitor 屏幕网格上放置可变尺寸屏幕（两点矩形选择）。
 */
public record PlaceScreenPayload(
    BlockPos pos,
    int gridX1, int gridY1,
    int gridX2, int gridY2
) implements CustomPacketPayload {

    public static final Type<PlaceScreenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "place_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceScreenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PlaceScreenPayload::pos,
                    ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridX1,
                    ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridY1,
                    ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridX2,
                    ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridY2,
                    PlaceScreenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
