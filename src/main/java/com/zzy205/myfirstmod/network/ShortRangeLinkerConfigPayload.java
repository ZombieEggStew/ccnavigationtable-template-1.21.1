package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存短程信号链接器的频道号与「加载物理体」共享开关。
 * 照 {@link SensorFilterPayload} codec 模式。
 */
public record ShortRangeLinkerConfigPayload(BlockPos linkerPos, int channel, boolean bodyLoad) implements CustomPacketPayload {

    public static final Type<ShortRangeLinkerConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "short_range_linker_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShortRangeLinkerConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ShortRangeLinkerConfigPayload::linkerPos,
                    ByteBufCodecs.INT, ShortRangeLinkerConfigPayload::channel,
                    ByteBufCodecs.BOOL, ShortRangeLinkerConfigPayload::bodyLoad,
                    ShortRangeLinkerConfigPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
