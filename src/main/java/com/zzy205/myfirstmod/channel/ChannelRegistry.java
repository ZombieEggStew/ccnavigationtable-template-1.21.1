package com.zzy205.myfirstmod.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 通用频道注册表：维护 频道号 → 占用者 的一对一映射。
 * <p>
 * 分配最小空闲频道、冲突顺延、注销、僵尸清理等逻辑通用。
 * 传感器与显示器通过 {@code GlobalChannelRegistry} 共享同一实例以保证频道全局唯一，
 * 其它需要独立频道空间的系统可自行创建新实例。
 *
 * @param <O> 占用者类型
 */
public final class ChannelRegistry<O> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelRegistry.class);

    /** 频道号 → 占用者 */
    private final Map<Integer, O> byChannel = new ConcurrentHashMap<>();
    /** 僵尸条目判定（可为 null，表示不做僵尸清理） */
    private final Predicate<O> isInvalid;
    /** 占用集合变化时的广播回调（用于刷新各持有者的快照） */
    private final Runnable onChange;

    private ChannelRegistry(Predicate<O> isInvalid, Runnable onChange) {
        this.isInvalid = isInvalid;
        this.onChange = onChange;
    }

    public static <O> ChannelRegistry<O> create() {
        return new ChannelRegistry<>(null, null);
    }

    public static <O> ChannelRegistry<O> create(Predicate<O> isInvalid, Runnable onChange) {
        return new ChannelRegistry<>(isInvalid, onChange);
    }

    private void cleanup() {
        if (isInvalid != null) {
            byChannel.entrySet().removeIf(e -> isInvalid.test(e.getValue()));
        }
    }

    /** 自动分配最小的未被占用的频道号。 */
    public int assign(O owner) {
        cleanup();
        int channel = 0;
        while (byChannel.containsKey(channel)) {
            channel++;
        }
        byChannel.put(channel, owner);
        if (onChange != null) onChange.run();
        return channel;
    }

    /**
     * 以期望频道号注册占用者。
     * <ul>
     *   <li>期望频道为负 → 自动分配</li>
     *   <li>占用者在其它频道有旧条目 → 先清除（chunk 重载导致的多频道残留）</li>
     *   <li>目标频道被其它活跃占用者占用 → 顺延到下一个可用频道</li>
     * </ul>
     *
     * @return 实际分配的频道号
     */
    public int register(int desired, O owner) {
        if (desired < 0) return assign(owner);
        cleanup();
        removeOwner(owner, desired);

        O existing = byChannel.get(desired);
        if (existing != null && !existing.equals(owner)) {
            int newChannel = nextAvailable(desired + 1);
            LOGGER.warn("Channel {} occupied — reassigning {} to channel {}", desired, owner, newChannel);
            desired = newChannel;
        }

        byChannel.put(desired, owner);
        if (onChange != null) onChange.run();
        return desired;
    }

    /** 注销指定频道上的占用者（仅当确实是该占用者时）。 */
    public boolean unregister(int channel, O owner) {
        O existing = byChannel.get(channel);
        if (existing != null && existing.equals(owner)) {
            byChannel.remove(channel);
            if (onChange != null) onChange.run();
            return true;
        }
        return false;
    }

    /** 注销该占用者占用的所有频道。 */
    public void unregisterAll(O owner) {
        boolean removed = byChannel.entrySet().removeIf(e -> e.getValue().equals(owner));
        if (removed && onChange != null) onChange.run();
    }

    /** 注销所有满足条件的占用者（一次性广播）。 */
    public void unregisterIf(Predicate<O> filter) {
        boolean removed = byChannel.entrySet().removeIf(e -> filter.test(e.getValue()));
        if (removed && onChange != null) onChange.run();
    }

    private void removeOwner(O owner, int exceptChannel) {
        byChannel.entrySet().removeIf(e -> e.getValue().equals(owner) && e.getKey() != exceptChannel);
    }

    private int nextAvailable(int start) {
        int channel = start;
        int safety = 0;
        while (safety < 100000) {
            if (!byChannel.containsKey(channel)) return channel;
            channel++;
            safety++;
        }
        LOGGER.error("nextAvailable overflow from start={}", start);
        return channel;
    }

    public O get(int channel) {
        return byChannel.get(channel);
    }

    public boolean isOccupied(int channel) {
        return byChannel.containsKey(channel);
    }

    public Set<Integer> occupiedChannels() {
        return Set.copyOf(byChannel.keySet());
    }

    /** 所有占用者的快照（广播刷新用）。 */
    public Collection<O> owners() {
        return List.copyOf(byChannel.values());
    }

    public int size() {
        return byChannel.size();
    }
}
