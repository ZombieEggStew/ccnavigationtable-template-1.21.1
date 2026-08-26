package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：保存 controlDesk 的摇杆2（joystick_2）配置。
 * 结构与操纵杆配置（{@link ControlDeskConfigPayload}）完全相同：两轴回正时间（tick）+
 * 两轴档位模式（开关 + 档位数 + 自由模式满偏 tick 数）+ 四向按键
 * （InputConstants.Key.getName() 格式，空串 = 未绑定）。
 * <p>
 * 与操纵杆/脚踏板/油门杆配置包分开（各存各的 BE 字段），避免各模块屏幕互相覆盖对方的配置
 * （joystick 与 joystick_2 可同时安装，配置独立）。
 */
public record Joystick2ConfigPayload(BlockPos pos,
                                     int returnTime, int returnTimeYaw,
                                     boolean gearModePitch, int gearCountPitch, int freeSpeedPitch,
                                     boolean gearModeYaw, int gearCountYaw, int freeSpeedYaw,
                                     String keyUp, String keyDown, String keyLeft, String keyRight)
        implements CustomPacketPayload {

    public static final Type<Joystick2ConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "joystick2_config"));

    // composite 重载最多 6 字段，本包 13 字段 → 手动编解码（沿用 ControlDeskConfigPayload 风格）
    public static final StreamCodec<RegistryFriendlyByteBuf, Joystick2ConfigPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeInt(p.returnTime());
                        buf.writeInt(p.returnTimeYaw());
                        buf.writeBoolean(p.gearModePitch());
                        buf.writeInt(p.gearCountPitch());
                        buf.writeInt(p.freeSpeedPitch());
                        buf.writeBoolean(p.gearModeYaw());
                        buf.writeInt(p.gearCountYaw());
                        buf.writeInt(p.freeSpeedYaw());
                        buf.writeUtf(p.keyUp());
                        buf.writeUtf(p.keyDown());
                        buf.writeUtf(p.keyLeft());
                        buf.writeUtf(p.keyRight());
                    },
                    buf -> new Joystick2ConfigPayload(
                            buf.readBlockPos(),
                            buf.readInt(), buf.readInt(),
                            buf.readBoolean(), buf.readInt(), buf.readInt(),
                            buf.readBoolean(), buf.readInt(), buf.readInt(),
                            buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
