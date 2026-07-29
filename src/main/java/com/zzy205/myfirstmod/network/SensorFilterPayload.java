package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存传感器滚轮数值和选择菜单的状态。
 */
public record SensorFilterPayload(BlockPos sensorPos, int scrolledValue, int selectIndex) implements CustomPacketPayload {

    public static final Type<SensorFilterPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "sensor_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorFilterPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SensorFilterPayload::sensorPos,
                    net.minecraft.network.codec.ByteBufCodecs.INT, SensorFilterPayload::scrolledValue,
                    net.minecraft.network.codec.ByteBufCodecs.INT, SensorFilterPayload::selectIndex,
                    SensorFilterPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
