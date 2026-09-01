package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Collection;
import java.util.UUID;

/**
 * 控制台频道注册表 —— 维护 频道 → ControlDeskBlockEntity 的一对一映射。
 * <p>
 * 与传感器、显示器不同，控制台频道<b>不</b>使用全局 {@link GlobalChannelRegistry}，
 * 而是复用短程信号链接器的物理体作用域频道空间（{@link ShortRangeLinkerRegistry}）：
 * 频道只在本物理体（含约束链）内寻址，非物理体上的控制台不注册频道。
 */
public final class ControlDeskRegistry {

    private ControlDeskRegistry() {}

    /**
     * 以期望频道号注册控制台（负值自动分配），链内冲突顺延，返回实际频道号。
     * 不在物理体上不注册，返回 -1。
     */
    public static int register(int channel, ControlDeskBlockEntity be) {
        return ShortRangeLinkerRegistry.register(channel, be);
    }

    /** 注销控制台。 */
    public static void unregister(int channel, ControlDeskBlockEntity be) {
        ShortRangeLinkerRegistry.unregisterAll(be);
    }

    /** 按频道号在给定物理体链内查询控制台方块实体；该频道被其他设备占用 / 不在链内返回 null。 */
    public static ControlDeskBlockEntity get(Collection<UUID> chainUuids, int channel) {
        BlockEntity owner = ShortRangeLinkerRegistry.get(chainUuids, channel);
        return owner instanceof ControlDeskBlockEntity desk ? desk : null;
    }
}
