package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.block.ShortRangeLinkerBlockEntity;
import com.zzy205.myfirstmod.compat.cc.ShortRangeLinkerRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 短程信号链接器网络包处理。
 * <ul>
 *   <li>{@link ShortRangeLinkerConfigPayload} — 客户端→服务端：保存频道 + 加载物理体开关</li>
 * </ul>
 */
public final class ShortRangeLinkerPacketHandlers {

    private ShortRangeLinkerPacketHandlers() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                ShortRangeLinkerConfigPayload.TYPE,
                ShortRangeLinkerConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.linkerPos(), ShortRangeLinkerBlockEntity.class);
                    if (be == null) return;
                    // 非物理体严格不注册：忽略频道/开关改动（与「非物理体不链接」语义一致）
                    if (!be.isOnPhysicsBody()) return;

                    int newChannel = payload.channel();
                    if (newChannel != be.getScrolledValue()) {
                        // 同链冲突自动顺延，回写实际频道（GUI 关闭后以服务端为准）
                        int assigned = ShortRangeLinkerRegistry.register(newChannel, be);
                        be.setScrolledValue(assigned);
                    }
                    // bodyLoad 为链上共享开关：setBodyLoad 会同步同链全部链接器并各自更新 ticket
                    be.setBodyLoad(payload.bodyLoad());
                    // 刷新链内占用快照并推给客户端
                    be.refreshOccupiedChannels();
                }
        );
    }
}
