package com.zzy205.myfirstmod.compat.sable;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Sable 物理子次元兼容层。
 * <p>
 * 通过反射与 Sable 的 {@code SubLevelLoadingTicket} 系统交互，
 * 使放置了传感器的物理结构绕过 Sable 的距离优化卸载机制。
 * <p>
 * Sable 未加载时，所有方法安全返回 null/false，无副作用。
 */
public final class SableCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ccnavigationtable:SableCompat");

    // ═══════════════ 反射缓存 ═══════════════
    private static boolean sableAvailable;
    private static boolean initialized;

    private static Object sableHelper;              // Sable.HELPER
    private static Method getContainingBeMethod;    // getContaining(BlockEntity)
    private static Method getContainingPosMethod;   // getContaining(Level, Position)
    private static Method getContainerMethod;       // SubLevelContainer.getContainer(Level)
    private static Method addForceLoadTicketMethod; // ServerSubLevelContainer.addForceLoadTicket
    private static Method removeForceLoadTicketMethod;
    private static Method subLevelGetUniqueId;      // SubLevel.getUniqueId()
    private static Method subLevelLogicalPose;      // SubLevel.logicalPose()
    private static Method subLevelIsRemoved;        // SubLevel.isRemoved()
    private static Method connectedChainMethod;     // SubLevelHelper.getConnectedChain(SubLevel)
    private static Object sensorTicketType;         // SubLevelLoadingTicketType<BlockPos>

    private SableCompat() {}

    // ═══════════════ 初始化 ═══════════════

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable");
            sableHelper = sableClass.getField("HELPER").get(null);

            Class<?> helperClass = sableHelper.getClass();
            getContainingBeMethod = helperClass.getMethod("getContaining", BlockEntity.class);
            getContainingPosMethod = helperClass.getMethod("getContaining", Level.class, net.minecraft.core.Position.class);

            // 容器
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            getContainerMethod = containerClass.getMethod("getContainer", Level.class);

            // Ticket 系统
            Class<?> serverContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
            Class<?> ticketTypeClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType");
            Class<?> serverSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");

            addForceLoadTicketMethod = serverContainerClass.getMethod(
                    "addForceLoadTicket", serverSubLevelClass, ticketTypeClass, Object.class);
            removeForceLoadTicketMethod = serverContainerClass.getMethod(
                    "removeForceLoadTicket", serverSubLevelClass, ticketTypeClass, Object.class);

            subLevelGetUniqueId = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel")
                    .getMethod("getUniqueId");

            // SubLevel 位置 & 状态
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            subLevelLogicalPose = subLevelClass.getMethod("logicalPose");
            subLevelIsRemoved = subLevelClass.getMethod("isRemoved");

            // 连接链
            Class<?> subLevelHelperClass = Class.forName("dev.ryanhcode.sable.api.SubLevelHelper");
            connectedChainMethod = subLevelHelperClass.getMethod("getConnectedChain", subLevelClass);

            // 注册自定义 TicketType
            Method createMethod = ticketTypeClass.getMethod("create", ResourceLocation.class, Codec.class);
            sensorTicketType = createMethod.invoke(null,
                    ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "sensor_force_load"),
                    BlockPos.CODEC);

            sableAvailable = true;
            LOGGER.info("Sable compat initialized — sensor sub-level force-load tickets enabled.");
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            LOGGER.debug("Sable not loaded, sub-level ticket support disabled.");
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize Sable compat: {}", e.toString());
        }
    }

    /** 确保已初始化 */
    private static void ensureInit() {
        if (!initialized) init();
    }

    // ═══════════════ 公开 API ═══════════════

    /**
     * @return Sable 是否已加载且兼容层初始化成功
     */
    public static boolean isAvailable() {
        ensureInit();
        return sableAvailable;
    }

    /**
     * 获取包含指定 BlockEntity 的 Sable SubLevel。
     *
     * @return SubLevel 对象（反射），不在子次元中则返回 null
     */
    public static Object getContainingSubLevel(BlockEntity be) {
        ensureInit();
        if (!sableAvailable || be == null || be.getLevel() == null) return null;
        try {
            return getContainingBeMethod.invoke(sableHelper, be);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取指定位置所在的 Sable SubLevel。
     */
    public static Object getContainingSubLevel(Level level, BlockPos pos) {
        ensureInit();
        if (!sableAvailable || level == null) return null;
        try {
            return getContainingPosMethod.invoke(sableHelper, level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 为 SubLevel 添加传感器强制加载 ticket。
     * 如果 subLevel 或 container 不可用，静默失败。
     *
     * @param level       传感器所在的 Level
     * @param subLevel    Sable SubLevel 对象（反射）
     * @param sensorPos   传感器坐标（作为 ticket key）
     * @return 是否成功添加
     */
    public static boolean tryAddForceLoadTicket(Level level, Object subLevel, BlockPos sensorPos) {
        ensureInit();
        if (!sableAvailable || !(level instanceof ServerLevel) || subLevel == null) return false;
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) return false;
            return (boolean) addForceLoadTicketMethod.invoke(container, subLevel, sensorTicketType, sensorPos);
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
    public static boolean tryRemoveForceLoadTicket(Level level, Object subLevel, BlockPos sensorPos) {
        ensureInit();
        if (!sableAvailable || !(level instanceof ServerLevel) || subLevel == null) return false;
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) return false;
            return (boolean) removeForceLoadTicketMethod.invoke(container, subLevel, sensorTicketType, sensorPos);
        } catch (Exception e) {
            LOGGER.debug("Failed to remove Sable force-load ticket: {}", e.toString());
            return false;
        }
    }

    /**
     * 获取 SubLevel 的 UUID 字符串（用于日志/调试）。
     */
    public static String getSubLevelId(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return "null";
        try {
            return String.valueOf(subLevelGetUniqueId.invoke(subLevel));
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 获取 SubLevel 的 UUID。
     */
    public static UUID getSubLevelUUID(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return null;
        try {
            return (UUID) subLevelGetUniqueId.invoke(subLevel);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 在世界空间中的位置（投影后）。
     *
     * @return 世界坐标 Vec3，失败返回 null
     */
    public static Vec3 getSubLevelWorldPos(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return null;
        try {
            Object pose = subLevelLogicalPose.invoke(subLevel);
            // Pose3dc.position() → Vector3dc
            Object vec = pose.getClass().getMethod("position").invoke(pose);
            double x = (double) vec.getClass().getMethod("x").invoke(vec);
            double y = (double) vec.getClass().getMethod("y").invoke(vec);
            double z = (double) vec.getClass().getMethod("z").invoke(vec);
            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查 SubLevel 是否已被移除。
     */
    public static boolean isSubLevelRemoved(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return true;
        try {
            return (boolean) subLevelIsRemoved.invoke(subLevel);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 获取与指定 SubLevel 通过约束（轴承等）连接的所有 SubLevel。
     * 始终至少包含自身。
     *
     * @return SubLevel 列表（反射对象），失败返回空列表
     */
    @SuppressWarnings("unchecked")
    public static List<Object> getConnectedChain(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return Collections.emptyList();
        try {
            List<Object> chain = (List<Object>) connectedChainMethod.invoke(null, subLevel);
            return chain != null ? new ArrayList<>(chain) : Collections.emptyList();
        } catch (Exception e) {
            return Collections.singletonList(subLevel); // 至少返回自身
        }
    }

    /**
     * 尝试通过反射获取 ServerSubLevelContainer 对象。
     */
    public static Object getServerContainer(Level level) {
        ensureInit();
        if (!sableAvailable || !(level instanceof ServerLevel)) return null;
        try {
            return getContainerMethod.invoke(null, level);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════ 物理数据读取 ═══════════════

    /**
     * 将 SubLevel 内的局部坐标投影到世界空间。
     * 等效于 {@code Sable.HELPER.projectOutOfSubLevel(level, pos)}。
     */
    public static Vec3 projectOutOfSubLevel(Level level, BlockPos pos) {
        ensureInit();
        if (!sableAvailable || level == null || pos == null) return null;
        try {
            return (Vec3) sableHelper.getClass()
                    .getMethod("projectOutOfSubLevel", Level.class, net.minecraft.core.Position.class)
                    .invoke(sableHelper, level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取指定位置所在物理结构的世界空间线速度。
     * 等效于 {@code Sable.HELPER.getVelocity(level, pos)}。
     */
    public static Vec3 getVelocity(Level level, BlockPos pos) {
        ensureInit();
        if (!sableAvailable || level == null || pos == null) return null;
        try {
            return (Vec3) sableHelper.getClass()
                    .getMethod("getVelocity", Level.class, net.minecraft.core.Position.class)
                    .invoke(sableHelper, level, pos.getCenter());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 当前姿态的四元数朝向。
     *
     * @return {@code {x, y, z, w}}，失败返回 null
     */
    public static double[] getSubLevelOrientation(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return null;
        try {
            Object pose = subLevelLogicalPose.invoke(subLevel);
            Object quat = pose.getClass().getMethod("orientation").invoke(pose);
            double x = (double) quat.getClass().getMethod("x").invoke(quat);
            double y = (double) quat.getClass().getMethod("y").invoke(quat);
            double z = (double) quat.getClass().getMethod("z").invoke(quat);
            double w = (double) quat.getClass().getMethod("w").invoke(quat);
            return new double[]{x, y, z, w};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的角速度。
     *
     * @return {@code {wx, wy, wz}}，失败返回 null
     */
    public static Vec3 getAngularVelocity(Level level, Object subLevel) {
        ensureInit();
        if (!sableAvailable || !(level instanceof ServerLevel) || subLevel == null) return null;
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) return null;

            // container.physicsSystem()
            Object physicsSystem = container.getClass().getMethod("physicsSystem").invoke(container);
            if (physicsSystem == null) return null;

            // physicsSystem.getPhysicsHandle(ServerSubLevel)
            Object handle = physicsSystem.getClass()
                    .getMethod("getPhysicsHandle",
                            Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel"))
                    .invoke(physicsSystem, subLevel);
            if (handle == null) return null;

            // handle.getAngularVelocity() → Vector3dc
            Object angVel = handle.getClass().getMethod("getAngularVelocity").invoke(handle);
            double x = (double) angVel.getClass().getMethod("x").invoke(angVel);
            double y = (double) angVel.getClass().getMethod("y").invoke(angVel);
            double z = (double) angVel.getClass().getMethod("z").invoke(angVel);
            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的总质量。
     * {@code ServerSubLevel} 实现了 {@code PhysicsPipelineBody}，
     * 可直接调用 {@code getMassTracker().getMass()}。
     *
     * @return 质量（kg），失败返回 null
     */
    public static Double getMass(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return null;
        try {
            // subLevel.getMassTracker() → MassData
            Object massTracker = subLevel.getClass()
                    .getMethod("getMassTracker").invoke(subLevel);
            if (massTracker == null) return null;
            // massTracker.getMass() → double
            return (double) massTracker.getClass().getMethod("getMass").invoke(massTracker);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 SubLevel 物理刚体的质心位置（局部坐标）。
     *
     * @return 质心 {@code Vec3}，失败或不存在时返回 null
     */
    public static Vec3 getCenterOfMass(Object subLevel) {
        ensureInit();
        if (!sableAvailable || subLevel == null) return null;
        try {
            // subLevel.getMassTracker() → MassData
            Object massTracker = subLevel.getClass()
                    .getMethod("getMassTracker").invoke(subLevel);
            if (massTracker == null) return null;
            // massTracker.getCenterOfMass() → @Nullable Vector3dc
            Object com = massTracker.getClass().getMethod("getCenterOfMass").invoke(massTracker);
            if (com == null) return null;
            double x = (double) com.getClass().getMethod("x").invoke(com);
            double y = (double) com.getClass().getMethod("y").invoke(com);
            double z = (double) com.getClass().getMethod("z").invoke(com);
            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }
}
