package com.zzy205.myfirstmod.client;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端已加载 Monitor 方块坐标注册表。
 * <p>
 * 独立命中检测（{@link MonitorHitDetector}）不再依赖原版 {@code mc.hitResult}，
 * 而是遍历本注册表枚举候选 Monitor。Monitor 数量少，每帧遍历开销可忽略；
 * 且每个坐标保留 BlockEntity 的 plot 坐标语义，兼容 Sable 子次元。
 * <p>
 * 仅客户端线程访问；使用线程安全集合以应对加载/卸载与渲染帧的交错。
 */
public final class MonitorClientRegistry {

    private static final Set<BlockPos> LOADED = ConcurrentHashMap.newKeySet();

    private MonitorClientRegistry() {}

    public static void add(BlockPos pos) { LOADED.add(pos); }

    public static void remove(BlockPos pos) { LOADED.remove(pos); }

    public static Collection<BlockPos> loaded() { return LOADED; }
}
