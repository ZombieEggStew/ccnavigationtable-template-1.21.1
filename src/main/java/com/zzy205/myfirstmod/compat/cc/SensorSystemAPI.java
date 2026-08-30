package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.PitotTubeBlock;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorEntry;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorType;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 *     <b>门控：仅当物理体同时有皮托管与静压孔时计算</b>）；机体上有惯性导航系统
 *     （ccpe:ins，ATTITUDE 传感器）时计算姿态角（pitch/roll/yaw，度，
 *     <b>门控：仅当物理体上有 INS 时计算</b>）——同一 tick 快照；</li>
 * <li><b>读数基准 = 各传感器自身位置</b>；物理体上没有对应传感器时
 *     {@code getAltitude()/getPressure()/getSpeed()} 返回 nil（严格语义）；姿态读数
 *     {@code getAngles()} 在物理体上没有 INS 时返回 nil；</li>
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
 * print(ss.getAngles())           -- {pitch=, roll=, yaw=}（度；门控：机体上必须有 INS）
 * print(ss.getPosition())         -- 最后放置的 INS 的世界坐标 {x, y, z}（门控：机体上必须有 INS）
 * print(ss.getOrientation())      -- 机体姿态四元数 {x, y, z, w}（门控：机体上必须有 INS）
 * print(ss.getAngularVelocity())  -- 机体世界系角速度 {x, y, z} rad/s（门控：机体上必须有 INS）
 * print(ss.getBodyPosition())     -- 物理体原点世界坐标 {x, y, z}（门控：机体上必须有 INS）
 * print(ss.getPhysicsCenterOfMassRel()) -- 重心相对电脑的机体局部系位置 {x, y, z}（不门控）
 * print(ss.getPhysicsMass())      -- 所在物理体质量 kg（不门控）
 * print(ss.getPhysicsChainMass()) -- 所在物理体链总质量 kg（不门控）
 * print(ss.getPhysicsGravityForce()) -- 所在物理体重力 pN（不门控）
 * print(ss.getPhysicsChainGravityForce()) -- 所在物理体链总重力 pN（不门控）
 * local sensors = ss.getSensors() -- 全部传感器快照
 * -- {{type="static_port", pos={x,y,z}, pos_rel={x,y,z}, altitude=..., pressure=...},
 * --  {type="pitot_tube",  pos={x,y,z}, pos_rel={x,y,z}, speed=..., air_speed=...},
 * --  {type="ins",         pos={x,y,z}, pos_rel={x,y,z}}, ...}
 * }</pre>
 */
public class SensorSystemAPI implements ILuaAPI {

    private final IComputerSystem computer;

    // ═══════════════ 缓存（主线程 update() 写，Lua 线程读，全部 volatile） ═══════════════

    private volatile boolean onBody = false;
    private volatile @Nullable String bodyId = null;
    private volatile List<SensorSnapshot> sensors = List.of();

    /** 姿态缓存（度）：门控 = 所在物理体（含约束链）上有 ≥1 个 INS（ATTITUDE 传感器） */
    private volatile boolean attitudeAvailable = false;
    private volatile double pitchDeg = 0;
    private volatile double rollDeg = 0;
    private volatile double yawDeg = 0;

    /** INS 位置缓存（世界坐标）：门控与姿态相同——机体（含约束链）上有 ≥1 个 INS */
    private volatile boolean insPositionAvailable = false;
    private volatile double insPosX = 0;
    private volatile double insPosY = 0;
    private volatile double insPosZ = 0;

    /** 姿态四元数缓存（{x,y,z,w}）：门控与姿态相同——机体（含约束链）上有 ≥1 个 INS */
    private volatile boolean orientationAvailable = false;
    private volatile double orientX = 0;
    private volatile double orientY = 0;
    private volatile double orientZ = 0;
    private volatile double orientW = 1;

    /** 角速度缓存（世界系 rad/s）：门控与姿态相同——机体（含约束链）上有 ≥1 个 INS */
    private volatile boolean angularVelocityAvailable = false;
    private volatile double angVelX = 0;
    private volatile double angVelY = 0;
    private volatile double angVelZ = 0;

    /** 物理体原点世界坐标缓存：门控与姿态相同——机体（含约束链）上有 ≥1 个 INS */
    private volatile boolean bodyPosAvailable = false;
    private volatile double bodyPosX = 0;
    private volatile double bodyPosY = 0;
    private volatile double bodyPosZ = 0;

    /** 重力常数：重力（pN）= 质量（kg）× {@value}（Sable 物理单位） */
    private static final double GRAVITY_CONSTANT = 11.0;

    // ── 物理数据缓存（不门控：只要在物理体上就计算，与传感器无关） ──

    /** 重心相对当前电脑的局部坐标（plot 帧差值，机体局部系，不随旋转变化） */
    private volatile boolean comRelAvailable = false;
    private volatile double comRelX = 0;
    private volatile double comRelY = 0;
    private volatile double comRelZ = 0;

    /** 所在物理体质量（kg） */
    private volatile boolean massAvailable = false;
    private volatile double massKg = 0;

    /** 所在物理体（含约束链）总质量（kg） */
    private volatile boolean chainMassAvailable = false;
    private volatile double chainMassKg = 0;

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
            attitudeAvailable = false;
            pitchDeg = rollDeg = yawDeg = 0;
            insPositionAvailable = false;
            insPosX = insPosY = insPosZ = 0;
            orientationAvailable = false;
            orientX = orientY = orientZ = 0;
            orientW = 1;
            angularVelocityAvailable = false;
            angVelX = angVelY = angVelZ = 0;
            bodyPosAvailable = false;
            bodyPosX = bodyPosY = bodyPosZ = 0;
            comRelAvailable = false;
            comRelX = comRelY = comRelZ = 0;
            massAvailable = false;
            massKg = 0;
            chainMassAvailable = false;
            chainMassKg = 0;
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
        // 姿态类读数（getAngles）要求 ≥1 惯性导航系统（INS，ATTITUDE 传感器）。
        boolean speedGate = false;
        boolean attitudeGate = false;
        {
            boolean hasSpeed = false;
            boolean hasPressure = false;
            boolean hasIns = false;
            for (SensorEntry e : entries) {
                if (e.type() == SensorType.SPEED) hasSpeed = true;
                else if (e.type() == SensorType.PRESSURE) hasPressure = true;
                else if (e.type() == SensorType.ATTITUDE) hasIns = true;
            }
            speedGate = hasSpeed && hasPressure;
            attitudeGate = hasIns;
        }

        // 姿态缓存（度；门控：机体上有 INS 才计算，与速度门控同一 tick 快照）
        double[] attitude = attitudeGate ? computeAttitudeDeg(sub) : null;
        if (attitude != null) {
            attitudeAvailable = true;
            pitchDeg = attitude[0];
            rollDeg = attitude[1];
            yawDeg = attitude[2];
        } else {
            attitudeAvailable = false;
            pitchDeg = rollDeg = yawDeg = 0;
        }

        // INS 位置缓存（世界坐标；门控：机体上有 INS 才计算，与姿态同一 tick 快照）
        double[] insPos = attitudeGate ? computeInsPosition(sub, entries) : null;
        if (insPos != null) {
            insPositionAvailable = true;
            insPosX = insPos[0];
            insPosY = insPos[1];
            insPosZ = insPos[2];
        } else {
            insPositionAvailable = false;
            insPosX = insPosY = insPosZ = 0;
        }

        // 姿态四元数缓存（{x,y,z,w}；门控：机体上有 INS 才计算，与姿态/位置同一 tick 快照）
        double[] orient = attitudeGate ? SableCompat.getSubLevelOrientation(sub) : null;
        if (orient != null) {
            orientationAvailable = true;
            orientX = orient[0];
            orientY = orient[1];
            orientZ = orient[2];
            orientW = orient[3];
        } else {
            orientationAvailable = false;
            orientX = orientY = orientZ = 0;
            orientW = 1;
        }

        // 角速度缓存（世界系 rad/s；门控：机体上有 INS 才计算）
        Vec3 angVel = attitudeGate ? SableCompat.getAngularVelocity(sub.getLevel(), sub) : null;
        if (angVel != null) {
            angularVelocityAvailable = true;
            angVelX = angVel.x;
            angVelY = angVel.y;
            angVelZ = angVel.z;
        } else {
            angularVelocityAvailable = false;
            angVelX = angVelY = angVelZ = 0;
        }

        // 物理体原点世界坐标缓存（门控：机体上有 INS 才计算，与姿态同一 tick 快照）
        Vec3 bodyPos = attitudeGate ? SableCompat.getSubLevelWorldPos(sub) : null;
        if (bodyPos != null) {
            bodyPosAvailable = true;
            bodyPosX = bodyPos.x;
            bodyPosY = bodyPos.y;
            bodyPosZ = bodyPos.z;
        } else {
            bodyPosAvailable = false;
            bodyPosX = bodyPosY = bodyPosZ = 0;
        }

        // ── 物理数据缓存（不门控：只要在物理体上就计算，与传感器无关） ──
        // 重心相对当前电脑 = 重心相对原点偏移 − 电脑相对原点偏移（plot 帧差值，机体局部系，
        // 不随物理体移动/旋转变化；与 getSensors 的 pos_rel 同帧可比较）
        Vec3 comLocal = SableCompat.getCenterOfMassLocal(sub);
        if (comLocal != null && compRel != null) {
            comRelAvailable = true;
            comRelX = comLocal.x - crx;
            comRelY = comLocal.y - cry;
            comRelZ = comLocal.z - crz;
        } else {
            comRelAvailable = false;
            comRelX = comRelY = comRelZ = 0;
        }

        Double mass = SableCompat.getMass(sub);
        if (mass != null) {
            massAvailable = true;
            massKg = mass;
        } else {
            massAvailable = false;
            massKg = 0;
        }

        Double chainMass = SableCompat.getChainMass(sub);
        if (chainMass != null) {
            chainMassAvailable = true;
            chainMassKg = chainMass;
        } else {
            chainMassAvailable = false;
            chainMassKg = 0;
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
     *     惯性导航系统（ins）条目无读数（姿态用 {@link #getAngles()} 读取）；其余类型读数为 nil。</li>
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

    /**
     * 机体姿态角（度）：{@code {pitch=, roll=, yaw=}}。
     * <ul>
     * <li><b>pitch</b> 俯仰：绕机体局部 X 轴，正 = 抬头（机头向上）；</li>
     * <li><b>roll</b> 滚转：绕机体局部 Z 轴，正 = 右翼下压（右倾）；</li>
     * <li><b>yaw</b> 航向：0 = 机体局部 −Z 指向世界北，正 = 右转（从上往下看顺时针），−180..180。</li>
     * </ul>
     * 算法同 {@code simulated:gimbal_sensor} 的 XAngle/ZAngle（世界"下"方向投影），
     * yaw 由世界北方向在机体局部系的水平方位提取（稳态下等于 INS 指北标记读数）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个惯性导航系统
     * （ccpe:ins），否则返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getAngles() {
        if (!attitudeAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("pitch", pitchDeg);
        m.put("roll", rollDeg);
        m.put("yaw", yawDeg);
        return m;
    }

    /**
     * 最后放置（最新注册）的惯性导航系统（ccpe:ins）的世界坐标 {@code {x, y, z}}。
     * <p>
     * 方块 plot 坐标经 Sable 物理体变换投影到世界（{@link SableCompat#projectOutOfSubLevel}，
     * 与静压孔高度/气压的世界点同一来源），随物理体移动/旋转实时变化；
     * 与 {@link #getSensors()} 的 {@code pos}（相对物理体原点）/{@code pos_rel}（相对电脑）不同，
     * 这是 INS 在世界中实际渲染的位置。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个惯性导航系统
     * （ccpe:ins），否则返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getPosition() {
        if (!insPositionAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("x", insPosX);
        m.put("y", insPosY);
        m.put("z", insPosZ);
        return m;
    }

    /**
     * 所在物理体（含约束链）的姿态四元数 {@code {x, y, z, w}}
     * （{@code subLevel.logicalPose().orientation()}，世界系）。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #getAngles()} 相同——所在物理体上必须有 ≥1 个
     * 惯性导航系统（ccpe:ins），否则返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getOrientation() {
        if (!orientationAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("x", orientX);
        m.put("y", orientY);
        m.put("z", orientZ);
        m.put("w", orientW);
        return m;
    }

    /**
     * 所在物理体（含约束链）的世界系角速度 {@code {x, y, z}}（rad/s，
     * {@link SableCompat#getAngularVelocity}，刚体角速度）。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #getAngles()} 相同——所在物理体上必须有 ≥1 个
     * 惯性导航系统（ccpe:ins），否则返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getAngularVelocity() {
        if (!angularVelocityAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("x", angVelX);
        m.put("y", angVelY);
        m.put("z", angVelZ);
        return m;
    }

    /**
     * 所在物理体原点的世界坐标 {@code {x, y, z}}
     * （{@code subLevel.logicalPose().position()}，经 Sable 物理体变换投影到世界；
     * 与 {@link #getPosition()}（INS 方块坐标）不同，这是整个物理体的原点/质心枢轴位置）。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #getAngles()} 相同——所在物理体上必须有 ≥1 个
     * 惯性导航系统（ccpe:ins），否则返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getBodyPosition() {
        if (!bodyPosAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("x", bodyPosX);
        m.put("y", bodyPosY);
        m.put("z", bodyPosZ);
        return m;
    }

    // ═══════════════ 物理数据（不门控：只要在物理体上就有值） ═══════════════

    /**
     * 物理体重心相对于当前电脑的机体局部系位置 {@code {x, y, z}}。
     * <p>
     * = 重心相对物理体原点的偏移 − 电脑相对物理体原点的偏移（plot 帧差值，
     * 与 {@link #getSensors()} 的 {@code pos_rel} 同帧），<b>不随物理体移动/旋转变化</b>。
     * <p>
     * <b>不门控</b>：只要电脑在物理体上就有值；不在物理体上或质量数据不可用返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getPhysicsCenterOfMassRel() {
        if (!comRelAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("x", comRelX);
        m.put("y", comRelY);
        m.put("z", comRelZ);
        return m;
    }

    /**
     * 电脑所在物理体的质量（kg）。
     * <p>
     * <b>不门控</b>：只要电脑在物理体上就有值；不在物理体上或质量数据不可用返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsMass() {
        return massAvailable ? massKg : null;
    }

    /**
     * 电脑所在物理体（含约束链）的总质量（kg）。
     * <p>
     * <b>不门控</b>：只要电脑在物理体上就有值；不在物理体上或质量数据不可用返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsChainMass() {
        return chainMassAvailable ? chainMassKg : null;
    }

    /**
     * 电脑所在物理体的重力（pN，= 质量 × {@value #GRAVITY_CONSTANT}）。
     * <p>
     * <b>不门控</b>：只要电脑在物理体上就有值；不在物理体上或质量数据不可用返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsGravityForce() {
        return massAvailable ? massKg * GRAVITY_CONSTANT : null;
    }

    /**
     * 电脑所在物理体（含约束链）的总重力（pN，= 链总质量 × {@value #GRAVITY_CONSTANT}）。
     * <p>
     * <b>不门控</b>：只要电脑在物理体上就有值；不在物理体上或质量数据不可用返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsChainGravityForce() {
        return chainMassAvailable ? chainMassKg * GRAVITY_CONSTANT : null;
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
            case SPEED -> "pitot_tube";
            case ATTITUDE -> "ins"; // 惯性导航系统：姿态读数走 getAngles()
        };
    }

    /**
     * 机体姿态欧拉角（度）：{@code {pitch, roll, yaw}}，算法同
     * {@code simulated:gimbal_sensor}：
     * <ul>
     * <li>pitch/roll：世界"下"方向经机体姿态转到局部系后投影（XAngle/ZAngle 同款公式），
     *     pitch 正 = 抬头（绕局部 X）、roll 正 = 右翼下压（绕局部 Z）；</li>
     * <li>yaw：世界北 (0,0,-1) 转到机体局部系后取水平方位（忽略局部 Y 分量），
     *     0 = 局部 −Z 指北，正 = 右转（顺时针从上往下看），−180..180。</li>
     * </ul>
     *
     * @return {pitch, roll, yaw}（度）
     */
    private double[] computeAttitudeDeg(SubLevel sub) {
        final Pose3dc pose = sub.logicalPose();

        final Vector3d ld = JOMLConversion.toJOML(Vec3.atLowerCornerOf(Direction.DOWN.getNormal()));
        pose.orientation().transformInverse(ld);
        final double pitch = ld.y() < 0 || ld.z() * ld.z() > 0.001 ? Math.atan2(ld.z(), -ld.y()) : 0;
        final double roll = ld.y() < 0 || ld.x() * ld.x() > 0.001 ? Math.atan2(ld.x(), -ld.y()) : 0;

        final Vector3d north = new Vector3d(0, 0, -1);
        pose.orientation().transformInverse(north);
        final double yaw = -Math.atan2(north.x(), -north.z());

        return new double[] { Math.toDegrees(pitch), Math.toDegrees(roll), Math.toDegrees(yaw) };
    }

    /**
     * 最后放置（最新注册）的 INS 的世界坐标（plot 坐标经 Sable 物理体变换投影到世界，
     * 同 {@code simulated:altitude_sensor} 的 worldPos 用法）。
     *
     * @return {x, y, z}（世界坐标）；机体上无 INS（门控）或投影失败返回 null
     */
    private @Nullable double[] computeInsPosition(SubLevel sub, List<SensorEntry> entries) {
        SensorEntry lastIns = null;
        for (SensorEntry e : entries)
            if (e.type() == SensorType.ATTITUDE) lastIns = e; // 注册顺序 = 放置顺序，取最后
        if (lastIns == null) return null;
        Vec3 worldPos = SableCompat.projectOutOfSubLevel(sub.getLevel(), lastIns.pos());
        if (worldPos == null) return null;
        return new double[] { worldPos.x, worldPos.y, worldPos.z };
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
