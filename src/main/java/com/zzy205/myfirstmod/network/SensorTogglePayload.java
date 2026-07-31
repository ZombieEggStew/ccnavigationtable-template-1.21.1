package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：切换传感器功能开关。
 *
 * @param sensorPos 传感器坐标
 * @param toggleId  开关 ID：0=区块加载, 1=物理结构强制加载
 * @param enabled   是否启用
 */
public record SensorTogglePayload(BlockPos sensorPos, int toggleId, boolean enabled) implements CustomPacketPayload {

    public static final Type<SensorTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "sensor_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorTogglePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SensorTogglePayload::sensorPos,
                    net.minecraft.network.codec.ByteBufCodecs.INT, SensorTogglePayload::toggleId,
                    net.minecraft.network.codec.ByteBufCodecs.BOOL, SensorTogglePayload::enabled,
                    SensorTogglePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
