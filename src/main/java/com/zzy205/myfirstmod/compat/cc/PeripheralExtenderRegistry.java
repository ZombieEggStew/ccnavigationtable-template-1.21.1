package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;

/**
 * 传感器频道注册表 —— 维护 频道 → PeripheralExtenderBlockEntity 的一对一映射。
 * <p>
 * 委托给全局 {@link GlobalChannelRegistry}，与显示器共享同一频道命名空间。
 */
public final class PeripheralExtenderRegistry {

    private PeripheralExtenderRegistry() {}

    /** 自动分配最小的未被占用的频道号，并注册传感器。 */
    public static int assignChannel(PeripheralExtenderBlockEntity be) {
        return GlobalChannelRegistry.assign(be);
    }

    /** 以指定频道号注册传感器，冲突时顺延到下一个可用频道。 */
    public static int register(int channel, PeripheralExtenderBlockEntity be) {
        return GlobalChannelRegistry.register(channel, be);
    }

    /** 注销传感器。仅当指定频道上确实是该 BE 时才移除。 */
    public static void unregister(int channel, PeripheralExtenderBlockEntity be) {
        GlobalChannelRegistry.unregister(channel, be);
    }

    public static PeripheralExtenderBlockEntity get(int channel) {
        BlockEntity owner = GlobalChannelRegistry.get(channel);
        return owner instanceof PeripheralExtenderBlockEntity sensor ? sensor : null;
    }

    public static boolean isChannelOccupied(int channel) {
        return GlobalChannelRegistry.isOccupied(channel);
    }

    public static Set<Integer> getOccupiedChannels() {
        return GlobalChannelRegistry.occupiedChannels();
    }

    public static int size() {
        return GlobalChannelRegistry.size();
    }
}
