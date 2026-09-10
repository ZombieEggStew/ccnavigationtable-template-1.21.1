package com.zzy205.myfirstmod.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sable 物理子次元兼容层。
 * <p>
 * 直接调用 Sable API，不再使用反射。
 * 使放置了传感器的物理结构绕过 Sable 的距离优化卸载机制。
 */
public final class SableCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ccpe:SableCompat");

    /** 传感器强制加载 Ticket 类型 */
    private static final SubLevelLoadingTicketType<BlockPos> SENSOR_TICKET_TYPE =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath("ccpe", "sensor_force_load"),
                    BlockPos.CODEC);

    private SableCompat() {}

    // ═══════════════ 公开 API ═══════════════

    /**
     * 获取包含指定 BlockEntity 的 Sable SubLevel。
     *
     * @return SubLevel，不在子次元中则返回 null
     */
    public static SubLevel getContainingSubLevel(BlockEntity be) {
        if (be == null || be.getLevel() == null) return null;
        try {
            return Sable.HELPER.getContaining(be);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取指定位置所在的 Sable SubLevel。
     */
    public static SubLevel getContainingSubLevel(Level level, BlockPos pos) {
        if (level == null) return null;
        try {
            return Sable.HELPER.getContaining(level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 为 SubLevel 添加传感器强制加载 ticket。
     *
     * @param level     传感器所在的 Level
     * @param subLevel  Sable SubLevel
     * @param sensorPos 传感器坐标（作为 ticket key）
     * @return 是否成功添加
     */
    public static boolean tryAddForceLoadTicket(Level level, SubLevel subLevel, BlockPos sensorPos) {
        if (!(level instanceof ServerLevel) || subLevel == null) return false;
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return false;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (!(container instanceof ServerSubLevelContainer serverContainer)) return false;
            return serverContainer.addForceLoadTicket(serverSubLevel, SENSOR_TICKET_TYPE, sensorPos);
        } catch (Exception e) {
            LOGGER.debug("Failed to add Sable force-load ticket: {}", e.toString());
            return false;
        }
    }

    /**
     * 移除传感器强制加载 ticket。
     *
     * @return 是否成功移除
     */
    public static boolean tryRemoveForceLoadTicket(Level level, SubLevel subLevel, BlockPos sensorPos) {
        if (!(level instanceof ServerLevel) || subLevel == null) return false;
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return false;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (!(container instanceof ServerSubLevelContainer serverContainer)) return false;
            return serverContainer.removeForceLoadTicket(serverSubLevel, SENSOR_TICKET_TYPE, sensorPos);
        } catch (Exception e) {
            LOGGER.debug("Failed to remove Sable force-load ticket: {}", e.toString());
            return false;
        }
    }

    /**
     * 获取 SubLevel 的 UUID 字符串（用于日志/调试）。
     */
    public static String getSubLevelId(SubLevel subLevel) {
        if (subLevel == null) return "null";
        try {
            return String.valueOf(subLevel.getUniqueId());
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 获取 SubLevel 的 UUID。
     */
    public static UUID getSubLevelUUID(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            return subLevel.getUniqueId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 在世界空间中的位置（投影后）。
     *
     * @return 世界坐标 Vec3，失败返回 null
     */
    public static Vec3 getSubLevelWorldPos(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            Pose3dc pose = subLevel.logicalPose();
            Vector3dc pos = pose.position();
            return new Vec3(pos.x(), pos.y(), pos.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查 SubLevel 是否已被移除。
     */
    public static boolean isSubLevelRemoved(SubLevel subLevel) {
        if (subLevel == null) return true;
        try {
            return subLevel.isRemoved();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 获取与指定 SubLevel 通过约束（轴承等）连接的所有 SubLevel。
     * 始终至少包含自身。
     *
     * @return SubLevel 列表，失败返回空列表
     */
    public static List<SubLevel> getConnectedChain(SubLevel subLevel) {
        if (subLevel == null) return Collections.emptyList();
        try {
            Collection<SubLevel> chain = SubLevelHelper.getConnectedChain(subLevel);
            return chain != null ? new ArrayList<>(chain) : Collections.singletonList(subLevel);
        } catch (Exception e) {
            return Collections.singletonList(subLevel);
        }
    }

    /**
     * 获取 ServerSubLevelContainer 对象。
     */
    public static ServerSubLevelContainer getServerContainer(Level level) {
        if (!(level instanceof ServerLevel)) return null;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            return container instanceof ServerSubLevelContainer sc ? sc : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════ 物理数据读取 ═══════════════

    /**
     * 将 SubLevel 内的局部坐标投影到世界空间。
     */
    public static Vec3 projectOutOfSubLevel(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            return Sable.HELPER.projectOutOfSubLevel(level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════ 客户端坐标变换（Outliner 渲染用） ═══════════════

    /**
     * 获取 SubLevel 用于交互/渲染的姿态。
     * 客户端优先取插值后的 renderPose（与方块实体渲染一致），
     * 服务端或无法插值时退回 logicalPose。
     *
     * @return 姿态；失败或 subLevel 为 null 时返回 null
     */
    public static Pose3dc getPose(SubLevel subLevel, float partialTick) {
        if (subLevel == null) return null;
        try {
            if (subLevel instanceof ClientSubLevelAccess client) {
                return client.renderPose(partialTick);
            }
            return subLevel.logicalPose();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SubLevel 局部坐标 → 世界坐标。
     * 用于把 Outliner 网格线 / 放置预览 / 悬停高亮的端点从子次元局部坐标系
     * 投影回世界，使它们画在物理体真实渲染的位置上。
     */
    public static Vec3 toWorldPosition(SubLevel subLevel, float partialTick, Vec3 localPos) {
        if (subLevel == null || localPos == null) return localPos;
        try {
            Pose3dc pose = getPose(subLevel, partialTick);
            return pose != null ? pose.transformPosition(localPos) : localPos;
        } catch (Exception e) {
            return localPos;
        }
    }

    /**
     * SubLevel 世界坐标 → 局部（plot）坐标。
     * 与 Sable 的 clip mixin 一致：把世界空间的射线端点投影回子次元，
     * 使它们与 {@link BlockEntity#getBlockPos()}（plot 坐标）处于同一坐标系。
     */
    public static Vec3 toLocalPosition(SubLevel subLevel, float partialTick, Vec3 worldPos) {
        if (subLevel == null || worldPos == null) return worldPos;
        try {
            Pose3dc pose = getPose(subLevel, partialTick);
            return pose != null ? pose.transformPositionInverse(worldPos) : worldPos;
        } catch (Exception e) {
            return worldPos;
        }
    }

    /**
     * SubLevel 世界方向 → 局部（plot）方向。
     * 用于把玩家视线方向投影回子次元，配合 {@link #toLocalPosition} 做射线求交。
     */
    public static Vec3 toLocalDirection(SubLevel subLevel, float partialTick, Vec3 worldDir) {
        if (subLevel == null || worldDir == null) return worldDir;
        try {
            Pose3dc pose = getPose(subLevel, partialTick);
            return pose != null ? pose.transformNormalInverse(worldDir) : worldDir;
        } catch (Exception e) {
            return worldDir;
        }
    }

    /**
     * 获取指定位置所在物理结构的世界空间线速度。
     */
    public static Vec3 getVelocity(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            return Sable.HELPER.getVelocity(level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 当前姿态的四元数朝向。
     *
     * @return {@code {x, y, z, w}}，失败返回 null
     */
    public static double[] getSubLevelOrientation(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            Pose3dc pose = subLevel.logicalPose();
            Quaterniondc quat = pose.orientation();
            return new double[]{quat.x(), quat.y(), quat.z(), quat.w()};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的角速度。
     *
     * @return 角速度 Vec3，失败返回 null
     */
    public static Vec3 getAngularVelocity(Level level, SubLevel subLevel) {
        if (!(level instanceof ServerLevel) || subLevel == null) return null;
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (!(container instanceof ServerSubLevelContainer serverContainer)) return null;

            SubLevelPhysicsSystem physicsSystem = serverContainer.physicsSystem();
            RigidBodyHandle handle = physicsSystem.getPhysicsHandle(serverSubLevel);
            if (handle == null) return null;

            Vector3dc angVel = handle.getAngularVelocity(new Vector3d());
            return new Vec3(angVel.x(), angVel.y(), angVel.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 的<b>世界系</b>线速度（m/s，每 tick 由 Sable 用世界 pose 位置差分 ×20 计算，
     * 即 {@code logicalPose().position() − lastPose().position()}，机体原点平移速度）。
     * <p>
     * ⚠️ 这是<b>唯一可信</b>的世界系线速度源：实测（飞行日志）世界静止的机体上，裸读物理 handle
     * （{@link #getLinearVelocity}）仍返回非零"幻影值"（如 y≈-0.067），裸读角速度同样
     * （{@link #getAngularVelocity}，如 x≈0.008 rad/s）；这些幻影值还污染了
     * {@code Sable.HELPER.getVelocity}（内部 = ω×r + linearVelocity，静止机体上会得到
     * 约 -0.03 的假速度）。只有本方法（pose 位置差分，世界系）在静止时严格为 0。
     *
     * @return 世界系线速度 Vec3（m/s）；非服务端 sub-level 或读取失败返回 null
     */
    public static Vec3 getWorldLinearVelocity(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            Vector3d v = serverSubLevel.latestLinearVelocity;
            return new Vec3(v.x, v.y, v.z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 的<b>世界系</b>角速度（rad/s，每 tick 由 Sable 用世界 pose 姿态差分 ×20
     * 计算，即 {@code ServerSubLevel.latestAngularVelocity}）。
     * <p>
     * ⚠️ 这是<b>唯一可信</b>的世界系角速度源：裸读物理 handle（{@link #getAngularVelocity}）
     * 在世界静止的机体上仍返回非零幻影值（飞行日志 wX 列实证，静止机体 ≈0.008 rad/s）；
     * 本方法在姿态恒定时严格为 0。
     *
     * @return 世界系角速度 Vec3（rad/s）；非服务端 sub-level 或读取失败返回 null
     */
    public static Vec3 getWorldAngularVelocity(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            Vector3d v = serverSubLevel.latestAngularVelocity;
            return new Vec3(v.x, v.y, v.z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 世界系<b>点速度</b>（m/s）= 机体原点平移速度 + 角速度 × (点 − 原点)，即刚体上某点的真实
     * 世界系运动速度（含自转杠杆臂贡献）。输入用干净的世界系 latest 值（
     * {@link #getWorldLinearVelocity} / {@link #getWorldAngularVelocity}），与
     * {@code Sable.HELPER.getVelocity} 公式一致但无其幻影值污染（静止机体严格为 0）。
     *
     * @param subLevel 所在物理体
     * @param worldPos 世界系点位（可用 {@link #projectOutOfSubLevel} 得到）
     * @return 世界系点速度 Vec3（m/s）；读取失败返回 null
     */
    public static Vec3 getWorldPointVelocity(SubLevel subLevel, Vec3 worldPos) {
        if (subLevel == null || worldPos == null) return null;
        try {
            Vec3 lin = getWorldLinearVelocity(subLevel);
            Vec3 ang = getWorldAngularVelocity(subLevel);
            if (lin == null || ang == null) return null;
            Pose3dc pose = subLevel.logicalPose();
            Vector3dc posP = pose.position();
            double rx = worldPos.x - posP.x();
            double ry = worldPos.y - posP.y();
            double rz = worldPos.z - posP.z();
            // v_point = v_origin + ω × r
            return new Vec3(lin.x + ang.y * rz - ang.z * ry,
                            lin.y + ang.z * rx - ang.x * rz,
                            lin.z + ang.x * ry - ang.y * rx);
        } catch (Exception e) {
            return null;
        }
    }

    /** 便捷：plot 坐标 → 世界系点速度（内部先投影到世界） */
    public static Vec3 getWorldPointVelocity(Level level, SubLevel subLevel, BlockPos plotPos) {
        if (level == null || subLevel == null || plotPos == null) return null;
        Vec3 worldPos = projectOutOfSubLevel(level, plotPos);
        if (worldPos == null) return null;
        return getWorldPointVelocity(subLevel, worldPos);
    }

    /**
     * 世界系<b>空速</b>（相对空气的）点速度（m/s）= 修正点速度 − 风速。
     * <p>
     * 风速 = {@code Sable.HELPER.getVelocity} − {@code Sable.HELPER.getVelocityRelativeToAir}
     * （两个调用共享同一污染基底，差值恰好抵消幻影值，只剩 wind provider 贡献；Sable 本身不
     * 注册风，未装风模组（如 PMWeather）时为 0）。
     *
     * @return 世界系空速 Vec3（m/s）；读取失败返回 null
     */
    public static Vec3 getWorldAirVelocity(Level level, SubLevel subLevel, BlockPos plotPos) {
        if (level == null || subLevel == null || plotPos == null) return null;
        try {
            Vec3 ground = getWorldPointVelocity(level, subLevel, plotPos);
            if (ground == null) return null;
            Vec3 sableGround = Sable.HELPER.getVelocity(level, plotPos.getCenter());
            Vec3 sableAir = Sable.HELPER.getVelocityRelativeToAir(level, plotPos.getCenter());
            Vec3 wind = sableGround.subtract(sableAir);
            return ground.subtract(wind);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的线速度（世界系，m/s）。
     * 镜像 {@link #getAngularVelocity}（调试/数据记录用）。
     *
     * @return 线速度 Vec3，失败返回 null
     */
    public static Vec3 getLinearVelocity(Level level, SubLevel subLevel) {
        if (!(level instanceof ServerLevel) || subLevel == null) return null;
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (!(container instanceof ServerSubLevelContainer serverContainer)) return null;

            SubLevelPhysicsSystem physicsSystem = serverContainer.physicsSystem();
            RigidBodyHandle handle = physicsSystem.getPhysicsHandle(serverSubLevel);
            if (handle == null) return null;

            Vector3dc linVel = handle.getLinearVelocity(new Vector3d());
            return new Vec3(linVel.x(), linVel.y(), linVel.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的总质量。
     *
     * @return 质量（kg），失败返回 null
     */
    public static Double getMass(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            MassData massTracker = serverSubLevel.getMassTracker();
            if (massTracker == null) return null;
            return massTracker.getMass();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取指定位置物理结构的相对空气速度（已减去风速）。
     *
     * @return Vec3（m/s），失败返回 null
     */
    public static Vec3 getAirVelocity(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            return Sable.HELPER.getVelocityRelativeToAir(level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体在 plot（方块）坐标系中的<b>绝对</b>质心坐标
     * （{@code MassTracker.getCenterOfMass()} 原值，未做 rotationPoint 归零）。
     * 与 Sable 力组的点力（{@code QueuedForceGroup.PointForce.point()}）同一坐标系，
     * 供力矩计算 {@code Σ (point − comPlot) × force} 使用。
     *
     * @return 质心 plot 坐标；失败或不存在时返回 null
     */
    public static Vec3 getCenterOfMassPlot(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            MassData massTracker = serverSubLevel.getMassTracker();
            if (massTracker == null) return null;
            Vector3dc com = massTracker.getCenterOfMass();
            if (com == null) return null;
            return new Vec3(com.x(), com.y(), com.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的质心位置（世界坐标）。
     *
     * @return 质心 Vec3（世界空间），失败或不存在时返回 null
     */
    public static Vec3 getCenterOfMass(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            MassData massTracker = serverSubLevel.getMassTracker();
            if (massTracker == null) return null;
            Vector3dc com = massTracker.getCenterOfMass();
            if (com == null) return null;
            Vector3d globalCom = subLevel.logicalPose().transformPosition(com, new Vector3d());
            return new Vec3(globalCom.x(), globalCom.y(), globalCom.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的质心在局部（plot）坐标系中<b>相对物理体原点（质心枢轴）</b>的偏移。
     * <p>
     * Sable 的 mass tracker 返回的是 plot 空间<b>绝对坐标</b>（与方块坐标同帧，
     * 见 {@code MassTracker.build} 按 plot 方块坐标加权平均），必须按
     * {@link #toRelativePos} 同款转换（{@code plot − rotationPoint}）才是相对原点的偏移。
     * rotationPoint 运行时与质心同步（{@code MergedMassTracker.uploadData}），但方块
     * 增删后的同一 tick 内二者可能瞬时不同，因此这一步转换不能省。
     * 与 {@link #getCenterOfMass}（世界坐标）不同，该偏移不随物理体移动/旋转变化；
     * 与 {@link #toRelativePos} 同帧（plot 帧差值），可与电脑/传感器的相对坐标直接相减。
     *
     * @return 相对原点偏移（plot 帧）；失败或不存在时返回 null
     */
    public static Vec3 getCenterOfMassLocal(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        try {
            MassData massTracker = serverSubLevel.getMassTracker();
            if (massTracker == null) return null;
            Vector3dc com = massTracker.getCenterOfMass();
            if (com == null) return null;
            Vector3dc rp = subLevel.logicalPose().rotationPoint();
            return new Vec3(com.x() - rp.x(), com.y() - rp.y(), com.z() - rp.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取约束链（含全部约束连接，轴承等；始终包含自身）的<b>总质心</b>在局部（plot）坐标系中
     * 相对<b>参考物理体原点（质心枢轴）</b>的偏移。
     * <p>
     * Sable 没有链级质心 API：{@code MergedMassTracker} 只合并单个 sub-level 自身方块 +
     * 其 plot 内 contraptions，约束链上其他 sub-level 各有独立的 MassTracker / pose。
     * 因此这里在世界系按质量加权平均链上各 sub-level 的合并质心
     * （{@code Σ(mᵢ·comᵢ)/Σmᵢ}，用 {@link #getMass} + {@link #getCenterOfMass}，后者已是世界系），
     * 再经参考 sub-level 的 pose 逆变换转回其 plot 帧并减去 rotationPoint——与
     * {@link #toRelativePos} / {@link #getCenterOfMassLocal} 同帧（plot 帧差值），
     * 不随物理体移动/旋转变化。
     *
     * @return 链质心相对参考物理体原点的偏移（plot 帧）；失败或总质量非正时返回 null
     */
    public static Vec3 getChainCenterOfMassLocal(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            List<SubLevel> chain = getConnectedChain(subLevel);
            double totalMass = 0.0;
            Vector3d weighted = new Vector3d();
            for (SubLevel sl : chain) {
                Double m = getMass(sl);
                Vec3 com = getCenterOfMass(sl); // 世界系合并质心
                if (m == null || com == null) continue;
                totalMass += m;
                weighted.fma(m, new Vector3d(com.x, com.y, com.z));
            }
            if (totalMass <= 0.0) return null;
            weighted.div(totalMass);
            Pose3dc pose = subLevel.logicalPose();
            Vector3d local = pose.transformPositionInverse(weighted, new Vector3d());
            Vector3dc rp = pose.rotationPoint();
            return new Vec3(local.x - rp.x(), local.y - rp.y(), local.z - rp.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取约束链（含全部约束连接，轴承等；始终包含自身）的<b>总质心（世界坐标）</b>。
     * <p>
     * 与 {@link #getChainCenterOfMassLocal} 同源：世界系按质量加权平均链上各 sub-level 的
     * 合并质心（{@code Σ(mᵢ·comᵢ)/Σmᵢ}，用 {@link #getMass} + {@link #getCenterOfMass}，
     * 后者已是世界系），但<b>不转回局部系</b>，直接返回世界坐标。
     * <p>
     * 用途：整链受力/力矩应以链质心为参考点（真实动力学与 Sable 约束求解、风洞多物理体
     * 重心一致），而非主机自身质心——主机质心不含尾部子体，会引入虚假的恒定俯仰力矩
     * （= 链质心偏移 × 升力，见 memo §11）。
     *
     * @return 链质心（世界空间）；失败或总质量非正时返回 null
     */
    public static Vec3 getChainCenterOfMass(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            List<SubLevel> chain = getConnectedChain(subLevel);
            double totalMass = 0.0;
            Vector3d weighted = new Vector3d();
            for (SubLevel sl : chain) {
                Double m = getMass(sl);
                Vec3 com = getCenterOfMass(sl); // 世界系合并质心
                if (m == null || com == null) continue;
                totalMass += m;
                weighted.fma(m, new Vector3d(com.x, com.y, com.z));
            }
            if (totalMass <= 0.0) return null;
            weighted.div(totalMass);
            return new Vec3(weighted.x, weighted.y, weighted.z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 及其所有约束连接的物理结构的总质量。
     */
    public static Double getChainMass(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            List<SubLevel> chain = getConnectedChain(subLevel);
            double total = 0.0;
            for (SubLevel sl : chain) {
                Double m = getMass(sl);
                if (m != null) total += m;
            }
            return total;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 SubLevel 内的 plot 坐标转换为相对物理体原点（质心枢轴）的局部坐标。
     * <p>
     * 依据 companion Pose3d 变换公式 {@code world = R·(scale⊙(v − rotationPoint)) + position}：
     * rotationPoint 是物理体原点在 plot 空间的坐标（序列化时为 selfCenterOfMass），
     * 因此 {@code plot − rotationPoint} 即相对原点的偏移；该值在物理体移动/旋转时保持不变。
     *
     * @return 相对坐标（plot 帧，可能为小数）；失败返回 null
     */
    public static Vec3 toRelativePos(SubLevel subLevel, BlockPos plotPos) {
        if (subLevel == null || plotPos == null) return null;
        try {
            Vector3dc rp = subLevel.logicalPose().rotationPoint();
            return new Vec3(plotPos.getX() - rp.x(), plotPos.getY() - rp.y(), plotPos.getZ() - rp.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 body-frame 法线向量变换到世界空间。
     */
    public static Vec3 transformNormalToWorld(SubLevel subLevel, Vec3 bodyNormal) {
        if (subLevel == null || bodyNormal == null) return null;
        try {
            Pose3dc pose = subLevel.logicalPose();
            Vector3d jomlNormal = new Vector3d(bodyNormal.x, bodyNormal.y, bodyNormal.z);
            Vector3d worldNormal = pose.transformNormal(jomlNormal, new Vector3d());
            return new Vec3(worldNormal.x(), worldNormal.y(), worldNormal.z());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * [DEBUG-临时] 转储指定 plot 位置的点速度计算中间量，用于定位「静止物理体上
     * {@code Sable.HELPER.getVelocity} 返回非零（如 y=-0.03）」的问题。
     * <p>
     * 返回字段：
     * <ul>
     * <li><b>helper_velocity</b>：{@link #getVelocity} 的当前结果（= sum，理论应等于 ω×local + linear）；</li>
     * <li><b>pose_position</b>：{@code logicalPose().position()}（世界系机体原点）；</li>
     * <li><b>rotation_point</b>：{@code logicalPose().rotationPoint()}（plot 系机体原点/质心枢轴）；</li>
     * <li><b>plot_pos</b>：查询点的 plot 坐标（通常为超大数）；</li>
     * <li><b>ins_world_pos</b>：{@link #projectOutOfSubLevel}（世界系查询点位置）；</li>
     * <li><b>local_pos</b>：ins_world_pos − pose_position（世界系杠杆臂 r）；</li>
     * <li><b>linear_velocity</b>：{@code handle.getLinearVelocity()}（刚体质心线速度，物理原始值）；</li>
     * <li><b>angular_velocity</b>：{@code handle.getAngularVelocity()}（刚体角速度）；</li>
     * <li><b>omega_cross_local</b>：ω × r（自转杠杆臂贡献）；</li>
     * <li><b>sum</b>：omega_cross_local + linear_velocity（应等于 helper_velocity）；</li>
     * <li><b>latest_linear_velocity</b>：{@code subLevel.latestLinearVelocity}（每 tick 由
     *     pose 位置差分 ×20 得到的世界系线速度，独立参照）。</li>
     * </ul>
     * 静止机体上 helper_velocity 应为 0；非零值来自哪一项（ω×r 或 linear_velocity）一目了然。
     *
     * @return 转储表；读取失败时含 {@code error} 字段
     */
    public static Map<String, Object> debugVelocity(Level level, SubLevel subLevel, BlockPos plotPos) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (level == null || subLevel == null || plotPos == null) return out;
        try {
            Vec3 helperVel = getVelocity(level, plotPos);
            out.put("helper_velocity", helperVel != null
                    ? Map.of("x", helperVel.x, "y", helperVel.y, "z", helperVel.z) : null);

            Pose3dc pose = subLevel.logicalPose();
            Vector3dc posP = pose.position();
            Vector3dc rp = pose.rotationPoint();
            out.put("pose_position", Map.of("x", posP.x(), "y", posP.y(), "z", posP.z()));
            out.put("rotation_point", Map.of("x", rp.x(), "y", rp.y(), "z", rp.z()));
            out.put("plot_pos", Map.of("x", (double) plotPos.getX(), "y", (double) plotPos.getY(), "z", (double) plotPos.getZ()));

            Vec3 insWorld = projectOutOfSubLevel(level, plotPos);
            out.put("ins_world_pos", insWorld != null
                    ? Map.of("x", insWorld.x, "y", insWorld.y, "z", insWorld.z) : null);

            Vector3d localPos = new Vector3d();
            if (insWorld != null) {
                localPos.set(insWorld.x - posP.x(), insWorld.y - posP.y(), insWorld.z - posP.z());
                out.put("local_pos", Map.of("x", localPos.x, "y", localPos.y, "z", localPos.z));
            }

            if (level instanceof ServerLevel serverLevel && subLevel instanceof ServerSubLevel serverSubLevel) {
                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container instanceof ServerSubLevelContainer serverContainer) {
                    RigidBodyHandle handle = serverContainer.physicsSystem().getPhysicsHandle(serverSubLevel);
                    if (handle != null) {
                        Vector3dc lin = handle.getLinearVelocity(new Vector3d());
                        Vector3dc ang = handle.getAngularVelocity(new Vector3d());
                        out.put("linear_velocity", Map.of("x", lin.x(), "y", lin.y(), "z", lin.z()));
                        out.put("angular_velocity", Map.of("x", ang.x(), "y", ang.y(), "z", ang.z()));
                        Vector3d cross = ang.cross(localPos, new Vector3d());
                        out.put("omega_cross_local", Map.of("x", cross.x, "y", cross.y, "z", cross.z));
                        Vector3d sum = new Vector3d(cross).add(lin);
                        out.put("sum", Map.of("x", sum.x, "y", sum.y, "z", sum.z));
                    }
                }
                try {
                    Vector3d latest = serverSubLevel.latestLinearVelocity;
                    out.put("latest_linear_velocity", Map.of("x", latest.x, "y", latest.y, "z", latest.z));
                } catch (Exception ignored) {
                    // latestLinearVelocity 不可用时跳过（非关键字段）
                }
            }
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

}
