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
 * <li><b>倾斜姿态 + 渐变到位</b>（{@link #getRenderTiltQuaternion}）：Lua 只写<b>目标</b>角度；
 *     服务端按<b>恒速</b>（度/秒，无加减速）推进真实角度给推力用；客户端把视觉姿态写成
 *     <b>墙钟（{@link System#nanoTime()}）的纯函数</b>，因此与 tick / 渲染 / 同步包频率全都无关。</li>
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
     * <b>偏转速度恒定</b>（用户定稿：<b>不要加速度/减速度</b>）。
     * <p>
     * 权威单位是「度/秒」：服务端按 tick 换算成步长推进（{@code /20}），
     * 客户端视觉动画直接用墙钟（{@link System#nanoTime()}）算 —— 两者速度完全一致。
     * <p>
     * 耗时（当前值，为了排查刻意放慢到肉眼可见）：方位 90° = 0.75s、180° = 1.5s；锥角满行程 15° = 0.38s。
     */
    private static final float AZIMUTH_DEG_PER_SECOND = 120f;
    private static final float TILT_DEG_PER_SECOND = 40f;

    /** 服务端每 tick 的步长（与上面的度/秒严格对应） */
    private static final float AZIMUTH_DEG_PER_TICK = AZIMUTH_DEG_PER_SECOND / 20f;
    private static final float TILT_DEG_PER_TICK = TILT_DEG_PER_SECOND / 20f;

    /** 出力系数步长（0→1 用 4 tick = 0.2s），与偏转同一套"恒速"语义 */
    private static final float THRUST_SCALE_PER_TICK = 0.25f;

    /**
     * 调试开关（**默认关闭**）：打开后打印 `[ATN-anim]`（每 20 次渲染）、`[ATN-pkt ]`、`[ATN-load]`，
     * 都带 `inst=` 实例哈希，可用来验证"渲染的实例 == 收包的实例"以及视觉角有没有在追 target。
     * <p>
     * ⚠️ 渲染线程上的同步 stdout 会掉帧，只在排查时临时打开。
     * `static final false` ⇒ 里面几处 printf 会被 javac 整段消除，运行时零开销。
     */
    private static final boolean DEBUG_ANIM_LOG = false;

    /** 方位角（度，绕 FACING 轴）。内部可超出 0..360（便于走最短路径 / 连续追踪），对外一律归正 */
    private final Axis azimuth = new Axis(0f);

    /** 锥角（度，0..{@link #MAX_TILT_DEG}，0 = 直出） */
    private final Axis tilt = new Axis(0f);

    /** 额外出力系数（0..1，默认 1 = 满出力）：乘在「风扇转速 × 气压」之上 */
    private final Axis thrustScaleAxis = new Axis(1f);

    /**
     * 视觉角（**只在客户端**，本地动画状态）：一个会自己朝 {@link Axis#target} 走的数。
     * <p>
     * 推进方式 = <b>每次渲染用墙钟差值走一步</b>：
     * <pre>visual += sign(target − visual) × 速度(度/秒) × Δt</pre>
     * 这样做的三个好处（也是前几版翻车的点）：
     * <ul>
     * <li>Δt 来自 {@link System#nanoTime()} 的<b>差值</b>，所以无论渲染一帧被调用几次、
     *     帧率多少，积分都是对的（"每次调用走固定步长"才会被调用频率放大）；</li>
     * <li><b>没有 (起点, 终点, 起始时刻) 三元组</b>可以失效 —— 以前那套一旦 `animTo` 没更新，
     *     `from == to` 就永远不动（实测日志里正是如此）；</li>
     * <li>只依赖同步来的 <b>target</b>（日志已证明它稳定到达），与当前角、包频率无关。</li>
     * </ul>
     */
    private float visualAzimuthDeg;
    private float visualTiltDeg;
    private long visualLastNanos;
    private boolean visualInit;
    /** 渲染调用计数（仅调试日志用） */
    private int debugAnimFrames;

    /**
     * 请求偏转（Lua 入口）：<b>只写目标</b>，实际角度由 {@link #tickMotion()} 逐 tick <b>恒速</b>逼近。
     * <p>
     * 方位角按<b>最短路径</b>从当前角度出发（350° → 10° 走 −20°，不会绕大半圈）；
     * 锥角钳到 {@code 0..MAX_TILT_DEG}。
     */
    public void requestDeflection(final float azimuthDeg, final float tiltDeg) {
        this.azimuth.setTarget(shortestTargetAzimuth(this.azimuth.value, normalizeDeg(azimuthDeg)));
        this.tilt.setTarget(Mth.clamp(tiltDeg, 0f, MAX_TILT_DEG));
        // **一次命令只发一次包**（照 VectorThrusterBlockEntity#onVectorSignalChanged 的 notifyUpdate）：
        // 客户端只需知道目标，动画由它自己的 ticker 推进；每 tick 广播会触发光照/块更新，
        // 正是画面"卡卡的"的另一个来源。
        this.syncToClient();
    }

    /** 请求出力系数（0..1，越界钳位）：同样只写目标，恒速逼近 */
    public void requestThrustScale(final float scale) {
        this.thrustScaleAxis.setTarget(Mth.clamp(scale, 0f, 1f));
        this.setChanged();
    }

    /**
     * 服务端每 tick 推进偏转 / 出力（由 {@link #serverTick} 驱动）——<b>恒速</b>，没有加减速。
     * <p>
     * 这里<b>不</b>广播：客户端有自己的一份恒速动画（{@link #getRenderTiltQuaternion}，渲染时钟纯函数），
     * 只靠"目标值"驱动。
     * 服务端的推进只为产出一个真实的当前角给推力与服务器侧逻辑用。
     */
    private void tickMotion() {
        final boolean azimuthMoving = this.azimuth.stepToward(AZIMUTH_DEG_PER_TICK);
        final boolean tiltMoving = this.tilt.stepToward(TILT_DEG_PER_TICK);
        final boolean scaleMoving = this.thrustScaleAxis.stepToward(THRUST_SCALE_PER_TICK);

        if (azimuthMoving || tiltMoving || scaleMoving) {
            // 只为落盘（不推客户端）
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
     * <b>渲染用</b>倾斜四元数：先把视觉角朝 {@link Axis#target} 推进一步（墙钟差值），再合成四元数。
     * <p>
     * ⚠️ 这里**故意不判断 {@code level.isClientSide}**：本方法只由 BER（客户端渲染）调用，
     * 而"物理体/子次元里的 level 侧标记"不可靠 —— 之前一版正是在这里提前返回了服务端真值，
     * 表现为"偏转瞬间到达目标"。
     *
     * @param partialTicks 渲染插值系数（墙钟差值已连续，不需要它）
     */
    public Quaternionf getRenderTiltQuaternion(final float partialTicks) {
        this.advanceVisualAnimation();
        return tiltQuaternion(tiltDirModel(this.visualAzimuthDeg, this.visualTiltDeg));
    }

    /**
     * 视觉角推进一步（每次渲染调用一次；用墙钟差值，与调用频率无关）。
     * <p>
     * 首次调用直接落在同步来的当前角上（不从 0 追）；单次 Δt 上限 0.25s，避免卡顿后一次跳完。
     */
    private void advanceVisualAnimation() {
        final long now = System.nanoTime();

        if (!this.visualInit) {
            this.visualAzimuthDeg = this.azimuth.value;
            this.visualTiltDeg = this.tilt.value;
            this.visualLastNanos = now;
            this.visualInit = true;
            return;
        }

        final float dt = Math.min(0.25f, (now - this.visualLastNanos) / 1.0e9f);
        this.visualLastNanos = now;

        if (dt <= 0f) {
            return;
        }

        this.visualAzimuthDeg = stepToward(this.visualAzimuthDeg, this.azimuth.target,
                AZIMUTH_DEG_PER_SECOND * dt);
        this.visualTiltDeg = stepToward(this.visualTiltDeg, this.tilt.target,
                TILT_DEG_PER_SECOND * dt);

        if (DEBUG_ANIM_LOG && this.debugAnimFrames++ % 20 == 0) {
            System.out.printf("[ATN-anim] inst=%d pos=%s visual=(%.2f,%.2f) target=(%.2f,%.2f)"
                            + " value=(%.2f,%.2f) dt=%.4f%n",
                    System.identityHashCode(this), this.worldPosition,
                    this.visualAzimuthDeg, this.visualTiltDeg,
                    this.azimuth.target, this.tilt.target,
                    this.azimuth.value, this.tilt.value, dt);
        }
    }

    /** 恒速逼近：走够 {@code step} 就够了，剩余不足一步就落位，绝不过冲 */
    private static float stepToward(final float current, final float target, final float step) {
        final float remaining = target - current;

        if (remaining == 0f) {
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
     * <b>客户端读"视觉角"</b>（渲染时钟纯函数，与 BER 画出来的模型同源）而不是服务端当前角：
     * 客户端这条链只喂粒子（喷口位置 / 粒子基向量 / 遮挡射线 / 进气漏斗 / 尾迹锚点），
     * 必须和<b>画出来的模型</b>对齐。服务端才是推力真值的来源，读 {@link Axis#value}。
     */
    @Override
    protected Vec3 nozzleAxis(final BlockState state) {
        // 客户端（粒子链）用视觉角，与 BER 画出来的模型同源
        final Vector3f dir = this.level != null && this.level.isClientSide
                ? tiltDirModel(this.visualAzimuthDeg, this.visualTiltDeg)
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
        // 目标也落盘：物理体（Sable 子次元）会周期性重载 BE 数据，只存当前角会让客户端的
        // 视觉动画丢掉"终点"，退化成一格一格跟服务端当前角
        tag.putFloat(TAG_TARGET_AZIMUTH, this.azimuth.target);
        tag.putFloat(TAG_TARGET_TILT, this.tilt.target);
        tag.putFloat(TAG_THRUST_SCALE, this.thrustScaleAxis.value);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;
        // 当前角 + 目标角一起恢复（没存过目标的老存档 ⇒ 目标 = 当前，等效"原地不动"）
        this.azimuth.reset(tag.contains(TAG_AZIMUTH) ? tag.getFloat(TAG_AZIMUTH) : 0f);
        this.tilt.reset(Mth.clamp(tag.contains(TAG_TILT) ? tag.getFloat(TAG_TILT) : 0f, 0f, MAX_TILT_DEG));
        if (tag.contains(TAG_TARGET_AZIMUTH)) {
            this.azimuth.setTarget(tag.getFloat(TAG_TARGET_AZIMUTH));
        }
        if (tag.contains(TAG_TARGET_TILT)) {
            this.tilt.setTarget(Mth.clamp(tag.getFloat(TAG_TARGET_TILT), 0f, MAX_TILT_DEG));
        }
        this.thrustScaleAxis.reset(tag.contains(TAG_THRUST_SCALE) ? tag.getFloat(TAG_THRUST_SCALE) : 1f);
        // ⚠️ 这里**不要**重置视觉角动画状态（曾写成 visualInit = false）：
        // 物理体（Sable 子次元）会周期性重发/重载方块实体数据，一旦重载就把视觉角吸附到
        // 服务端当前角，"渐变"直接退化成"瞬间跳转"（实测症状）。
        // 视觉角只在**真正的新实例**（字段默认 false，首次渲染）时落位一次。
        if (DEBUG_ANIM_LOG && this.level != null && this.level.isClientSide) {
            System.out.printf("[ATN-load] inst=%d pos=%s value=(%.2f,%.2f) visual=(%.2f,%.2f) visualInit=%b%n",
                    System.identityHashCode(this), this.worldPosition,
                    this.azimuth.value, this.tilt.value,
                    this.visualAzimuthDeg, this.visualTiltDeg, this.visualInit);
        }
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

        // 客户端照抄"当前角 + 目标角"。视觉动画**不需要**在这里做任何事：
        // 它只是"每次渲染朝 target 走一步"，target 变了自然就会转过去（没有会失效的中间状态）。
        final float newAzimuth = tag.contains(TAG_AZIMUTH) ? tag.getFloat(TAG_AZIMUTH) : 0f;
        final float newTilt = tag.contains(TAG_TILT) ? tag.getFloat(TAG_TILT) : 0f;
        this.azimuth.setSynced(newAzimuth,
                tag.contains(TAG_TARGET_AZIMUTH) ? tag.getFloat(TAG_TARGET_AZIMUTH) : newAzimuth);
        this.tilt.setSynced(Mth.clamp(newTilt, 0f, MAX_TILT_DEG),
                Mth.clamp(tag.contains(TAG_TARGET_TILT) ? tag.getFloat(TAG_TARGET_TILT) : newTilt,
                        0f, MAX_TILT_DEG));

        if (DEBUG_ANIM_LOG) {
            System.out.printf("[ATN-pkt ] inst=%d pos=%s value=(%.2f,%.2f) target=(%.2f,%.2f)"
                            + " visual=(%.2f,%.2f) visualInit=%b%n",
                    System.identityHashCode(this), this.worldPosition,
                    this.azimuth.value, this.tilt.value,
                    this.azimuth.target, this.tilt.target,
                    this.visualAzimuthDeg, this.visualTiltDeg, this.visualInit);
        }

        // onPhysicsBody 不落盘也不进 update tag：客户端以「频道号是否有效」推断
        this.onPhysicsBody = this.channel >= 0;
    }

    // ═══════════════ 单轴运动（恒速线性） ═══════════════

    /**
     * 一条轴的运动状态（当前值 / 目标），逐 tick <b>恒速</b>逼近目标（用户定稿：不要加减速）。
     * <p>
     * 服务端 tick 推进 {@link #value}（推力的真值）；客户端在 {@link #getRenderTiltQuaternion} 里
     * 用同一个速度把视觉角算成渲染时钟的纯函数，两边最终角相同。
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
