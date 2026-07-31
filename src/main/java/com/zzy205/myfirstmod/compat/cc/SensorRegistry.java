package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浼犳劅鍣ㄩ閬撴敞鍐岃〃 鈥旓拷?缁存姢 棰戦亾 锟?PeripheralExtenderBlockEntity 鐨勪竴瀵逛竴鏄犲皠锟?
 * <p>
 * 姣忎釜浼犳劅鍣ㄥ崰鐢ㄤ竴涓閬撳彿锛堢鍙ｆā鍨嬶級銆傛斁缃紶鎰熷櫒鏃惰嚜鍔ㄥ垎閰嶆渶灏忔湭琚崰鐢ㄧ殑棰戦亾鍙凤拷?
 */
public final class SensorRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SensorRegistry.class);

    /** 频道号 → 传感器方块实体 */
    private static final Map<Integer, PeripheralExtenderBlockEntity> registry = new ConcurrentHashMap<>();

    private SensorRegistry() {}

    /**
     * 鑷姩鍒嗛厤鏈€灏忕殑鏈鍗犵敤鐨勯閬撳彿锛屽苟娉ㄥ唽浼犳劅鍣拷?
     * 浼氳烦锟?removed=true 鐨勫兊灏告潯鐩拷?
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
     * 浠ユ寚瀹氶閬撳彿娉ㄥ唽浼犳劅鍣拷?
     * <p>
     * 鑷姩澶勭悊涓夌鎯呭喌锟?
     * <ol>
     *   <li>锟?BE 宸插湪鍏朵粬棰戦亾鏈夋棫鏉＄洰 锟?鍏堟竻闄ゆ棫鏉＄洰锛坈hunk 閲嶈浇瀵艰嚧鐨勫棰戦亾娈嬬暀锟?/li>
     *   <li>鐩爣棰戦亾锟?removed=true 鐨勫兊锟?锟?娓呴櫎鍍靛案鍚庢敞锟?/li>
     *   <li>鐩爣棰戦亾琚叾浠栨椿锟?BE 鍗犵敤 锟?鎵句笅涓€涓彲鐢ㄩ锟?/li>
     * </ol>
     *
     * @param channel 鏈熸湜鐨勯閬撳彿
     * @param be      浼犳劅鍣ㄦ柟鍧楀疄锟?
     * @return 瀹為檯鍒嗛厤鐨勯閬撳彿
     */
    public static int register(int channel, PeripheralExtenderBlockEntity be) {
        // 娓呴櫎锟?BE 鍦ㄥ叾浠栭閬撲笂鐨勬棫鏉＄洰锛坈hunk 閲嶈浇瀵艰嚧鍚屼竴 BE 澶氶閬撴畫鐣欙級
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
