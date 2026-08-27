package com.zzy205.myfirstmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 网格 payload 的目标网格：同一种 payload 可能作用于不同 GridState——
 * <ul>
 *   <li>{@link #SCREEN}：Monitor 方块网格 / controlDesk 的 monitor_2 表面网格（默认）</li>
 *   <li>{@link #DESK_TOP}：controlDesk 桌顶 6×14 棋盘网格（monitor 模块 button/knob/toggle 自由放置）</li>
 * </ul>
 */
public enum GridTarget {

    SCREEN, DESK_TOP;

    /** 单字节编码（ordinal），未知值钳位到合法范围。 */
    public static final StreamCodec<ByteBuf, GridTarget> STREAM_CODEC =
            ByteBufCodecs.BYTE.map(
                    i -> values()[Math.max(0, Math.min(values().length - 1, i))],
                    t -> (byte) t.ordinal());
}
