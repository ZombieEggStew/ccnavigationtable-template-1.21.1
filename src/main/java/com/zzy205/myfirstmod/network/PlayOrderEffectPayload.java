package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：通知客户端在指定方块位置播放下单 WiFi 粒子。
 * <p>
 * {@code WiFiParticle.Data} 的流编解码是 {@code StreamCodec.unit(注册时单例)}，
 * 编码时会做对象身份校验（Can't encode ... expected ...），因此它无法走
 * {@code ServerLevel.sendParticles} 之类的网络通道，只能在客户端本地
 * {@code level.addParticle(...)} 生成。本包由服务端广播给附近玩家，
 * 客户端收到后在对应位置生成粒子（与 Create 自己的 WiFiEffectPacket 思路一致）。
 */
public record PlayOrderEffectPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<PlayOrderEffectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "play_order_effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayOrderEffectPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PlayOrderEffectPayload::pos,
                    PlayOrderEffectPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
