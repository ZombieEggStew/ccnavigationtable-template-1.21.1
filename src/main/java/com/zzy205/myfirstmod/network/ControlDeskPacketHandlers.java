package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.ControlDeskSeatLink;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * controlDesk 相关网络包处理。
 * <ul>
 *   <li>{@link ControlDeskChannelPayload} — 客户端→服务端：保存控制台全局频道</li>
 *   <li>{@link ControlDeskConfigPayload} — 客户端→服务端：保存操纵杆配置（两轴回正时间 + 两轴档位模式/档位数 + 两轴自由模式速度 + 四向按键）</li>
 *   <li>{@link Joystick2ConfigPayload} — 客户端→服务端：保存摇杆2 配置（字段与操纵杆相同，独立于 joystick）</li>
 *   <li>{@link PedalConfigPayload} — 客户端→服务端：保存脚踏板配置（回正时间 + 四向按键）</li>
 *   <li>{@link ThrottleConfigPayload} — 客户端→服务端：保存油门杆配置（前进/后退按键）</li>
 *   <li>{@link Throttle2ConfigPayload} — 客户端→服务端：保存油门2（总距杆）配置（上抬/下拉按键 + 满偏时间）</li>
 *   <li>{@link SeatInputPayload} — 客户端→服务端（运行时每 tick）：坐垫操作输入，服务端校验后驱动 BE 轴状态</li>
 * </ul>
 */
public final class ControlDeskPacketHandlers {

    private ControlDeskPacketHandlers() {}

    public static void register(PayloadRegistrar registrar) {
        // 客户端→服务端：保存 controlDesk 全局频道（服务端权威 + 落盘 + 蓝图兼容）
        registrar.playToServer(
                ControlDeskChannelPayload.TYPE,
                ControlDeskChannelPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), ControlDeskBlockEntity.class);
                    if (be != null) {
                        be.setChannel(payload.channel());
                    }
                }
        );

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
                        be.setJoystickFreeSpeed(payload.freeSpeedPitch(), payload.freeSpeedYaw());
                        be.setJoystickKeys(payload.keyUp(), payload.keyDown(), payload.keyLeft(), payload.keyRight());
                    }
                }
        );

        // 客户端→服务端：保存 controlDesk 摇杆2 配置（独立于操纵杆，服务端权威 + 落盘）
        registrar.playToServer(
                Joystick2ConfigPayload.TYPE,
                Joystick2ConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), ControlDeskBlockEntity.class);
                    if (be != null) {
                        be.setJoystick2ReturnTime(payload.returnTime());
                        be.setJoystick2ReturnTimeYaw(payload.returnTimeYaw());
                        be.setGear2Config(payload.gearModePitch(), payload.gearCountPitch(),
                                payload.gearModeYaw(), payload.gearCountYaw());
                        be.setJoystick2FreeSpeed(payload.freeSpeedPitch(), payload.freeSpeedYaw());
                        be.setJoystick2Keys(payload.keyUp(), payload.keyDown(), payload.keyLeft(), payload.keyRight());
                    }
                }
        );

        // 客户端→服务端：保存 controlDesk 脚踏板配置（服务端权威 + 落盘）
        registrar.playToServer(
                PedalConfigPayload.TYPE,
                PedalConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), ControlDeskBlockEntity.class);
                    if (be != null) {
                        be.setPedalReturnTime(payload.returnTime());
                        be.setPedalFreeSpeed(payload.freeSpeed());
                        be.setPedalKeys(payload.leftUp(), payload.leftDown(), payload.rightUp(), payload.rightDown());
                    }
                }
        );

        // 客户端→服务端：保存 controlDesk 油门杆配置（服务端权威 + 落盘）
        registrar.playToServer(
                ThrottleConfigPayload.TYPE,
                ThrottleConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), ControlDeskBlockEntity.class);
                    if (be != null) {
                        be.setThrottleKeys(payload.forward(), payload.back());
                        be.setThrottleTicksPerGear(payload.ticksPerGear());
                    }
                }
        );

        // 客户端→服务端：保存 controlDesk 油门2（总距杆）配置（上抬/下拉按键 + 满偏时间 + 回正开关/时间；服务端权威 + 落盘 + 蓝图兼容）
        registrar.playToServer(
                Throttle2ConfigPayload.TYPE,
                Throttle2ConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), ControlDeskBlockEntity.class);
                    if (be != null) {
                        be.setThrottle2Keys(payload.up(), payload.down());
                        be.setThrottle2FreeSpeed(payload.freeSpeed());
                        be.setThrottle2Return(payload.returnEnabled(), payload.returnTime());
                    }
                }
        );

        // 客户端→服务端（运行时每 tick）：坐垫操作输入 → 服务端权威驱动操纵杆轴状态 / 踏板压下值
        // 校验：玩家确实骑乘在该坐垫上（防作弊/异常）；联动控制台由坐垫四邻现查（判定①）
        registrar.playToServer(
                SeatInputPayload.TYPE,
                SeatInputPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var player = ctx.player();
                    if (player == null) return;
                    BlockPos actualSeat = ControlDeskSeatLink.seatPosOf(player);
                    if (actualSeat == null || !actualSeat.equals(payload.seatPos())) return;
                    for (ControlDeskBlockEntity desk
                            : ControlDeskSeatLink.findLinkedDesks(player.level(), payload.seatPos())) {
                        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)
                                || desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)
                                || desk.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)
                                || desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)
                                || desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)) {
                            desk.setSeatInput(player.getUUID(), payload.seatPos(),
                                    payload.up(), payload.down(), payload.left(), payload.right(),
                                    payload.pedalLeftDown(), payload.pedalLeftUp(),
                                    payload.pedalRightDown(), payload.pedalRightUp(),
                                    payload.throttleForward(), payload.throttleBack(),
                                    payload.throttle2Up(), payload.throttle2Down());
                        }
                    }
                }
        );
    }
}
