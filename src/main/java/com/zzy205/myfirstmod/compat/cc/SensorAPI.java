package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.block.MySensorBlock;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import net.minecraft.nbt.*;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * CC:Tweaked 传感器 Lua API。
 * <p>
 * 使用方式：
 * <pre>{@code
 * local sensors = require("ccnav.sensors")
 * local data = sensors.getAll(1)   -- 获取频道1的全量NBT
 * local x = data.x                 -- 读取具体字段
 * }</pre>
 */
public class SensorAPI implements ILuaAPI {

    // ═══════════════ NBT 缓存（同 tick 内多次路径查询只序列化一次） ═══════════════
    private CompoundTag cachedNBT;
    private int cachedChannel = -1;
    private long cacheGameTime = -1;

    @Override
    public String[] getNames() {
        return new String[0];
    }

    @Override
    public @Nullable String getModuleName() {
        return "ccnav.sensors";
    }

    /**
     * 按路径读取传感器 NBT 中的特定字段。
     * <p>
     * 路径语法：
     * <ul>
     *   <li>{@code "id"} → 顶层 NBT key</li>
     *   <li>{@code "a.b.c"} → 嵌套 CompoundTag</li>
     *   <li>{@code "Items[0]"} → ListTag 索引</li>
     *   <li>{@code "Items[0].Count"} → 列表中元素的字段</li>
     * </ul>
     *
     * @param channel 频道号
     * @param path    NBT 路径
     * @return 字段值，路径不存在时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final Object get(int channel, String path) {
        CompoundTag nbt = getOrLoadNBT(channel);
        if (nbt == null || nbt.isEmpty()) return null;

        return resolvePath(nbt, path);
    }

    /**
     * 获取指定频道传感器的完整 NBT 数据。
     *
     * @param channel 频道号
     * @return NBT 数据转为 Lua Table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAll(int channel) {
        CompoundTag nbt = getOrLoadNBT(channel);
        if (nbt == null || nbt.isEmpty()) return Collections.emptyMap();
        return convertCompoundToMap(nbt);
    }

    // ═══════════════ Layer 1: 快捷方法 ═══════════════

    /**
     * 获取传感器附着方块的世界坐标（已含 Sable 子次元坐标修正）。
     *
     * @param channel 频道号
     * @return {@code {x=100.5, y=64.0, z=200.0}}
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getBlockPos(int channel) {
        CompoundTag nbt = getOrLoadNBT(channel);
        if (nbt == null || nbt.isEmpty()) return Collections.emptyMap();

        Map<String, Double> pos = new LinkedHashMap<>();
        pos.put("x", nbt.getDouble("x"));
        pos.put("y", nbt.getDouble("y"));
        pos.put("z", nbt.getDouble("z"));
        return pos;
    }

    /**
     * 获取传感器附着方块的注册 ID。
     *
     * @param channel 频道号
     * @return 如 {@code "create:speed_controller"}
     */
    @LuaFunction(mainThread = true)
    public final String getBlockId(int channel) {
        CompoundTag nbt = getOrLoadNBT(channel);
        if (nbt == null || nbt.isEmpty()) return "";
        return nbt.getString("id");
    }

    /**
     * 获取导航桌（simulated:navigation_table）的当前导航目标坐标。
     * 从 NBT {@code CurrentTarget} 字段读取，返回 {@code {x, y, z}}。
     *
     * @param channel 频道号
     * @return {@code {x=100.5, y=64.0, z=200.0}}，不存在时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getNavTargetPos(int channel) {
        CompoundTag nbt = getOrLoadNBT(channel);
        if (nbt == null) return Collections.emptyMap();

        Tag target = resolvePathDirect(nbt, "CurrentTarget");
        if (target instanceof ListTag list && list.size() >= 3) {
            Map<String, Double> pos = new LinkedHashMap<>();
            pos.put("x", getDoubleSafe(list.get(0)));
            pos.put("y", getDoubleSafe(list.get(1)));
            pos.put("z", getDoubleSafe(list.get(2)));
            return pos;
        }
        return Collections.emptyMap();
    }

    // ═══════════════ 路径解析 ═══════════════

    /**
     * 解析路径字符串为独立段。例如 {@code "ForgeData.Items[0].Count"} →
     * {@code ["ForgeData", "Items", "[0]", "Count"]}
     */
    static String[] parsePath(String path) {
        List<String> segments = new ArrayList<>();
        int i = 0;
        int len = path.length();
        while (i < len) {
            int dot = path.indexOf('.', i);
            int bracket = path.indexOf('[', i);

            // 先遇到 [ 还是 . ？
            if (bracket >= 0 && (dot < 0 || bracket < dot)) {
                // key 在 [ 之前（如 "Items[0]" → 先取 "Items" 段）
                if (bracket > i) {
                    segments.add(path.substring(i, bracket));
                }
                int end = path.indexOf(']', bracket);
                if (end < 0) break; // malformed, stop
                segments.add("[" + path.substring(bracket + 1, end) + "]");
                i = end + 1;
                if (i < len && path.charAt(i) == '.') i++;
            } else if (dot >= 0) {
                segments.add(path.substring(i, dot));
                i = dot + 1;
            } else {
                segments.add(path.substring(i));
                break;
            }
        }
        return segments.toArray(new String[0]);
    }

    /** 沿路径段在 NBT 树中导航 */
    static Object resolvePath(CompoundTag nbt, String path) {
        String[] segments = parsePath(path);
        Tag current = nbt;
        for (String seg : segments) {
            current = resolveSegment(current, seg);
            if (current == null) return null;
        }
        return convertTag(current);
    }

    /** 处理单个路径段：key 或 [index] */
    private static Tag resolveSegment(Tag current, String segment) {
        if (segment.startsWith("[")) {
            int index = Integer.parseInt(segment.substring(1, segment.length() - 1));
            if (current instanceof ListTag list && index >= 0 && index < list.size()) {
                return list.get(index);
            }
            return null;
        }
        if (current instanceof CompoundTag ct) {
            return ct.get(segment);
        }
        return null;
    }

    // ═══════════════ NBT 获取 & 缓存 ═══════════════

    private CompoundTag getOrLoadNBT(int channel) {
        MySensorBlockEntity sensor = SensorRegistry.get(channel);
        if (sensor == null || sensor.getLevel() == null) return null;

        long now = sensor.getLevel().getGameTime();

        // 同 tick 同频道命中缓存
        if (cachedChannel == channel && cacheGameTime == now && cachedNBT != null) {
            return cachedNBT;
        }

        cachedNBT = MySensorBlock.getAttachedBlockNBT(
                sensor.getLevel(), sensor.getBlockState(), sensor.getBlockPos());
        cachedChannel = channel;
        cacheGameTime = now;
        return cachedNBT;
    }

    // ═══════════════ NBT → Lua Table 转换 ═══════════════

    private static Map<String, Object> convertCompoundToMap(CompoundTag nbt) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : nbt.getAllKeys()) {
            result.put(key, convertTag(nbt.get(key)));
        }
        return result;
    }

    private static Object convertTag(Tag tag) {
        return switch (tag) {
            case CompoundTag ct -> convertCompoundToMap(ct);
            case ListTag lt -> convertList(lt);
            case ByteTag bt -> (int) bt.getAsByte();
            case ShortTag st -> (int) st.getAsShort();
            case IntTag it -> it.getAsInt();
            case LongTag lt -> lt.getAsLong();
            case FloatTag ft -> ft.getAsFloat();
            case DoubleTag dt -> dt.getAsDouble();
            case StringTag st -> st.getAsString();
            default -> tag.getAsString();
        };
    }

    private static List<Object> convertList(ListTag list) {
        List<Object> result = new ArrayList<>(list.size());
        for (Tag tag : list) {
            result.add(convertTag(tag));
        }
        return result;
    }

    // ═══════════════ 辅助方法 ═══════════════

    /** 直接在 NBT 树上沿路径导航（不经过 convertTag），返回原始 Tag */
    private static Tag resolvePathDirect(CompoundTag nbt, String path) {
        String[] segments = parsePath(path);
        Tag current = nbt;
        for (String seg : segments) {
            current = resolveSegment(current, seg);
            if (current == null) return null;
        }
        return current;
    }

    private static double getDoubleSafe(Tag tag) {
        if (tag instanceof NumericTag nt) return nt.getAsDouble();
        return 0.0;
    }
}
