package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * controlDesk 相关网络包处理。
 * <ul>
 *   <li>{@link ControlDeskConfigPayload} — 客户端→服务端：保存操纵杆配置（两轴回正时间 + 两轴档位模式 + 四向按键）</li>
 * </ul>
 */
public final class ControlDeskPacketHandlers {

    private ControlDeskPacketHandlers() {}

    public static void register(PayloadRegistrar registrar) {
        // 客户端→服务端：保存 controlDesk 操纵杆配置（服务端权威 + 落盘）
        registrar.playToServer(
                ControlDeskConfigPayload.TYPE,
                ControlDeskConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), ControlDeskBlockEntity.class);
                    if (be != null) {
                        be.setJoystickReturnTime(payload.returnTime());
                        be.setJoystickReturnTimeYaw(payload.returnTimeYaw());
                        be.setGearConfig(payload.gearModePitch(), payload.gearCountPitch(),
                                payload.gearModeYaw(), payload.gearCountYaw());
                        be.setJoystickKeys(payload.keyUp(), payload.keyDown(), payload.keyLeft(), payload.keyRight());
                    }
                }
        );
    }
}
