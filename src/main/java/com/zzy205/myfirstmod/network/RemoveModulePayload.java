package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：从 Monitor 屏幕网格（或 controlDesk 桌顶网格）上移除模块。
 * {@code target} 区分目标网格（默认 {@link GridTarget#SCREEN} = Monitor / monitor_2 表面）。
 */
public record RemoveModulePayload(BlockPos pos, int moduleId, GridTarget target) implements CustomPacketPayload {

    /** 兼容旧调用点：默认目标为屏幕网格（Monitor / monitor_2 表面）。 */
    public RemoveModulePayload(BlockPos pos, int moduleId) {
        this(pos, moduleId, GridTarget.SCREEN);
    }

    public static final Type<RemoveModulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "remove_module"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveModulePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RemoveModulePayload::pos,
                    ByteBufCodecs.INT, RemoveModulePayload::moduleId,
                    GridTarget.STREAM_CODEC, RemoveModulePayload::target,
                    RemoveModulePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
