package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的操纵杆配置。
 * 含回正时间（tick）+ 四向按键（InputConstants.Key.getName() 格式，空串 = 未绑定）。
 */
public record ControlDeskConfigPayload(BlockPos pos, int returnTime,
                                       String keyUp, String keyDown, String keyLeft, String keyRight)
        implements CustomPacketPayload {

    public static final Type<ControlDeskConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "control_desk_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlDeskConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ControlDeskConfigPayload::pos,
                    ByteBufCodecs.INT, ControlDeskConfigPayload::returnTime,
                    ByteBufCodecs.STRING_UTF8, ControlDeskConfigPayload::keyUp,
                    ByteBufCodecs.STRING_UTF8, ControlDeskConfigPayload::keyDown,
                    ByteBufCodecs.STRING_UTF8, ControlDeskConfigPayload::keyLeft,
                    ByteBufCodecs.STRING_UTF8, ControlDeskConfigPayload::keyRight,
                    ControlDeskConfigPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
