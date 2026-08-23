package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的操纵杆配置。
 * 含两轴回正时间（tick）+ 两轴档位模式（开关 + 档位数）+ 四向按键（InputConstants.Key.getName() 格式，空串 = 未绑定）。
 */
public record ControlDeskConfigPayload(BlockPos pos,
                                       int returnTime, int returnTimeYaw,
                                       boolean gearModePitch, int gearCountPitch,
                                       boolean gearModeYaw, int gearCountYaw,
                                       String keyUp, String keyDown, String keyLeft, String keyRight)
        implements CustomPacketPayload {

    public static final Type<ControlDeskConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "control_desk_config"));

    // composite 重载最多 6 字段，本包 11 字段 → 手动编解码
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlDeskConfigPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeInt(p.returnTime());
                        buf.writeInt(p.returnTimeYaw());
                        buf.writeBoolean(p.gearModePitch());
                        buf.writeInt(p.gearCountPitch());
                        buf.writeBoolean(p.gearModeYaw());
                        buf.writeInt(p.gearCountYaw());
                        buf.writeUtf(p.keyUp());
                        buf.writeUtf(p.keyDown());
                        buf.writeUtf(p.keyLeft());
                        buf.writeUtf(p.keyRight());
                    },
                    buf -> new ControlDeskConfigPayload(
                            buf.readBlockPos(),
                            buf.readInt(), buf.readInt(),
                            buf.readBoolean(), buf.readInt(),
                            buf.readBoolean(), buf.readInt(),
                            buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
