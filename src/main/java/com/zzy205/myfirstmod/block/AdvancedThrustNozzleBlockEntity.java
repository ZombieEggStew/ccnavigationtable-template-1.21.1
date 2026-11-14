package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.cc.ShortRangeLinkerRegistry;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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
 * <li><b>倾斜姿态 + 渐变到位</b>（{@link #getTiltQuaternion}）：Lua 只写<b>目标</b>角度，
 *     服务端每 tick 按<b>梯形速度曲线</b>（最大角速度 / 加减速度写死在代码里）把
 *     <b>当前</b>角度推过去，行程短时自动退化成三角形曲线（短行程耗时更短）；
 *     渲染再按 {@code partialTicks} 在上一 tick 与当前 tick 之间插值。
 *     推力 / 粒子 / 渲染都读"当前"角度，所以三者必然一致地跟着渐变。</li>
 * </ol>
 */
public class AdvancedThrustNozzleBlockEntity extends ThrustNozzleBlockEntity {

    private static final String TAG_CHANNEL = "Channel";
    private static final String TAG_OCCUPIED = "OccupiedChannels";
    private static final String TAG_AZIMUTH = "AzimuthDeg";
    private static final String TAG_TILT = "TiltDeg";
    private static final String TAG_TARGET_AZIMUTH = "TargetAzimuthDeg";
    private static final String TAG_TARGET_TILT = "TargetTiltDeg";
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
     * 由 {@link AdvancedThrustNozzleBlock#getTicker} 的服务端 ticker 驱动：
     * 每 tick 推进一次偏转/出力的运动曲线，每 20 tick 复核一次频道。
     * <p>
     * 为什么必须复核频道：两个物理体经轴承连接/断开时约束链会变，频道空间随之合并/拆分，
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

        // 偏转 / 出力的渐变（停下后 advance 直接返回 false，不产生任何开销与发包）
        be.tickMotion();

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

    // ═══════════════ 倾斜姿态 / 出力（Lua 控制 + 渐变到位） ═══════════════

    /**
     * 锥角钳位上限（度）：用户定稿 15°。
     * <p>
     * 枢轴在 {@code y=1px}（底板中部），再大裙边/下沿会可见地切进底板
     * （{@code 6px × sin θ}，20° 约 2px），活塞杆摆幅也会顶出十字臂带。
     */
    public static final float MAX_TILT_DEG = 15f;

    /**
     * <b>偏转速度恒定</b>（用户定稿：<b>不要加速度/减速度</b>）：每 tick 走固定步长，到点落位。
     * 单位是「度/tick」（1 tick = 0.05s ⇒ ×20 就是度/秒）。
     * <p>
     * 耗时：锥角满行程 15° = 3 tick(0.15s)、方位 90° = 4.5 tick(0.23s)、方位 180° = 9 tick(0.45s)。
     * 渲染端再叠一层本地指数逼近（{@link #getRenderTiltQuaternion}）。
     */
    private static final float AZIMUTH_DEG_PER_TICK = 20f;
    private static final float TILT_DEG_PER_TICK = 5f;

    /** 出力系数步长（0→1 用 4 tick = 0.2s），与偏转同一套"恒速"语义 */
    private static final float THRUST_SCALE_PER_TICK = 0.25f;

    /** 方位角（度，绕 FACING 轴）。内部可超出 0..360（便于走最短路径 / 连续追踪），对外一律归正 */
    private final Axis azimuth = new Axis(0f);

    /** 锥角（度，0..{@link #MAX_TILT_DEG}，0 = 直出） */
    private final Axis tilt = new Axis(0f);

    /** 额外出力系数（0..1，默认 1 = 满出力）：乘在「风扇转速 × 气压」之上 */
    private final Axis thrustScaleAxis = new Axis(1f);

    /** 渲染端本地动画状态（只在客户端用）：指数逼近后的实际绘制角 */
    private float renderAzimuthDeg;
    private float renderTiltDeg;
    private boolean renderAnglesInit;

    /**
     * 请求偏转（Lua 入口）：<b>只写目标</b>，实际角度由 {@link #tickMotion()} 逐 tick <b>恒速</b>逼近。
     * <p>
     * 方位角按<b>最短路径</b>从当前角度出发（350° → 10° 走 −20°，不会绕大半圈）；
     * 锥角钳到 {@code 0..MAX_TILT_DEG}。
     */
    public void requestDeflection(final float azimuthDeg, final float tiltDeg) {
        this.azimuth.setTarget(shortestTargetAzimuth(this.azimuth.value, normalizeDeg(azimuthDeg)));
        this.tilt.setTarget(Mth.clamp(tiltDeg, 0f, MAX_TILT_DEG));
        this.setChanged();
    }

    /** 请求出力系数（0..1，越界钳位）：同样只写目标，恒速逼近 */
    public void requestThrustScale(final float scale) {
        this.thrustScaleAxis.setTarget(Mth.clamp(scale, 0f, 1f));
        this.setChanged();
    }

    /**
     * 服务端每 tick 推进偏转 / 出力（由 {@link #serverTick} 驱动）——<b>恒速</b>，没有加减速。
     * <p>
     * 只有<b>真的动了</b>才广播（照 {@code ControlDeskBlockEntity#simulateJoystick} 的 {@code notifyChange}），
     * 静止时零发包。渲染端的平滑不在这里做，而在 {@link #getRenderTiltQuaternion}（本地指数逼近）。
     */
    private void tickMotion() {
        final boolean azimuthMoving = this.azimuth.stepToward(AZIMUTH_DEG_PER_TICK);
        final boolean tiltMoving = this.tilt.stepToward(TILT_DEG_PER_TICK);
        final boolean scaleMoving = this.thrustScaleAxis.stepToward(THRUST_SCALE_PER_TICK);

        if (azimuthMoving || tiltMoving) {
            // 倾斜姿态是客户端渲染（BER + 粒子）的输入，动的时候每 tick 推一次
            this.syncToClient();
        } else if (scaleMoving) {
            // 出力系数客户端不读，只需落盘
            this.setChanged();
        }
    }

    /** 度归正到 {@code [0, 360)} */
    private static float normalizeDeg(final float deg) {
        final float wrapped = deg % 360f;
        return wrapped < 0f ? wrapped + 360f : wrapped;
    }

    /**
     * 从 {@code current}（可为任意连续值）出发、走<b>最短路径</b>到达 {@code requested}（0..360）的目标角。
     * <p>
     * 返回值可能落在 0..360 之外（例如 current=350、requested=10 ⇒ 370），这样才能让
     * {@link Axis} 用一条单调曲线走过去；对外报数时再 {@link #normalizeDeg 归正}。
     */
    private static float shortestTargetAzimuth(final float current, final float requested) {
        float delta = (requested - current) % 360f;

        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }

        return current + delta;
    }

    /**
     * 喷嘴模型空间的倾斜朝向（+Y = 未倾斜时的直出方向，即 FACING=up 的模型帧）。
     * 用<b>当前</b>角度，供物理（{@link #nozzleAxis}）与渲染的未插值版本共用。
     */
    private Vector3f tiltDirModel() {
        return tiltDirModel(this.azimuth.value, this.tilt.value);
    }

    private static Vector3f tiltDirModel(final float azimuthDeg, final float tiltDeg) {
        final float tiltRad = (float) Math.toRadians(Mth.clamp(tiltDeg, 0f, MAX_TILT_DEG));
        final float azimuthRad = (float) Math.toRadians(azimuthDeg);

        return new Vector3f(
                (float) (Math.sin(tiltRad) * Math.cos(azimuthRad)),
                (float) Math.cos(tiltRad),
                (float) (Math.sin(tiltRad) * Math.sin(azimuthRad)));
    }

    /**
     * 喷嘴倾斜姿态的四元数（模型空间，<b>当前</b>角度，不做动画）：把 +Y 转到 {@link #tiltDirModel()}
     * 的最小弧旋转；{@code tiltDeg = 0} ⇒ 恒等旋转（直出）。
     */
    public Quaternionf getTiltQuaternion() {
        return tiltQuaternion(tiltDirModel());
    }

    /**
     * <b>渲染用</b>倾斜四元数：客户端拿<b>目标角</b>在本地按<b>帧时间</b>恒速推进
     * （{@link #renderAzimuthDeg}/{@link #renderTiltDeg}），再合成四元数。
     * <p>
     * 与"追服务端同步来的当前角"的写法差别很关键：追同步值会让动画质量取决于<b>同步包频率</b>
     * （服务端 20Hz 广播 ⇒ 客户端每 50ms 才收到一个新值，中间没有新信息，看起来就是一格一格跳）。
     * 这里客户端只把同步值当<b>目标</b>，自己按帧时间匀速走完，因此同步频率再低也不影响平滑度；
     * 服务端那套恒速 tick 推进依旧负责推力真值（两边速度相同，最终角必然一致）。
     * <p>
     * 为什么不用 {@link JoystickTilt#approach}：操纵杆是"慢轴值 + 指数跟手"，而喷口要求<b>速度恒定</b>，
     * 指数逼近会变成"起步快、末尾拖"。这里是恒速线性推进，只是把时间单位从 tick 换成帧时间。
     *
     * @param frameTicks 本帧经过的游戏刻数（{@code Minecraft#getTimer().getGameTimeDeltaTicks()}）
     */
    public Quaternionf getRenderTiltQuaternion(final float frameTicks) {
        if (!this.renderAnglesInit) {
            // 首次渲染（区块刚加载 / 蓝图放下去）直接落位，不要从 0 追过来
            this.renderAzimuthDeg = this.azimuth.value;
            this.renderTiltDeg = this.tilt.value;
            this.renderAnglesInit = true;
        }

        this.renderAzimuthDeg = advanceVisual(this.renderAzimuthDeg, this.azimuth.target,
                AZIMUTH_DEG_PER_TICK * frameTicks);
        this.renderTiltDeg = advanceVisual(this.renderTiltDeg, this.tilt.target,
                TILT_DEG_PER_TICK * frameTicks);

        return tiltQuaternion(tiltDirModel(this.renderAzimuthDeg, this.renderTiltDeg));
    }

    /** 恒速逼近一小步（帧时间步长）：剩余不足一步就落位，绝不过冲 */
    private static float advanceVisual(final float current, final float target, final float step) {
        final float remaining = target - current;

        if (remaining == 0f || step <= 0f) {
            return current;
        }

        return Math.abs(remaining) <= step ? target : current + Math.copySign(step, remaining);
    }

    private static Quaternionf tiltQuaternion(final Vector3f dir) {
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
     * <p>
     * <b>客户端读"视觉角"</b>（{@link #renderAzimuthDeg}/{@link #renderTiltDeg}）而不是服务端当前角：
     * 客户端这条链只喂粒子（喷口位置 / 粒子基向量 / 遮挡射线 / 进气漏斗 / 尾迹锚点），
     * 必须和<b>画出来的模型</b>对齐，否则喷口在平滑转动时粒子会一格格跳。
     * 服务端才是推力真值的来源，读 {@link Axis#value}。
     */
    @Override
    protected Vec3 nozzleAxis(final BlockState state) {
        final boolean client = this.level != null && this.level.isClientSide;
        final Vector3f dir = client
                ? tiltDirModel(this.renderAzimuthDeg, this.renderTiltDeg)
                : tiltDirModel();
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
        return this.thrustScaleAxis.value;
    }

    /** 当前方位角（度，0..360；过渡中就是"此刻实际"的角度） */
    public float getAzimuthDeg() {
        return normalizeDeg(this.azimuth.value);
    }

    /** 当前锥角（度，0..{@link #MAX_TILT_DEG}；过渡中就是"此刻实际"的角度） */
    public float getTiltDeg() {
        return this.tilt.value;
    }

    /** 当前出力系数（0..1；过渡中就是"此刻实际"的值） */
    public float getThrustScale() {
        return this.thrustScaleAxis.value;
    }

    /** 是否还在渐变中（偏转或出力没到位） */
    public boolean isMoving() {
        return !this.azimuth.atRest() || !this.tilt.atRest() || !this.thrustScaleAxis.atRest();
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
         * <b>不会瞬移</b>：这里只写目标，喷口按运动曲线（方位走最短路径）渐变过去，
         * 推力方向、排气/进气/尾迹粒子、渲染四者同时跟着走。
         * 用 {@link #getDeflection()} 的 {@code azimuth}/{@code tilt} 看实际角、
         * {@code targetAzimuth}/{@code targetTilt} 看目标角。非有限值返回 false。
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

            be.requestDeflection((float) azimuthDeg, (float) tiltDeg);
            return true;
        }

        /**
         * 当前偏转：返回表
         * {@code {azimuth = 实际角, tilt = 实际角, targetAzimuth = 目标角, targetTilt = 目标角}}。
         * <p>
         * 渐变过程中两者不同；{@code azimuth/tilt} 到位后等于目标值（方位已归正到 0..360）。
         */
        @LuaFunction
        public final Map<String, Object> getDeflection() {
            final AdvancedThrustNozzleBlockEntity be = AdvancedThrustNozzleBlockEntity.this;
            return Map.of(
                    "azimuth", normalizeDeg(be.azimuth.value),
                    "tilt", be.tilt.value,
                    "targetAzimuth", normalizeDeg(be.azimuth.target),
                    "targetTilt", be.tilt.target);
        }

        /** 锥角钳位上限（度）：脚本用它做归一化 */
        @LuaFunction
        public final double getMaxTilt() {
            return MAX_TILT_DEG;
        }

        /** 是否还在渐变中（偏转或出力尚未到位） */
        @LuaFunction
        public final boolean isMoving() {
            return AdvancedThrustNozzleBlockEntity.this.isMoving();
        }

        // ── 出力 ──

        /**
         * 设置出力系数（0..1，越界钳位）：乘在「风扇转速 × 气压」之上，0 = 完全不出力、1 = 满出力。
         * 只影响推力，不改变粒子（粒子仍按风扇转速喷）。同样按运动曲线渐变（约 0.8s 走完 0→1）。
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

            be.requestThrustScale((float) scale);
            return true;
        }

        /** 当前出力系数（0..1，渐变中是此刻实际值） */
        @LuaFunction
        public final double getThrustScale() {
            return AdvancedThrustNozzleBlockEntity.this.thrustScaleAxis.value;
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

        tag.putFloat(TAG_AZIMUTH, this.azimuth.value);
        tag.putFloat(TAG_TILT, this.tilt.value);
        tag.putFloat(TAG_THRUST_SCALE, this.thrustScaleAxis.value);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;
        // 只存"当前角"：读档后直接落位（没有目标、不会自己动起来）
        this.azimuth.reset(tag.contains(TAG_AZIMUTH) ? tag.getFloat(TAG_AZIMUTH) : 0f);
        this.tilt.reset(Mth.clamp(tag.contains(TAG_TILT) ? tag.getFloat(TAG_TILT) : 0f, 0f, MAX_TILT_DEG));
        this.thrustScaleAxis.reset(tag.contains(TAG_THRUST_SCALE) ? tag.getFloat(TAG_THRUST_SCALE) : 1f);
        // 渲染端平滑状态重新落位
        this.renderAnglesInit = false;
    }

    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
        final CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt(TAG_CHANNEL, this.channel);
        tag.putIntArray(TAG_OCCUPIED, this.occupiedChannels);
        // 当前角：客户端首次落位 / 重新加载用；目标角：客户端本地动画的终点（**动画只靠它驱动**）
        tag.putFloat(TAG_AZIMUTH, this.azimuth.value);
        tag.putFloat(TAG_TILT, this.tilt.value);
        tag.putFloat(TAG_TARGET_AZIMUTH, this.azimuth.target);
        tag.putFloat(TAG_TARGET_TILT, this.tilt.target);
        return tag;
    }

    @Override
    public void handleUpdateTag(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;
        this.occupiedChannels = tag.contains(TAG_OCCUPIED) ? tag.getIntArray(TAG_OCCUPIED) : new int[0];

        // 客户端照抄"当前角 + 目标角"，**不动 renderAzimuthDeg/renderTiltDeg**：
        // 本地动画由 getRenderTiltQuaternion 按帧时间推进，收到包就改写会把它打断（这正是"卡卡的"的根源）
        final float newAzimuth = tag.contains(TAG_AZIMUTH) ? tag.getFloat(TAG_AZIMUTH) : 0f;
        final float newTilt = tag.contains(TAG_TILT) ? tag.getFloat(TAG_TILT) : 0f;
        this.azimuth.setSynced(newAzimuth,
                tag.contains(TAG_TARGET_AZIMUTH) ? tag.getFloat(TAG_TARGET_AZIMUTH) : newAzimuth);
        this.tilt.setSynced(Mth.clamp(newTilt, 0f, MAX_TILT_DEG),
                Mth.clamp(tag.contains(TAG_TARGET_TILT) ? tag.getFloat(TAG_TARGET_TILT) : newTilt,
                        0f, MAX_TILT_DEG));

        // onPhysicsBody 不落盘也不进 update tag：客户端以「频道号是否有效」推断
        this.onPhysicsBody = this.channel >= 0;
    }

    // ═══════════════ 单轴运动（恒速线性） ═══════════════

    /**
     * 一条轴的运动状态（当前值 / 目标），逐 tick <b>恒速</b>逼近目标（用户定稿：不要加减速）。
     * <p>
     * 服务端 tick 推进 {@link #value}；客户端只收同步值，动画平滑在
     * {@link AdvancedThrustNozzleBlockEntity#getRenderTiltQuaternion}（照 {@code joystick} 的分层）。
     */
    private static final class Axis {

        private float value;
        private float target;

        private Axis(final float initial) {
            this.reset(initial);
        }

        /** 直接落位（读档 / 初始化）：目标 = 当前 */
        private void reset(final float v) {
            this.value = v;
            this.target = v;
        }

        /**
         * 客户端收到同步：当前角 + 目标角都照抄服务端（客户端**不**推进运动，
         * 也不碰本地动画状态 —— 那是 {@link #getRenderTiltQuaternion} 的事）。
         */
        private void setSynced(final float current, final float newTarget) {
            this.value = current;
            this.target = newTarget;
        }

        /** 只换目标：从当前值继续恒速走过去 */
        private void setTarget(final float t) {
            this.target = t;
        }

        private boolean atRest() {
            return this.value == this.target;
        }

        /**
         * 恒速逼近一个 tick（仅服务端调用）：每 tick 最多走 {@code step}，剩余不足一步就落位。
         *
         * @return 本 tick 是否真的动了（决定要不要广播）
         */
        private boolean stepToward(final float step) {
            final float remaining = this.target - this.value;

            if (remaining == 0f) {
                return false;
            }

            if (Math.abs(remaining) <= step) {
                this.value = this.target;
            } else {
                this.value += Math.copySign(step, remaining);
            }

            return true;
        }
    }
}
