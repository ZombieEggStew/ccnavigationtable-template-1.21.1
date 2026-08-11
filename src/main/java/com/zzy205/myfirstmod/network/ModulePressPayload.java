package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：Monitor 模块按钮按下/释放。
 */
public record ModulePressPayload(BlockPos pos, int moduleId, boolean pressed) implements CustomPacketPayload {

    public static final Type<ModulePressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "module_press"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ModulePressPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ModulePressPayload::pos,
                    ByteBufCodecs.INT, ModulePressPayload::moduleId,
                    ByteBufCodecs.BOOL, ModulePressPayload::pressed,
                    ModulePressPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
