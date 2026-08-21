package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.channel.ChannelRegistry;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 全局频道注册表 —— 传感器与显示器共享同一频道命名空间，保证频道全局唯一。
 * <p>
 * 委托给通用 {@link ChannelRegistry}，复用分配/冲突顺延/注销/僵尸清理逻辑。
 */
public final class GlobalChannelRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalChannelRegistry.class);

    private static final ChannelRegistry<BlockEntity> REGISTRY =
            ChannelRegistry.create(BlockEntity::isRemoved, GlobalChannelRegistry::broadcastRefresh);

    private GlobalChannelRegistry() {}

    /** 占用集合变化时，刷新各持有者的“已占用频道”快照。 */
    private static void broadcastRefresh() {
        for (BlockEntity be : REGISTRY.owners()) {
            if (be instanceof PeripheralExtenderBlockEntity sensor) {
                try { sensor.refreshOccupiedChannels(); }
                catch (Exception e) { LOGGER.debug("Failed to refresh sensor channel snapshot", e); }
            } else if (be instanceof MonitorBlockEntity monitor) {
                try { monitor.refreshOccupiedChannels(); }
                catch (Exception e) { LOGGER.debug("Failed to refresh monitor channel snapshot", e); }
            }
        }
    }

    /** 自动分配最小空闲频道并注册。 */
    public static int assign(BlockEntity owner) {
        return REGISTRY.assign(owner);
    }

    /** 以期望频道注册，冲突顺延。desired 为负时自动分配。 */
    public static int register(int desired, BlockEntity owner) {
        return REGISTRY.register(desired, owner);
    }

    /** 注销。仅当该频道上确实是该 owner 时移除。 */
    public static void unregister(int channel, BlockEntity owner) {
        REGISTRY.unregister(channel, owner);
    }

    /** 注销该 owner 占用的所有频道。 */
    public static void unregisterAll(BlockEntity owner) {
        REGISTRY.unregisterAll(owner);
    }

    public static BlockEntity get(int channel) {
        return REGISTRY.get(channel);
    }

    public static boolean isOccupied(int channel) {
        return REGISTRY.isOccupied(channel);
    }

    public static Set<Integer> occupiedChannels() {
        return REGISTRY.occupiedChannels();
    }

    /** 已占用频道号数组快照（客户端菜单 / BE 快照同步用）。 */
    public static int[] occupiedChannelsArray() {
        Set<Integer> channels = REGISTRY.occupiedChannels();
        int[] arr = new int[channels.size()];
        int i = 0;
        for (int ch : channels) arr[i++] = ch;
        return arr;
    }

    public static int size() {
        return REGISTRY.size();
    }
}
