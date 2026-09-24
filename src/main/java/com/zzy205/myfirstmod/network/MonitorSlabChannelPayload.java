package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 monitor_slab 的全局频道号（与显示器/传感器共享全局频道命名空间，对齐
 * {@link MonitorChannelPayload}；自动分配与冲突顺延在服务端 {@code MonitorSlabBlockEntity} 完成）。
 */
public record MonitorSlabChannelPayload(BlockPos pos, int channel) implements CustomPacketPayload {

    public static final Type<MonitorSlabChannelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "monitor_slab_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorSlabChannelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, MonitorSlabChannelPayload::pos,
                    ByteBufCodecs.INT, MonitorSlabChannelPayload::channel,
                    MonitorSlabChannelPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
