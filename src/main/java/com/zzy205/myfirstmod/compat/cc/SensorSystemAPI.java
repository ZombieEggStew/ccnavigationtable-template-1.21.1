package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PitotTubeBlock;
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
import net.minecraft.world.level.block.state.BlockState;
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
 *     同源公式）；每个皮托管求沿管口朝向的世界速度分量（同 simulated:velocity_sensor 算法，
 *     <b>门控：仅当物理体同时有皮托管与静压孔时计算</b>）——同一 tick 快照；</li>
 * <li><b>读数基准 = 各传感器自身位置</b>；物理体上没有对应传感器时
 *     {@code getAltitude()/getPressure()/getSpeed()} 返回 nil（严格语义）；</li>
 * <li>缓存字段全部 {@code volatile}（主线程写、电脑线程读）；</li>
 * <li>Lua 方法 {@code @LuaFunction}（默认 mainThread=false）直读缓存，零主线程调度 ——
 *     实测直读版（mainThread=true）单次约 50ms（1 tick），缓存后 100 轮 ~0ms。</li>
 * </ul>
 *
 * <pre>{@code
 * local ss = require("ccpe.sensor_system")
 * print(ss.isOnBody())            -- boolean
 * print(ss.getBodyId())           -- 物理体 UUID
 * print(ss.getAltitude())         -- 最后放置的静压孔的高度（便捷方法）
 * print(ss.getPressure())         -- 最后放置的静压孔的气压（便捷方法）
 * print(ss.getSpeed())            -- 最后放置的皮托管沿管口朝向的对地速度（m/s，便捷方法）
 * print(ss.getAirSpeed())         -- 最后放置的皮托管沿管口朝向的空速（m/s，便捷方法）
 * local sensors = ss.getSensors() -- 全部传感器快照
 * -- {{type="static_port", pos={x,y,z}, pos_rel={x,y,z}, altitude=..., pressure=...},
 * --  {type="pitot_tube",  pos={x,y,z}, pos_rel={x,y,z}, speed=..., air_speed=...}, ...}
 * }</pre>
 */
public class SensorSystemAPI implements ILuaAPI {

    private final IComputerSystem computer;

    // ═══════════════ 缓存（主线程 update() 写，Lua 线程读，全部 volatile） ═══════════════

    private volatile boolean onBody = false;
    private volatile @Nullable String bodyId = null;
    private volatile List<SensorSnapshot> sensors = List.of();

    /** 单个传感器的同一 tick 快照（相对物理体原点 + 相对当前电脑的局部坐标 + 读数；非对应类型读数为 null） */
    private record SensorSnapshot(SensorType type, double relX, double relY, double relZ,
                                  double compX, double compY, double compZ,
                                  @Nullable Double altitude, @Nullable Double pressure,
                                  @Nullable Double speed, @Nullable Double airSpeed) {}

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

        // 电脑自身相对物理体原点的坐标（用于计算每个传感器的"相对电脑"坐标）
        Vec3 compRel = SableCompat.toRelativePos(sub, computer.getPosition());
        double crx = compRel != null ? compRel.x : Double.NaN;
        double cry = compRel != null ? compRel.y : Double.NaN;
        double crz = compRel != null ? compRel.z : Double.NaN;

        List<SensorEntry> entries = BodySensorRegistry.sensorsOnBody(sub);

        // 门控（存在性）：速度类读数（speed / air_speed）要求物理体（含约束链）同时有
        // ≥1 皮托管 且 ≥1 静压孔（皮托管-静压系统：空速 = 动压 − 静压，缺静态参考无法得空速）。
        // 不满足 → 速度读数为 null（getSpeed/getAirSpeed 返回 nil，getSensors 对应字段为 nil）。
        boolean speedGate = false;
        {
            boolean hasSpeed = false;
            boolean hasPressure = false;
            for (SensorEntry e : entries) {
                if (e.type() == SensorType.SPEED) hasSpeed = true;
                else if (e.type() == SensorType.PRESSURE) hasPressure = true;
            }
            speedGate = hasSpeed && hasPressure;
        }

        List<SensorSnapshot> list = new ArrayList<>();
        for (SensorEntry e : entries) {
            Double alt = null;
            Double press = null;
            Double speed = null;
            Double airSpeed = null;
            if (e.type() == SensorType.PRESSURE) {
                Vec3 worldPos = SableCompat.projectOutOfSubLevel(sub.getLevel(), e.pos());
                if (worldPos != null) {
                    alt = worldPos.y;
                    press = computePressure(sub.getLevel(), worldPos);
                }
            } else if (e.type() == SensorType.SPEED && speedGate) {
                speed = computeSpeed(sub, e.pos());
                airSpeed = computeAirSpeed(sub, e.pos());
            }
            Vec3 rel = SableCompat.toRelativePos(sub, e.pos());
            double rx = rel != null ? rel.x : e.pos().getX();
            double ry = rel != null ? rel.y : e.pos().getY();
            double rz = rel != null ? rel.z : e.pos().getZ();
            // 相对当前电脑 = 传感器相对原点 − 电脑相对原点；电脑相对坐标不可用时退化为原点相对
            double cx = compRel != null ? rx - crx : rx;
            double cy = compRel != null ? ry - cry : ry;
            double cz = compRel != null ? rz - crz : rz;
            list.add(new SensorSnapshot(e.type(), rx, ry, rz, cx, cy, cz, alt, press, speed, airSpeed));
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
     * 每项：{@code {type, pos={x,y,z}, pos_rel={x,y,z}, altitude, pressure, speed, air_speed}}。
     * <ul>
     * <li><b>pos</b>：相对物理体原点的局部坐标（plot 帧 {@code plot − rotationPoint}，
     *     rotationPoint = 物理体原点/质心枢轴）；物理体移动/旋转时不变，但<b>在物理体上
     *     增删方块会改变原点（质心），pos 整体漂移</b>；</li>
     * <li><b>pos_rel</b>：相对当前电脑的局部坐标（{@code 传感器 plot − 电脑 plot}），
     *     只要电脑不动就不受原点漂移影响，跨会话更稳定，推荐用它标识特定传感器；</li>
     * <li>压力类（static_port）带 altitude/pressure 读数，速度类（pitot_tube）带
     *     speed（对地，沿管口朝向）与 air_speed（空速，相对空气，沿管口朝向，m/s，有符号）——
     *     <b>速度读数受门控：仅当物理体同时有 ≥1 皮托管 且 ≥1 静压孔时才有值</b>，否则为 nil；
     *     其余类型读数为 nil。</li>
     * </ul>
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
            m.put("pos_rel", Map.of("x", s.compX(),
                                    "y", s.compY(),
                                    "z", s.compZ()));
            m.put("altitude", s.altitude());
            m.put("pressure", s.pressure());
            m.put("speed", s.speed());
            m.put("air_speed", s.airSpeed());
            out.add(m);
        }
        return out;
    }

    /** 最后放置（最新注册）的静压孔的高度（世界 Y）；物理体上无静压孔返回 nil（便捷方法） */
    @LuaFunction
    public final @Nullable Double getAltitude() {
        SensorSnapshot last = lastPressurePort();
        return last != null ? last.altitude() : null;
    }

    /** 最后放置（最新注册）的静压孔的气压（大气压分数，海平面 = 1.0）；无静压孔返回 nil（便捷方法） */
    @LuaFunction
    public final @Nullable Double getPressure() {
        SensorSnapshot last = lastPressurePort();
        return last != null ? last.pressure() : null;
    }

    /**
     * 最后放置（最新注册）的皮托管沿管口朝向的对地速度分量（m/s，有符号：正 = 朝向管口，
     * 负 = 背向管口）（便捷方法）。
     * <p>
     * <b>门控（存在性）</b>：物理体（含约束链）必须<b>同时</b>有 ≥1 皮托管 且 ≥1 静压孔
     * （皮托管-静压系统），否则返回 nil。
     * <p>
     * 算法同 {@code simulated:velocity_sensor}：速度 = 皮托管位置的世界点速度
     * （{@link SableCompat#getVelocity}，含旋转贡献，服务端 = {@code ω×r + v}），
     * 轴向 = 该皮托管 24 态管口朝向经物理体姿态转到世界（{@link SableCompat#transformNormalToWorld}），
     * 二者点积；|读数| &lt; 0.05 归零（防静止抖动）。
     */
    @LuaFunction
    public final @Nullable Double getSpeed() {
        SensorSnapshot last = lastPitot();
        return last != null ? last.speed() : null;
    }

    /**
     * 最后放置（最新注册）的皮托管沿管口朝向的<b>空速</b>分量（m/s，有符号：正 = 朝向管口，
     * 负 = 背向管口）（便捷方法）。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #getSpeed()} 相同——物理体（含约束链）必须<b>同时</b>
     * 有 ≥1 皮托管 且 ≥1 静压孔，否则返回 nil。
     * <p>
     * 与 {@link #getSpeed()} 同构，仅速度源不同：空速 = 相对空气速度（
     * {@link SableCompat#getAirVelocity} = {@code Sable.HELPER.getVelocityRelativeToAir}，
     * 已减去风速，同 {@code ccpe.pe.getPhysicsAirVelocity}），沿管口朝向的有符号投影；
     * |读数| &lt; 0.05 归零（防静止抖动）。
     */
    @LuaFunction
    public final @Nullable Double getAirSpeed() {
        SensorSnapshot last = lastPitot();
        return last != null ? last.airSpeed() : null;
    }

    /** 全部静压孔高度的简单平均值；无静压孔返回 nil */
    @LuaFunction
    public final @Nullable Double getAverageAltitude() {
        double sum = 0;
        int count = 0;
        for (SensorSnapshot s : sensors) {
            if (s.type() != SensorType.PRESSURE || s.altitude() == null) continue;
            sum += s.altitude();
            count++;
        }
        return count > 0 ? sum / count : null;
    }

    /** 全部静压孔气压的简单平均值；无静压孔返回 nil */
    @LuaFunction
    public final @Nullable Double getAveragePressure() {
        double sum = 0;
        int count = 0;
        for (SensorSnapshot s : sensors) {
            if (s.type() != SensorType.PRESSURE || s.pressure() == null) continue;
            sum += s.pressure();
            count++;
        }
        return count > 0 ? sum / count : null;
    }

    /**
     * 全部静压孔高度的距离加权平均值（权重 = 1/距物理体原点距离，反距离加权 IDW）。
     * 静压孔恰在原点（距离 ≈ 0）时直接返回该孔的高度；无静压孔返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getWeightedAltitude() {
        return weightedAverage(true);
    }

    /** 全部静压孔气压的距离加权平均值（权重 = 1/距物理体原点距离，反距离加权 IDW）；无静压孔返回 nil */
    @LuaFunction
    public final @Nullable Double getWeightedPressure() {
        return weightedAverage(false);
    }

    /** 距离加权平均（IDW，权重 1/d，d = 距物理体原点，由快照相对坐标算出）；altitude=true 对高度加权，否则对气压加权 */
    private @Nullable Double weightedAverage(boolean altitude) {
        double num = 0;
        double den = 0;
        for (SensorSnapshot s : sensors) {
            if (s.type() != SensorType.PRESSURE) continue;
            Double v = altitude ? s.altitude() : s.pressure();
            if (v == null) continue;
            double d = Math.sqrt(s.relX() * s.relX() + s.relY() * s.relY() + s.relZ() * s.relZ());
            if (d <= 1e-9) return v; // 恰在原点：该孔权重无穷大，直接返回该孔读数
            double w = 1.0 / d;
            num += w * v;
            den += w;
        }
        return den > 0 ? num / den : null;
    }

    // ═══════════════ 主线程辅助 ═══════════════

    /** 最后注册的静压孔（注册顺序 = 放置顺序，LinkedHashSet 保序） */
    private @Nullable SensorSnapshot lastPressurePort() {
        SensorSnapshot last = null;
        for (SensorSnapshot s : sensors)
            if (s.type() == SensorType.PRESSURE) last = s;
        return last;
    }

    /** 最后注册的皮托管（注册顺序 = 放置顺序，LinkedHashSet 保序） */
    private @Nullable SensorSnapshot lastPitot() {
        SensorSnapshot last = null;
        for (SensorSnapshot s : sensors)
            if (s.type() == SensorType.SPEED) last = s;
        return last;
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

    /** 皮托管沿管口朝向的<b>对地</b>速度分量（m/s，有符号） */
    private @Nullable Double computeSpeed(SubLevel sub, BlockPos sensorPos) {
        return axisSpeed(sub, sensorPos, SableCompat.getVelocity(sub.getLevel(), sensorPos));
    }

    /** 皮托管沿管口朝向的<b>空速</b>分量（相对空气，已减风速，m/s，有符号） */
    private @Nullable Double computeAirSpeed(SubLevel sub, BlockPos sensorPos) {
        return axisSpeed(sub, sensorPos, SableCompat.getAirVelocity(sub.getLevel(), sensorPos));
    }

    /**
     * 速度 {@code v}（世界系，皮托管位置的点速度）在<b>世界管口朝向</b>上的有符号投影
     * （同 simulated:velocity_sensor 算法）；管口朝向 = blockstate 的 24 态轴
     * （{@link PitotTubeBlock#axisOf}，plot 帧）经物理体姿态转到世界；|读数| &lt; 0.05 归零（防静止抖动）。
     * <p>
     * 注：轴向与速度均以电脑所在 sub-level 的姿态为基准（与静压孔读数一致）；
     * 传感器位于约束链其它 sub-level 时轴向姿态可能有偏差（现有已知边界）。
     *
     * @return 沿轴速度；注册表滞后（方块已拆）或速度/姿态读取失败返回 null
     */
    private @Nullable Double axisSpeed(SubLevel sub, BlockPos sensorPos, @Nullable Vec3 vel) {
        if (vel == null) return null;
        BlockState state = sub.getLevel().getBlockState(sensorPos);
        if (!(state.getBlock() instanceof PitotTubeBlock)) return null; // 注册表滞后：方块已拆
        Vec3 worldAxis = SableCompat.transformNormalToWorld(sub,
                Vec3.atLowerCornerOf(PitotTubeBlock.axisOf(state).getNormal()));
        if (worldAxis == null) return null;
        double dot = vel.dot(worldAxis);
        return Math.abs(dot) < 0.05 ? 0.0 : dot;
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
