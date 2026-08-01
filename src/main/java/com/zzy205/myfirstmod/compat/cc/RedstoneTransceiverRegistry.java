package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receiver 频道注册表 —— 追踪每个 receiver 的 banner 频道占用。
 * 与 PeripheralExtenderRegistry 完全独立，两套频道互不干扰。
 */
public final class RedstoneTransceiverRegistry {
    /** BE -> BE 当前占用的频道号集合 */
    private static final Map<RedstoneTransceiverBlockEntity, Set<Integer>> beChannels = new ConcurrentHashMap<>();

    private RedstoneTransceiverRegistry() {}

    /** BE 加载时注册，从 bannerData 读取所有频道 */
    public static void registerBE(RedstoneTransceiverBlockEntity be) {
        Set<Integer> channels = getChannelsFromData(be.getBannerData());
        if (!channels.isEmpty()) {
            beChannels.put(be, channels);
        }
    }

    /** BE 卸载时移除 */
    public static void unregisterBE(RedstoneTransceiverBlockEntity be) {
        beChannels.remove(be);
    }

    /** 更新指定 BE 的频道占用（数据同步时调用） */
    public static void updateChannels(RedstoneTransceiverBlockEntity be, CompoundTag data) {
        Set<Integer> channels = getChannelsFromData(data);
        if (channels.isEmpty()) {
            beChannels.remove(be);
        } else {
            beChannels.put(be, channels);
        }
    }

    /** 获取当前所有已被占用的频道号 */
    public static Set<Integer> getOccupiedChannels() {
        beChannels.entrySet().removeIf(e -> e.getKey().isRemoved());
        Set<Integer> all = new HashSet<>();
        for (Set<Integer> chs : beChannels.values()) {
            all.addAll(chs);
        }
        return all;
    }

    private static Set<Integer> getChannelsFromData(CompoundTag data) {
        Set<Integer> channels = new HashSet<>();
        if (data.isEmpty()) return channels;
        ListTag list = data.getList("Channels", Tag.TAG_INT);
        for (int i = 0; i < list.size(); i++) {
            channels.add(((net.minecraft.nbt.IntTag) list.get(i)).getAsInt());
        }
        return channels;
    }
}
