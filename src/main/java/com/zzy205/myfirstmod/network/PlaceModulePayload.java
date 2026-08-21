package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：在 Monitor 屏幕网格上放置模块。
 */
public record PlaceModulePayload(BlockPos pos, int gridX, int gridY, String moduleTypeName) implements CustomPacketPayload {

    public static final Type<PlaceModulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "place_module"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceModulePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PlaceModulePayload::pos,
                    ByteBufCodecs.INT, PlaceModulePayload::gridX,
                    ByteBufCodecs.INT, PlaceModulePayload::gridY,
                    ByteBufCodecs.STRING_UTF8, PlaceModulePayload::moduleTypeName,
                    PlaceModulePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
