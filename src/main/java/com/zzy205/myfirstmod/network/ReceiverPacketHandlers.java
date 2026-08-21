package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import com.zzy205.myfirstmod.compat.cc.RedstoneTransceiverRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 红石收发器（Receiver）相关网络包处理。
 * <ul>
 *   <li>{@link ReceiverSyncPayload} — 客户端→服务端：完整数据同步（banner + 加载模式）</li>
 * </ul>
 */
public final class ReceiverPacketHandlers {

    private ReceiverPacketHandlers() {}

    public static void register(PayloadRegistrar registrar) {
        // 客户端→服务端：Receiver 完整数据同步
        registrar.playToServer(
                ReceiverSyncPayload.TYPE,
                ReceiverSyncPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), RedstoneTransceiverBlockEntity.class);
                    if (be != null) {
                        be.setBannerData(payload.data());
                        be.setLoadMode(payload.loadMode());
                        RedstoneTransceiverRegistry.updateChannels(be, payload.data());
                    }
                }
        );
    }
}
