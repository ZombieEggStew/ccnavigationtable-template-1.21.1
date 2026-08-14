package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 显示器频道注册表 —— 维护 频道 → MonitorBlockEntity 的一对一映射。
 * <p>
 * 与传感器共享 {@link GlobalChannelRegistry} 的同一频道命名空间，保证频道全局唯一。
 */
public final class MonitorRegistry {

    private MonitorRegistry() {}

    /** 以期望频道号注册显示器（负值自动分配），冲突顺延，返回实际频道号。 */
    public static int register(int channel, MonitorBlockEntity be) {
        return GlobalChannelRegistry.register(channel, be);
    }

    /** 注销显示器。 */
    public static void unregister(int channel, MonitorBlockEntity be) {
        GlobalChannelRegistry.unregister(channel, be);
    }

    public static MonitorBlockEntity get(int channel) {
        BlockEntity owner = GlobalChannelRegistry.get(channel);
        return owner instanceof MonitorBlockEntity monitor ? monitor : null;
    }
}
