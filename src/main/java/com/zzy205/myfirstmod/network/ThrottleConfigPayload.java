package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的油门杆配置。
 * 含前进/后退按键绑定（InputConstants.Key.getName() 格式，空串 = 未绑定）+ 档位切换节奏
 * （tick，按住满该 tick 数进/退一档）。
 * 与操纵杆配置（{@link ControlDeskConfigPayload}）/ 脚踏板配置（{@link PedalConfigPayload}）分开，
 * 避免各模块屏幕互相覆盖对方的配置。
 */
public record ThrottleConfigPayload(BlockPos pos,
                                    String forward, String back,
                                    int ticksPerGear)
        implements CustomPacketPayload {

    public static final Type<ThrottleConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "throttle_config"));

    // composite 重载最多 6 字段，本包 4 字段；沿用手动编解码风格
    public static final StreamCodec<RegistryFriendlyByteBuf, ThrottleConfigPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeUtf(p.forward());
                        buf.writeUtf(p.back());
                        buf.writeInt(p.ticksPerGear());
                    },
                    buf -> new ThrottleConfigPayload(
                            buf.readBlockPos(),
                            buf.readUtf(), buf.readUtf(), buf.readInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
