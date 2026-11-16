package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.EngineCoreBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理体作用域<b>引擎</b>注册表：Sable sub-level UUID → 该物理体内的 {@code engine_core} 各节 BE。
 * <p>
 * 目的只有一个：让 {@link SensorSystemAPI} 提供 {@code ss.getEngine()} 快捷方法，脚本开头
 * 一次拿到机体上引擎的 {@code ccpe:engine} 外设（外设类型注册见
 * {@code compat/cc/CCPeripheralCapabilities}，非 controller 节委托给整条引擎的 controller，
 * 见 {@link EngineCoreBlockEntity#getPeripheral()}）。
 * <p>
 * <b>成本模型（刻意做成最轻）</b>：
 * <ul>
 *   <li>写入只在方块生命周期事件发生：BE {@code onLoad} / 放置组网后 {@code updateConnectivity} /
 *       {@code setRemoved} —— <b>不每 tick 刷新</b>（与 {@link BodySensorRegistry} 的传感器不同，后者
 *       每 tick 被 {@code update()} 聚合）；</li>
 *   <li>查询 = 遍历所在物理体约束链的 UUID 集合 + 哈希表查找，<b>无 plot 包围盒扫描</b>；
 *       由 {@code ss.getEngine()}（{@code mainThread = true}）在调用时执行，脚本通常只调一次；</li>
 *   <li>登记所有核心节（含非 controller），查询时按 {@link EngineCoreBlockEntity#getControllerBE()}
 *       去重成「引擎组」，所以 controller 易主/延长不需要重登记。</li>
 * </ul>
 * 线程模型：登记/注销/查询都在服务端主线程（BE 生命周期与 {@code mainThread = true} 的 Lua 调用线程
 * 都是主线程），容器仍用并发实现兜底跨线程读。
 */
public final class BodyEngineRegistry {

    /** 子次元 UUID → 该子次元内的引擎核心节（BE 引用，查询时按 controller 去重） */
    private static final Map<UUID, Set<EngineCoreBlockEntity>> ENGINES = new ConcurrentHashMap<>();

    private BodyEngineRegistry() {}

    // ═══════════════ 登记 / 注销 ═══════════════

    /**
     * 登记一个引擎核心节（服务端；不在物理体上或非引擎核心返回 false）。
     * 同一 BE 重复登记是幂等的（Set 语义）；子次元 UUID 变化（装配/拆卸/重载）请先 {@link #unregister}。
     *
     * @return 是否已登记到某个物理体
     */
    public static boolean register(BlockEntity be) {
        if (be == null || be.isRemoved()) return false;
        if (!(be instanceof EngineCoreBlockEntity core)) return false;
        if (core.getLevel() == null || core.getLevel().isClientSide) return false;
        UUID id = subLevelIdOf(core);
        if (id == null) return false;
        ENGINES.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(core);
        return true;
    }

    /** 注销该引擎核心节（setRemoved 时调用；不存在则忽略） */
    public static void unregister(BlockEntity be) {
        if (be == null) return;
        ENGINES.values().forEach(set -> set.remove(be));
        ENGINES.values().removeIf(Set::isEmpty);
    }

    /** 服务器停止（关世界/回主菜单）：清空注册表，避免跨世界残留（对齐 {@link ShortRangeLinkerRegistry#clear()}） */
    public static void clear() {
        ENGINES.clear();
    }

    // ═══════════════ 查询 ═══════════════

    /**
     * 给定物理体链内<b>恰好一个</b>引擎组时返回其 controller 的 BE；0 个或 ≥2 个（歧义）返回 null。
     * <p>
     * 语义刻意保守：双发 / 多发机体上返回 null（宁可让脚本显式失败，也不静默把控制全推给某个引擎）。
     * 链上引擎组数量按 controller BE 身份去重；未组网完成（controller 为 null）的孤立核心节
     * 各自算作一个组，因此组网瞬态期间可能短暂返回 null，下一 tick 恢复。
     */
    public static @Nullable EngineCoreBlockEntity singleEngineControllerOnChain(Collection<UUID> chainUuids) {
        if (chainUuids == null || chainUuids.isEmpty()) return null;
        Set<EngineCoreBlockEntity> controllers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (UUID id : chainUuids) {
            Set<EngineCoreBlockEntity> set = ENGINES.get(id);
            if (set == null) continue;
            for (EngineCoreBlockEntity core : set) {
                if (core == null || core.isRemoved()) continue;
                EngineCoreBlockEntity controller = core.getControllerBE();
                if (controller == null || controller.isRemoved()) continue;
                controllers.add(controller);
                if (controllers.size() > 1) return null;   // 多个引擎组 → 歧义
            }
        }
        return controllers.size() == 1 ? controllers.iterator().next() : null;
    }

    // ═══════════════ 内部工具 ═══════════════

    /** 引擎核心节所在子次元 UUID；不在物理体上返回 null */
    private static @Nullable UUID subLevelIdOf(BlockEntity be) {
        SubLevel sub = SableCompat.getContainingSubLevel(be);
        return sub != null ? SableCompat.getSubLevelUUID(sub) : null;
    }
}
