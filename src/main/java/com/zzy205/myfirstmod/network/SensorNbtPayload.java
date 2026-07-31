package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 鏈嶅姟绔啋瀹㈡埛绔細鎺ㄩ€佷紶鎰熷櫒闄勭潃鏂瑰潡鐨勬渶锟?NBT 鏁版嵁锟?
 */
public record SensorNbtPayload(BlockPos sensorPos, CompoundTag nbt) implements CustomPacketPayload {

    public static final Type<SensorNbtPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "peripheral_extender_nbt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorNbtPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buf, payload.sensorPos);
                        buf.writeNbt(payload.nbt);
                    },
                    buf -> new SensorNbtPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readNbt()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
