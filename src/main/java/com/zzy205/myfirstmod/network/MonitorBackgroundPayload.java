package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 Monitor 的背景选项。
 */
public record MonitorBackgroundPayload(BlockPos monitorPos, String background) implements CustomPacketPayload {

    public static final Type<MonitorBackgroundPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "monitor_background"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorBackgroundPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, MonitorBackgroundPayload::monitorPos,
                    ByteBufCodecs.STRING_UTF8, MonitorBackgroundPayload::background,
                    MonitorBackgroundPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
