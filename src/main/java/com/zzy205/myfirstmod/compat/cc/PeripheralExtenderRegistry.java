package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.channel.ChannelRegistry;

import java.util.Set;

/**
 * 传感器频道注册表 —— 维护 频道 → PeripheralExtenderBlockEntity 的一对一映射。
 * <p>
 * 委托给通用 {@link ChannelRegistry}，与 Monitor 模块频道共用分配/冲突/注销逻辑，数据完全独立。
 */
public final class PeripheralExtenderRegistry {

    private static final ChannelRegistry<PeripheralExtenderBlockEntity> REGISTRY =
            ChannelRegistry.create(
                    PeripheralExtenderBlockEntity::isRemoved,
                    PeripheralExtenderRegistry::broadcastRefresh);

    private PeripheralExtenderRegistry() {}

    private static void broadcastRefresh() {
        for (var be : REGISTRY.owners()) {
            try { be.refreshOccupiedChannels(); } catch (Exception ignored) {}
        }
    }

    /** 自动分配最小的未被占用的频道号，并注册传感器。 */
    public static int assignChannel(PeripheralExtenderBlockEntity be) {
        return REGISTRY.assign(be);
    }

    /** 以指定频道号注册传感器，冲突时顺延到下一个可用频道。 */
    public static int register(int channel, PeripheralExtenderBlockEntity be) {
        return REGISTRY.register(channel, be);
    }

    /** 注销传感器。仅当指定频道上确实是该 BE 时才移除。 */
    public static void unregister(int channel, PeripheralExtenderBlockEntity be) {
        REGISTRY.unregister(channel, be);
    }

    public static PeripheralExtenderBlockEntity get(int channel) {
        return REGISTRY.get(channel);
    }

    public static boolean isChannelOccupied(int channel) {
        return REGISTRY.isOccupied(channel);
    }

    public static Set<Integer> getOccupiedChannels() {
        return REGISTRY.occupiedChannels();
    }

    public static int size() {
        return REGISTRY.size();
    }
}
