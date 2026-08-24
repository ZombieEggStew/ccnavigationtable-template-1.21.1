package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的全局频道号。
 */
public record ControlDeskChannelPayload(BlockPos pos, int channel) implements CustomPacketPayload {

    public static final Type<ControlDeskChannelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "control_desk_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlDeskChannelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ControlDeskChannelPayload::pos,
                    ByteBufCodecs.INT, ControlDeskChannelPayload::channel,
                    ControlDeskChannelPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
