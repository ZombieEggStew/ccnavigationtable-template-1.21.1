package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 瀹㈡埛绔啋鏈嶅姟绔細淇濆瓨浼犳劅鍣ㄦ粴杞暟鍊煎拰鍔犺浇妯″紡锟?
 */
public record SensorFilterPayload(BlockPos sensorPos, int scrolledValue, int loadMode) implements CustomPacketPayload {

    public static final Type<SensorFilterPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "peripheral_extender_filter"));

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
