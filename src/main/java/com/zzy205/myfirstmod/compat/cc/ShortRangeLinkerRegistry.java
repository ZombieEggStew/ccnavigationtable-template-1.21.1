package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ShortRangeLinkerBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理体作用域频道注册表 —— Sable 约束链内的「频道 → 设备 BlockEntity」一对一映射。
 * <p>
 * 短程信号链接器（{@link ShortRangeLinkerBlockEntity}）与控制台（{@link ControlDeskBlockEntity}）
 * 共用同一频道空间：按「子次元 UUID → (频道 → BlockEntity)」登记，查询 / 占用判定按调用方
 * 所在物理体的约束链（{@link SableCompat#getConnectedChain}）聚合——同链设备共享频道空间、
 * 冲突顺延，不同物理体上的相同频道号互不可见；链动态变化（轴承连接/断开）由「查询时按链聚合」天然兜底。
 * <p>
 * 线程安全：BE onLoad/setRemoved/tick（服务端主线程）写；Lua 电脑线程（getPeripheral 等）读，
 * 与 {@link GlobalChannelRegistry} 一样用并发容器 + 僵尸清理兜底。
 */
public final class ShortRangeLinkerRegistry {

    /** 子次元 UUID → (频道号 → 设备 BE) */
    private static final Map<UUID, Map<Integer, BlockEntity>> BY_SUBLEVEL = new ConcurrentHashMap<>();

    private ShortRangeLinkerRegistry() {}

    // ═══════════════ 物理体解析 ═══════════════

    /** 设备所在子次元 UUID；不在物理体上返回 null */
    public static UUID subLevelIdOf(BlockEntity be) {
        SubLevel sub = SableCompat.getContainingSubLevel(be);
        return sub != null ? SableCompat.getSubLevelUUID(sub) : null;
    }

    /** 设备所在物理体（含约束链）的全部子次元 UUID 集合 */
    public static Set<UUID> chainUuidsOf(BlockEntity be) {
        Set<UUID> ids = new HashSet<>();
        SubLevel sub = SableCompat.getContainingSubLevel(be);
        if (sub == null) return ids;
        for (SubLevel s : SableCompat.getConnectedChain(sub)) {
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    // ═══════════════ 登记 / 注销 ═══════════════

    /**
     * 以期望频道号注册设备（仅服务端主线程调用）。
     * <ul>
     *   <li>不在物理体上 → 不注册，返回 -1（严格语义：非物理体不链接）</li>
     *   <li>期望频道为负 → 链内最小空闲频道</li>
     *   <li>期望频道已被同链其它设备占用 → 顺延到链内下一个空闲频道</li>
     *   <li>该设备的旧条目（换过子次元 / chunk 重载残留）先清除</li>
     * </ul>
     *
     * @return 实际分配的频道号；不在物理体上返回 -1
     */
    public static int register(int desired, BlockEntity be) {
        UUID id = subLevelIdOf(be);
        if (id == null) return -1;
        cleanup();
        removeAll(be);

        Set<UUID> chain = chainUuidsOf(be);
        int channel = desired < 0 ? nextAvailableIn(chain, 0, be) : desired;
        if (isOccupiedIn(chain, channel, be)) {
            channel = nextAvailableIn(chain, channel + 1, be);
        }
        BY_SUBLEVEL.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(channel, be);
        return channel;
    }

    /** 注销该设备占用的所有频道（setRemoved 时调用）。 */
    public static void unregisterAll(BlockEntity be) {
        cleanup();
        removeAll(be);
    }

    /** 清空全部登记（服务器停止时调用，避免静态注册表跨世界残留）。 */
    public static void clear() {
        BY_SUBLEVEL.clear();
    }

    // ═══════════════ 查询（Lua 电脑线程只读） ═══════════════

    /** 在给定物理体链内按频道找设备 BE；不在链内 / 未占用返回 null */
    public static BlockEntity get(Collection<UUID> chainUuids, int channel) {
        for (UUID id : chainUuids) {
            Map<Integer, BlockEntity> m = BY_SUBLEVEL.get(id);
            if (m == null) continue;
            BlockEntity be = m.get(channel);
            if (be != null && !be.isRemoved()) return be;
        }
        return null;
    }

    /** 在给定物理体链内按频道找短程链接器（只认链接器；频道被控制台占用返回 null） */
    public static ShortRangeLinkerBlockEntity getLinker(Collection<UUID> chainUuids, int channel) {
        BlockEntity be = get(chainUuids, channel);
        return be instanceof ShortRangeLinkerBlockEntity linker ? linker : null;
    }

    /** 链内全部已占用频道号（GUI 跳过占用用，含自己——调用方自行排除自身频道） */
    public static Set<Integer> occupiedChannels(Collection<UUID> chainUuids) {
        Set<Integer> out = new HashSet<>();
        for (UUID id : chainUuids) {
            Map<Integer, BlockEntity> m = BY_SUBLEVEL.get(id);
            if (m != null) out.addAll(m.keySet());
        }
        return out;
    }

    /** 链内全部链接器（bodyLoad 共享开关同步用；控制台不计入） */
    public static List<ShortRangeLinkerBlockEntity> linkersOnChain(Collection<UUID> chainUuids) {
        List<ShortRangeLinkerBlockEntity> out = new ArrayList<>();
        for (UUID id : chainUuids) {
            Map<Integer, BlockEntity> m = BY_SUBLEVEL.get(id);
            if (m == null) continue;
            for (BlockEntity be : m.values()) {
                if (be instanceof ShortRangeLinkerBlockEntity linker) out.add(linker);
            }
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

    /** 僵尸清理：移除已卸载（isRemoved）的设备条目 */
    private static void cleanup() {
        BY_SUBLEVEL.values().removeIf(m -> {
            m.entrySet().removeIf(e -> e.getValue().isRemoved());
            return m.isEmpty();
        });
    }

    /** 清除该设备在所有子次元下的旧条目 */
    private static void removeAll(BlockEntity be) {
        BY_SUBLEVEL.values().removeIf(m -> {
            m.entrySet().removeIf(e -> e.getValue().equals(be));
            return m.isEmpty();
        });
    }

    /** 链内该频道是否已被其它设备占用 */
    private static boolean isOccupiedIn(Set<UUID> chain, int channel, BlockEntity self) {
        for (UUID id : chain) {
            Map<Integer, BlockEntity> m = BY_SUBLEVEL.get(id);
            if (m == null) continue;
            BlockEntity existing = m.get(channel);
            if (existing != null && !existing.equals(self)) return true;
        }
        return false;
    }

    /** 链内从 start 起的下一个空闲频道 */
    private static int nextAvailableIn(Set<UUID> chain, int start, BlockEntity self) {
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
