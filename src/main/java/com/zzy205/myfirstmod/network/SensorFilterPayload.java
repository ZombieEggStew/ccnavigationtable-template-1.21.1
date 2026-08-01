package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存传感器滚动数值和加载模式
 */
public record SensorFilterPayload(BlockPos sensorPos, int scrolledValue, int loadMode) implements CustomPacketPayload {

    public static final Type<SensorFilterPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "peripheral_extender_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorFilterPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SensorFilterPayload::sensorPos,
                    net.minecraft.network.codec.ByteBufCodecs.INT, SensorFilterPayload::scrolledValue,
                    net.minecraft.network.codec.ByteBufCodecs.INT, SensorFilterPayload::loadMode,
                    SensorFilterPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
