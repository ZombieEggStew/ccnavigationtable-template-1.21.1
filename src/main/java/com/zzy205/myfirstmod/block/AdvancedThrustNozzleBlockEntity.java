package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.cc.ShortRangeLinkerRegistry;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/**
 * 矢量喷管（{@code ccpe:advanced_thrust_nozzle}）方块实体。
 * <p>
 * 除继承 {@link ThrustNozzleBlockEntity} 的全部推力 / 气压 / 粒子逻辑外，这里有两块自有状态：
 * <ol>
 * <li><b>物理体作用域频道</b>（与 {@code short_range_linker} / 控制台共用
 *     {@link ShortRangeLinkerRegistry} 的频道空间）：放置时自动分配、只在 Sable 物理体
 *     （含约束链）上生效、同链冲突顺延、每 20 tick 复核（轴承连/断后频道空间会自动合并/拆分）。
 *     频道号是<b>喷嘴在物理体内的地址</b>，后续用 {@code ccpe.sensor_system.getPeripheral(ch)} 寻址；</li>
 * <li><b>倾斜姿态</b>（{@link #getTiltQuaternion}）：当前固定为<b>直出</b>（恒等旋转）；
 *     角度控制逻辑（锥角钳位 / 服务端权威同步 / Lua setDirection）在下一步接进这个 seam，
 *     渲染器不用改。</li>
 * </ol>
 */
public class AdvancedThrustNozzleBlockEntity extends ThrustNozzleBlockEntity {

    private static final String TAG_CHANNEL = "Channel";
    private static final String TAG_OCCUPIED = "OccupiedChannels";
    private static final String TAG_AZIMUTH = "AzimuthDeg";
    private static final String TAG_TILT = "TiltDeg";
    private static final String TAG_THRUST_SCALE = "ThrustScale";

    /** 频道未分配：交给注册表在物理体内自动取最小空闲号 */
    public static final int CHANNEL_UNASSIGNED = -1;

    /** 实际分配到的频道号（{@link #CHANNEL_UNASSIGNED} = 尚未分配，= 不在物理体上或还没 onLoad） */
    private int channel = CHANNEL_UNASSIGNED;

    /** 是否位于 Sable 物理体上（客户端 GUI 据此显示提示；运行时状态，不落盘） */
    private boolean onPhysicsBody = false;

    /** 同链（物理体内）已占用频道快照（GUI 滚轮跳过占用用；运行时状态，不落盘） */
    private int[] occupiedChannels = new int[0];

    public AdvancedThrustNozzleBlockEntity(final BlockPos pos, final BlockState state) {
        super(MyModBlockEntities.advanced_thrust_nozzle_entity.get(), pos, state);
    }

    // ═══════════════ 频道：注册 / 注销 / 复核 ═══════════════

    @Override
    public void onLoad() {
        super.onLoad();

        if (this.level == null || this.level.isClientSide) {
            return;
        }

        this.registerChannel();
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            ShortRangeLinkerRegistry.unregisterAll(this);
            this.onPhysicsBody = false;
            this.occupiedChannels = new int[0];
        }

        super.setRemoved();
    }

    /**
     * 由 {@link AdvancedThrustNozzleBlock#getTicker} 的服务端 ticker 驱动：每 20 tick 复核一次频道。
     * <p>
     * 为什么必须复核：两个物理体经轴承连接/断开时约束链会变，频道空间随之合并/拆分，
     * 同链冲突要顺延（照 {@code ShortRangeLinkerBlockEntity#revalidate}）。
     */
    public static void serverTick(final Level level, final BlockPos pos, final BlockState state,
                                  final AdvancedThrustNozzleBlockEntity be) {
        if (!(level instanceof final ServerLevel serverLevel)) {
            return;
        }

        if (!level.isLoaded(pos)) {
            return;
        }

        if (serverLevel.getServer().getTickCount() % 20 == 0) {
            be.registerChannel();
        }
    }

    /**
     * 在当前物理体内注册频道（服务端主线程）。
     * <p>
     * {@code channel < 0} ⇒ 由注册表取链内最小空闲号（= 放置时自动分配）；
     * 已占用的号会顺延，并把实际频道回写到本 BE。
     */
    private void registerChannel() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        final int assigned = ShortRangeLinkerRegistry.register(this.channel, this);
        this.onPhysicsBody = assigned >= 0;

        if (assigned >= 0 && assigned != this.channel) {
            this.channel = assigned;
            this.setChanged();
            // 频道是 GUI 显示用的客户端数据（Screen 的 fallback 路径也读它），变化后推一次
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        } else if (!this.onPhysicsBody && this.channel >= 0) {
            // 离开物理体（拆到地上）：登记失效，但保留频道号，重新装上物理体时优先复用
            this.channel = CHANNEL_UNASSIGNED;
            this.setChanged();
        }

        this.refreshOccupiedChannels();
    }

    /**
     * 重算「同链已占用频道」快照（打开 GUI 前必须调一次，保证滚轮跳过的是最新占用）。
     */
    public void refreshOccupiedChannels() {
        final int[] next = ShortRangeLinkerRegistry
                .occupiedChannels(ShortRangeLinkerRegistry.chainUuidsOf(this))
                .stream()
                .mapToInt(Integer::intValue)
                .toArray();

        if (!java.util.Arrays.equals(next, this.occupiedChannels)) {
            this.occupiedChannels = next;
            this.setChanged();
        }
    }

    /**
     * GUI 保存：以期望频道重新注册（同链冲突自动顺延），返回最终生效的频道号。
     *
     * @param desired 玩家在 GUI 里选的频道（&lt;0 表示重新自动分配）
     * @return 实际生效的频道；不在物理体上返回 {@link #CHANNEL_UNASSIGNED}
     */
    public int applyChannel(final int desired) {
        if (this.level == null || this.level.isClientSide) {
            return this.channel;
        }

        // 先按玩家选择的值登记；注册表发现同链冲突会顺延
        this.channel = desired;
        final int assigned = ShortRangeLinkerRegistry.register(desired, this);
        this.onPhysicsBody = assigned >= 0;
        this.channel = assigned;
        this.setChanged();
        this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        this.refreshOccupiedChannels();
        return assigned;
    }

    /** 实际频道号（{@link #CHANNEL_UNASSIGNED} = 当前不在物理体上 / 未分配） */
    public int getChannel() {
        return this.channel;
    }

    /** 是否位于 Sable 物理体上（决定 GUI 显示控件区还是「需放置在物理体上」提示） */
    public boolean isOnPhysicsBody() {
        return this.onPhysicsBody;
    }

    /** 同链已占用频道快照 */
    public int[] getOccupiedChannels() {
        return this.occupiedChannels;
    }

    // ═══════════════ 倾斜姿态 / 出力（Lua 控制） ═══════════════

    /**
     * 锥角钳位上限（度）：用户定稿 15°。
     * <p>
     * 枢轴在 {@code y=1px}（底板中部），再大裙边/下沿会可见地切进底板
     * （{@code 6px × sin θ}，20° 约 2px），活塞杆摆幅也会顶出十字臂带。
     */
    public static final float MAX_TILT_DEG = 15f;

    /** 方位角（度，0..360）：绕 FACING 轴，决定倾斜朝哪一侧 */
    private float azimuthDeg = 0f;

    /** 锥角（度，0..{@link #MAX_TILT_DEG}）：偏离轴线的角度，0 = 直出 */
    private float tiltDeg = 0f;

    /** 额外出力系数（0..1，默认 1 = 满出力）：乘在「风扇转速 × 气压」之上 */
    private float thrustScaleValue = 1f;

    /**
     * 喷嘴模型空间的倾斜朝向（+Y = 未倾斜时的直出方向，即 FACING=up 的模型帧）。
     * 由 {@link #azimuthDeg} / {@link #tiltDeg} 派生，供渲染（{@link #getTiltQuaternion()}）
     * 和物理（{@link #nozzleAxis}）共用——两者方向一定一致。
     */
    private Vector3f tiltDirModel() {
        final float tilt = (float) Math.toRadians(Math.max(0f, Math.min(MAX_TILT_DEG, this.tiltDeg)));
        final float azimuth = (float) Math.toRadians(this.azimuthDeg);

        return new Vector3f(
                (float) (Math.sin(tilt) * Math.cos(azimuth)),
                (float) Math.cos(tilt),
                (float) (Math.sin(tilt) * Math.sin(azimuth)));
    }

    /**
     * 喷嘴倾斜姿态的四元数（模型空间）：把 +Y 转到 {@link #tiltDirModel()} 的最小弧旋转。
     * <p>
     * 渲染器用它绕枢轴转 block 部分：{@code tiltDeg = 0} ⇒ 恒等旋转（直出）。
     */
    public Quaternionf getTiltQuaternion() {
        final Vector3f dir = tiltDirModel();

        if (dir.lengthSquared() < 1.0E-6f) {
            return new Quaternionf();
        }

        dir.normalize();
        return new Quaternionf().rotationTo(0f, 1f, 0f, dir.x, dir.y, dir.z);
    }

    /**
     * 覆盖父类钩子：推力的实际轴向 = 倾斜后的轴，并转到 <b>plot 系</b>。
     * <p>
     * 模型空间的方向要按 blockstate 的模型旋转（{@code x}/{@code y}）转到 plot 系，
     * 顺序与 vanilla 一致：{@code R = Ry(−y) · Rx(−x)}（{@code Quaternionf#rotationYXZ}），
     * 六个朝向的 (x,y) 与 {@code blockstates/advanced_thrust_nozzle.json} 逐条对应。
     */
    @Override
    protected Vec3 nozzleAxis(final BlockState state) {
        final Vector3f dir = tiltDirModel();
        final Direction facing = state.getValue(ThrustNozzleBlock.FACING);

        float xDeg = 0f;
        float yDeg = 0f;
        switch (facing) {
            case UP -> { }
            case DOWN -> xDeg = 180f;
            case NORTH -> xDeg = 90f;
            case EAST -> { xDeg = 90f; yDeg = 90f; }
            case SOUTH -> { xDeg = 90f; yDeg = 180f; }
            case WEST -> { xDeg = 90f; yDeg = 270f; }
            default -> { }
        }

        dir.rotate(new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-yDeg), (float) Math.toRadians(-xDeg), 0f));
        dir.normalize();
        return new Vec3(dir.x, dir.y, dir.z);
    }

    @Override
    protected double thrustScale() {
        return this.thrustScaleValue;
    }

    /** 当前方位角（度，0..360） */
    public float getAzimuthDeg() {
        return this.azimuthDeg;
    }

    /** 当前锥角（度，0..{@link #MAX_TILT_DEG}） */
    public float getTiltDeg() {
        return this.tiltDeg;
    }

    /** 当前出力系数（0..1） */
    public float getThrustScale() {
        return this.thrustScaleValue;
    }

    // ═══════════════ CC:T 外设（Lua 控制偏转 / 出力） ═══════════════

    private IPeripheral peripheral;

    /** 获取本方块的外设实例（懒加载）。注册见 {@code compat/cc/CCPeripheralCapabilities}。 */
    public IPeripheral getPeripheral() {
        if (this.peripheral == null) {
            this.peripheral = new Peripheral();
        }

        return this.peripheral;
    }

    /**
     * 矢量喷管 Lua 外设（type = {@code advanced_thrust_nozzle}）。
     * <p>
     * 寻址：同一物理体内可用 {@code ccpe.sensor_system.getPeripheral(频道)} 拿到它
     * （频道见 {@link #getChannel()}，与 {@code short_range_linker} / 控制台共享同一频道空间）。
     */
    private class Peripheral implements IPeripheral {

        @Override
        public String getType() {
            return "advanced_thrust_nozzle";
        }

        @Override
        public boolean equals(@Nullable final IPeripheral other) {
            if (this == other) {
                return true;
            }
            if (other instanceof final AdvancedThrustNozzleBlockEntity.Peripheral that) {
                return AdvancedThrustNozzleBlockEntity.this.worldPosition
                        .equals(that.outer().worldPosition);
            }
            return false;
        }

        private AdvancedThrustNozzleBlockEntity outer() {
            return AdvancedThrustNozzleBlockEntity.this;
        }

        // ── 偏转 ──

        /**
         * 设置喷口偏转：{@code azimuthDeg} = 绕 FACING 轴的方位角（0..360），
         * {@code tiltDeg} = 偏离轴线的锥角（自动钳到 0..{@link #MAX_TILT_DEG}）。
         * <p>
         * 推力方向、排气/进气/尾迹粒子、渲染三者同时跟着变。非有限值返回 false。
         */
        @LuaFunction(mainThread = true)
        public final boolean setDeflection(final double azimuthDeg, final double tiltDeg) {
            if (!Double.isFinite(azimuthDeg) || !Double.isFinite(tiltDeg)) {
                return false;
            }

            final AdvancedThrustNozzleBlockEntity be = AdvancedThrustNozzleBlockEntity.this;
            if (be.level == null || be.level.isClientSide) {
                return false;
            }

            float azimuth = (float) (azimuthDeg % 360.0D);
            if (azimuth < 0f) {
                azimuth += 360f;
            }

            be.azimuthDeg = azimuth;
            be.tiltDeg = (float) Math.max(0.0D, Math.min(MAX_TILT_DEG, tiltDeg));
            be.syncToClient();
            return true;
        }

        /** 当前偏转：返回表 {@code {azimuth = 度, tilt = 度}} */
        @LuaFunction
        public final Map<String, Object> getDeflection() {
            final AdvancedThrustNozzleBlockEntity be = AdvancedThrustNozzleBlockEntity.this;
            return Map.of("azimuth", be.azimuthDeg, "tilt", be.tiltDeg);
        }

        /** 锥角钳位上限（度）：脚本用它做归一化 */
        @LuaFunction
        public final double getMaxTilt() {
            return MAX_TILT_DEG;
        }

        // ── 出力 ──

        /**
         * 设置出力系数（0..1，越界钳位）：乘在「风扇转速 × 气压」之上，0 = 完全不出力、1 = 满出力。
         * 只影响推力，不改变粒子（粒子仍按风扇转速喷）。
         */
        @LuaFunction(mainThread = true)
        public final boolean setThrustScale(final double scale) {
            if (!Double.isFinite(scale)) {
                return false;
            }

            final AdvancedThrustNozzleBlockEntity be = AdvancedThrustNozzleBlockEntity.this;
            if (be.level == null || be.level.isClientSide) {
                return false;
            }

            be.thrustScaleValue = (float) Math.max(0.0D, Math.min(1.0D, scale));
            be.syncToClient();
            return true;
        }

        /** 当前出力系数（0..1） */
        @LuaFunction
        public final double getThrustScale() {
            return AdvancedThrustNozzleBlockEntity.this.thrustScaleValue;
        }

        /** 本喷嘴在物理体内的频道号（-1 = 不在物理体上/未分配），配合 {@code sensor_system.getPeripheral} 使用 */
        @LuaFunction
        public final int getChannel() {
            return AdvancedThrustNozzleBlockEntity.this.channel;
        }
    }

    /** 状态变化后落盘 + 推给客户端（渲染要读倾斜姿态） */
    private void syncToClient() {
        this.setChanged();

        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    // ═══════════════ NBT / 客户端同步 ═══════════════

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (this.channel != CHANNEL_UNASSIGNED) {
            tag.putInt(TAG_CHANNEL, this.channel);
        }

        tag.putFloat(TAG_AZIMUTH, this.azimuthDeg);
        tag.putFloat(TAG_TILT, this.tiltDeg);
        tag.putFloat(TAG_THRUST_SCALE, this.thrustScaleValue);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;
        this.azimuthDeg = tag.contains(TAG_AZIMUTH) ? tag.getFloat(TAG_AZIMUTH) : 0f;
        this.tiltDeg = tag.contains(TAG_TILT) ? tag.getFloat(TAG_TILT) : 0f;
        this.thrustScaleValue = tag.contains(TAG_THRUST_SCALE) ? tag.getFloat(TAG_THRUST_SCALE) : 1f;
    }

    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
        final CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt(TAG_CHANNEL, this.channel);
        tag.putIntArray(TAG_OCCUPIED, this.occupiedChannels);
        // 倾斜姿态是客户端渲染（BER）的输入，必须同步
        tag.putFloat(TAG_AZIMUTH, this.azimuthDeg);
        tag.putFloat(TAG_TILT, this.tiltDeg);
        return tag;
    }

    @Override
    public void handleUpdateTag(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;
        this.occupiedChannels = tag.contains(TAG_OCCUPIED) ? tag.getIntArray(TAG_OCCUPIED) : new int[0];
        this.azimuthDeg = tag.contains(TAG_AZIMUTH) ? tag.getFloat(TAG_AZIMUTH) : 0f;
        this.tiltDeg = tag.contains(TAG_TILT) ? tag.getFloat(TAG_TILT) : 0f;
        // onPhysicsBody 不落盘也不进 update tag：客户端以「频道号是否有效」推断
        this.onPhysicsBody = this.channel >= 0;
    }
}
