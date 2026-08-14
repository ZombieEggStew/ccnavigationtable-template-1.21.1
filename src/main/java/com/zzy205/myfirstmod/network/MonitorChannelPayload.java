package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 Monitor 的全局频道号。
 */
public record MonitorChannelPayload(BlockPos monitorPos, int channel) implements CustomPacketPayload {

    public static final Type<MonitorChannelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "monitor_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorChannelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, MonitorChannelPayload::monitorPos,
                    ByteBufCodecs.INT, MonitorChannelPayload::channel,
                    MonitorChannelPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
