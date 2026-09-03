package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.PitotTubeBlock;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorEntry;
import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry.SensorType;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 飞行数据记录器（调试工具，方案 B v1 + 控制输入）。
 * <p>
 * 服务端每个 ServerTick（可配置间隔）对<b>每个已注册 FMC（含 AIC）的物理体</b>采样一行，
 * 写入 {@code <gameDir>/flight_logs/flight_<维度>_<bodyUuid前8>_<起始tick>.csv}。
 * <p>
 * 数据列 = tick/时间/机体 UUID + 机体原点世界坐标 + 姿态四元数 + 欧拉角（pitch/roll/yaw，
 * 与 {@code sensor_system.getAngles()} 同约定）+ 世界系线速度/角速度 + 机体系角速度
 * + 静压孔平均气压/高度 + 皮托管沿管口对地速度/空速 + 质量/链质量/重心（世界 + 相对原点）/
 * 链质心（相对原点）+ <b>受力</b>（LIFT/DRAG/PROPULSION 力组：合力 F 与绕质心合力矩 M，
 * 均机体局部系，由 Sable {@code QueuedForceGroup} 点力重算：F=Σf、M=Σ(point−comPlot)×f）
 * + <b>控制输入</b>（摇杆2 ch7、油门 ch8、脚踏板 ch6，按本机座舱接线，
 * 见 {@link #CHANNEL_CONTROL_DESK} / {@link #CHANNEL_JOYSTICK} / {@link #CHANNEL_THROTTLE}；
 * 频道在物理体链内寻址 = Lua {@code ss.getPeripheral(ch)} 同源，控制台 BE 服务端直读，
 * 数据源与 joystick_2 / throttle / pedal 模块句柄一致）。数值读取失败列写 {@code nan}，
 * 对应频道无控制台时通道列写 -1；力组不存在（无对应力源）时力列为 nan。
 * <p>
 * <b>受力前提</b>：记录器对主机及其整条约束链（含 aero_bearing 从动 sub-level）调用
 * {@code ServerSubLevel.enableIndividualQueuedForcesTracking(true)}（Simulated 图纸同款机制，
 * 每 tick 幂等开启、文件关闭时对当前链恢复 false）；否则 LIFT/DRAG 组点力不会被 Sable 记录。
 * 采样时机 = ServerTick（每游戏 tick 末），读到的是该 tick 最后一个物理步记录的力组（组在每个
 * 物理步开始被 reset）；phugoid 级分析足够。力/力矩单位与 Sable 内部一致（每物理步冲量刻度）。
 * <p>
 * 只读 Sable / 控制台 BE 公开 API，不改任何物理或控制行为；开关/采样间隔见
 * {@link Config#FLIGHT_RECORDER_ENABLED}（默认开，仅在有 FMC 物理体时产生文件）。
 */
public final class FlightDataRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("ccpe:FlightDataRecorder");

    // ═══════════ 控制输入所在控制台频道（物理体链内寻址，同 Lua ss.getPeripheral）═══════════
    /** 仪表台控制台（含 monitor + 脚踏板）频道——与你的座舱接线一致，变了改这里 */
    private static final int CHANNEL_CONTROL_DESK = 6;
    /** 摇杆2 控制台频道 */
    private static final int CHANNEL_JOYSTICK = 7;
    /** 油门杆控制台频道 */
    private static final int CHANNEL_THROTTLE = 8;

    private static final String[] HEADER = {
            "tick", "time_s", "body",
            "x", "y", "z",
            "qx", "qy", "qz", "qw",
            "pitchDeg", "rollDeg", "yawDeg",
            "vX", "vY", "vZ", "vNorm",
            "wX", "wY", "wZ",
            "wbX", "wbY", "wbZ",
            "pressure", "altitude", "airSpeed", "groundSpeed",
            "massKg", "chainMassKg",
            "comX", "comY", "comZ",
            "comRelX", "comRelY", "comRelZ",
            "chainComRelX", "chainComRelY", "chainComRelZ",
            "liftFx", "liftFy", "liftFz", "liftMx", "liftMy", "liftMz",
            "dragFx", "dragFy", "dragFz", "dragMx", "dragMy", "dragMz",
            "propFx", "propFy", "propFz", "propMx", "propMy", "propMz",
            "chainLiftFx", "chainLiftFy", "chainLiftFz", "chainLiftMx", "chainLiftMy", "chainLiftMz",
            "chainDragFx", "chainDragFy", "chainDragFz", "chainDragMx", "chainDragMy", "chainDragMz",
            "chainPropFx", "chainPropFy", "chainPropFz", "chainPropMx", "chainPropMy", "chainPropMz",
            "joyCh", "joyX", "joyY", "joyXA", "joyYA",
            "thrCh", "thrAxis", "thrGear", "thrFwd", "thrBack",
            "pedCh", "pedL", "pedR"
    };

    private static final double NaN = Double.NaN;

    /** 每个物理体一个 CSV 写者（按 UUID 键） */
    private static final Map<UUID, RowWriter> WRITERS = new HashMap<>();

    private FlightDataRecorder() {}

    /** 服务端每 tick（主线程）。开关关或没有 FMC 物理体时无事发生 */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.FLIGHT_RECORDER_ENABLED.get()) {
            closeAll();
            return;
        }
        int interval = Config.FLIGHT_RECORDER_INTERVAL_TICKS.get();
        if (interval <= 0) {
            closeAll();
            return;
        }
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        if (tick % interval != 0) return;

        try {
            List<ServerSubLevel> bodies = BodySensorRegistry.fmcBodies(server);
            Set<UUID> alive = new HashSet<>();
            for (ServerSubLevel sub : bodies) {
                UUID id = SableCompat.getSubLevelUUID(sub);
                if (id == null) continue;
                alive.add(id);
                RowWriter writer = WRITERS.get(id);
                // 维度/level 变了（重装、跨维度）→ 换新文件
                if (writer == null || writer.level != sub.getLevel()) {
                    if (writer != null) writer.close();
                    try {
                        writer = new RowWriter(sub, tick);
                        WRITERS.put(id, writer);
                    } catch (IOException e) {
                        LOGGER.error("Failed to open flight log for body {}: {}", id, e.toString());
                        WRITERS.remove(id);
                        continue;
                    }
                }
                // 开启整条约束链的力组跟踪（含 aero_bearing 从动 sub-level 的尾翼/副翼面；
                // 幂等，重复调用无副作用）
                for (SubLevel chainMember : SableCompat.getConnectedChain(sub)) {
                    if (chainMember instanceof ServerSubLevel serverMember) {
                        try {
                            serverMember.enableIndividualQueuedForcesTracking(true);
                        } catch (Exception ignored) {}
                    }
                }
                writer.writeRow(sub, tick);
            }
            // 关闭已消失机体的写者（拆卸/卸载）
            WRITERS.entrySet().removeIf(entry -> {
                if (!alive.contains(entry.getKey())) {
                    entry.getValue().close();
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            LOGGER.debug("FlightDataRecorder tick failed: {}", e.toString());
        }
    }

    /** 服务器停止 / 记录器关闭：关掉全部文件 */
    public static void closeAll() {
        for (RowWriter w : WRITERS.values()) w.close();
        WRITERS.clear();
    }

    /** 每物理体一个 CSV 文件：创建时写表头 + 开启 Sable 力组跟踪；关闭时恢复 */
    private static final class RowWriter implements AutoCloseable {
        private final ServerLevel level;
        private final ServerSubLevel sub;
        private final boolean trackingEnabled;
        private final BufferedWriter out;

        RowWriter(ServerSubLevel sub, long startTick) throws IOException {
            this.level = sub.getLevel();
            this.sub = sub;
            // 开启单体力组跟踪（同 Simulated 图纸 DiagramEntity）：否则 LIFT/DRAG 点力不记录
            sub.enableIndividualQueuedForcesTracking(true);
            this.trackingEnabled = true;
            Path dir = FMLPaths.GAMEDIR.get().resolve("flight_logs");
            Files.createDirectories(dir);
            String dim = level.dimension().location().getPath().replaceAll("[^A-Za-z0-9_.-]", "_");
            UUID body = sub.getUniqueId();
            String id8 = body != null ? body.toString().replace("-", "").substring(0, 8) : "unknown";
            Path file = dir.resolve(String.format(Locale.ROOT, "flight_%s_%s_%d.csv", dim, id8, startTick));
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            out.write(String.join(",", HEADER));
            out.newLine();
        }

        void writeRow(ServerSubLevel sub, long tick) {
            try {
                out.write(sampleRow(sub, tick));
                out.newLine();
                out.flush();
            } catch (Exception e) {
                LOGGER.debug("Flight log row write failed (body {}): {}", SableCompat.getSubLevelId(sub), e.toString());
            }
        }

        @Override
        public void close() {
            try {
                out.close();
            } catch (IOException ignored) {}
            if (trackingEnabled) {
                try {
                    for (SubLevel chainMember : SableCompat.getConnectedChain(sub)) {
                        if (chainMember instanceof ServerSubLevel serverMember) {
                            serverMember.enableIndividualQueuedForcesTracking(false);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 采样一行（字段顺序与 {@link #HEADER} 严格一致）。所有读数失败为 nan；采样整体失败时
     * 输出一行全 nan 占位（保持列对齐），便于 Python 直接解析。
     */
    private static String sampleRow(ServerSubLevel sub, long tick) {
        List<String> f = new ArrayList<>(HEADER.length);
        try {
            f.add(String.valueOf(tick));
            f.add(String.format(Locale.ROOT, "%.3f", tick / 20.0));
            UUID id = SableCompat.getSubLevelUUID(sub);
            f.add(id != null ? id.toString() : "null");

            // ── 机体位姿 / 运动学 ──
            double[] pose = poseOf(sub);              // x y z qx qy qz qw（世界）
            double[] euler = eulerDeg(sub);           // pitchDeg rollDeg yawDeg（sensor_system 同约定）
            Vec3 lin = SableCompat.getLinearVelocity(sub.getLevel(), sub);
            Vec3 ang = SableCompat.getAngularVelocity(sub.getLevel(), sub);
            double[] wb = bodyAngVel(sub, ang);
            for (double v : pose) f.add(n(v));
            for (double v : euler) f.add(n(v));
            f.add(n(lin != null ? lin.x : NaN));
            f.add(n(lin != null ? lin.y : NaN));
            f.add(n(lin != null ? lin.z : NaN));
            f.add(n(lin != null ? lin.length() : NaN));
            f.add(n(ang != null ? ang.x : NaN));
            f.add(n(ang != null ? ang.y : NaN));
            f.add(n(ang != null ? ang.z : NaN));
            f.add(n(wb[0]));
            f.add(n(wb[1]));
            f.add(n(wb[2]));

            // ── 环境（静压孔/皮托管，门控与 sensor_system 同）──
            double[] env = sensorEnv(sub);            // pressure altitude airSpeed groundSpeed
            for (double v : env) f.add(n(v));

            // ── 质量 / 重心 ──
            Double mass = SableCompat.getMass(sub);
            Double chainMass = SableCompat.getChainMass(sub);
            Vec3 comW = SableCompat.getCenterOfMass(sub);
            Vec3 comL = SableCompat.getCenterOfMassLocal(sub);
            Vec3 chainL = SableCompat.getChainCenterOfMassLocal(sub);
            f.add(n(mass != null ? mass : NaN));
            f.add(n(chainMass != null ? chainMass : NaN));
            f.add(n(comW != null ? comW.x : NaN));
            f.add(n(comW != null ? comW.y : NaN));
            f.add(n(comW != null ? comW.z : NaN));
            f.add(n(comL != null ? comL.x : NaN));
            f.add(n(comL != null ? comL.y : NaN));
            f.add(n(comL != null ? comL.z : NaN));
            f.add(n(chainL != null ? chainL.x : NaN));
            f.add(n(chainL != null ? chainL.y : NaN));
            f.add(n(chainL != null ? chainL.z : NaN));

            // ── 受力（Sable 力组点力重算：机体局部系合力 F + 绕 plot 质心合力矩 M）──
            Vec3 comPlot = SableCompat.getCenterOfMassPlot(sub);
            appendForces(f, sub, comPlot);
            // ── 整链受力（方案 A：链上全部 sub-level 的力组合并，绕主机世界质心求矩后转回主机局部系）──
            Vec3 mainComWorld = SableCompat.getCenterOfMass(sub);
            appendChainForces(f, sub, mainComWorld);

            // ── 控制输入（控制台频道寻址，BE 服务端直读，同模块句柄数据源）──
            appendControls(f, sub);
        } catch (Exception e) {
            LOGGER.debug("Flight log sampling failed (body {}): {}", SableCompat.getSubLevelId(sub), e.toString());
            return tick + "," + String.format(Locale.ROOT, "%.3f", tick / 20.0) + ","
                    + (SableCompat.getSubLevelUUID(sub) != null ? SableCompat.getSubLevelUUID(sub) : "null")
                    + "," + String.join(",", Collections.nCopies(HEADER.length - 3, "nan"));
        }
        return String.join(",", f);
    }

    /** Sable 力组注册表 key（sable:force_groups）。不直接引用 ForceGroups 类（其静态字段类型引用 veil 的
     *  RegistryObject，veil 不在编译 classpath），改为运行时按注册表 id 匹配力组。 */
    private static final ResourceKey<Registry<ForceGroup>> FORCE_GROUP_REGISTRY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("sable", "force_groups"));

    /**
     * 追加 LIFT / DRAG / PROPULSION 三个力组（固定顺序，各 6 列）：
     * 合力 F（机体局部系）+ 绕 plot 质心的合力矩 M = Σ(point − comPlot) × force（局部系）。
     * 力组不存在 / 无点力 / 注册表匹配失败 → 对应组全 nan；comPlot 读不到时只记合力、力矩 nan。
     */
    private static void appendForces(List<String> f, ServerSubLevel sub, Vec3 comPlot) {
        double[][] acc = new double[3][6];   // [lift, drag, propulsion][Fx,Fy,Fz,Mx,My,Mz]
        boolean[] any = new boolean[3];
        try {
            Registry<ForceGroup> registry = forceGroupRegistry(sub);
            Map<ForceGroup, QueuedForceGroup> groups = sub.getQueuedForceGroups();
            if (groups != null) {
                for (Map.Entry<ForceGroup, QueuedForceGroup> entry : groups.entrySet()) {
                    int slot = groupSlot(registry, entry.getKey());
                    if (slot < 0) continue;
                    double[] out = acc[slot];
                    List<QueuedForceGroup.PointForce> points = entry.getValue().getRecordedPointForces();
                    if (points == null || points.isEmpty()) continue;
                    double cx = comPlot != null ? comPlot.x : 0;
                    double cy = comPlot != null ? comPlot.y : 0;
                    double cz = comPlot != null ? comPlot.z : 0;
                    for (QueuedForceGroup.PointForce pf : points) {
                        Vector3dc p = pf.point();
                        Vector3dc fo = pf.force();
                        out[0] += fo.x();
                        out[1] += fo.y();
                        out[2] += fo.z();
                        if (comPlot != null) {
                            double rx = p.x() - cx;
                            double ry = p.y() - cy;
                            double rz = p.z() - cz;
                            out[3] += ry * fo.z() - rz * fo.y();
                            out[4] += rz * fo.x() - rx * fo.z();
                            out[5] += rx * fo.y() - ry * fo.x();
                        }
                    }
                    any[slot] = true;
                }
            }
        } catch (Exception ignored) {}
        for (int slot = 0; slot < 3; slot++) {
            if (any[slot]) {
                for (int k = 0; k < 6; k++) f.add(n(acc[slot][k]));
            } else {
                f.add("nan");
                f.add("nan");
                f.add("nan");
                f.add("nan");
                f.add("nan");
                f.add("nan");
            }
        }
    }

    /**
     * 整链力组聚合（方案 A）：遍历约束链上每个 ServerSubLevel 的 LIFT/DRAG/PROPULSION 点力，
     * 各自转到世界系后求和；力矩绕<b>主机的世界质心</b>计算；最后整体转回主机局部系。
     * 适用于尾翼/副翼在 aero_bearing 从动 sub-level 上的布局——主机自己的力组只有机身面，
     * 链级列才包含全部控制面（近似把约束链当刚体：aero bearing PD 锁定刚度高）。
     */
    private static void appendChainForces(List<String> f, ServerSubLevel main, Vec3 mainComWorld) {
        double[][] acc = new double[3][6];   // 世界系累计 [Fx,Fy,Fz,Mx,My,Mz]
        boolean[] any = new boolean[3];
        boolean comOk = mainComWorld != null;
        double ccx = comOk ? mainComWorld.x : 0;
        double ccy = comOk ? mainComWorld.y : 0;
        double ccz = comOk ? mainComWorld.z : 0;
        try {
            Registry<ForceGroup> registry = forceGroupRegistry(main);
            for (SubLevel member : SableCompat.getConnectedChain(main)) {
                if (!(member instanceof ServerSubLevel serverMember)) continue;
                Map<ForceGroup, QueuedForceGroup> groups = serverMember.getQueuedForceGroups();
                if (groups == null) continue;
                Pose3dc pose = serverMember.logicalPose();
                for (Map.Entry<ForceGroup, QueuedForceGroup> entry : groups.entrySet()) {
                    int slot = groupSlot(registry, entry.getKey());
                    if (slot < 0) continue;
                    double[] out = acc[slot];
                    List<QueuedForceGroup.PointForce> points = entry.getValue().getRecordedPointForces();
                    if (points == null || points.isEmpty()) continue;
                    for (QueuedForceGroup.PointForce pf : points) {
                        // 点力矢量：局部系 → 世界系（旋转）
                        Vector3d wf = pose.transformNormal(new Vector3d(pf.force()), new Vector3d());
                        // 施力点：plot 帧 → 世界
                        Vector3d wp = pose.transformPosition(new Vector3d(pf.point()), new Vector3d());
                        out[0] += wf.x();
                        out[1] += wf.y();
                        out[2] += wf.z();
                        if (comOk) {
                            double rx = wp.x() - ccx;
                            double ry = wp.y() - ccy;
                            double rz = wp.z() - ccz;
                            out[3] += ry * wf.z() - rz * wf.y();
                            out[4] += rz * wf.x() - rx * wf.z();
                            out[5] += rx * wf.y() - ry * wf.x();
                        }
                    }
                    any[slot] = true;
                }
            }
        } catch (Exception ignored) {}
        // 世界系合力/力矩 → 主机局部系（逆旋转），与其它局部系列一致
        Quaterniond invMain = new Quaterniond(main.logicalPose().orientation());
        for (int slot = 0; slot < 3; slot++) {
            if (any[slot]) {
                Vector3d fw = new Vector3d(acc[slot][0], acc[slot][1], acc[slot][2]);
                invMain.transformInverse(fw);
                f.add(n(fw.x()));
                f.add(n(fw.y()));
                f.add(n(fw.z()));
                Vector3d mw = new Vector3d(acc[slot][3], acc[slot][4], acc[slot][5]);
                invMain.transformInverse(mw);
                f.add(n(mw.x()));
                f.add(n(mw.y()));
                f.add(n(mw.z()));
            } else {
                f.add("nan");
                f.add("nan");
                f.add("nan");
                f.add("nan");
                f.add("nan");
                f.add("nan");
            }
        }
    }

    /** 该 level 的 sable:force_groups 注册表（读取失败返回 null → 组匹配不可用） */
    private static @Nullable Registry<ForceGroup> forceGroupRegistry(ServerSubLevel sub) {
        try {
            return sub.getLevel().registryAccess().registry(FORCE_GROUP_REGISTRY).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 力组 → 列槽位：0=lift 1=drag 2=propulsion；匹配不到返回 -1 */
    private static int groupSlot(@Nullable Registry<ForceGroup> registry, ForceGroup group) {
        if (registry == null) return -1;
        try {
            ResourceLocation id = registry.getKey(group);
            return switch (id != null ? id.getPath() : "") {
                case "lift" -> 0;
                case "drag" -> 1;
                case "propulsion" -> 2;
                default -> -1;
            };
        } catch (Exception e) {
            return -1;
        }
    }

    /** 控制输入列：摇杆2（ch7）+ 油门（ch8）+ 脚踏板（ch6）；频道内无控制台 → 通道列 -1、数值列 nan */
    private static void appendControls(List<String> f, ServerSubLevel sub) {
        Set<UUID> chain = chainUuidsOf(sub);
        ControlDeskBlockEntity joy = ControlDeskRegistry.get(chain, CHANNEL_JOYSTICK);
        ControlDeskBlockEntity thr = ControlDeskRegistry.get(chain, CHANNEL_THROTTLE);
        ControlDeskBlockEntity ped = ControlDeskRegistry.get(chain, CHANNEL_CONTROL_DESK);
        if (joy != null) {
            f.add(String.valueOf(CHANNEL_JOYSTICK));
            f.add(n(joy.getJoystick2AxisX()));
            f.add(n(joy.getJoystick2AxisY()));
            f.add(joy.isJoystick2XActive() ? "1" : "0");
            f.add(joy.isJoystick2YActive() ? "1" : "0");
        } else {
            f.add("-1");
            f.add("nan");
            f.add("nan");
            f.add("0");
            f.add("0");
        }
        if (thr != null) {
            f.add(String.valueOf(CHANNEL_THROTTLE));
            f.add(n(thr.getThrottleAxis()));
            f.add(String.valueOf(thr.getThrottleGear()));
            f.add(thr.isThrottleForwardActive() ? "1" : "0");
            f.add(thr.isThrottleBackActive() ? "1" : "0");
        } else {
            f.add("-1");
            f.add("nan");
            f.add("nan");
            f.add("0");
            f.add("0");
        }
        if (ped != null) {
            f.add(String.valueOf(CHANNEL_CONTROL_DESK));
            f.add(n(ped.getPedalLeftAxis()));
            f.add(n(ped.getPedalRightAxis()));
        } else {
            f.add("-1");
            f.add("nan");
            f.add("nan");
        }
    }

    /** 物理体（含约束链）的全部子次元 UUID 集合（控制台链内频道寻址用） */
    private static Set<UUID> chainUuidsOf(SubLevel sub) {
        Set<UUID> ids = new HashSet<>();
        for (SubLevel s : SableCompat.getConnectedChain(sub)) {
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** 机体原点世界位姿 {x,y,z, qx,qy,qz,qw}（logicalPose，同 sensor_system 姿态基准） */
    private static double[] poseOf(SubLevel sub) {
        try {
            Pose3dc pose = sub.logicalPose();
            return new double[]{
                    pose.position().x(), pose.position().y(), pose.position().z(),
                    pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w()
            };
        } catch (Exception e) {
            return new double[]{NaN, NaN, NaN, NaN, NaN, NaN, NaN};
        }
    }

    /**
     * 姿态欧拉角（度）{pitch, roll, yaw}——镜像 {@code SensorSystemAPI.computeAttitudeDeg}：
     * pitch/roll/yaw 约定与 {@code ss.getAngles()} 完全一致（你的实测：低头 = pitch 正、抬头 = pitch 负）。
     */
    private static double[] eulerDeg(SubLevel sub) {
        try {
            Pose3dc pose = sub.logicalPose();
            Vector3d ld = JOMLConversion.toJOML(Vec3.atLowerCornerOf(Direction.DOWN.getNormal()));
            pose.orientation().transformInverse(ld);
            double pitch = ld.y() < 0 || ld.z() * ld.z() > 0.001 ? Math.atan2(ld.z(), -ld.y()) : 0;
            double roll = ld.y() < 0 || ld.x() * ld.x() > 0.001 ? Math.atan2(ld.x(), -ld.y()) : 0;
            Vector3d north = new Vector3d(0, 0, -1);
            pose.orientation().transformInverse(north);
            double yaw = -Math.atan2(north.x(), -north.z());
            return new double[]{Math.toDegrees(pitch), Math.toDegrees(roll), Math.toDegrees(yaw)};
        } catch (Exception e) {
            return new double[]{NaN, NaN, NaN};
        }
    }

    /** 世界系角速度 → 机体系（用同一 tick 的姿态四元数逆旋转，同 sensor_system） */
    private static double[] bodyAngVel(SubLevel sub, Vec3 angVelWorld) {
        if (angVelWorld == null) return new double[]{NaN, NaN, NaN};
        try {
            Vector3d v = new Vector3d(angVelWorld.x, angVelWorld.y, angVelWorld.z);
            new Quaterniond(sub.logicalPose().orientation()).transformInverse(v);
            return new double[]{v.x(), v.y(), v.z()};
        } catch (Exception e) {
            return new double[]{NaN, NaN, NaN};
        }
    }

    /**
     * 传感器读数（门控与 sensor_system 同）：
     * {平均气压 P, 平均高度（静压孔世界 y）, 最后皮托管空速, 最后皮托管对地速度}；
     * 无对应传感器或门控不满足时对应列为 nan。
     */
    private static double[] sensorEnv(SubLevel sub) {
        double pressure = NaN, altitude = NaN, airSpeed = NaN, groundSpeed = NaN;
        try {
            List<SensorEntry> entries = BodySensorRegistry.sensorsOnBody(sub);
            boolean hasPitot = false;
            boolean hasPort = false;
            double sumP = 0;
            double sumA = 0;
            int count = 0;
            SensorEntry lastPitot = null;
            for (SensorEntry e : entries) {
                switch (e.type()) {
                    case PRESSURE -> {
                        hasPort = true;
                        Vec3 worldPos = SableCompat.projectOutOfSubLevel(sub.getLevel(), e.pos());
                        if (worldPos != null) {
                            Double pr = computePressure(sub.getLevel(), worldPos);
                            if (pr != null) {
                                sumP += pr;
                                sumA += worldPos.y;
                                count++;
                            }
                        }
                    }
                    case SPEED -> {
                        hasPitot = true;
                        lastPitot = e;
                    }
                    default -> {}
                }
            }
            if (count > 0) {
                pressure = sumP / count;
                altitude = sumA / count;
            }
            if (hasPitot && hasPort && lastPitot != null) {
                Double g = axisSpeed(sub, lastPitot, SableCompat.getVelocity(sub.getLevel(), lastPitot.pos()));
                Double a = axisSpeed(sub, lastPitot, SableCompat.getAirVelocity(sub.getLevel(), lastPitot.pos()));
                if (g != null) groundSpeed = g;
                if (a != null) airSpeed = a;
            }
        } catch (Exception ignored) {}
        return new double[]{pressure, altitude, airSpeed, groundSpeed};
    }

    /** 镜像 SensorSystemAPI.computePressure */
    private static Double computePressure(Level level, Vec3 worldPos) {
        try {
            return DimensionPhysicsData.getAirPressure(level, new Vector3d(worldPos.x, worldPos.y, worldPos.z));
        } catch (Exception e) {
            return null;
        }
    }

    /** 镜像 SensorSystemAPI.axisSpeed：速度在世界管口朝向（blockstate 轴转世界）上的有符号投影，|·|<0.05 归零 */
    private static Double axisSpeed(SubLevel sub, SensorEntry entry, Vec3 vel) {
        if (vel == null) return null;
        try {
            BlockState state = sub.getLevel().getBlockState(entry.pos());
            if (!(state.getBlock() instanceof PitotTubeBlock)) return null; // 注册表滞后：方块已拆
            Vec3 worldAxis = SableCompat.transformNormalToWorld(sub,
                    Vec3.atLowerCornerOf(PitotTubeBlock.axisOf(state).getNormal()));
            if (worldAxis == null) return null;
            double dot = vel.dot(worldAxis);
            return Math.abs(dot) < 0.05 ? 0.0 : dot;
        } catch (Exception e) {
            return null;
        }
    }

    /** 数值列格式化（nan / 无穷原样标记，避免 CSV 数字解析混乱） */
    private static String n(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "nan";
        return String.format(Locale.ROOT, "%.5f", v);
    }
}
