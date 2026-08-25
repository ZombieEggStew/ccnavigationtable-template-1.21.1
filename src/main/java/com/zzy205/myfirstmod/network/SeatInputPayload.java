package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端（运行时，每 tick）：坐垫操作输入。
 * 含坐垫位置 + 操纵杆四方向按键状态（按住态）+ 踏板四键状态（左右各 踩下/抬起，按住态）
 * + 油门两键状态（前进/后退，按住态，写死 空格/左Ctrl）；
 * 服务端按每 tick 模拟控件动力学（操纵杆轴 / 踏板压下值 / 油门轴），服务端是控件状态的权威来源。
 * <p>
 * 服务端处理（{@link ControlDeskPacketHandlers}）：校验玩家确实骑乘在该坐垫上，
 * 再把输入写入坐垫四邻所有装了对应控件的 controlDesk BE（未装的忽略）。
 */
public record SeatInputPayload(BlockPos seatPos,
                               boolean up, boolean down, boolean left, boolean right,
                               boolean pedalLeftDown, boolean pedalLeftUp,
                               boolean pedalRightDown, boolean pedalRightUp,
                               boolean throttleForward, boolean throttleBack)
        implements CustomPacketPayload {

    public static final Type<SeatInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "seat_input"));

    // composite 重载最多 6 字段，本包 11 字段；沿用 ControlDeskConfigPayload 的手动编解码风格
    public static final StreamCodec<RegistryFriendlyByteBuf, SeatInputPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.seatPos());
                        buf.writeBoolean(p.up());
                        buf.writeBoolean(p.down());
                        buf.writeBoolean(p.left());
                        buf.writeBoolean(p.right());
                        buf.writeBoolean(p.pedalLeftDown());
                        buf.writeBoolean(p.pedalLeftUp());
                        buf.writeBoolean(p.pedalRightDown());
                        buf.writeBoolean(p.pedalRightUp());
                        buf.writeBoolean(p.throttleForward());
                        buf.writeBoolean(p.throttleBack());
                    },
                    buf -> new SeatInputPayload(
                            buf.readBlockPos(),
                            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                            buf.readBoolean(), buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
