package com.zzy205.myfirstmod.compat.sable;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
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
import java.util.List;
import java.util.UUID;

/**
 * Sable 物理子次元兼容层。
 * <p>
 * 直接调用 Sable API，不再使用反射。
 * 使放置了传感器的物理结构绕过 Sable 的距离优化卸载机制。
 */
public final class SableCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ccnavigationtable:SableCompat");

    /** 传感器强制加载 Ticket 类型 */
    private static final SubLevelLoadingTicketType<BlockPos> SENSOR_TICKET_TYPE =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "sensor_force_load"),
                    BlockPos.CODEC);

    private SableCompat() {}

    // ═══════════════ 公开 API ═══════════════

    /**
     * @return Sable 是否已加载（始终为 true，因为 Sable 是必需的编译依赖）
     */
    public static boolean isAvailable() {
        return true;
    }

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
     * 获取 SubLevel 物理刚体的质心位置（局部坐标）。
     *
     * @return 质心 Vec3，失败或不存在时返回 null
     */
    public static Vec3 getCenterOfMass(SubLevel subLevel) {
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
}
