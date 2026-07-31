package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receiver 棰戦亾娉ㄥ唽锟?鈥旓拷?杩借釜姣忎釜 receiver 锟?banner 棰戦亾鍗犵敤锟?
 * 锟?SensorRegistry 瀹屽叏鐙珛锛屼袱濂楅閬撲簰涓嶅共鎵帮拷?
 */
public final class ReceiverRegistry {
    /** BE 锟?锟?BE 褰撳墠鍗犵敤鐨勯閬撳彿闆嗗悎 */
    private static final Map<RedstoneTransceiverBlockEntity, Set<Integer>> beChannels = new ConcurrentHashMap<>();

    private ReceiverRegistry() {}

    /** BE 鍔犺浇鏃舵敞鍐岋紝锟?bannerData 璇诲彇鎵€鏈夐锟?*/
    public static void registerBE(RedstoneTransceiverBlockEntity be) {
        Set<Integer> channels = getChannelsFromData(be.getBannerData());
        if (!channels.isEmpty()) {
            beChannels.put(be, channels);
        }
    }

    /** BE 鍗歌浇鏃剁Щ锟?*/
    public static void unregisterBE(RedstoneTransceiverBlockEntity be) {
        beChannels.remove(be);
    }

    /** 鏇存柊鎸囧畾 BE 鐨勯閬撳崰鐢紙鏁版嵁鍚屾鏃惰皟鐢級 */
    public static void updateChannels(RedstoneTransceiverBlockEntity be, CompoundTag data) {
        Set<Integer> channels = getChannelsFromData(data);
        if (channels.isEmpty()) {
            beChannels.remove(be);
        } else {
            beChannels.put(be, channels);
        }
    }

    /** 鑾峰彇褰撳墠鎵€鏈夊凡琚崰鐢ㄧ殑棰戦亾锟?*/
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
