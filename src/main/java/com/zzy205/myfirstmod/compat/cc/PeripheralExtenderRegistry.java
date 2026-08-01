package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传感器频道注册表 —— 维护 频道 → PeripheralExtenderBlockEntity 的一对一映射
 * <p>
 * 每个传感器占用一个频道号（端口模型）。放置传感器时自动分配最小未被占用的频道号。
 */
public final class PeripheralExtenderRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PeripheralExtenderRegistry.class);

    /** 频道号 → 传感器方块实体 */
    private static final Map<Integer, PeripheralExtenderBlockEntity> registry = new ConcurrentHashMap<>();

    private PeripheralExtenderRegistry() {}

    /**
     * 自动分配最小的未被占用的频道号，并注册传感器。
     * 会跳过 removed=true 的僵尸条目。
     */
    public static int assignChannel(PeripheralExtenderBlockEntity be) {
        cleanupZombies();
        int channel = 0;
        while (registry.containsKey(channel)) {
            channel++;
        }
        removeExistingEntryFor(be, channel);
        registry.put(channel, be);
        return channel;
    }

    /**
     * 以指定频道号注册传感器。
     * <p>
     * 自动处理三种情况：
     * <ol>
     *   <li>该 BE 已在其他频道有旧条目 → 先清除旧条目（chunk 重载导致的多频道残留）</li>
     *   <li>目标频道有 removed=true 的僵尸 → 清除僵尸后注册</li>
     *   <li>目标频道被其他活跃 BE 占用 → 找下一个可用频道</li>
     * </ol>
     *
     * @param channel 期望的频道号
     * @param be      传感器方块实体
     * @return 实际分配的频道号
     */
    public static int register(int channel, PeripheralExtenderBlockEntity be) {
        // 清除该 BE 在其他频道上的旧条目（chunk 重载导致同一 BE 多频道残留）
        removeExistingEntryFor(be, channel);

        // 目标频道上的现有条目检查
        PeripheralExtenderBlockEntity existing = registry.get(channel);

        // 僵尸清理：已标记 removed 的 BE 视为不存在
        if (existing != null && existing.isRemoved()) {
            registry.remove(channel);
            existing = null;
        }

        // 频道冲突：另一个活跃 BE 占用
        if (existing != null && existing != be) {
            int newChannel = findNextAvailable(channel);
            LOGGER.warn("Channel {} occupied — reassigning {} to channel {}",
                    channel, be.getBlockPos(), newChannel);
            registry.put(newChannel, be);
            return newChannel;
        }

        registry.put(channel, be);
        return channel;
    }

    private static int removeExistingEntryFor(PeripheralExtenderBlockEntity be, int exceptChannel) {
        int[] count = {0};
        registry.entrySet().removeIf(e -> {
            if (e.getValue() == be && e.getKey() != exceptChannel) { count[0]++; return true; }
            return false;
        });
        return count[0];
    }

    private static void cleanupZombies() {
        registry.entrySet().removeIf(e -> e.getValue().isRemoved());
    }

    private static int findNextAvailable(int start) {
        int channel = start + 1;
        int safety = 0;
        while (safety < 100000) {
            PeripheralExtenderBlockEntity existing = registry.get(channel);
            if (existing == null || existing.isRemoved()) {
                if (existing != null) registry.remove(channel);
                return channel;
            }
            channel++;
            safety++;
        }
        LOGGER.error("findNextAvailable overflow from start={}", start);
        return channel;
    }

    /**
     * 注销传感器。仅当指定频道上确实是该 BE 时才移除。
     */
    public static void unregister(int channel, PeripheralExtenderBlockEntity be) {
        PeripheralExtenderBlockEntity existing = registry.get(channel);
        if (existing == be) {
            registry.remove(channel);
            notifyAllToRefresh();
        }
    }

    private static void notifyAllToRefresh() {
        for (PeripheralExtenderBlockEntity be : List.copyOf(registry.values())) {
            try { be.refreshOccupiedChannels(); } catch (Exception ignored) {}
        }
    }

    public static PeripheralExtenderBlockEntity get(int channel) {
        return registry.get(channel);
    }

    public static boolean isChannelOccupied(int channel) {
        return registry.containsKey(channel);
    }

    public static Set<Integer> getOccupiedChannels() {
        return Set.copyOf(registry.keySet());
    }

    public static int size() {
        return registry.size();
    }
}
