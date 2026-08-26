package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的油门2（总距杆）配置。
 * 含上台/下拉按键绑定（InputConstants.Key.getName() 格式，空串 = 未绑定）+ 满偏时间
 * （tick，按住满该 tick 数从最底端到满偏 +30°）+ 回正开关（开启后松开按键回中位 15°）
 * + 回正时间（tick，0 = 关闭回正）。
 * 与油门配置（{@link ThrottleConfigPayload}）分开，避免两模块屏幕互相覆盖对方的配置。
 */
public record Throttle2ConfigPayload(BlockPos pos,
                                     String up, String down,
                                     int freeSpeed,
                                     boolean returnEnabled, int returnTime)
        implements CustomPacketPayload {

    public static final Type<Throttle2ConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "throttle_2_config"));

    // composite 重载最多 6 字段，本包 6 字段；沿用手动编解码风格
    public static final StreamCodec<RegistryFriendlyByteBuf, Throttle2ConfigPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeUtf(p.up());
                        buf.writeUtf(p.down());
                        buf.writeInt(p.freeSpeed());
                        buf.writeBoolean(p.returnEnabled());
                        buf.writeInt(p.returnTime());
                    },
                    buf -> new Throttle2ConfigPayload(
                            buf.readBlockPos(),
                            buf.readUtf(), buf.readUtf(), buf.readInt(),
                            buf.readBoolean(), buf.readInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
