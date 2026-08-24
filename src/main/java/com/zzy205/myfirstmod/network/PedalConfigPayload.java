package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的脚踏板配置。
 * 含回正时间（tick，左右两个踏板共用）+ 四个按键绑定（InputConstants.Key.getName() 格式，空串 = 未绑定）。
 * 与操纵杆配置（{@link ControlDeskConfigPayload}）分开，避免两个屏幕互相覆盖对方的配置。
 */
public record PedalConfigPayload(BlockPos pos,
                                 int returnTime,
                                 String leftUp, String leftDown, String rightUp, String rightDown)
        implements CustomPacketPayload {

    public static final Type<PedalConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "pedal_config"));

    // composite 重载最多 6 字段，本包恰为 6 字段；沿用 ControlDeskConfigPayload 的手动编解码风格
    public static final StreamCodec<RegistryFriendlyByteBuf, PedalConfigPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeInt(p.returnTime());
                        buf.writeUtf(p.leftUp());
                        buf.writeUtf(p.leftDown());
                        buf.writeUtf(p.rightUp());
                        buf.writeUtf(p.rightDown());
                    },
                    buf -> new PedalConfigPayload(
                            buf.readBlockPos(),
                            buf.readInt(),
                            buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
