package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：从 Monitor 屏幕网格上移除模块。
 */
public record RemoveModulePayload(BlockPos pos, int moduleId) implements CustomPacketPayload {

    public static final Type<RemoveModulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "remove_module"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveModulePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RemoveModulePayload::pos,
                    ByteBufCodecs.INT, RemoveModulePayload::moduleId,
                    RemoveModulePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
