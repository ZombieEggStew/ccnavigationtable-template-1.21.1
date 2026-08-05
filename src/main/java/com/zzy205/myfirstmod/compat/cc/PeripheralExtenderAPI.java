package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.block.PeripheralExtenderBlock;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * CC:Tweaked 传感器 Lua API。
 * <p>
 * 使用方式：
 * <pre>{@code
 * local pe = require("ccpe.pe")
 * local data = pe.getAll(1)   -- 获取频道1的全量NBT
 * local x = data.x            -- 读取具体字段
 * }</pre>
 */
public class PeripheralExtenderAPI implements ILuaAPI {

    @Override
    public String[] getNames() { return new String[0]; }

    @Override
    public @Nullable String getModuleName() { return "ccpe.pe"; }

    // ═══════════════ NBT 通用读取（BE 缓存，mainThread=false） ═══════════════

    @LuaFunction
    public final Object get(int channel, String path) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;
        CompoundTag nbt = sensor.getCachedAttachedCompoundTag();
        if (nbt.isEmpty()) return null;
        return resolvePath(nbt, path);
    }

    @LuaFunction
    public final Map<String, Object> getAll(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        CompoundTag nbt = sensor.getCachedAttachedCompoundTag();
        if (nbt.isEmpty()) return Collections.emptyMap();
        return convertCompoundToMap(nbt);
    }

    // ═══════════════ 方块信息（BE 缓存，mainThread=false） ═══════════════

    @LuaFunction
    public final String getBlockId(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return "";
        BlockEntity be = sensor.getCachedAttachedBE();
        if (be == null) return "";
        return be.getBlockState().getBlock().builtInRegistryHolder().key().location().toString();
    }

    @LuaFunction
    public final Map<String, Double> getBlockPos(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        SubLevel sub = sensor.getCachedSubLevel();
        if (sub == null) return Collections.emptyMap();
        Vec3 pos = SableCompat.getSubLevelWorldPos(sub);
        if (pos == null) return Collections.emptyMap();
        return vec3ToMap(pos);
    }

    // ═══════════════ NavTable 导航数据（BE 缓存，mainThread=false） ═══════════════

    @LuaFunction
    public final @Nullable Map<String, Double> getNavTargetPos(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;
        Vec3 target = sensor.getCachedNavTargetPos();
        return target != null ? vec3ToMap(target) : null;
    }

    @LuaFunction
    public final Map<String, Double> getNavSelfPos(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        return vec3ToMap(sensor.getCachedNavSelfPos());
    }

    @LuaFunction
    public final double getNavDistance(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return 0.0;
        return sensor.getCachedNavDistance();
    }

    @LuaFunction
    public final float getNavRelativeAngle(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return 0.0f;
        return sensor.getCachedNavRelativeAngle();
    }

    @LuaFunction
    public final double getNavRelativeAngleRad(int channel) {
        return Math.toRadians(getNavRelativeAngle(channel));
    }

    // ═══════════════ 红石（传感器自有字段，mainThread=false 读 / true 写） ═══════════════

    @LuaFunction
    public final int getRedstoneOutput(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        return sensor != null ? sensor.getRedstoneOutput() : 0;
    }

    @LuaFunction
    public final int getRedstoneInput(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        return sensor != null ? sensor.getRedstoneInput() : 0;
    }

    @LuaFunction(mainThread = true)
    public final void setRedstoneOutput(int channel, int signal) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor != null) sensor.setRedstoneOutput(Math.clamp(signal, 0, 15));
    }

    // ═══════════════ Sable 物理数据（SubLevel 缓存，mainThread=false） ═══════════════

    @LuaFunction
    public final Map<String, Double> getPhysicsPos(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        SubLevel sub = sensor.getCachedSubLevel();
        if (sub == null) return Collections.emptyMap();
        Vec3 pos = SableCompat.getSubLevelWorldPos(sub);
        if (pos == null) return Collections.emptyMap();
        return vec3ToMap(pos);
    }

    @LuaFunction
    public final Map<String, Double> getPhysicsVelocity(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        BlockEntity be = sensor.getCachedAttachedBE();
        if (!(be instanceof dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity))
            return Collections.emptyMap();
        Level level = sensor.getLevel();
        if (level == null) return Collections.emptyMap();
        Vec3 vel = SableCompat.getVelocity(level, sensor.getBlockPos());
        if (vel == null) return Collections.emptyMap();
        return vec3ToMap(vel);
    }

    @LuaFunction
    public final Map<String, Double> getPhysicsAirVelocity(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        BlockEntity be = sensor.getCachedAttachedBE();
        if (!(be instanceof dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity))
            return Collections.emptyMap();
        Level level = sensor.getLevel();
        if (level == null) return Collections.emptyMap();
        Vec3 vel = SableCompat.getAirVelocity(level, sensor.getBlockPos());
        if (vel == null) return Collections.emptyMap();
        return vec3ToMap(vel);
    }

    @LuaFunction
    public final Map<String, Double> getPhysicsAngularVelocity(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        BlockEntity be = sensor.getCachedAttachedBE();
        if (!(be instanceof dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity))
            return Collections.emptyMap();
        SubLevel sub = sensor.getCachedSubLevel();
        Level level = sensor.getLevel();
        if (sub == null || level == null) return Collections.emptyMap();
        Vec3 angVel = SableCompat.getAngularVelocity(level, sub);
        if (angVel == null) return Collections.emptyMap();
        return vec3ToMap(angVel);
    }

    @LuaFunction
    public final Map<String, Double> getPhysicsOrientation(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        SubLevel sub = sensor.getCachedSubLevel();
        if (sub == null) return Collections.emptyMap();
        double[] quat = SableCompat.getSubLevelOrientation(sub);
        if (quat == null) return Collections.emptyMap();
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("x", quat[0]); result.put("y", quat[1]);
        result.put("z", quat[2]); result.put("w", quat[3]);
        return result;
    }



    @LuaFunction
    public final Map<String, Double> getPhysicsCenterOfMass(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return Collections.emptyMap();
        SubLevel sub = sensor.getCachedSubLevel();
        if (sub == null) return Collections.emptyMap();
        Vec3 com = SableCompat.getCenterOfMass(sub);
        if (com == null) return Collections.emptyMap();
        return vec3ToMap(com);
    }

    @LuaFunction
    public final Double getPhysicsMass(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;
        SubLevel sub = sensor.getCachedSubLevel();
        if (sub == null) return null;
        return SableCompat.getMass(sub);
    }

    @LuaFunction
    public final Double getPhysicsChainMass(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;
        SubLevel sub = sensor.getCachedSubLevel();
        if (sub == null) return null;
        return SableCompat.getChainMass(sub);
    }

    @LuaFunction
    public final Double getPhysicsGravityForce(int channel) {
        Double mass = getPhysicsMass(channel);
        return mass != null && mass > 0 ? mass * 11.0 : null;
    }

    @LuaFunction
    public final Double getPhysicsChainGravityForce(int channel) {
        Double mass = getPhysicsChainMass(channel);
        return mass != null && mass > 0 ? mass * 11.0 : null;
    }


    private static Map<String, Double> vec3ToMap(Vec3 v) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("x", v.x); result.put("y", v.y); result.put("z", v.z);
        return result;
    }

    // ═══════════════ 外设代理（需 NeoForge Capability 查询，mainThread=true） ═══════════════

    @LuaFunction(mainThread = true)
    public final @Nullable Object getPeripheral(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;
        BlockEntity be = sensor.getCachedAttachedBE();
        if (be == null) return null;
        if (be instanceof IPeripheral p) return p;
        if (sensor.getLevel() == null) return null;
        Direction side = getSensorSide(sensor.getBlockState());
        return sensor.getLevel().getCapability(PeripheralCapability.get(),
                PeripheralExtenderBlock.getAttachedPos(sensor.getBlockState(), sensor.getBlockPos()), side);
    }

    /**
     * 计算传感器在附着方块上的"面"（从附着方块的视角）。
     */
    private static Direction getSensorSide(BlockState state) {
        AttachFace face = state.getValue(PeripheralExtenderBlock.FACE);
        return switch (face) {
            case FLOOR -> Direction.UP;      // 传感器在地面 → 附着方块的下方 → 从方块看是 UP
            case CEILING -> Direction.DOWN;   // 传感器在天花板 → 附着方块的上方 → 从方块看是 DOWN
            case WALL -> state.getValue(PeripheralExtenderBlock.FACING); // 传感器在墙上 → FACING 就是传感器的朝向，也是从方块看的方向
        };
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
            case LongTag l -> l.getAsLong();
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

    private static double getDoubleSafe(Tag tag) {
        if (tag instanceof NumericTag nt) return nt.getAsDouble();
        return 0.0;
    }
}
