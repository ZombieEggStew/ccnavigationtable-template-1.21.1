package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端→服务端：同步幽灵物品槽中的展示物品。
 */
public record SensorItemPayload(BlockPos sensorPos, ItemStack item, int slotIndex) implements CustomPacketPayload {

    public static final Type<SensorItemPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "sensor_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorItemPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SensorItemPayload::sensorPos,
                    ItemStack.OPTIONAL_STREAM_CODEC, SensorItemPayload::item,
                    ByteBufCodecs.INT, SensorItemPayload::slotIndex,
                    SensorItemPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
