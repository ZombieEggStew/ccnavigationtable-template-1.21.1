package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.RegistrateBlocks;
import com.zzy205.myfirstmod.block.AicBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.FmcBlock;
import com.zzy205.myfirstmod.block.PitotTubeBlock;
import com.zzy205.myfirstmod.block.ShortRangeLinkerBlock;
import com.zzy205.myfirstmod.block.ShortRangeLinkerBlockEntity;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorEntry;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorType;
import com.zzy205.myfirstmod.compat.create.CreateStressReadout;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.eriksonn.aeronautics.config.AeroConfig;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ccpe.sensor_system}：所在物理体（Sable sub-level）的环境数据 Lua API。
 * <p>
 * 同时并入原 {@code ccpe.link} 的短程信号链接器寻址（作用域同为调用电脑所在物理体）：
 * {@link #getPeripheral(int)} / {@link #getRedstoneOutput(int)} /
 * {@link #getRedstoneInput(int)} / {@link #setRedstoneOutput(int, int)}
 * / {@link #enableNbtCache(int, java.util.Optional)} / {@link #getNbt(int, String)}
 * / {@link #getAllNbt(int)}。
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
 * print(ss.getPhysicsCenterOfMassRel()) -- 重心相对最后放置的 FMC 的机体局部系位置 {x, y, z}（门控：机体上有 FMC）
 * print(ss.getPhysicsMass())      -- 所在物理体质量 kg（门控：机体上有 FMC）
 * print(ss.getPhysicsChainMass()) -- 所在物理体链总质量 kg（门控：机体上有 FMC）
 * print(ss.getPhysicsGravityForce()) -- 所在物理体重力 pN（门控：机体上有 FMC）
 * print(ss.getPhysicsChainGravityForce()) -- 所在物理体链总重力 pN（门控：机体上有 FMC）
 * print(ss.getStressRemaining()) -- 最后放置的 FMC 的附着面方块所在 Create 应力网络的剩余应力 su（门控：机体上有 FMC 且附着面方块是动力方块）
 * print(ss.getStressCapacity()) -- 同上，网络总容量 su（门控同上）
 * local n = ss.setLights("red", true) -- 开机体（含约束链）上全部红色航行灯，返回实际设置的灯数（门控：机体上有 FMC；color 还支持 "green"/"white"/"all"）
 * local m = ss.setAllLights(true)     -- 开机体上全部颜色（红/绿/白）的航行灯，返回实际设置的灯数（等价于 setLights("all", true)）
 * local sensors = ss.getSensors() -- 全部传感器快照
 * -- {{type="static_port", pos={x,y,z}, pos_rel={x,y,z}, altitude=..., pressure=...},
 * --  {type="pitot_tube",  pos={x,y,z}, pos_rel={x,y,z}, speed=..., air_speed=...},
 * --  {type="ins",         pos={x,y,z}, pos_rel={x,y,z}}, ...}
 * local sensor = ss.getPeripheral(1)    -- 本机物理体（含约束链）上频道 1 的设备外设（寻址模型，频道 = 目标设备地址；链接器 → 附着外设，控制台 → 控制台外设）
 * local desk = ss.getPeripheral(2)      -- 频道 2 被控制台占用 → 返回控制台外设（ccpe:control_desk）
 * print(ss.getRedstoneInput(2))         -- 目标链接器位置的红石输入
 * ss.setRedstoneOutput(2, 15)           -- 写目标链接器红石输出（相邻红石线随之响应）
 * ss.enableNbtCache(1)                  -- 开启频道 1 链接器的附着方块 NBT 缓存（默认每 20 tick 刷新）
 * ss.enableNbtCache(1, 5)               -- 改为每 5 tick 刷新一次
 * local fuel = ss.getNbt(1, "Fuel")     -- 读缓存中附着方块 NBT 的路径值（未开启缓存返回 nil）
 * local all = ss.getAllNbt(1)           -- 读全量 NBT（未开启缓存返回空表）
 * ss.enableNbtCache(1, 0)               -- 关闭缓存
 * }</pre>
 */
public class SensorSystemAPI implements ILuaAPI {

    private final IComputerSystem computer;

    // ═══════════════ 缓存（主线程 update() 写，Lua 线程读，全部 volatile） ═══════════════

    private volatile boolean onBody = false;
    private volatile @Nullable String bodyId = null;
    /** 所在物理体（含约束链）的全部子次元 UUID（主线程 update() 计算，Lua 线程只读缓存；不在物理体上为空集合） */
    private volatile Set<UUID> chainUuids = Set.of();
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

    /** FMC 参考点 = 方块中心：BlockPos（角点）到方块单元中心的偏移（半格） */
    private static final double BLOCK_CENTER_OFFSET = 0.5;

    // ── 物理数据缓存（门控：机体（含约束链）上有 ≥1 个 FMC 才计算，与传感器存在性相关） ──

    /** 重心相对最后放置的 FMC（含 AIC）的方块中心的局部坐标（plot 帧差值，机体局部系，不随旋转变化） */
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

    /** 所在物理体（含约束链）总质心相对最后放置的 FMC 方块中心的局部坐标（plot 帧差值，机体局部系） */
    private volatile boolean chainComRelAvailable = false;
    private volatile double chainComRelX = 0;
    private volatile double chainComRelY = 0;
    private volatile double chainComRelZ = 0;

    // ── 附着方块应力网络缓存（门控：机体（含约束链）上有 ≥1 个 FMC；读数 = 最后放置的 FMC 的附着面方块） ──

    /** 附着方块是否可读（机体上有 FMC 且其附着面方块是 Create 动力方块且读数成功） */
    private volatile boolean attachedStressAvailable = false;

    /** 附着方块所在应力网络的当前总应力（su） */
    private volatile double attachedStress = 0;

    /** 附着方块所在应力网络的总容量（su） */
    private volatile double attachedCapacity = 0;

    // ── 螺旋桨转速工具缓存（门控：机体（含约束链）上有 ≥1 个 FMC；T/A 静态缓存，不每 tick 刷新） ──

    /** 螺旋桨工具门控：所在物理体（含约束链）上有 ≥1 个 FMC（ccpe:fmc），与物理数据门控同源 */
    private volatile boolean propellerGateAvailable = false;

    /** 螺旋桨参数是否已 init（N/S 已设置） */
    private volatile boolean propellerInit = false;

    /** 螺旋桨数量 N */
    private volatile int propellerCount = 0;

    /** 每个螺旋桨上的动力方块数量（风帆/对称风帆/羊毛方块）S */
    private volatile int sailsPerPropeller = 0;

    /**
     * aeronautics 配置 T：Propeller Bearing Thrust（默认 0.2）。
     * 静态缓存：进游戏（服务器启动）与放置/加载 FMC 时刷新，不每 tick 读配置。
     */
    private static volatile double propellerBearingThrust = 0.2;

    /**
     * aeronautics 配置 A：Propeller Bearing Airflow（默认 0.05）。
     * 静态缓存：进游戏（服务器启动）与放置/加载 FMC 时刷新，不每 tick 读配置。
     */
    private static volatile double propellerBearingAirflow = 0.05;

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
            chainUuids = Set.of();
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
            chainComRelAvailable = false;
            chainComRelX = chainComRelY = chainComRelZ = 0;
            resetAttachedStress();
            propellerGateAvailable = false;
            return;
        }
        onBody = true;
        bodyId = SableCompat.getSubLevelId(sub);
        chainUuids = chainUuidsOf(sub);

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
        // 物理数据类读数（getPhysics*）与附着方块应力网络（getStressRemaining/getStressCapacity）
        // 要求 ≥1 飞行管理计算机（FMC，ccpe:fmc）。
        boolean speedGate = false;
        boolean attitudeGate = false;
        boolean physicsGate = false;
        {
            boolean hasSpeed = false;
            boolean hasPressure = false;
            boolean hasIns = false;
            boolean hasFmc = false;
            for (SensorEntry e : entries) {
                if (e.type() == SensorType.SPEED) hasSpeed = true;
                else if (e.type() == SensorType.PRESSURE) hasPressure = true;
                else if (e.type() == SensorType.ATTITUDE) hasIns = true;
                else if (e.type() == SensorType.FMC) hasFmc = true;
            }
            speedGate = hasSpeed && hasPressure;
            attitudeGate = hasIns;
            physicsGate = hasFmc;
        }

        // 螺旋桨工具门控（与物理数据同源：机体上有 ≥1 个 FMC）。
        // T/A 配置不在此刷新（静态缓存，进游戏/放置 FMC 时刷新一次，见 refreshAeroConfig）。
        propellerGateAvailable = physicsGate;

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

        // ── 物理数据缓存（门控：机体（含约束链）上有 ≥1 个 FMC 才计算，与姿态/速度同一 tick 快照） ──
        if (physicsGate) {
            // 重心相对 FMC（最后放置的 FMC 传感器，AIC 等同 FMC）=
            //   重心相对物理体原点偏移（getCenterOfMassLocal 已做 plot−rotationPoint 转换）
            //   − FMC 方块中心相对物理体原点偏移（lastFmcRel = toRelativePos + 0.5 半格），
            // 两者同为 plot 帧差值（机体局部系），不随物理体移动/旋转变化；参考点不依赖电脑位置。
            Vec3 comLocal = SableCompat.getCenterOfMassLocal(sub);
            Vec3 fmcRel = lastFmcRel(sub, entries);
            if (comLocal != null && fmcRel != null) {
                comRelAvailable = true;
                comRelX = comLocal.x - fmcRel.x;
                comRelY = comLocal.y - fmcRel.y;
                comRelZ = comLocal.z - fmcRel.z;
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

            // 链总质心相对 FMC（最后放置的 FMC 的方块中心，AIC 等同 FMC）=
            //   链质心相对物理体原点偏移（getChainCenterOfMassLocal，世界系加权平均后转回 plot 帧）
            //   − FMC 方块中心相对物理体原点偏移（fmcRel，与上方重心同一参考点），
            // 两者同为 plot 帧差值（机体局部系），不随物理体移动/旋转变化。
            Vec3 chainCom = SableCompat.getChainCenterOfMassLocal(sub);
            if (chainCom != null && fmcRel != null) {
                chainComRelAvailable = true;
                chainComRelX = chainCom.x - fmcRel.x;
                chainComRelY = chainCom.y - fmcRel.y;
                chainComRelZ = chainCom.z - fmcRel.z;
            } else {
                chainComRelAvailable = false;
                chainComRelX = chainComRelY = chainComRelZ = 0;
            }

            // 附着方块应力网络（最后放置的 FMC 的附着面方块；与质量/重心同一 tick 快照）
            computeAttachedStress(sub, entries);
        } else {
            comRelAvailable = false;
            comRelX = comRelY = comRelZ = 0;
            massAvailable = false;
            massKg = 0;
            chainMassAvailable = false;
            chainMassKg = 0;
            chainComRelAvailable = false;
            chainComRelX = chainComRelY = chainComRelZ = 0;
            resetAttachedStress();
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
     *     惯性导航系统（ins）条目无读数（姿态用 {@link #getAngles()} 读取）；
     *     飞行管理计算机（fmc）条目无读数（物理数据门控用，见 {@code getPhysics*} 方法）；
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

    // ═══════════════ 物理数据（门控：机体（含约束链）上有 ≥1 个 FMC 才有值） ═══════════════

    /**
     * 物理体重心相对于<b>最后放置的 FMC</b>（含 AIC，AIC 等同 FMC）的<b>方块中心</b>
     * 的机体局部系位置 {@code {x, y, z}}。
     * <p>
     * = 重心相对物理体原点的偏移 − FMC 方块中心相对物理体原点的偏移（两者均经 Sable 的
     * {@code plot − rotationPoint} 转换，plot 帧差值，与 {@link #getSensors()} 的
     * {@code pos} 同帧），<b>不随物理体移动/旋转变化</b>；参考点 = 所在物理体（含约束链）
     * 上最后放置的 FMC 的方块中心（BlockPos 角点 + 半格，多个 FMC 时取最后）。
     * <p>
     * 注：Sable 的物理体原点（rotationPoint）运行时与质心同步，因此该值 ≈
     * FMC 方块中心相对物理体原点的偏移取反（重心在机体上相对 FMC 方块中心的方位）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 nil；不在物理体上或质量数据不可用同样返回 nil。
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
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 nil；不在物理体上或质量数据不可用同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsMass() {
        return massAvailable ? massKg : null;
    }

    /**
     * 电脑所在物理体（含约束链）的总质量（kg）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 nil；不在物理体上或质量数据不可用同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsChainMass() {
        return chainMassAvailable ? chainMassKg : null;
    }

    /**
     * 电脑所在物理体的重力（pN，= 质量 × {@value #GRAVITY_CONSTANT}）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 nil；不在物理体上或质量数据不可用同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsGravityForce() {
        return massAvailable ? massKg * GRAVITY_CONSTANT : null;
    }

    /**
     * 电脑所在物理体（含约束链）的总重力（pN，= 链总质量 × {@value #GRAVITY_CONSTANT}）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 nil；不在物理体上或质量数据不可用同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPhysicsChainGravityForce() {
        return chainMassAvailable ? chainMassKg * GRAVITY_CONSTANT : null;
    }

    /**
     * 电脑所在物理体（含全部约束链，轴承等）的<b>总质心</b>相对于<b>最后放置的 FMC</b>
     * （含 AIC，AIC 等同 FMC）的<b>方块中心</b>的机体局部系位置 {@code {x, y, z}}
     * （plot 帧差值，与 {@link #getPhysicsCenterOfMassRel()} 同一参考点，不随物理体
     * 移动/旋转变化）。
     * <p>
     * = 链质心相对电脑所在物理体原点的偏移（{@link SableCompat#getChainCenterOfMassLocal}，
     * 世界系按质量加权平均链上各 sub-level 的合并质心后转回 plot 帧）
     * − FMC 方块中心相对物理体原点的偏移（与 {@link #getPhysicsCenterOfMassRel()} 相同）。
     * <p>
     * Sable 没有链级质心 API（各 sub-level 的 {@code MergedMassTracker} 只合并自身 + 其 plot
     * 内 contraptions），本方法在世界系按质量加权平均链上各 sub-level 的合并质心，再经电脑所在
     * 物理体的 pose 逆变换转回其 plot 帧并相对其原点（与 {@link #getPhysicsChainMass()} 对应）。
     * <p>
     * <b>门控（存在性）</b>：与其余 FMC 物理数据方法相同——所在物理体（含约束链）上必须有
     * ≥1 个飞行管理计算机（FMC，ccpe:fmc），否则返回 nil；不在物理体上或底层物理数据不可用
     * 同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Map<String, Double> getPhysicsChainCenterOfMassRel() {
        if (!chainComRelAvailable) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("x", chainComRelX);
        m.put("y", chainComRelY);
        m.put("z", chainComRelZ);
        return m;
    }

    /**
     * 最后放置的 FMC（含 AIC，AIC 等同 FMC）的<b>附着面方块</b>所在 Create 应力网络的
     * <b>剩余应力</b>（su = 总容量 − 当前总应力，过载时为负）。
     * <p>
     * 附着面方块 = FMC 支撑方向上的方块（FMC：FACE/FACING 决定的支撑面；AIC：FACING 背面），
     * 参考点语义与 {@link #getPhysicsCenterOfMassRel()} 相同（机体上多个 FMC 时取最后放置的）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），且最后放置的 FMC 的附着面方块必须是 Create 动力方块
     * （{@link KineticBlockEntity}，如齿轮箱/传动轴/螺旋桨轴承）且读数成功，
     * 否则返回 nil；不在物理体上同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getStressRemaining() {
        return attachedStressAvailable ? attachedCapacity - attachedStress : null;
    }

    /**
     * 最后放置的 FMC（含 AIC，AIC 等同 FMC）的<b>附着面方块</b>所在 Create 应力网络的
     * <b>总容量</b>（su）。
     * <p>
     * 门控（存在性）与 {@link #getStressRemaining()} 相同：所在物理体（含约束链）上必须有
     * ≥1 个飞行管理计算机（FMC，ccpe:fmc），且最后放置的 FMC 的附着面方块必须是 Create
     * 动力方块（{@link KineticBlockEntity}）且读数成功，否则返回 nil；不在物理体上同样返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getStressCapacity() {
        return attachedStressAvailable ? attachedCapacity : null;
    }

    // ═══════════════ 螺旋桨转速工具（门控：机体（含约束链）上有 ≥1 个 FMC） ═══════════════

    /**
     * 初始化螺旋桨参数（求解所需转速前必须调用一次）。
     * <p>
     * 参数来自 aeronautics 的螺旋桨（Propeller Bearing）装配：
     * <ul>
     * <li><b>N</b>：螺旋桨（Propeller Bearing）数量；</li>
     * <li><b>S</b>：每个螺旋桨上动力方块的数量（风帆 / 对称风帆 / 羊毛方块）。</li>
     * </ul>
     * 转速求解使用以下 aeronautics 配置（aeronautics &gt; server &gt; Physics）：
     * <ul>
     * <li><b>T</b>：Propeller Bearing Thrust，默认 0.2；</li>
     * <li><b>A</b>：Propeller Bearing Airflow，默认 0.05。</li>
     * </ul>
     * 配置值每 tick 由主线程缓存（改配置后最多滞后 1 tick 生效）。
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 false。
     *
     * @param n 螺旋桨数量（≥ 1）
     * @param s 每个螺旋桨上的动力方块数量（≥ 1）
     * @return 是否初始化成功（门控不满足或参数非法返回 false）
     */
    @LuaFunction
    public final boolean initPropeller(double n, double s) {
        if (!propellerGateAvailable) return false;
        if (n < 1 || s < 1) return false;
        propellerCount = (int) Math.floor(n);
        sailsPerPropeller = (int) Math.floor(s);
        propellerInit = true;
        return true;
    }

    /**
     * 求解螺旋桨要达到需求输出所需的转速 R（与 aeronautics 转速单位一致，同
     * {@code PropellerBearingBlockEntity} 的转速语义）。
     * <p>
     * 公式（由 aeronautics 推力/气流模型反解）：
     * <pre>{@code
     * R = F / (P × S^1.5 × N × T) + V × sin(θ) / (S^0.5 × A)
     * }</pre>
     * 其中：
     * <ul>
     * <li><b>F</b>：期望推力（与 aeronautics 推力单位一致）；</li>
     * <li><b>P</b>：气压（大气压分数，海平面 = 1.0，可用 {@link #getPressure()}）；</li>
     * <li><b>V</b>：速度（m/s，机体当前速度，可用 {@link #getSpeed()}）；</li>
     * <li><b>θ</b>：螺旋桨平面与速度方向的夹角（度，可选，默认 0）；</li>
     * <li><b>N</b>、<b>S</b>：{@link #initPropeller(double, double)} 设置的螺旋桨参数；</li>
     * <li><b>T</b>、<b>A</b>：aeronautics 配置（见 {@link #initPropeller(double, double)}）。</li>
     * </ul>
     * <p>
     * <b>门控（存在性）</b>：所在物理体（含约束链）上必须有 ≥1 个飞行管理计算机
     * （FMC，ccpe:fmc），否则返回 nil。
     *
     * @param force   期望推力 F
     * @param pressure 气压 P（必须 &gt; 0）
     * @param velocity 速度 V（m/s）
     * @param thetaDeg 螺旋桨平面与速度方向的夹角 θ（度，可选，缺省 = 0）
     * @return 所需转速 R；未 init / 门控不满足 / 参数非法返回 nil
     */
    @LuaFunction
    public final @Nullable Double getPropellerRPM(double force, double pressure, double velocity,
                                                  Optional<Double> thetaDeg) {
        if (!propellerGateAvailable || !propellerInit) return null;
        if (pressure <= 0) return null;
        double s = sailsPerPropeller;
        double n = propellerCount;
        double theta = thetaDeg.map(Math::toRadians).orElse(0.0);
        double baseRpm = force / (pressure * Math.pow(s, 1.5) * n * propellerBearingThrust);
        double flowRpm = velocity * Math.sin(theta) / (Math.sqrt(s) * propellerBearingAirflow);
        return baseRpm + flowRpm;
    }

    /**
     * 刷新 aeronautics 螺旋桨配置静态缓存（T：Propeller Bearing Thrust，A：Propeller Bearing Airflow）。
     * <p>
     * 调用时机：进游戏（服务器启动）与放置/加载 FMC（{@link FmcBlockEntity#onLoad()}）时调用一次；
     * 不随每 tick 刷新。aeronautics 为必须依赖，但仍防御性兜底——读取失败时保留默认值（0.2 / 0.05）。
     */
    public static void refreshAeroConfig() {
        try {
            propellerBearingThrust = AeroConfig.server().physics.propellerBearingThrust.get();
            propellerBearingAirflow = AeroConfig.server().physics.propellerBearingAirflowMult.get();
        } catch (Exception ignored) {
            // aeronautics 配置不可用时保留默认值（0.2 / 0.05）
        }
    }

    // ═══════════════ 航行灯控制（门控：机体（含约束链）上有 ≥1 个 FMC） ═══════════════

    /**
     * 按颜色开关所在物理体（含约束链）上的全部航行灯（红/绿/白 position light）。
     * <p>
     * 调用时即时扫描约束链上各 sub-level 的 plot 包围盒收集航行灯（不依赖缓存，无陈旧数据）；
     * 对每个匹配灯直接写服务端方块状态 {@code LIT}（{@code mainThread=true}，与
     * {@link #setRedstoneOutput(int, int)} 同款服务端写模式）。航行灯不响应红石，
     * 亮灭只由本方法与玩家右键控制。
     * <p>
     * <b>门控（存在性）</b>：电脑必须在物理体上，且所在物理体（含约束链）上有 ≥1 个
     * 飞行管理计算机（FMC，ccpe:fmc），否则返回 0（一盏都不设置）。
     *
     * @param color 颜色："red" / "green" / "white" / "all"（全部颜色）；大小写不敏感
     * @param on    目标亮灭（true = 亮）
     * @return 实际写入（状态发生变化）的灯数量；门控不满足或颜色非法返回 0
     */
    @LuaFunction(mainThread = true)
    public final int setLights(String color, boolean on) {
        SubLevel sub = resolveSubLevel();
        if (sub == null) return 0;
        if (!hasFmcOnBody(sub)) return 0;
        String want = color.trim().toLowerCase(Locale.ROOT);
        if (!want.equals("all") && !want.equals("red") && !want.equals("green") && !want.equals("white")) {
            return 0;
        }
        int count = 0;
        for (SubLevel s : SableCompat.getConnectedChain(sub)) {
            LevelPlot plot = s.getPlot();
            if (plot == null) continue;
            BoundingBox3ic bb = plot.getBoundingBox();
            if (bb.maxX() < bb.minX()) continue; // 空包围盒（BoundingBox3i.EMPTY）
            Level level = s.getLevel();
            for (BlockPos p : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
                BlockState st = level.getBlockState(p);
                String lightColor = lightColorOf(st.getBlock());
                if (lightColor == null) continue;
                if (!want.equals("all") && !want.equals(lightColor)) continue;
                if (st.getValue(BlockStateProperties.LIT) != on) {
                    level.setBlock(p, st.setValue(BlockStateProperties.LIT, on), 3);
                    count++;
                }
            }
        }
        return count;
    }

    /** 航行灯方块 → 颜色名（"red"/"green"/"white"）；非航行灯方块返回 null */
    private static @Nullable String lightColorOf(Block block) {
        if (block == RegistrateBlocks.RED_POSITION_LIGHT.get()) return "red";
        if (block == RegistrateBlocks.GREEN_POSITION_LIGHT.get()) return "green";
        if (block == RegistrateBlocks.WHITE_POSITION_LIGHT.get()) return "white";
        return null;
    }

    /**
     * 开关所在物理体（含约束链）上的<b>全部</b>航行灯（所有颜色）。
     * <p>
     * 等价于 {@link #setLights(String, boolean)} 传 {@code "all"}：调用时即时扫描
     * 约束链各 sub-level 的 plot 包围盒，对每个航行灯写服务端方块状态 {@code LIT}。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #setLights(String, boolean)} 相同——电脑必须在
     * 物理体上，且所在物理体（含约束链）上有 ≥1 个飞行管理计算机（FMC，ccpe:fmc），
     * 否则返回 0（一盏都不设置）。
     *
     * @param on 目标亮灭（true = 亮）
     * @return 实际写入（状态发生变化）的灯数量；门控不满足返回 0
     */
    @LuaFunction(mainThread = true)
    public final int setAllLights(boolean on) {
        return setLights("all", on);
    }

    /** 所在物理体（含约束链）上是否有 ≥1 个 FMC（航行灯控制门控，与物理数据门控同源） */
    private static boolean hasFmcOnBody(SubLevel sub) {
        for (SensorEntry e : BodySensorRegistry.sensorsOnBody(sub))
            if (e.type() == SensorType.FMC) return true;
        return false;
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
     * 全部皮托管对地速度（沿各自管口轴线的有符号分量，m/s）的<b>简单平均值</b>。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #getSpeed()} 相同——物理体（含约束链）必须<b>同时</b>
     * 有 ≥1 皮托管 且 ≥1 静压孔（皮托管-静压系统），否则返回 nil。
     * 门控满足时，各皮托管读数均来自同一 tick 快照（若个别读数不可用则跳过）。
     */
    @LuaFunction
    public final @Nullable Double getAverageSpeed() {
        double sum = 0;
        int count = 0;
        for (SensorSnapshot s : sensors) {
            if (s.type() != SensorType.SPEED || s.speed() == null) continue;
            sum += s.speed();
            count++;
        }
        return count > 0 ? sum / count : null;
    }

    /**
     * 全部皮托管空速（沿各自管口轴线的有符号分量，相对空气已减风速，m/s）的<b>简单平均值</b>。
     * <p>
     * <b>门控（存在性）</b>：与 {@link #getAirSpeed()} 相同——物理体（含约束链）必须<b>同时</b>
     * 有 ≥1 皮托管 且 ≥1 静压孔（皮托管-静压系统），否则返回 nil。
     * 门控满足时，各皮托管读数均来自同一 tick 快照（若个别读数不可用则跳过）。
     */
    @LuaFunction
    public final @Nullable Double getAverageAirSpeed() {
        double sum = 0;
        int count = 0;
        for (SensorSnapshot s : sensors) {
            if (s.type() != SensorType.SPEED || s.airSpeed() == null) continue;
            sum += s.airSpeed();
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

    // ═══════════════ 短程信号链接器（并入 sensor_system：原 ccpe.link 的四个方法 + NBT 缓存三方法） ═══════════════
    //
    // 寻址模型：频道号是设备在物理体内的「地址」（同体内 1:1，冲突自动顺延，见
    // ShortRangeLinkerRegistry），查询方（电脑）不需要自己的频道号；
    // 作用域 = 调用电脑所在物理体（含约束链），由 update() 主线程刷新的 chainUuids 缓存决定
    // （电脑不在物理体上 → 空集合 → 一律 nil，严格语义与「非物理体不链接」一致）。
    // 频道空间同时容纳链接器与控制台：getPeripheral 先找链接器（返回附着方块外设），
    // 频道被控制台占用时返回控制台自身外设（ControlDeskRegistry 委托同一注册表）。
    // NBT 缓存与 pe 不同：默认关闭，需 enableNbtCache 显式开启并配置刷新间隔（默认 20 tick），
    // 开启后服务端按间隔缓存附着方块 NBT，getNbt / getAllNbt 直读该缓存（mainThread=false）。

    /**
     * 本机物理体（含约束链）内频道 {@code channel} 对应设备的外设（IPeripheral）：
     * <ul>
     *   <li>频道被链接器占用 → 链接器所附着方块的外设；</li>
     *   <li>频道被控制台占用 → 控制台自身外设（{@code ccpe:control_desk}）。</li>
     * </ul>
     * 电脑不在任何物理体上 / 频道未被同体设备占用 / 附着方块无 CC:T 外设时返回 nil。
     *
     * @param channel 目标设备的频道号
     * @return 目标设备外设；未命中返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Object getPeripheral(int channel) {
        // 链接器：返回附着方块外设
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        if (linker != null) {
            Level level = linker.getLevel();
            if (level == null) return null;
            BlockState state = linker.getBlockState();
            BlockPos attachedPos = ShortRangeLinkerBlock.getAttachedPos(state, linker.getBlockPos());
            BlockEntity attached = level.getBlockEntity(attachedPos);
            // 附着方块自身就是 CC:T 外设（如 Monitor）→ 直接返回；否则走 Capability 查询
            if (attached instanceof IPeripheral p) return p;
            return level.getCapability(PeripheralCapability.get(), attachedPos, sideFromAttachedView(state));
        }
        // 控制台：返回控制台自身外设（与链接器共用同一物理体作用域频道空间）
        ControlDeskBlockEntity desk = ControlDeskRegistry.get(chainUuids, channel);
        if (desk != null) return desk.getPeripheral();
        return null;
    }

    /**
     * 目标链接器当前的红石输出信号（0-15，只读，mainThread=false）。
     * 未命中（电脑不在物理体上 / 频道空闲）返回 0。
     */
    @LuaFunction
    public final int getRedstoneOutput(int channel) {
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        return linker != null ? linker.getRedstoneOutput() : 0;
    }

    /**
     * 目标链接器位置当前接收到的最强红石信号（0-15，只读，mainThread=false）。
     * 未命中返回 0。
     */
    @LuaFunction
    public final int getRedstoneInput(int channel) {
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        return linker != null ? linker.getRedstoneInput() : 0;
    }

    /**
     * 写目标链接器的红石输出（0-15，越界自动钳位），并更新方块 POWERED 状态
     * （相邻红石线 / 红石机械随之响应；mainThread=true）。
     */
    @LuaFunction(mainThread = true)
    public final void setRedstoneOutput(int channel, int signal) {
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        if (linker != null) linker.setRedstoneOutput(Math.clamp(signal, 0, 15));
    }

    // ═══════════════ 附着方块 NBT 缓存（与 pe 不同：默认关闭，需 enableNbtCache 显式开启） ═══════════════

    /**
     * 开启 / 关闭 / 调整目标链接器的<b>附着方块 NBT 缓存</b>并设置刷新间隔。
     * <p>
     * 与 {@code ccpe.pe}（按需缓存、永远开启）不同，短程信号链接器<b>默认不缓存</b>NBT：
     * 本方法显式开启后，服务端每 {@code ticks} tick 刷新一次附着方块的 NBT 快照（照
     * {@link ShortRangeLinkerBlockEntity#setNbtCache}），随后 {@link #getNbt(int, String)} /
     * {@link #getAllNbt(int)} 读取该缓存。
     * <ul>
     *   <li>{@code ticks} 缺省 = 20（默认刷新间隔）；</li>
     *   <li>{@code ticks <= 0} → 关闭缓存（保留已有快照，读取方法返回 nil/空表）；</li>
     *   <li>已开启时重复调用仅修改间隔；开启/修改后下一个服务端 tick 立即刷新一次快照。</li>
     * </ul>
     * 开关与间隔随 NBT / Create 蓝图持久化（世界重载、蓝图部署后保持）。
     * <p>
     * mainThread=true：写链接器 BE 状态并置脏。
     *
     * @param channel 目标链接器频道号（本机物理体内）
     * @param ticks   刷新间隔（tick），可选，缺省 20；≤ 0 表示关闭缓存
     * @return 是否成功（目标链接器存在；频道空闲 / 电脑不在物理体上返回 false）
     */
    @LuaFunction(mainThread = true)
    public final boolean enableNbtCache(int channel, Optional<Double> ticks) {
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        if (linker == null) return false;
        int interval = (int) Math.floor(ticks.orElse(20.0));
        linker.setNbtCache(interval);
        return true;
    }

    /**
     * 读取目标链接器缓存的<b>附着方块 NBT</b>中路径 {@code path} 处的值
     * （mainThread=false，直读 volatile 缓存，零主线程调度；路径语法与
     * {@code ccpe.pe.get} 相同，如 {@code "ForgeData.Items[0].Count"}）。
     * <p>
     * 缓存未开启（默认）或快照为空 → 返回 nil。
     *
     * @param channel 目标链接器频道号（本机物理体内）
     * @param path    NBT 路径（点号 / 下标语法）
     * @return 路径处的值（标量 / table / 列表）；未开启缓存或路径不存在返回 nil
     */
    @LuaFunction
    public final @Nullable Object getNbt(int channel, String path) {
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        if (linker == null || !linker.isNbtCacheEnabled()) return null;
        CompoundTag nbt = linker.getCachedAttachedNBT();
        if (nbt == null || nbt.isEmpty()) return null;
        return PeripheralExtenderAPI.resolvePath(nbt, path);
    }

    /**
     * 读取目标链接器缓存的<b>附着方块 NBT</b>全量（转 Lua table，mainThread=false 直读缓存）。
     * <p>
     * 缓存未开启（默认）或快照为空 → 返回空表。
     *
     * @param channel 目标链接器频道号（本机物理体内）
     * @return 全量 NBT 转换后的 table；未开启缓存返回空表
     */
    @LuaFunction
    public final Map<String, Object> getAllNbt(int channel) {
        ShortRangeLinkerBlockEntity linker = ShortRangeLinkerRegistry.getLinker(chainUuids, channel);
        if (linker == null || !linker.isNbtCacheEnabled()) return Collections.emptyMap();
        CompoundTag nbt = linker.getCachedAttachedNBT();
        if (nbt == null || nbt.isEmpty()) return Collections.emptyMap();
        return PeripheralExtenderAPI.convertCompoundToMap(nbt);
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
            case FMC -> "fmc"; // 飞行管理计算机：物理数据门控，无读数
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

    /**
     * 最后放置（最新注册）的 FMC 传感器（AIC 等同 FMC，也登记 FMC 类型）的<b>方块中心</b>
     * 相对物理体原点的局部坐标（plot 帧，与 {@link SableCompat#getCenterOfMassLocal} 同帧，
     * 可直接相减）。参考点 = FMC 方块中心 = BlockPos（角点）+ {@link #BLOCK_CENTER_OFFSET}
     * 半格偏移；机体上无 FMC（门控）时返回 null。
     */
    private @Nullable Vec3 lastFmcRel(SubLevel sub, List<SensorEntry> entries) {
        SensorEntry lastFmc = null;
        for (SensorEntry e : entries)
            if (e.type() == SensorType.FMC) lastFmc = e; // 注册顺序 = 放置顺序，取最后
        if (lastFmc == null) return null;
        Vec3 rel = SableCompat.toRelativePos(sub, lastFmc.pos());
        if (rel == null) return null;
        return new Vec3(rel.x + BLOCK_CENTER_OFFSET, rel.y + BLOCK_CENTER_OFFSET, rel.z + BLOCK_CENTER_OFFSET);
    }

    /**
     * 刷新附着方块应力网络缓存：最后放置（最新注册）的 FMC 传感器（AIC 等同 FMC）的
     * 附着面方块若为 Create 动力方块（{@link KineticBlockEntity}），读其所在应力网络的
     * 总应力/总容量（{@link CreateStressReadout}，缓存值）与公开读数；任一环节失败 → 重置为空。
     */
    private void computeAttachedStress(SubLevel sub, List<SensorEntry> entries) {
        SensorEntry lastFmc = null;
        for (SensorEntry e : entries)
            if (e.type() == SensorType.FMC) lastFmc = e; // 注册顺序 = 放置顺序，取最后
        if (lastFmc == null) {
            resetAttachedStress();
            return;
        }
        Level level = sub.getLevel();
        BlockState fmcState = level.getBlockState(lastFmc.pos());
        BlockPos attachedPos = attachedBlockPos(fmcState, lastFmc.pos());
        BlockEntity be = attachedPos != null ? level.getBlockEntity(attachedPos) : null;
        if (!(be instanceof KineticBlockEntity kbe)) {
            resetAttachedStress();
            return;
        }
        CreateStressReadout.StressInfo info = CreateStressReadout.stressOf(kbe);
        if (info == null) {
            resetAttachedStress();
            return;
        }
        attachedStressAvailable = true;
        attachedStress = info.stress();
        attachedCapacity = info.capacity();
    }

    /** 传感器方块（FMC/AIC）的附着面方块坐标；非 FMC/AIC 方块返回 null */
    private @Nullable BlockPos attachedBlockPos(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof FmcBlock) {
            return pos.relative(FmcBlock.supportDirectionOf(state));
        }
        if (state.getBlock() instanceof AicBlock) {
            return pos.relative(state.getValue(AicBlock.FACING).getOpposite());
        }
        return null;
    }

    /** 附着方块应力网络缓存重置为空（不可读） */
    private void resetAttachedStress() {
        attachedStressAvailable = false;
        attachedStress = 0;
        attachedCapacity = 0;
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

    /** 物理体（含约束链）的全部子次元 UUID 集合（主线程 update() 计算，供 Lua 线程只读缓存） */
    private static Set<UUID> chainUuidsOf(SubLevel sub) {
        Set<UUID> ids = new HashSet<>();
        for (SubLevel s : SableCompat.getConnectedChain(sub)) {
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** 从附着方块的视角看链接器所在的面（照 PeripheralExtenderAPI.getSensorSide） */
    private static Direction sideFromAttachedView(BlockState state) {
        return switch (state.getValue(ShortRangeLinkerBlock.FACE)) {
            case FLOOR -> Direction.UP;      // 链接器在地面 → 附着方块在下方 → 从附着方块看是 UP
            case CEILING -> Direction.DOWN;  // 链接器在天花板 → 附着方块在上方 → 从附着方块看是 DOWN
            case WALL -> state.getValue(ShortRangeLinkerBlock.FACING);
        };
    }
}
