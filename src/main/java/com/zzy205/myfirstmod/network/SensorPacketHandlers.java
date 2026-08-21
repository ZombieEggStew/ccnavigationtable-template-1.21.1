package com.zzy205.myfirstmod.network;

import com.simibubi.create.content.logistics.packagerLink.WiFiParticle;
import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.compat.cc.PeripheralExtenderRegistry;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 传感器（Micro Peripheral Extender）相关网络包处理。
 * <ul>
 *   <li>{@link SensorNbtPayload} — 服务端→客户端：推送附着方块 NBT</li>
 *   <li>{@link SensorFilterPayload} — 客户端→服务端：保存频道 / filter / 加载模式</li>
 *   <li>{@link PlayOrderEffectPayload} — 服务端→客户端：下单 WiFi 粒子</li>
 * </ul>
 */
public final class SensorPacketHandlers {

    private SensorPacketHandlers() {}

    public static void register(PayloadRegistrar registrar) {
        // 服务端→客户端：收到 NBT 后直接更新客户端 BE
        registrar.playToClient(
                SensorNbtPayload.TYPE,
                SensorNbtPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.sensorPos(), PeripheralExtenderBlockEntity.class);
                    if (be != null) {
                        be.setCachedAttachedNBT(payload.nbt());
                    }
                }
        );

        // 客户端→服务端：保存 filter 文本
        registrar.playToServer(
                SensorFilterPayload.TYPE,
                SensorFilterPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.sensorPos(), PeripheralExtenderBlockEntity.class);
                    if (be != null) {
                        int newChannel = payload.scrolledValue();
                        if (newChannel != be.getScrolledValue()) {
                            int assigned = PeripheralExtenderRegistry
                                    .register(newChannel, be);
                            be.setScrolledValue(assigned);
                        }
                        be.setLoadMode(payload.loadMode());
                        be.refreshOccupiedChannels();
                    }
                }
        );

        // 服务端→客户端：播放下单 WiFi 粒子（WiFiParticle.Data 无法走网络编码，
        // 只能由客户端本地 level.addParticle 生成）
        registrar.playToClient(
                PlayOrderEffectPayload.TYPE,
                PlayOrderEffectPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var level = ctx.player().level();
                    var vec3 = Vec3.atCenterOf(payload.pos());
                    level.addParticle(new WiFiParticle.Data(), vec3.x, vec3.y, vec3.z, 1, 1, 1);
                }
        );
    }
}
