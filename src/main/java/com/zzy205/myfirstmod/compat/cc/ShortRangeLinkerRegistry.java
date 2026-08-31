package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ShortRangeLinkerBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短程信号链接器频道注册表 —— 物理体作用域（Sable 约束链）内的「频道 → 链接器」一对一映射。
 * <p>
 * 与全局 {@link GlobalChannelRegistry} 完全独立：按「子次元 UUID → (频道 → 链接器)」登记，
 * 查询 / 占用判定按调用方所在物理体的约束链（{@link SableCompat#getConnectedChain}）聚合——
 * 同链链接器共享频道空间，不同物理体上的相同频道号互不可见；链动态变化（轴承连接/断开）
 * 由「查询时按链聚合」天然兜底。
 * <p>
 * 线程安全：BE onLoad/setRemoved/tick（服务端主线程）写；Lua 电脑线程（getPeripheral 等）读，
 * 与 {@link GlobalChannelRegistry} 一样用并发容器 + 僵尸清理兜底。
 */
public final class ShortRangeLinkerRegistry {

    /** 子次元 UUID → (频道号 → 链接器) */
    private static final Map<UUID, Map<Integer, ShortRangeLinkerBlockEntity>> BY_SUBLEVEL = new ConcurrentHashMap<>();

    private ShortRangeLinkerRegistry() {}

    // ═══════════════ 物理体解析 ═══════════════

    /** 链接器所在子次元 UUID；不在物理体上返回 null */
    public static UUID subLevelIdOf(ShortRangeLinkerBlockEntity linker) {
        SubLevel sub = SableCompat.getContainingSubLevel(linker);
        return sub != null ? SableCompat.getSubLevelUUID(sub) : null;
    }

    /** 链接器所在物理体（含约束链）的全部子次元 UUID 集合 */
    public static Set<UUID> chainUuidsOf(ShortRangeLinkerBlockEntity linker) {
        Set<UUID> ids = new HashSet<>();
        SubLevel sub = SableCompat.getContainingSubLevel(linker);
        if (sub == null) return ids;
        for (SubLevel s : SableCompat.getConnectedChain(sub)) {
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    // ═══════════════ 登记 / 注销 ═══════════════

    /**
     * 以期望频道号注册链接器（仅服务端主线程调用）。
     * <ul>
     *   <li>不在物理体上 → 不注册，返回 -1（严格语义：非物理体不链接）</li>
     *   <li>期望频道为负 → 链内最小空闲频道</li>
     *   <li>期望频道已被同链其它链接器占用 → 顺延到链内下一个空闲频道</li>
     *   <li>该链接器的旧条目（换过子次元 / chunk 重载残留）先清除</li>
     * </ul>
     *
     * @return 实际分配的频道号；不在物理体上返回 -1
     */
    public static int register(int desired, ShortRangeLinkerBlockEntity linker) {
        UUID id = subLevelIdOf(linker);
        if (id == null) return -1;
        cleanup();
        removeAll(linker);

        Set<UUID> chain = chainUuidsOf(linker);
        int channel = desired < 0 ? nextAvailableIn(chain, 0, linker) : desired;
        if (isOccupiedIn(chain, channel, linker)) {
            channel = nextAvailableIn(chain, channel + 1, linker);
        }
        BY_SUBLEVEL.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(channel, linker);
        return channel;
    }

    /** 注销该链接器占用的所有频道（setRemoved 时调用）。 */
    public static void unregisterAll(ShortRangeLinkerBlockEntity linker) {
        cleanup();
        removeAll(linker);
    }

    /** 清空全部登记（服务器停止时调用，避免静态注册表跨世界残留）。 */
    public static void clear() {
        BY_SUBLEVEL.clear();
    }

    // ═══════════════ 查询（Lua 电脑线程只读） ═══════════════

    /** 在给定物理体链内按频道找链接器；不在链内 / 未占用返回 null */
    public static ShortRangeLinkerBlockEntity get(Collection<UUID> chainUuids, int channel) {
        for (UUID id : chainUuids) {
            Map<Integer, ShortRangeLinkerBlockEntity> m = BY_SUBLEVEL.get(id);
            if (m == null) continue;
            ShortRangeLinkerBlockEntity be = m.get(channel);
            if (be != null && !be.isRemoved()) return be;
        }
        return null;
    }

    /** 链内全部已占用频道号（GUI 跳过占用用，含自己——调用方自行排除自身频道） */
    public static Set<Integer> occupiedChannels(Collection<UUID> chainUuids) {
        Set<Integer> out = new HashSet<>();
        for (UUID id : chainUuids) {
            Map<Integer, ShortRangeLinkerBlockEntity> m = BY_SUBLEVEL.get(id);
            if (m != null) out.addAll(m.keySet());
        }
        return out;
    }

    /** 链内全部链接器（bodyLoad 共享开关同步用） */
    public static List<ShortRangeLinkerBlockEntity> linkersOnChain(Collection<UUID> chainUuids) {
        List<ShortRangeLinkerBlockEntity> out = new ArrayList<>();
        for (UUID id : chainUuids) {
            Map<Integer, ShortRangeLinkerBlockEntity> m = BY_SUBLEVEL.get(id);
            if (m != null) out.addAll(m.values());
        }
        return out;
    }

    /** 链内是否有链接器开启「加载物理体」（bodyLoad OR 自愈用） */
    public static boolean anyBodyLoadOn(Collection<UUID> chainUuids) {
        for (ShortRangeLinkerBlockEntity be : linkersOnChain(chainUuids)) {
            if (be.isBodyLoad()) return true;
        }
        return false;
    }

    // ═══════════════ 内部工具 ═══════════════

    /** 僵尸清理：移除已卸载（isRemoved）的链接器条目 */
    private static void cleanup() {
        BY_SUBLEVEL.values().removeIf(m -> {
            m.entrySet().removeIf(e -> e.getValue().isRemoved());
            return m.isEmpty();
        });
    }

    /** 清除该链接器在所有子次元下的旧条目 */
    private static void removeAll(ShortRangeLinkerBlockEntity linker) {
        BY_SUBLEVEL.values().removeIf(m -> {
            m.entrySet().removeIf(e -> e.getValue().equals(linker));
            return m.isEmpty();
        });
    }

    /** 链内该频道是否已被其它链接器占用 */
    private static boolean isOccupiedIn(Set<UUID> chain, int channel, ShortRangeLinkerBlockEntity self) {
        for (UUID id : chain) {
            Map<Integer, ShortRangeLinkerBlockEntity> m = BY_SUBLEVEL.get(id);
            if (m == null) continue;
            ShortRangeLinkerBlockEntity existing = m.get(channel);
            if (existing != null && !existing.equals(self)) return true;
        }
        return false;
    }

    /** 链内从 start 起的下一个空闲频道 */
    private static int nextAvailableIn(Set<UUID> chain, int start, ShortRangeLinkerBlockEntity self) {
        int channel = start;
        int safety = 0;
        while (safety < 100000) {
            if (!isOccupiedIn(chain, channel, self)) return channel;
            channel++;
            safety++;
        }
        return channel;
    }
}
