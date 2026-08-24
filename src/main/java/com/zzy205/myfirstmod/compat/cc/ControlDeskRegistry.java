package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 控制台频道注册表 —— 维护 频道 → ControlDeskBlockEntity 的一对一映射。
 * <p>
 * 与传感器、显示器共享 {@link GlobalChannelRegistry} 的同一频道命名空间，保证频道全局唯一。
 */
public final class ControlDeskRegistry {

    private ControlDeskRegistry() {}

    /** 以期望频道号注册控制台（负值自动分配），冲突顺延，返回实际频道号。 */
    public static int register(int channel, ControlDeskBlockEntity be) {
        return GlobalChannelRegistry.register(channel, be);
    }

    /** 注销控制台。 */
    public static void unregister(int channel, ControlDeskBlockEntity be) {
        GlobalChannelRegistry.unregister(channel, be);
    }

    /** 按频道号查询控制台方块实体；该频道被其他设备占用返回 null。 */
    public static ControlDeskBlockEntity get(int channel) {
        BlockEntity owner = GlobalChannelRegistry.get(channel);
        return owner instanceof ControlDeskBlockEntity desk ? desk : null;
    }
}
