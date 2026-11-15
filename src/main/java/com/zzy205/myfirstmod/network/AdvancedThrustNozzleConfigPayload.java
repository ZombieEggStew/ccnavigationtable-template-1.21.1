package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存矢量喷管的频道号。
 * 照 {@link ShortRangeLinkerConfigPayload} codec 模式（无「加载物理体」开关，用户定稿）。
 */
public record AdvancedThrustNozzleConfigPayload(BlockPos nozzlePos, int channel) implements CustomPacketPayload {

    public static final Type<AdvancedThrustNozzleConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "advanced_thrust_nozzle_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdvancedThrustNozzleConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, AdvancedThrustNozzleConfigPayload::nozzlePos,
                    ByteBufCodecs.INT, AdvancedThrustNozzleConfigPayload::channel,
                    AdvancedThrustNozzleConfigPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
