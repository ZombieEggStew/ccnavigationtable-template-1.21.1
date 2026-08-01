package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.block.PeripheralExtenderBlock;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlock;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity;
import dev.simulated_team.simulated.util.SimMathUtils;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * CC:Tweaked 传感器 Lua API。
 * <p>
 * 使用方式：
 * <pre>{@code
 * local pe = require("ccnav.pe")
 * local data = pe.getAll(1)   -- 获取频道1的全量NBT
 * local x = data.x            -- 读取具体字段
 * }</pre>
 */
public class PeripheralExtenderAPI implements ILuaAPI {

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
        return "ccnav.pe";
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
     * 直接调用 {@link NavTableBlockEntity} 公开 API，不经过 NBT 序列化。
     *
     * @param channel 频道号
     * @return {@code {x=100.5, y=64.0, z=200.0}}，不存在时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getNavTargetPos(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return Collections.emptyMap();

        // 从 NavTableBlockEntity 直接读取投影后的目标位置（含 Sable 子次元坐标修正）
        Vec3 target = navTable.getTargetPosition(true);
        if (target == null) return Collections.emptyMap();

        Map<String, Double> pos = new LinkedHashMap<>();
        pos.put("x", target.x);
        pos.put("y", target.y);
        pos.put("z", target.z);
        return pos;
    }

    /**
     * 获取导航桌（simulated:navigation_table）当前指针的相对角度。
     * 直接调用 {@link NavTableBlockEntity} 公开 API 实时计算，
     * 与 {@code NavTableBlockEntity.updateCurrentAngle()} 使用相同公式。
     * <p>
     * 角度是导航桌指针相对于方块朝向的偏转角度（0-360°），
     * 融合了子次元姿态和方块朝向的修正。
     *
     * @param channel 频道号
     * @return 角度（度），不存在或非导航桌时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Double getNavRelativeAngle(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return null;

        Vec3 targetPos = navTable.getTargetPosition(true);
        if (targetPos == null) return null;

        // 以下计算逻辑与 NavTableBlockEntity.updateCurrentAngle() 一致
        Vec3 originPos = navTable.getProjectedSelfPos();
        Vec3 directionToTarget = targetPos.subtract(originPos).normalize();

        Quaterniond sublevelRot = navTable.getSublevelRot();
        Quaternionf rotation = navTable.getBlockState().getValue(NavTableBlock.FACING).getRotation();

        directionToTarget = SimMathUtils.rotateQuat(directionToTarget, sublevelRot);
        directionToTarget = SimMathUtils.rotateQuat(directionToTarget, rotation);
        directionToTarget = new Vec3(directionToTarget.x, 0, directionToTarget.z);

        return (360.0 + AngleHelper.deg(Math.atan2(directionToTarget.z, directionToTarget.x))) % 360.0;
    }

    // ═══════════════ 导航桌制导数据 ═══════════════

    /**
     * 获取导航桌自身在世界空间中的投影坐标（发射点参考）。
     *
     * @param channel 频道号
     * @return {@code {x, y, z}}，非导航桌时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getNavSelfPos(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return Collections.emptyMap();

        Vec3 self = navTable.getProjectedSelfPos();
        Map<String, Double> pos = new LinkedHashMap<>();
        pos.put("x", self.x);
        pos.put("y", self.y);
        pos.put("z", self.z);
        return pos;
    }

    /**
     * 获取导航桌到目标的直线距离（米）。
     *
     * @param channel 频道号
     * @return 距离（米），不存在或非导航桌时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Double getNavDistance(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return null;

        Vec3 target = navTable.getTargetPosition(true);
        if (target == null) return null;

        return navTable.getProjectedSelfPos().distanceTo(target);
    }

    /**
     * 获取导航桌到目标的水平距离（XZ 平面，忽略 Y 轴，米）。
     *
     * @param channel 频道号
     * @return 水平距离（米），不存在或非导航桌时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Double getNavHorizontalDistance(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return null;

        Vec3 target = navTable.getTargetPosition(true);
        if (target == null) return null;

        Vec3 self = navTable.getProjectedSelfPos();
        double dx = target.x - self.x;
        double dz = target.z - self.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 获取导航桌指向目标的世界坐标系归一化方向向量。
     * <p>
     * 未经过子次元旋转和方块朝向修正，直接是全局坐标系下的方向。
     * 适合用于导弹的全局制导向量。
     *
     * @param channel 频道号
     * @return {@code {dx, dy, dz}}，不存在或非导航桌时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getNavDirection(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return Collections.emptyMap();

        Vec3 target = navTable.getTargetPosition(true);
        if (target == null) return Collections.emptyMap();

        Vec3 dir = target.subtract(navTable.getProjectedSelfPos()).normalize();
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("dx", dir.x);
        result.put("dy", dir.y);
        result.put("dz", dir.z);
        return result;
    }

    /**
     * 获取导航桌到目标的水平方位角（世界坐标系，度）。
     * <p>
     * 从导航桌正上方俯视：0° = +Z（南），90° = -X（西），180° = -Z（北），270° = +X（东）。
     * 适合导弹水平转向制导。
     *
     * @param channel 频道号
     * @return 方位角（0-360°），不存在或非导航桌时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Double getNavBearing(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return null;

        Vec3 target = navTable.getTargetPosition(true);
        if (target == null) return null;

        Vec3 self = navTable.getProjectedSelfPos();
        double dx = target.x - self.x;
        double dz = target.z - self.z;
        // atan2(dx, dz): 0° = +Z, CW
        return (360.0 + Math.toDegrees(Math.atan2(dx, dz))) % 360.0;
    }

    /**
     * 获取导航桌到目标的仰角（度）。
     * <p>
     * 水平面以上为正（+90° = 正上方），水平面以下为负（-90° = 正下方）。
     * 适合导弹俯仰制导。
     *
     * @param channel 频道号
     * @return 仰角（-90°~+90°），不存在或非导航桌时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Double getNavElevation(int channel) {
        NavTableBlockEntity navTable = getNavTableBE(channel);
        if (navTable == null) return null;

        Vec3 target = navTable.getTargetPosition(true);
        if (target == null) return null;

        Vec3 self = navTable.getProjectedSelfPos();
        double dx = target.x - self.x;
        double dy = target.y - self.y;
        double dz = target.z - self.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        return Math.toDegrees(Math.atan2(dy, horizontalDist));
    }

    /**
     * 获取指定频道传感器当前的红石输出信号。
     *
     * @param channel 频道号
     * @return 0-15 的模拟信号强度
     */
    @LuaFunction(mainThread = true)
    public final int getRedstoneOutput(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        return sensor != null ? sensor.getRedstoneOutput() : 0;
    }

    /**
     * 读取指定频道传感器位置接收到的最强红石输入信号。
     *
     * @param channel 频道号
     * @return 0-15 的红石信号强度
     */
    @LuaFunction(mainThread = true)
    public final int getRedstoneInput(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        return sensor != null ? sensor.getRedstoneInput() : 0;
    }

    /**
     * 设置指定频道传感器的红石输出信号（0-15），实现无线红石控制。
     * 传感器方块会像红石源一样向相邻方块输出该信号。
     *
     * <pre>{@code
     * pe.setRedstoneOutput(1, 15)  -- 频道 1 输出满信号
     * pe.setRedstoneOutput(1, 0)   -- 关闭
     * }</pre>
     *
     * @param channel 频道号
     * @param signal  0-15 的红石信号强度（自动钳位）
     */
    @LuaFunction(mainThread = true)
    public final void setRedstoneOutput(int channel, int signal) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor != null) {
            sensor.setRedstoneOutput(Math.clamp(signal, 0, 15));
        }
    }

    // ═══════════════ Layer 2: Sable 物理数据 ═══════════════

    /**
     * 获取传感器附着物理结构在世界空间中的实时位置（通过 Sable 投影）。
     *
     * @param channel 频道号
     * @return {@code {x, y, z}}，不在 Sable 子次元中时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getPhysicsPos(int channel) {
        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedPos == null) return Collections.emptyMap();

        Vec3 worldPos = SableCompat.projectOutOfSubLevel(ctx.level, ctx.attachedPos);
        if (worldPos == null) return Collections.emptyMap();

        Map<String, Double> result = new LinkedHashMap<>();
        result.put("x", worldPos.x);
        result.put("y", worldPos.y);
        result.put("z", worldPos.z);
        return result;
    }

    /**
     * 获取传感器附着物理结构的线速度。
     * 仅当附着方块为 {@code simulated:velocity_sensor} 时可用。
     *
     * @param channel 频道号
     * @return {@code {vx, vy, vz}}（m/s），不满足条件时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getPhysicsVelocity(int channel) {
        if (!isAttachedBlock(channel, "simulated:velocity_sensor")) return Collections.emptyMap();

        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedPos == null) return Collections.emptyMap();

        Vec3 vel = SableCompat.getVelocity(ctx.level, ctx.attachedPos);
        if (vel == null) return Collections.emptyMap();

        Map<String, Double> result = new LinkedHashMap<>();
        result.put("vx", vel.x);
        result.put("vy", vel.y);
        result.put("vz", vel.z);
        return result;
    }

    /**
     * 获取传感器附着物理结构的角速度。
     * 仅当附着方块为 {@code simulated:velocity_sensor} 时可用。
     *
     * @param channel 频道号
     * @return {@code {wx, wy, wz}}（rad/s），不满足条件时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getPhysicsAngularVelocity(int channel) {
        if (!isAttachedBlock(channel, "simulated:velocity_sensor")) return Collections.emptyMap();

        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedBE == null) return Collections.emptyMap();

        SubLevel subLevel = SableCompat.getContainingSubLevel(ctx.attachedBE);
        if (subLevel == null) return Collections.emptyMap();

        Vec3 angVel = SableCompat.getAngularVelocity(ctx.level, subLevel);
        if (angVel == null) return Collections.emptyMap();

        Map<String, Double> result = new LinkedHashMap<>();
        result.put("wx", angVel.x);
        result.put("wy", angVel.y);
        result.put("wz", angVel.z);
        return result;
    }

    /**
     * 获取传感器附着物理结构的四元数朝向。
     *
     * @param channel 频道号
     * @return {@code {x, y, z, w}}，不在 Sable 子次元中时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getPhysicsOrientation(int channel) {
        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedBE == null) return Collections.emptyMap();

        SubLevel subLevel = SableCompat.getContainingSubLevel(ctx.attachedBE);
        if (subLevel == null) return Collections.emptyMap();

        double[] quat = SableCompat.getSubLevelOrientation(subLevel);
        if (quat == null) return Collections.emptyMap();

        Map<String, Double> result = new LinkedHashMap<>();
        result.put("x", quat[0]);
        result.put("y", quat[1]);
        result.put("z", quat[2]);
        result.put("w", quat[3]);
        return result;
    }

    /**
     * 获取传感器附着物理结构的总质量。
     *
     * @param channel 频道号
     * @return 质量（kg），不在 Sable 子次元中时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final Double getPhysicsMass(int channel) {
        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedBE == null) return null;

        SubLevel subLevel = SableCompat.getContainingSubLevel(ctx.attachedBE);
        if (subLevel == null) return null;

        return SableCompat.getMass(subLevel);
    }

    /**
     * 获取传感器附着物理结构的质心位置（局部坐标）。
     *
     * @param channel 频道号
     * @return {@code {x, y, z}}，不在 Sable 子次元中时返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Double> getPhysicsCenterOfMass(int channel) {
        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedBE == null) return Collections.emptyMap();

        SubLevel subLevel = SableCompat.getContainingSubLevel(ctx.attachedBE);
        if (subLevel == null) return Collections.emptyMap();

        Vec3 com = SableCompat.getCenterOfMass(subLevel);
        if (com == null) return Collections.emptyMap();

        Map<String, Double> result = new LinkedHashMap<>();
        result.put("x", com.x);
        result.put("y", com.y);
        result.put("z", com.z);
        return result;
    }

    /**
     * 获取传感器附着物理结构所受的重力大小。
     * Sable 没有公开的重力 API，此处按 {@code mass × 11.0 m/s²} 计算。
     *
     * @param channel 频道号
     * @return 重力（N），不在 Sable 子次元中时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final Double getPhysicsGravityForce(int channel) {
        Double mass = getPhysicsMass(channel);
        return mass != null ? mass * 11.0 : null;
    }

    // ═══════════════ 外设代理 ═══════════════

    /**
     * 获取传感器附着方块的 CC:Tweaked 外设对象。
     * 通过频道无线访问附着方块的外设方法。
     *
     * <pre>{@code
     * local p = pe.getPeripheral(1)
     * if p then
     *     print(p.getSpeed())
     * end
     * }</pre>
     *
     * @param channel 频道号
     * @return 外设对象，不存在时返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Object getPeripheral(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;

        Level level = sensor.getLevel();
        if (level == null || level.isClientSide) return null;

        BlockState state = sensor.getBlockState();
        BlockPos attachedPos = PeripheralExtenderBlock.getAttachedPos(state, sensor.getBlockPos());
        BlockEntity be = level.getBlockEntity(attachedPos);
        if (be == null) return null;

        // 直接实现 IPeripheral（少部分方块，如某些 Create 外设）
        if (be instanceof IPeripheral p) return p;

        // NeoForge BlockCapability 查询（CC:T 官方外设的主要注册方式）
        Direction side = getSensorSide(state);
        IPeripheral found = level.getCapability(PeripheralCapability.get(), attachedPos, side);
        if (found != null) return found;

        return null;
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

    // ═══════════════ 传感器上下文辅助 ═══════════════

    /**
     * 获取传感器上下文：Level、附着方块位置、附着方块 BE。
     */
    private SensorContext getSensorContext(int channel) {
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;

        Level level = sensor.getLevel();
        if (level == null || level.isClientSide) return null;

        BlockState state = sensor.getBlockState();
        BlockPos attachedPos = PeripheralExtenderBlock.getAttachedPos(state, sensor.getBlockPos());
        BlockEntity attachedBE = level.getBlockEntity(attachedPos);

        return new SensorContext(level, attachedPos, attachedBE);
    }

    private record SensorContext(Level level, BlockPos attachedPos, BlockEntity attachedBE) {}

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
        PeripheralExtenderBlockEntity sensor = PeripheralExtenderRegistry.get(channel);
        if (sensor == null) return null;
        Level level = sensor.getLevel();
        if (level == null) return null;

        long now = level.getGameTime();

        // 同 tick 同频道命中缓存
        if (cachedChannel == channel && cacheGameTime == now && cachedNBT != null) {
            return cachedNBT;
        }

        cachedNBT = PeripheralExtenderBlock.getAttachedBlockNBT(
                level, sensor.getBlockState(), sensor.getBlockPos());
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

    /**
     * 获取传感器附着的导航桌 BlockEntity（仅在附着方块为 navigation_table 时返回）。
     */
    private @Nullable NavTableBlockEntity getNavTableBE(int channel) {
        var ctx = getSensorContext(channel);
        if (ctx == null || ctx.attachedBE == null) return null;
        if (ctx.attachedBE instanceof NavTableBlockEntity navTable) return navTable;
        return null;
    }

    /**
     * 检查指定频道传感器附着的方块是否为给定的注册 ID。
     */
    private boolean isAttachedBlock(int channel, String expectedId) {
        CompoundTag nbt = getOrLoadNBT(channel);
        return nbt != null && expectedId.equals(nbt.getString("id"));
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

    private static double getDoubleSafe(Tag tag) {
        if (tag instanceof NumericTag nt) return nt.getAsDouble();
        return 0.0;
    }
}
