package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * {@code ccpe.sensor_system}：所在物理体（Sable sub-level）的环境数据 Lua API。
 * <p>
 * <b>高频缓存模式</b>（对齐 memo/my_sensor_system.md）：
 * <ul>
 * <li>{@link #update()} 每电脑 tick（服务端主线程，见 CC:Tweaked
 *     {@code ComputerExecutor.tick()} → {@code ServerComputerRegistry} 主线程 tick 链）
 *     刷新缓存：解析所在 sub-level → 物理体原点高度 / 气压（{@link DimensionPhysicsData#getAirPressure}，
 *     与 {@code simulated:altitude_sensor} 同源公式）；</li>
 * <li>缓存字段全部 {@code volatile}（主线程写、电脑线程读）；</li>
 * <li>Lua 方法 {@code @LuaFunction}（默认 mainThread=false）直读缓存，零主线程调度 ——
 *     实测直读版（mainThread=true）单次约 50ms（1 tick），缓存后应降到微秒级。</li>
 * </ul>
 * 不做传感器存在性门控（后续实现 {@code BodySensorRegistry} 时再加）。
 *
 * <pre>{@code
 * local ss = require("ccpe.sensor_system")
 * print(ss.isOnBody())     -- boolean
 * print(ss.getAltitude())  -- 物理体原点世界 Y
 * print(ss.getPressure())  -- 大气压分数，海平面 = 1.0
 * }</pre>
 */
public class SensorSystemAPI implements ILuaAPI {

    private final IComputerSystem computer;

    // ═══════════════ 缓存（主线程 update() 写，Lua 线程读，全部 volatile） ═══════════════

    private volatile boolean onBody = false;
    private volatile @Nullable String bodyId = null;
    private volatile @Nullable Double altitude = null;
    private volatile @Nullable Double pressure = null;

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
     * 电脑不在物理体上 → 缓存重置为默认值（onBody=false，其余 null）。
     */
    @Override
    public void update() {
        SubLevel sub = resolveSubLevel();
        if (sub == null) {
            onBody = false;
            bodyId = null;
            altitude = null;
            pressure = null;
            return;
        }
        onBody = true;
        bodyId = SableCompat.getSubLevelId(sub);
        Vec3 origin = SableCompat.getSubLevelWorldPos(sub);
        altitude = origin != null ? origin.y : null;
        pressure = origin != null ? computePressure(origin) : null;
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

    /** 所在物理体原点的高度（世界 Y，方块 = 米）；不在物理体上返回 nil */
    @LuaFunction
    public final @Nullable Double getAltitude() {
        return altitude;
    }

    /**
     * 所在物理体原点高度的气压（大气压分数：海平面 = 1.0，0.0 = 真空）。
     * 与 {@code simulated:altitude_sensor} 同源：Sable {@link DimensionPhysicsData#getAirPressure}。
     * 不在物理体上返回 nil。
     */
    @LuaFunction
    public final @Nullable Double getPressure() {
        return pressure;
    }

    // ═══════════════ 主线程辅助 ═══════════════

    private @Nullable Double computePressure(Vec3 origin) {
        try {
            return DimensionPhysicsData.getAirPressure(computer.getLevel(),
                    new Vector3d(origin.x, origin.y, origin.z));
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
