package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存正式 Monitor 的俯仰 / 偏航角度（度）与前后偏移（像素）。
 */
public record MonitorTransformPayload(BlockPos monitorPos, float pitch, float yaw, int offset) implements CustomPacketPayload {

    public static final Type<MonitorTransformPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "monitor_transform"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorTransformPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, MonitorTransformPayload::monitorPos,
                    ByteBufCodecs.FLOAT, MonitorTransformPayload::pitch,
                    ByteBufCodecs.FLOAT, MonitorTransformPayload::yaw,
                    ByteBufCodecs.INT, MonitorTransformPayload::offset,
                    MonitorTransformPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
