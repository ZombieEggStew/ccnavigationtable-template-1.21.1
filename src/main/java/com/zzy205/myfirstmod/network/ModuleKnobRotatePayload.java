package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：旋钮旋转角度更新。
 */
public record ModuleKnobRotatePayload(BlockPos pos, int moduleId, float angle) implements CustomPacketPayload {

    public static final Type<ModuleKnobRotatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheraExtender.MOD_ID, "module_knob_rotate"));

    public static final StreamCodec<ByteBuf, ModuleKnobRotatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ModuleKnobRotatePayload::pos,
                    ByteBufCodecs.INT, ModuleKnobRotatePayload::moduleId,
                    ByteBufCodecs.FLOAT, ModuleKnobRotatePayload::angle,
                    ModuleKnobRotatePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
