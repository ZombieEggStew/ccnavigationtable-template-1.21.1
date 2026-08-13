package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存某个控件（模块或屏幕）的 ID 与配置。
 * name 为模块类型名或 "screen"；config 内含公共 "text" 与各类型专属键。
 */
public record ModuleConfigPayload(BlockPos pos, String name, int oldId, int newId, CompoundTag config)
        implements CustomPacketPayload {

    public static final Type<ModuleConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "module_config"));

    private static final StreamCodec<RegistryFriendlyByteBuf, CompoundTag> TAG_CODEC =
            StreamCodec.of(
                    (buf, tag) -> buf.writeNbt(tag),
                    buf -> buf.readNbt()
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, ModuleConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ModuleConfigPayload::pos,
                    ByteBufCodecs.STRING_UTF8, ModuleConfigPayload::name,
                    ByteBufCodecs.INT, ModuleConfigPayload::oldId,
                    ByteBufCodecs.INT, ModuleConfigPayload::newId,
                    TAG_CODEC, ModuleConfigPayload::config,
                    ModuleConfigPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
