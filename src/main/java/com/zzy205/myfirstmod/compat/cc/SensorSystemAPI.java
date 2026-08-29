package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorEntry;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorType;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ccpe.sensor_system}：所在物理体（Sable sub-level）的环境数据 Lua API。
 * <p>
 * <b>高频缓存模式</b>（对齐 memo/my_sensor_system.md）：
 * <ul>
 * <li>{@link #update()} 每电脑 tick（服务端主线程，见 CC:Tweaked
 *     {@code ComputerExecutor.tick()} → {@code ServerComputerRegistry} 主线程 tick 链）
 *     刷新缓存：解析所在 sub-level → 汇总 {@link BodySensorRegistry} 上本物理体（含约束链）
 *     的全部传感器 → 每个静压孔 plot 坐标投影到世界 → 该点高度 / 气压
 *     （{@link DimensionPhysicsData#getAirPressure}，与 {@code simulated:altitude_sensor}
 *     同源公式），同一 tick 快照；</li>
 * <li><b>读数基准 = 各静压孔自身位置</b>；物理体上没有静压孔时
 *     {@code getAltitude()/getPressure()} 返回 nil（严格语义）；</li>
 * <li>缓存字段全部 {@code volatile}（主线程写、电脑线程读）；</li>
 * <li>Lua 方法 {@code @LuaFunction}（默认 mainThread=false）直读缓存，零主线程调度 ——
 *     实测直读版（mainThread=true）单次约 50ms（1 tick），缓存后 100 轮 ~0ms。</li>
 * </ul>
 * 不做传感器存在性门控（后续结合 {@link BodySensorRegistry} 实现）。
 *
 * <pre>{@code
 * local ss = require("ccpe.sensor_system")
 * print(ss.isOnBody())            -- boolean
 * print(ss.getBodyId())           -- 物理体 UUID
 * print(ss.getAltitude())         -- 第一个静压孔的高度（便捷方法）
 * print(ss.getPressure())         -- 第一个静压孔的气压（便捷方法）
 * local sensors = ss.getSensors() -- 全部传感器快照
 * -- {{type="static_port", pos={x,y,z}, altitude=..., pressure=...}, ...}
 * }</pre>
 */
public class SensorSystemAPI implements ILuaAPI {

    private final IComputerSystem computer;

    // ═══════════════ 缓存（主线程 update() 写，Lua 线程读，全部 volatile） ═══════════════

    private volatile boolean onBody = false;
    private volatile @Nullable String bodyId = null;
    private volatile List<SensorSnapshot> sensors = List.of();

    /** 单个传感器的同一 tick 快照（相对物理体原点的局部坐标 + 读数；非压力类读数为 null） */
    private record SensorSnapshot(SensorType type, double relX, double relY, double relZ,
                                  @Nullable Double altitude, @Nullable Double pressure) {}

    public SensorSystemAPI(IComputerSystem computer) {
        this.computer = computer;
    }

    @Override
    public String[] getNames() {
        return new String[0];
    }

    @Override
    public @Nullable String getModuleName() {
        return "ccpe.sensor_system";
    }

    /**
     * 每电脑 tick（服务端主线程）刷新缓存。
     * 电脑不在物理体上 → 缓存重置为默认值（onBody=false，其余空）。
     */
    @Override
    public void update() {
        SubLevel sub = resolveSubLevel();
        if (sub == null) {
            onBody = false;
            bodyId = null;
            sensors = List.of();
            return;
        }
        onBody = true;
        bodyId = SableCompat.getSubLevelId(sub);

        List<SensorSnapshot> list = new ArrayList<>();
        for (SensorEntry e : BodySensorRegistry.sensorsOnBody(sub)) {
            Double alt = null;
            Double press = null;
            if (e.type() == SensorType.PRESSURE) {
                Vec3 worldPos = SableCompat.projectOutOfSubLevel(sub.getLevel(), e.pos());
                if (worldPos != null) {
                    alt = worldPos.y;
                    press = computePressure(sub.getLevel(), worldPos);
                }
            }
            Vec3 rel = SableCompat.toRelativePos(sub, e.pos());
            double rx = rel != null ? rel.x : e.pos().getX();
            double ry = rel != null ? rel.y : e.pos().getY();
            double rz = rel != null ? rel.z : e.pos().getZ();
            list.add(new SensorSnapshot(e.type(), rx, ry, rz, alt, press));
        }
        sensors = list;
    }

    // ═══════════════ Lua 读取（mainThread=false，直读 volatile 缓存） ═══════════════

    /** 电脑当前是否在 Sable 物理体（sub-level）内 */
    @LuaFunction
    public final boolean isOnBody() {
        return onBody;
    }

    /** 所在物理体的 UUID 字符串；不在物理体上返回 nil */
    @LuaFunction
    public final @Nullable String getBodyId() {
        return bodyId;
    }

    /**
     * 所在物理体（含约束链）的全部传感器快照（同一 tick 一致）。
     * 每项：{@code {type, pos={x,y,z}, altitude, pressure}}。
     * <b>pos 为相对物理体原点的局部坐标</b>（plot 帧：{@code plot − rotationPoint}，
     * rotationPoint = 物理体原点/质心枢轴在 plot 空间的坐标），可能为小数，
     * 物理体移动/旋转时保持不变，用于稳定标识不同位置的传感器；
     * 压力类（static_port）带 altitude/pressure 读数，其余类型读数为 nil。
     * 不在物理体上或物理体无传感器 → 空数组。
     */
    @LuaFunction
    public final List<Map<String, Object>> getSensors() {
        List<Map<String, Object>> out = new ArrayList<>(sensors.size());
        for (SensorSnapshot s : sensors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", typeName(s.type()));
            m.put("pos", Map.of("x", s.relX(),
                                "y", s.relY(),
                                "z", s.relZ()));
            m.put("altitude", s.altitude());
            m.put("pressure", s.pressure());
            out.add(m);
        }
        return out;
    }

    /** 第一个静压孔的高度（世界 Y）；物理体上无静压孔返回 nil（便捷方法） */
    @LuaFunction
    public final @Nullable Double getAltitude() {
        SensorSnapshot first = firstPressurePort();
        return first != null ? first.altitude() : null;
    }

    /** 第一个静压孔的气压（大气压分数，海平面 = 1.0）；无静压孔返回 nil（便捷方法） */
    @LuaFunction
    public final @Nullable Double getPressure() {
        SensorSnapshot first = firstPressurePort();
        return first != null ? first.pressure() : null;
    }

    // ═══════════════ 主线程辅助 ═══════════════

    private @Nullable SensorSnapshot firstPressurePort() {
        for (SensorSnapshot s : sensors)
            if (s.type() == SensorType.PRESSURE) return s;
        return null;
    }

    private static String typeName(SensorType type) {
        return switch (type) {
            case PRESSURE -> "static_port";
            case SPEED -> "pitot_tube"; // 后续接入
        };
    }

    private @Nullable Double computePressure(Level level, Vec3 worldPos) {
        try {
            return DimensionPhysicsData.getAirPressure(level,
                    new Vector3d(worldPos.x, worldPos.y, worldPos.z));
        } catch (Exception e) {
            return null;
        }
    }

    /** 电脑所在位置的 SubLevel；不在物理体上返回 null */
    private @Nullable SubLevel resolveSubLevel() {
        try {
            return SableCompat.getContainingSubLevel(computer.getLevel(), computer.getPosition());
        } catch (Exception e) {
            return null;
        }
    }
}
