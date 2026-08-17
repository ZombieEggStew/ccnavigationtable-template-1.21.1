package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存测试 monitor 的俯仰 / 偏航角度（度）。
 */
public record PitchMonitorAnglePayload(BlockPos monitorPos, float pitch, float yaw) implements CustomPacketPayload {

    public static final Type<PitchMonitorAnglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "pitch_monitor_angle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PitchMonitorAnglePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PitchMonitorAnglePayload::monitorPos,
                    ByteBufCodecs.FLOAT, PitchMonitorAnglePayload::pitch,
                    ByteBufCodecs.FLOAT, PitchMonitorAnglePayload::yaw,
                    PitchMonitorAnglePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
