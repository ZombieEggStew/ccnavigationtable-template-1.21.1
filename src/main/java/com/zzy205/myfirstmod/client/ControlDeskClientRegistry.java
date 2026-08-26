package com.zzy205.myfirstmod.client;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端已加载 controlDesk 方块坐标注册表。
 * <p>
 * monitor_2 表面小 Monitor 的独立命中检测（{@link Monitor2HitDetector}）不再依赖原版
 * {@code mc.hitResult}（monitor_2 屏幕在桌面碰撞体上方，准星瞄准屏幕时原版可能 MISS），
 * 而是遍历本注册表枚举候选控制台。控制台数量少，每帧遍历开销可忽略；
 * 每个坐标保留 BlockEntity 的 plot 坐标语义，兼容 Sable 子次元。
 * <p>
 * 仅客户端线程访问；使用线程安全集合以应对加载/卸载与渲染帧的交错。
 */
public final class ControlDeskClientRegistry {

    private static final Set<BlockPos> LOADED = ConcurrentHashMap.newKeySet();

    private ControlDeskClientRegistry() {}

    public static void add(BlockPos pos) { LOADED.add(pos); }

    public static void remove(BlockPos pos) { LOADED.remove(pos); }

    public static Collection<BlockPos> loaded() { return LOADED; }
}
