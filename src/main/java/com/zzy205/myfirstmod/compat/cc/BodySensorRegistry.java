package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PitotTubeBlockEntity;
import com.zzy205.myfirstmod.block.StaticPortBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 物理体传感器注册表：Sable sub-level UUID → 传感器集合（类型 + plot 坐标）。
 * <p>
 * 仅服务端主线程访问（BE onLoad/tick/setRemoved 与 {@link SensorSystemAPI#update()} 都在主线程）。
 * 与 {@code GlobalChannelRegistry} / Peripheral Extender 注册表同模式；登记
 * {@link StaticPortBlockEntity}（PRESSURE）与 {@link PitotTubeBlockEntity}（SPEED）两类传感器。
 */
public final class BodySensorRegistry {

    public enum SensorType { SPEED, PRESSURE }

    public record SensorEntry(SensorType type, BlockPos pos) {}

    private static final Map<UUID, Set<SensorEntry>> SENSORS = new HashMap<>();

    private BodySensorRegistry() {}

    /** 注册一个传感器 BE（仅服务端）；不在物理体上则忽略 */
    public static void register(BlockEntity be) {
        if (be.getLevel() == null || be.getLevel().isClientSide) return;
        SubLevel sub = SableCompat.getContainingSubLevel(be);
        UUID id = sub != null ? SableCompat.getSubLevelUUID(sub) : null;
        SensorType type = sensorTypeOf(be);
        if (id == null || type == null) return;
        SENSORS.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(new SensorEntry(type, be.getBlockPos()));
    }

    /** 注销一个传感器 BE（仅服务端）；按 plot 坐标从所有物理体条目中移除（不存在则忽略） */
    public static void unregister(BlockEntity be) {
        if (be.getLevel() == null || be.getLevel().isClientSide) return;
        BlockPos pos = be.getBlockPos();
        for (Iterator<Set<SensorEntry>> it = SENSORS.values().iterator(); it.hasNext(); ) {
            Set<SensorEntry> set = it.next();
            set.removeIf(e -> e.pos().equals(pos));
            if (set.isEmpty()) it.remove();
        }
    }

    /** 所在物理体（含约束链）的传感器清单 */
    public static List<SensorEntry> sensorsOnBody(SubLevel sub) {
        List<SensorEntry> out = new ArrayList<>();
        for (SubLevel s : SableCompat.getConnectedChain(sub)) {
            UUID id = SableCompat.getSubLevelUUID(s);
            Set<SensorEntry> set = id != null ? SENSORS.get(id) : null;
            if (set != null) out.addAll(set);
        }
        return out;
    }

    /** 所在物理体（含约束链）上全部静压孔（PRESSURE）的 plot 坐标（注册顺序） */
    public static List<BlockPos> findPressurePorts(SubLevel sub) {
        List<BlockPos> out = new ArrayList<>();
        for (SensorEntry e : sensorsOnBody(sub))
            if (e.type() == SensorType.PRESSURE) out.add(e.pos());
        return out;
    }

    private static @Nullable SensorType sensorTypeOf(BlockEntity be) {
        if (be instanceof StaticPortBlockEntity) return SensorType.PRESSURE;
        if (be instanceof PitotTubeBlockEntity) return SensorType.SPEED;
        return null;
    }
}
