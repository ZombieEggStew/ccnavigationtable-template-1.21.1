package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.block.AdvancedThrustNozzleBlockEntity;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 矢量喷管网络包处理。
 * <ul>
 *   <li>{@link AdvancedThrustNozzleConfigPayload} — 客户端→服务端：保存频道号</li>
 * </ul>
 */
public final class AdvancedThrustNozzlePacketHandlers {

    private AdvancedThrustNozzlePacketHandlers() {}

    public static void register(final PayloadRegistrar registrar) {
        registrar.playToServer(
                AdvancedThrustNozzleConfigPayload.TYPE,
                AdvancedThrustNozzleConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    final var be = PacketHelper.findBE(ctx.player().level(), payload.nozzlePos(),
                            AdvancedThrustNozzleBlockEntity.class);
                    if (be == null) {
                        return;
                    }

                    // 非物理体严格不注册（与「只在物理体上生效」语义一致）：GUI 也不该发这个包，
                    // 但客户端作弊包一律无视
                    if (!be.isOnPhysicsBody()) {
                        return;
                    }

                    // 同链冲突自动顺延，回写实际频道（GUI 关闭后以服务端为准）
                    be.applyChannel(payload.channel());
                    // 频道变化后刷新同链占用快照并推给客户端
                    be.refreshOccupiedChannels();
                }
        );
    }
}
