package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：在 Monitor 屏幕网格（或 controlDesk 桌顶网格）上放置模块。
 * {@code target} 区分目标网格（默认 {@link GridTarget#SCREEN} = Monitor / monitor_2 表面）。
 */
public record PlaceModulePayload(BlockPos pos, int gridX, int gridY, String moduleTypeName, GridTarget target) implements CustomPacketPayload {

    /** 兼容旧调用点：默认目标为屏幕网格（Monitor / monitor_2 表面）。 */
    public PlaceModulePayload(BlockPos pos, int gridX, int gridY, String moduleTypeName) {
        this(pos, gridX, gridY, moduleTypeName, GridTarget.SCREEN);
    }

    public static final Type<PlaceModulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "place_module"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceModulePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PlaceModulePayload::pos,
                    ByteBufCodecs.INT, PlaceModulePayload::gridX,
                    ByteBufCodecs.INT, PlaceModulePayload::gridY,
                    ByteBufCodecs.STRING_UTF8, PlaceModulePayload::moduleTypeName,
                    GridTarget.STREAM_CODEC, PlaceModulePayload::target,
                    PlaceModulePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
