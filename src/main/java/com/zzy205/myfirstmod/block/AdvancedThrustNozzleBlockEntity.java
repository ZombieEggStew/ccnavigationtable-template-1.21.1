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
 * <li><b>倾斜姿态 + 渐变到位</b>（{@link #getRenderTiltQuaternion}）：Lua 只写<b>目标</b>偏转点；
 *     服务端按<b>恒速</b>（偏转平面里「度/秒」，无加减速）推进真实偏转点给推力用；客户端把视觉姿态写成
 *     <b>墙钟（{@link System#nanoTime()}）的纯函数</b>，因此与 tick / 渲染 / 同步包频率全都无关。</li>
 * </ol>
 *
 * <p><b>偏转的两种 Lua 写法</b>（都换算成同一个偏转点 {@code (x, y)}；插值发生在<b>偏转平面</b>上
 * ⇒ 喷口轴端走<b>直线</b>，不再沿圆锥面绕半圆）：
 * <ul>
 * <li>{@code setDeflection(方位角, 锥角)}：极坐标写法，适合脚本已经算好角度的场合；</li>
 * <li>{@code setDirection(x, y)}：摇杆写法，{@code -1..1} 的向量，<b>方向</b> = 方位角、
 *     <b>长度</b>按圆形映射到锥角（长度 1 = {@link #MAX_TILT_DEG} 满锥角）。</li>
 * </ul>
 */
public class AdvancedThrustNozzleBlockEntity extends ThrustNozzleBlockEntity {

    private static final String TAG_CHANNEL = "Channel";
    private static final String TAG_OCCUPIED = "OccupiedChannels";
    /** 偏转点分量（度）：{@code x = 锥角·cos(方位)}, {@code y = 锥角·sin(方位)}，长度即锥角 */
    private static final String TAG_DEF_X = "DefX";
    private static final String TAG_DEF_Y = "DefY";
    private static final String TAG_TARGET_DEF_X = "TargetDefX";
    private static final String TAG_TARGET_DEF_Y = "TargetDefY";
    /** 旧版（极坐标两条轴）键：只用于旧存档的兼容读取 */
    private static final String TAG_LEGACY_AZIMUTH = "AzimuthDeg";
    private static final String TAG_LEGACY_TILT = "TiltDeg";
    private static final String TAG_LEGACY_TARGET_AZIMUTH = "TargetAzimuthDeg";
    private static final String TAG_LEGACY_TARGET_TILT = "TargetTiltDeg";
    private static final String TAG_THRUST_SCALE = "ThrustScale";
    private static final String TAG_TARGET_THRUST_SCALE = "TargetThrustScale";

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
     * 单位是「<b>偏转平面里直线推进的度/秒</b>」：服务端按 tick 换算成步长推进（{@code /20}），
     * 客户端视觉动画直接用墙钟（{@link System#nanoTime()}）算 —— 两者速度完全一致。
     * <p>
     * 耗时（160°/s）：中心 → 满锥角 15° = 0.09s；反向全行程（+15° → −15°，弦长 30°）= 0.19s；
     * 垂直换向 90°（弦长 15√2 ≈ 21.2°）= 0.13s。
     */
    private static final float DEFLECTION_DEG_PER_SECOND = 160f;

    /** 服务端每 tick 的步长（与上面的度/秒严格对应） */
    private static final float DEFLECTION_DEG_PER_TICK = DEFLECTION_DEG_PER_SECOND / 20f;

    /**
     * <b>矢量喷管的出力倍率</b>（用户定稿 2026-11：只要矢量喷管翻倍，定向喷管维持已标定的
     * {@link #THRUST_PER_RPM} = 1 pN/RPM）。
     * <p>
     * 它乘在父类公式的 {@code thrustScale()} 上 ⇒ 满出力（Lua 系数 1.0）时实际 <b>2 pN/RPM</b>：
     * 气压 1.0、256 RPM 时 <b>512 pN/喷管</b>（定向喷管仍是 256 pN）。
     * <p>
     * ⚠️ 只影响<b>推力</b>的<b>倍率</b>：它不改变粒子的强度（粒子强度由 Lua 的出力系数
     * {@code thrustScaleAxis} 决定，见 {@link #particleIntensityScale()}）。
     */
    private static final double THRUST_OUTPUT_MULTIPLIER = 2.0D;

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

    /**
     * 偏转点 x 分量（度）：与 {@link #defY} 合起来表示倾斜姿态 ——
     * <b>长度 = 锥角</b>、<b>方向 = 方位角</b>（即 {@code (锥角·cos 方位, 锥角·sin 方位)}）。
     * <p>
     * 为什么用「偏转点」而不是「方位角 + 锥角」两条极坐标轴：用户要求喷口<b>沿直线</b>移动。
     * 极坐标下只改方位角 ⇒ 锥角不变 ⇒ 轴端在圆锥面上画圆弧（实测就是走半圆）；
     * 改成对 {@code (x, y)} 做直线插值后，轴端在偏转平面里走直线（穿过中心 = 直出，不再绕半圈）。
     */
    private final Axis defX = new Axis(0f);

    /** 偏转点 y 分量（度）—— 见 {@link #defX} */
    private final Axis defY = new Axis(0f);

    /** 额外出力系数（0..1，默认 1 = 满出力）：乘在「风扇转速 × 气压」之上 */
    private final Axis thrustScaleAxis = new Axis(1f);

    /**
     * 视觉偏转点（**只在客户端**，本地动画状态）：一个会自己朝 {@link Axis#target} 走的二维点。
     * <p>
     * 推进方式 = <b>每次渲染用墙钟差值走一步</b>：
     * <pre>沿目标方向把 (x, y) 推进 速度(度/秒) × Δt</pre>
     * 这样做的三个好处（也是前几版翻车的点）：
     * <ul>
     * <li>Δt 来自 {@link System#nanoTime()} 的<b>差值</b>，所以无论渲染一帧被调用几次、
     *     帧率多少，积分都是对的（"每次调用走固定步长"才会被调用频率放大）；</li>
     * <li><b>没有 (起点, 终点, 起始时刻) 三元组</b>可以失效 —— 以前那套一旦 `animTo` 没更新，
     *     `from == to` 就永远不动（实测日志里正是如此）；</li>
     * <li>只依赖同步来的 <b>target</b>（日志已证明它稳定到达），与当前值、包频率无关。</li>
     * </ul>
     */
    private float visualDefX;
    private float visualDefY;
    private long visualLastNanos;
    private boolean visualInit;
    /** 渲染调用计数（仅调试日志用） */
    private int debugAnimFrames;

    /**
     * 请求偏转（Lua {@code setDeflection} 入口）：{@code 方位角 + 锥角} 换算成偏转点后<b>只写目标</b>，
     * 喷口沿偏转平面里的<b>直线</b>恒速走过去（见 {@link #tickMotion()}）。
     * <p>
     * ⚠️ 因为插值发生在偏转平面上：从「+15° 朝东」到「+15° 朝西」会<b>穿过中心</b>
     * （中途锥角降到 0、方向在中心处瞬间换向），而不是沿 15° 圆周走半圈 —— 这正是"走直线"的要求。
     * 锥角钳到 {@code 0..MAX_TILT_DEG}。
     */
    public void requestDeflection(final float azimuthDeg, final float tiltDeg) {
        final float tilt = Mth.clamp(tiltDeg, 0f, MAX_TILT_DEG);
        this.setDeflectionTarget(pointX(azimuthDeg, tilt), pointY(azimuthDeg, tilt));
    }

    /**
     * 请求偏转（Lua {@code setDirection} 入口）：<b>摇杆语义</b> ——
     * 向量的<b>方向</b>决定方位角，向量的<b>长度</b>映射到半径 1 的圆上决定锥角
     * （长度 0 = 直出、长度 1 = {@link #MAX_TILT_DEG} 满锥角）。
     * <p>
     * 输入先各自钳到 {@code -1..1}，再按<b>圆</b>钳位（长度 &gt; 1 时整体缩到 1）：
     * 斜推 {@code (1, 1)} 得到的是 45° 方向的满锥角，而不是被拉成方块角。
     */
    public void requestDirection(final float x, final float y) {
        final float clampedX = Mth.clamp(x, -1f, 1f);
        final float clampedY = Mth.clamp(y, -1f, 1f);
        final float length = (float) Math.sqrt(clampedX * clampedX + clampedY * clampedY);
        final float scale = length > 1f ? 1f / length : 1f;

        this.setDeflectionTarget(clampedX * scale * MAX_TILT_DEG, clampedY * scale * MAX_TILT_DEG);
    }

    /** 写偏转目标点 + 推一次客户端（一次命令只发一次包，见 memo §6.5） */
    private void setDeflectionTarget(final float targetX, final float targetY) {
        this.defX.setTarget(targetX);
        this.defY.setTarget(targetY);
        // **一次命令只发一次包**（照 VectorThrusterBlockEntity#onVectorSignalChanged 的 notifyUpdate）：
        // 客户端只需知道目标，动画由它自己的 ticker 推进；每 tick 广播会触发光照/块更新，
        // 正是画面"卡卡的"的另一个来源。
        this.syncToClient();
    }

    /** 请求出力系数（0..1，越界钳位）：同样只写目标，恒速逼近 */
    public void requestThrustScale(final float scale) {
        this.thrustScaleAxis.setTarget(Mth.clamp(scale, 0f, 1f));
        // 必须推一次客户端：粒子密度要跟着出力走，客户端得知道新目标（本地再按同一速度补渐变）
        this.syncToClient();
    }

    /**
     * 服务端每 tick 推进偏转 / 出力（由 {@link #serverTick} 驱动）——<b>恒速</b>，没有加减速。
     * <p>
     * 这里<b>不</b>广播：客户端有自己的一份恒速动画（{@link #getRenderTiltQuaternion}，渲染时钟纯函数），
     * 只靠"目标值"驱动。
     * 服务端的推进只为产出一个真实的当前偏转点给推力与服务器侧逻辑用。
     */
    private void tickMotion() {
        final boolean deflectionMoving = this.stepDeflectionTowardTarget(DEFLECTION_DEG_PER_TICK);
        final boolean scaleMoving = this.thrustScaleAxis.stepToward(THRUST_SCALE_PER_TICK);

        if (deflectionMoving || scaleMoving) {
            // 只为落盘（不推客户端）
            this.setChanged();
        }
    }

    /**
     * 偏转点朝目标走一步：<b>沿直线</b>（不是逐轴各走各的，否则斜向会变慢、轨迹也不是纯直线），
     * 把剩余距离缩到本 tick 步长后按比例分摊到 x / y。
     *
     * @return 本 tick 是否真的动了
     */
    private boolean stepDeflectionTowardTarget(final float step) {
        final float dx = this.defX.target - this.defX.value;
        final float dy = this.defY.target - this.defY.value;
        final float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance <= 1.0E-6f) {
            final boolean settled = this.defX.value != this.defX.target
                    || this.defY.value != this.defY.target;
            this.defX.value = this.defX.target;
            this.defY.value = this.defY.target;
            return settled;
        }

        if (distance <= step) {
            this.defX.value = this.defX.target;
            this.defY.value = this.defY.target;
        } else {
            final float k = step / distance;
            this.defX.value += dx * k;
            this.defY.value += dy * k;
        }

        return true;
    }

    /** 度归正到 {@code [0, 360)} */
    private static float normalizeDeg(final float deg) {
        final float wrapped = deg % 360f;
        return wrapped < 0f ? wrapped + 360f : wrapped;
    }

    /** 极坐标 → 偏转点 x 分量：{@code 锥角·cos(方位角)} */
    private static float pointX(final float azimuthDeg, final float tiltDeg) {
        return tiltDeg * (float) Math.cos(Math.toRadians(azimuthDeg));
    }

    /** 极坐标 → 偏转点 y 分量：{@code 锥角·sin(方位角)} */
    private static float pointY(final float azimuthDeg, final float tiltDeg) {
        return tiltDeg * (float) Math.sin(Math.toRadians(azimuthDeg));
    }

    /** 偏转点 → 锥角（度）= 点的长度，钳到 {@code 0..MAX_TILT_DEG} */
    private static float tiltDegOf(final float x, final float y) {
        return Math.min(MAX_TILT_DEG, (float) Math.sqrt(x * x + y * y));
    }

    /**
     * 偏转点 → 方位角（度，归正 0..360）。
     * <p>
     * ⚠️ 点在中心（锥角 0）时方位<b>没有意义</b>，返回 0；走直线穿过中心时会看到方位角瞬间反向，
     * 那是"直出"那一刻的正常现象，不是跳变 bug。
     */
    private static float azimuthDegOf(final float x, final float y) {
        if (Math.abs(x) < 1.0E-5f && Math.abs(y) < 1.0E-5f) {
            return 0f;
        }

        return normalizeDeg((float) Math.toDegrees(Math.atan2(y, x)));
    }

    /**
     * 喷嘴模型空间的倾斜朝向（+Y = 未倾斜时的直出方向，即 FACING=up 的模型帧）。
     * 用<b>当前</b>偏转点，供物理（{@link #nozzleAxis}）与渲染的未插值版本共用。
     */
    private Vector3f tiltDirModel() {
        return tiltDirModel(this.defX.value, this.defY.value);
    }

    /**
     * 由偏转点 {@code (x, y)}（度）算模型空间方向：半径 {@code r = |(x, y)|} 就是锥角，
     * <pre>(sin r / r · x, cos r, sin r / r · y)</pre>
     * 即 {@code (sin t·cos a, cos t, sin t·sin a)} 的等价写法；{@code r → 0} 时退化为 {@code (0, 1, 0)}（直出）。
     */
    private static Vector3f tiltDirModel(final float x, final float y) {
        final float radiusDeg = (float) Math.sqrt(x * x + y * y);

        if (radiusDeg < 1.0E-5f) {
            return new Vector3f(0f, 1f, 0f);
        }

        final float tiltRad = (float) Math.toRadians(Math.min(radiusDeg, MAX_TILT_DEG));
        final float k = (float) (Math.sin(tiltRad) / radiusDeg);

        return new Vector3f(x * k, (float) Math.cos(tiltRad), y * k);
    }

    /**
     * 喷嘴倾斜姿态的四元数（模型空间，<b>当前</b>偏转点，不做动画）：把 +Y 转到 {@link #tiltDirModel()}
     * 的最小弧旋转；{@code 锥角 = 0} ⇒ 恒等旋转（直出）。
     */
    public Quaternionf getTiltQuaternion() {
        return tiltQuaternion(tiltDirModel());
    }

    /**
     * <b>渲染用</b>倾斜四元数：先把视觉偏转点朝 {@link Axis#target} 推进一步（墙钟差值），再合成四元数。
     * <p>
     * ⚠️ 这里**故意不判断 {@code level.isClientSide}**：本方法只由 BER（客户端渲染）调用，
     * 而"物理体/子次元里的 level 侧标记"不可靠 —— 之前一版正是在这里提前返回了服务端真值，
     * 表现为"偏转瞬间到达目标"。
     *
     * @param partialTicks 渲染插值系数（墙钟差值已连续，不需要它）
     */
    public Quaternionf getRenderTiltQuaternion(final float partialTicks) {
        this.advanceVisualAnimation();
        return tiltQuaternion(tiltDirModel(this.visualDefX, this.visualDefY));
    }

    /**
     * 视觉偏转点推进一步（每次渲染调用一次；用墙钟差值，与调用频率无关），<b>沿直线</b>朝目标走。
     * <p>
     * 首次调用直接落在同步来的当前偏转点上（不从 0 追）；单次 Δt 上限 0.25s，避免卡顿后一次跳完。
     */
    private void advanceVisualAnimation() {
        final long now = System.nanoTime();

        if (!this.visualInit) {
            this.visualDefX = this.defX.value;
            this.visualDefY = this.defY.value;
            this.visualLastNanos = now;
            this.visualInit = true;
            return;
        }

        final float dt = Math.min(0.25f, (now - this.visualLastNanos) / 1.0e9f);
        this.visualLastNanos = now;

        if (dt <= 0f) {
            return;
        }

        final float dx = this.defX.target - this.visualDefX;
        final float dy = this.defY.target - this.visualDefY;
        final float distance = (float) Math.sqrt(dx * dx + dy * dy);
        final float step = DEFLECTION_DEG_PER_SECOND * dt;

        if (distance <= 1.0E-6f || distance <= step) {
            this.visualDefX = this.defX.target;
            this.visualDefY = this.defY.target;
        } else {
            final float k = step / distance;
            this.visualDefX += dx * k;
            this.visualDefY += dy * k;
        }

        if (DEBUG_ANIM_LOG && this.debugAnimFrames++ % 20 == 0) {
            System.out.printf("[ATN-anim] inst=%d pos=%s visual=(%.2f,%.2f) target=(%.2f,%.2f)"
                            + " value=(%.2f,%.2f) dt=%.4f%n",
                    System.identityHashCode(this), this.worldPosition,
                    this.visualDefX, this.visualDefY,
                    this.defX.target, this.defY.target,
                    this.defX.value, this.defY.value, dt);
        }
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
     * <b>客户端读"视觉偏转点"</b>（渲染时钟纯函数，与 BER 画出来的模型同源）而不是服务端当前值：
     * 客户端这条链只喂粒子（喷口位置 / 粒子基向量 / 遮挡射线 / 进气漏斗 / 尾迹锚点），
     * 必须和<b>画出来的模型</b>对齐。服务端才是推力真值的来源，读 {@link Axis#value}。
     */
    @Override
    protected Vec3 nozzleAxis(final BlockState state) {
        // 客户端（粒子链）用视觉偏转点，与 BER 画出来的模型同源
        final Vector3f dir = this.level != null && this.level.isClientSide
                ? tiltDirModel(this.visualDefX, this.visualDefY)
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

    /**
     * 覆盖父类钩子：矢量喷管满出力 = {@link #THRUST_OUTPUT_MULTIPLIER} × 转速（2 pN/RPM），
     * 再乘上 Lua 的出力系数（0..1 ⇒ 0..2 倍额定）。
     */
    @Override
    protected double thrustScale() {
        return this.thrustScaleAxis.value * THRUST_OUTPUT_MULTIPLIER;
    }

    /**
     * 覆盖父类钩子：<b>出力系数也决定粒子</b>（用户定稿 2026-11）。
     * <p>
     * 父类把「有效油门 = 油门 × 本系数」喂给排气与进气 ⇒ 系数 0.5 的观感约等于转速减半
     * （数量、排气速度、进气漏斗长度一起缩），系数 0 = 排气/进气完全不出。
     * 高空尾迹不在此列（按位移与高度发射，见 {@code tickContrail}）。
     * <p>
     * 客户端要在这里<b>本地补一步渐变</b>：同步包只在命令时发一次（不逐 tick 广播），
     * 客户端拿到的是「命令那一刻的值 + 目标」，而服务端那份 {@code value} 是每 tick 推进的。
     * 这里用同一个 {@link #THRUST_SCALE_PER_TICK} 把本地值朝目标推一步，
     * 粒子才会和推力一样在 0.2s 内平滑衰减，而不是跟着包瞬变。
     */
    @Override
    protected float particleIntensityScale() {
        if (this.level != null && this.level.isClientSide) {
            this.thrustScaleAxis.stepToward(THRUST_SCALE_PER_TICK);
        }

        return this.thrustScaleAxis.value;
    }

    /** 当前方位角（度，0..360；由偏转点反算，正处于直出中心时无意义、返回 0） */
    public float getAzimuthDeg() {
        return azimuthDegOf(this.defX.value, this.defY.value);
    }

    /** 当前锥角（度，0..{@link #MAX_TILT_DEG}；= 偏转点长度，过渡中就是"此刻实际"的值） */
    public float getTiltDeg() {
        return tiltDegOf(this.defX.value, this.defY.value);
    }

    /** 当前出力系数（0..1；过渡中就是"此刻实际"的值） */
    public float getThrustScale() {
        return this.thrustScaleAxis.value;
    }

    /** 是否还在渐变中（偏转或出力没到位） */
    public boolean isMoving() {
        return !this.defX.atRest() || !this.defY.atRest() || !this.thrustScaleAxis.atRest();
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
         * 设置喷口偏转（<b>极坐标写法</b>）：{@code azimuthDeg} = 绕 FACING 轴的方位角（0..360，负值自动归正），
         * {@code tiltDeg} = 偏离轴线的锥角（自动钳到 0..{@link #MAX_TILT_DEG}）。
         * <p>
         * <b>不会瞬移</b>：这里只写目标，喷口在<b>偏转平面里沿直线</b>恒速过去（见
         * {@link #getDeflection()} 与 memo §6.5），推力方向、排气/进气/尾迹粒子、渲染四者同时跟着走。
         * <p>
         * ⚠️ 只改方位角而锥角不变时，喷口不再沿圆锥面绕圆弧，而是<b>直线穿过偏转平面</b>
         * （反向 180° 的行程会先在中间降回直出再抬起），这正是"走直线"的语义。
         * 想要摇杆手感用 {@link #setDirection(double, double)}。非有限值返回 false。
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
         * 摇杆式设置偏转（<b>直角坐标写法</b>）：{@code x} / {@code y} ∈ {@code -1..1}，
         * <b>方向</b>决定方位角、<b>长度</b>按圆形映射决定锥角（长度 0 = 直出、长度 1 = 满锥角 15°）。
         * <p>
         * 输入先各自钳到 {@code -1..1} 再整体钳进单位圆：{@code (1,1)} 与 {@code (0.707,0.707)}
         * 等价于"45° 方向满锥角"。整体为零向量 ⇒ 回到直出。
         * <p>
         * 与 {@link #setDeflection(double, double)} 共用同一套运动曲线：都只写目标，
         * 喷口在偏转平面里沿直线恒速过去（两段行程之间也是直线，不会绕弧）。非有限值返回 false。
         *
         * @param x -1..1，喷口向模型「东」（+X）偏；负值向西
         * @param y -1..1，喷口向模型「南」（+Z）偏；负值向北
         */
        @LuaFunction(mainThread = true)
        public final boolean setDirection(final double x, final double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return false;
            }

            final AdvancedThrustNozzleBlockEntity be = AdvancedThrustNozzleBlockEntity.this;
            if (be.level == null || be.level.isClientSide) {
                return false;
            }

            be.requestDirection((float) x, (float) y);
            return true;
        }

        /**
         * 当前偏转：返回表
         * {@code {azimuth = 实际角, tilt = 实际角, targetAzimuth = 目标角, targetTilt = 目标角,
         *        x = 实际偏转点 X, y = 实际偏转点 Y, targetX = 目标偏转点 X, targetY = 目标偏转点 Y}}。
         * <p>
         * 渐变过程中实际值与目标值不同；{@code azimuth/tilt} 由偏转点反算（方位已归正 0..360，
         * 点落在直出中心时方位无意义、报 0）。{@code x/y} 是 {@code 锥角·(cos, sin) 方位}，
         * 调 {@link #setDirection(double, double)} 时可直接对照。
         */
        @LuaFunction
        public final Map<String, Object> getDeflection() {
            final AdvancedThrustNozzleBlockEntity be = AdvancedThrustNozzleBlockEntity.this;
            final float x = be.defX.value;
            final float y = be.defY.value;
            final float targetX = be.defX.target;
            final float targetY = be.defY.target;

            return Map.of(
                    "azimuth", azimuthDegOf(x, y),
                    "tilt", tiltDegOf(x, y),
                    "targetAzimuth", azimuthDegOf(targetX, targetY),
                    "targetTilt", tiltDegOf(targetX, targetY),
                    "x", x,
                    "y", y,
                    "targetX", targetX,
                    "targetY", targetY);
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
         * <p>
         * 满出力 = <b>2 pN/RPM</b>（{@link #THRUST_OUTPUT_MULTIPLIER}，用户定稿；定向喷管仍是 1 pN/RPM）
         * ⇒ 气压 1.0、256 RPM 时 512 pN/喷管。
         * <b>同时决定排气与进气粒子</b>：有效油门 = 转速 × 本系数 ⇒ 数量、排气速度、进气漏斗长度一起变，
         * 系数 0 时排气/进气完全不出（高空尾迹不受影响，它按位移与高度发射）。
         * 推力与粒子都按同一条运动曲线渐变（0.2s 走完 0→1）。
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

        tag.putFloat(TAG_DEF_X, this.defX.value);
        tag.putFloat(TAG_DEF_Y, this.defY.value);
        // 目标也落盘：物理体（Sable 子次元）会周期性重载 BE 数据，只存当前值会让客户端的
        // 视觉动画丢掉"终点"，退化成一格一格跟服务端当前值
        tag.putFloat(TAG_TARGET_DEF_X, this.defX.target);
        tag.putFloat(TAG_TARGET_DEF_Y, this.defY.target);
        tag.putFloat(TAG_THRUST_SCALE, this.thrustScaleAxis.value);
        tag.putFloat(TAG_TARGET_THRUST_SCALE, this.thrustScaleAxis.target);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;

        if (tag.contains(TAG_DEF_X) || tag.contains(TAG_DEF_Y)) {
            // 当前偏转点 + 目标偏转点一起恢复（没存过目标 ⇒ 目标 = 当前，等效"原地不动"）
            this.defX.reset(tag.getFloat(TAG_DEF_X));
            this.defY.reset(tag.getFloat(TAG_DEF_Y));
            if (tag.contains(TAG_TARGET_DEF_X) || tag.contains(TAG_TARGET_DEF_Y)) {
                this.defX.setTarget(tag.getFloat(TAG_TARGET_DEF_X));
                this.defY.setTarget(tag.getFloat(TAG_TARGET_DEF_Y));
            }
        } else {
            // 旧存档（极坐标 AzimuthDeg/TiltDeg）：换算成偏转点
            final float legacyAzimuth =
                    tag.contains(TAG_LEGACY_AZIMUTH) ? tag.getFloat(TAG_LEGACY_AZIMUTH) : 0f;
            final float legacyTilt = Mth.clamp(
                    tag.contains(TAG_LEGACY_TILT) ? tag.getFloat(TAG_LEGACY_TILT) : 0f, 0f, MAX_TILT_DEG);
            this.defX.reset(pointX(legacyAzimuth, legacyTilt));
            this.defY.reset(pointY(legacyAzimuth, legacyTilt));

            if (tag.contains(TAG_LEGACY_TARGET_AZIMUTH) || tag.contains(TAG_LEGACY_TARGET_TILT)) {
                final float legacyTargetAzimuth = tag.contains(TAG_LEGACY_TARGET_AZIMUTH)
                        ? tag.getFloat(TAG_LEGACY_TARGET_AZIMUTH) : legacyAzimuth;
                final float legacyTargetTilt = Mth.clamp(
                        tag.contains(TAG_LEGACY_TARGET_TILT) ? tag.getFloat(TAG_LEGACY_TARGET_TILT) : legacyTilt,
                        0f, MAX_TILT_DEG);
                this.defX.setTarget(pointX(legacyTargetAzimuth, legacyTargetTilt));
                this.defY.setTarget(pointY(legacyTargetAzimuth, legacyTargetTilt));
            }
        }

        final float scale = tag.contains(TAG_THRUST_SCALE) ? tag.getFloat(TAG_THRUST_SCALE) : 1f;
        this.thrustScaleAxis.reset(scale);
        // 目标也恢复：粒子与推力的渐变要"继续走完"而不是停在半路
        this.thrustScaleAxis.setTarget(tag.contains(TAG_TARGET_THRUST_SCALE)
                ? tag.getFloat(TAG_TARGET_THRUST_SCALE) : scale);
        // ⚠️ 这里**不要**重置视觉动画状态（曾写成 visualInit = false）：
        // 物理体（Sable 子次元）会周期性重发/重载方块实体数据，一旦重载就把视觉姿态吸附到
        // 服务端当前值，"渐变"直接退化成"瞬间跳转"（实测症状）。
        // 视觉偏转点只在**真正的新实例**（字段默认 false，首次渲染）时落位一次。
        if (DEBUG_ANIM_LOG && this.level != null && this.level.isClientSide) {
            System.out.printf("[ATN-load] inst=%d pos=%s value=(%.2f,%.2f) visual=(%.2f,%.2f) visualInit=%b%n",
                    System.identityHashCode(this), this.worldPosition,
                    this.defX.value, this.defY.value,
                    this.visualDefX, this.visualDefY, this.visualInit);
        }
    }

    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
        final CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt(TAG_CHANNEL, this.channel);
        tag.putIntArray(TAG_OCCUPIED, this.occupiedChannels);
        // 当前偏转点：客户端首次落位 / 重新加载用；目标偏转点：客户端本地动画的终点（**动画只靠它驱动**）
        tag.putFloat(TAG_DEF_X, this.defX.value);
        tag.putFloat(TAG_DEF_Y, this.defY.value);
        tag.putFloat(TAG_TARGET_DEF_X, this.defX.target);
        tag.putFloat(TAG_TARGET_DEF_Y, this.defY.target);
        // 出力系数：客户端要按它缩放排气/进气粒子（tickClient → particleIntensityScale）
        tag.putFloat(TAG_THRUST_SCALE, this.thrustScaleAxis.value);
        tag.putFloat(TAG_TARGET_THRUST_SCALE, this.thrustScaleAxis.target);
        return tag;
    }

    @Override
    public void handleUpdateTag(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        this.channel = tag.contains(TAG_CHANNEL) ? tag.getInt(TAG_CHANNEL) : CHANNEL_UNASSIGNED;
        this.occupiedChannels = tag.contains(TAG_OCCUPIED) ? tag.getIntArray(TAG_OCCUPIED) : new int[0];

        // 客户端照抄"当前偏转点 + 目标偏转点"。视觉动画**不需要**在这里做任何事：
        // 它只是"每次渲染朝 target 走一步"，target 变了自然就会转过去（没有会失效的中间状态）。
        final float newX = tag.contains(TAG_DEF_X) ? tag.getFloat(TAG_DEF_X) : 0f;
        final float newY = tag.contains(TAG_DEF_Y) ? tag.getFloat(TAG_DEF_Y) : 0f;
        this.defX.setSynced(newX, tag.contains(TAG_TARGET_DEF_X) ? tag.getFloat(TAG_TARGET_DEF_X) : newX);
        this.defY.setSynced(newY, tag.contains(TAG_TARGET_DEF_Y) ? tag.getFloat(TAG_TARGET_DEF_Y) : newY);

        // 出力系数同理：照抄「当时的值 + 目标」，本地平滑推进交给 particleIntensityScale（客户端每 tick 一步）。
        // ⚠️ 这里同样不许重置任何视觉动画状态（见 loadAdditional 的警告）。
        if (tag.contains(TAG_THRUST_SCALE) || tag.contains(TAG_TARGET_THRUST_SCALE)) {
            final float scale = tag.contains(TAG_THRUST_SCALE) ? tag.getFloat(TAG_THRUST_SCALE) : 1f;
            this.thrustScaleAxis.setSynced(scale,
                    tag.contains(TAG_TARGET_THRUST_SCALE) ? tag.getFloat(TAG_TARGET_THRUST_SCALE) : scale);
        }

        if (DEBUG_ANIM_LOG) {
            System.out.printf("[ATN-pkt ] inst=%d pos=%s value=(%.2f,%.2f) target=(%.2f,%.2f)"
                            + " visual=(%.2f,%.2f) visualInit=%b%n",
                    System.identityHashCode(this), this.worldPosition,
                    this.defX.value, this.defY.value,
                    this.defX.target, this.defY.target,
                    this.visualDefX, this.visualDefY, this.visualInit);
        }

        // onPhysicsBody 不落盘也不进 update tag：客户端以「频道号是否有效」推断
        this.onPhysicsBody = this.channel >= 0;
    }

    // ═══════════════ 单轴运动（恒速线性） ═══════════════

    /**
     * 一条轴的运动状态（当前值 / 目标），逐 tick <b>恒速</b>逼近目标（用户定稿：不要加减速）。
     * <p>
     * 服务端 tick 推进 {@link #value}（推力的真值）；客户端在 {@link #getRenderTiltQuaternion} 里
     * 用同一个速度把视觉状态算成渲染时钟的纯函数，两边最终值相同。
     * <p>
     * 偏转有<b>两个</b>轴（{@link #defX} / {@link #defY}），所以它走的是
     * {@link #stepDeflectionTowardTarget}（二维直线）；{@code stepToward} 只给单轴的出力系数用。
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
         * 客户端收到同步：当前值 + 目标值都照抄服务端（客户端**不**推进运动，
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
