package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.foundation.particle.AirParticleData;
import com.zzy205.myfirstmod.particle.ExhaustParticleData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * 定向喷管方块实体：把背后电扇的转速换算成<b>纯轴向恒定推力</b>，每个物理 substep 施加到所在物理体上。
 * <p>
 * 参考来源（均为只读参考）：
 * <ul>
 * <li>{@code api/create-1.21.1-6.0.10-280-sources/.../kinetics/fan/NozzleBlockEntity.java}
 *     —— 原版喷嘴只推原版实体（{@code setDeltaMovement}），对 Sable 物理体零作用；</li>
 * <li>{@code references/sable-main/neoforge/.../mixin/compatibility/create/nozzle/block_entity/NozzleHoveringMixin.java}
 *     + {@code mixinhelper/.../NozzleHoveringHelper.java} —— 原版喷嘴真正出力的地方（Sable 的 mixin）：
 *     13 根射线打到表面、按命中距离衰减、{@code magnitude = 5 · scale · softSpeed(>128RPM 软缩放) · 气压}，
 *     实测 256 RPM/1 格 = 319.46 pN；</li>
 * <li>{@code references/sable-main/neoforge/.../fans_provide_force/EncasedFanBlockEntityMixin.java}
 *     —— 无喷嘴时电扇自身的螺旋桨推力；注意其 {@code isActive()} 要求 FACING 前方是空气，
 *     装上喷管后该路推力自动归零 ⇒ 总出力 = 本方块，数值可预测。</li>
 * </ul>
 * 本实现的取舍（用户定稿：纯轴向恒定推力，完全不管表面）：
 * <ul>
 * <li><b>不做射线</b>：不需要命中任何表面，任意高度/距离出力相同（原版喷嘴 6.4 格外归零）；</li>
 * <li><b>不乘气压项</b>：出力与海拔无关（Sable 原版喷嘴乘 {@code DimensionPhysicsData.getAirPressure}）；</li>
 * <li><b>转速线性</b>：不做 Sable 的 {@code >128 RPM 软缩放}，保持 memo §6.5 已验证的"推力∝转速"手感；</li>
 * <li><b>力的大小</b>：{@link #THRUST_PER_RPM} × 转速，256 RPM 时单喷管 800 pN = 原版喷嘴实测
 *     319.46 pN 的 2.5 倍。</li>
 * </ul>
 * 力以点力的形式打进 Sable 的 {@code sable:propulsion} 力组：由 Sable 在物理步末统一施加
 * （{@code ServerSubLevel#applyQueuedForces}），并会被 {@code compat/cc/FlightDataRecorder} 的
 * 力组遥测列记录，方便进游戏标定。点力自带力臂，多个喷管的位置差自动产生力矩。
 */
public class ThrustNozzleBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

    /**
     * 每 RPM 的轴向推力（pN/RPM）。256 RPM → 800 pN/喷管。
     * 标定依据：原版 {@code create:nozzle} 实测 319.46 pN（256 RPM、离地 1 格、4 扇），×2.5 ≈ 800。
     */
    public static final double THRUST_PER_RPM = 800.0D / 256.0D;

    /**
     * 符号约定。<b>已进游戏实测校定：false 为正确值</b>（用户实测确认，不要擅自改回 true）。
     * <p>
     * 当前生效的规则（{@code false}）：
     * <ul>
     * <li><b>正转速 ⇒ 机体沿 -FACING 受力</b>（即牛顿第三定律：气流从喷口喷出，机体被反推）；
     *     负转速 ⇒ 沿 +FACING；</li>
     * <li>喷口 {@code facing=down}（装在车底、喷口朝下）时：正转速 = 向上推（上升、刹车），
     *     负转速 = 向下吸（贴地 / 倒挂吸附）——与旧 {@code create:nozzle} 的"正推反吸"手感一致；</li>
     * <li>喷管随车身翻转，所以倒挂时同一个"负转速 = 吸向头顶赛道"的语义不变。</li>
     * </ul>
     * 改成 {@code true} 即整体反向。
     */
    public static final boolean REVERSE_SIGN = false;

    // ── 粒子特效（create:air 气流粒子，电扇同族，密度更高） ──

    /**
     * 满转速（256 RPM）时每 tick 生成的粒子数，按 {@code |转速| / 256} 线性缩放。
     * 原版电扇自身只有 {@code AllConfigs.client().fanParticleDensity}（默认 0.5/tick）的密度；
     * 嫌密/稀就改这一个数（同屏粒子数 ≈ 本值 × 寿命，见下面 {@link #PARTICLE_SPEED}）。
     */
    public static final int MAX_PARTICLES_PER_TICK = 10;

    /**
     * {@link AirParticleData} 的参数。源码事实
     * （{@code com.simibubi.create.foundation.particle.AirParticle}，27-56 与 62-86 行）：
     * <ul>
     * <li>粒子是<b>位置插值</b>而不是速度积分：从起点到起点+位移插值，按 {@code lifetime} 走完；</li>
     * <li><b>lifetime = (位移长度 / speed)</b>，整数 tick，<b>硬上限 60</b>
     *     ⇒ {@code speed} 就是"块/tick 的平均飞行速度"，是唯一的"快慢"旋钮；</li>
     * <li>{@code progress = (age/lifetime)^drag} ⇒ {@code drag < 1} 出膛快、尾段减速（像气流扩散），
     *     {@code drag = 1} 匀速，{@code drag > 1} 尾段加速（不像气流）；</li>
     * <li>位移长度决定"拖尾多长"：寿命 = 长度/speed，想既快又长尾就得同时加大长度
     *     （副作用：同屏粒子数 = 密度 × 寿命，会变多）。</li>
     * </ul>
     */
    public static final float PARTICLE_DRAG = 0.7F;

    /** 平均飞行速度，单位 <b>块/tick</b>（1 块/tick = 20 块/秒）。原版 create:backtank 用 0.05 */
    public static final float PARTICLE_SPEED = 1.0F;

    /** 位移长度（格）：越大概率尾巴越长；寿命 = 位移长度 / {@link #PARTICLE_SPEED} ⇒ 现在约 8~15 tick */
    public static final double PARTICLE_TRAVEL_MIN = 6.0D;
    public static final double PARTICLE_TRAVEL_MAX = 10.0D;

    /** 喷口被挡时：射线可走距离短于它就完全不生成粒子（对应电扇气流被截断到 0） */
    public static final double MIN_PARTICLE_TRAVEL = 0.2D;

    // ── 排气粒子（自研 ccpe:exhaust：扫掠碰撞 + 撞墙向四周扩散，见 client/particle/ExhaustParticle） ──

    /**
     * {@code true} = 用自研排气粒子（<b>现行</b>，扫掠碰撞 + 撞墙向四周扩散，见 §3.6）；
     * {@code false} = 回退到 {@code create:air} 插值粒子（只在起点→终点之间插值、永不碰撞）。
     * <p>
     * 历史：v5 第一版撞墙"没反应"，根因是 {@code ExhaustCollision} 忘了把
     * {@code getCollisionShape} 返回的方块局部 AABB 加上方块坐标（已修，见该文件注释）；
     * 修好后重新打开这一版。要退回旧观感把这里改 {@code false} 即可。
     * <p>
     * 旧的环绕常量（{@link #PARTICLE_DRAG} / {@link #PARTICLE_SPEED} / {@code PARTICLE_TRAVEL_*}）
     * 只在 {@code false} 时生效；新粒子的旋钮见下面 {@code EXHAUST_*}。
     */
    public static final boolean USE_EXHAUST_PARTICLE = true;

    /** 出生尺寸（块，即四边形边长），寿命内会膨胀到约 1.37 倍。2.0 太大（实测反馈），已降到 0.6 */
    public static final float EXHAUST_SCALE = 0.3F;

    /** 基准寿命（tick），实际探测时再加 0~9 的随机 */
    public static final int EXHAUST_LIFETIME = 16;

    /** 出生速度（块/tick，沿排气方向）；之后按水平 0.935 / 垂直 0.965 阻尼衰减 */
    public static final double EXHAUST_SPEED = 2.0D;

    /** 出生速度的随机倍率区间（让排气有快慢差，不像整齐的一束） */
    public static final double EXHAUST_SPEED_RANDOM_MIN = 0.85D;
    public static final double EXHAUST_SPEED_RANDOM_MAX = 1.15D;

    /** 横向散开（垂直于喷口轴的初速度上限，块/tick） */
    public static final double EXHAUST_SPREAD = 0.05D;

    /** 颜色：冷白偏蓝的气流（沿用 create:air 的观感） */
    public static final float EXHAUST_RED = 0.86F;
    public static final float EXHAUST_GREEN = 0.90F;
    public static final float EXHAUST_BLUE = 0.96F;

    /**
     * 其它物理体是否也挡粒子。
     * <ul>
     * <li>{@code true}（默认）：和普通方块一样挡住 —— 别的物理体横在喷口前面就没有气流；</li>
     * <li>{@code false}：只有自身物理体与主世界的方块挡（原版 {@code create:encased_fan} 的
     *     {@code getFlowLimit} 只查自己那一层的 {@code getBlockState}，看不到别的物理体，
     *     所以它的气流不会被别的物理体截断）。</li>
     * </ul>
     */
    public static final boolean PARTICLES_BLOCKED_BY_OTHER_BODIES = true;

    /** 纯视觉：排气方向与"机体受力反方向"不符时翻这里（不影响物理） */
    public static final boolean INVERT_PARTICLE_FLOW = false;

    /** Sable 力组注册表 key（{@code sable:force_groups}）。不直接引用 {@code ForceGroups}：其静态字段
     *  类型引用 veil 的 {@code RegistryObject}，veil 不在编译 classpath（同 {@code FlightDataRecorder}）。 */
    private static final ResourceKey<Registry<ForceGroup>> FORCE_GROUP_REGISTRY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("sable", "force_groups"));
    private static final ResourceLocation PROPULSION_ID =
            ResourceLocation.fromNamespaceAndPath("sable", "propulsion");

    /** 运行时解析出的 {@code sable:propulsion} 力组（解析失败保持 null，下次再试） */
    private @Nullable ForceGroup cachedPropulsion;

    public ThrustNozzleBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.thrust_nozzle_entity.get(), pos, state);
    }

    // ── Sable 物理：每 substep 沿喷口轴向施加恒定推力 ──

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        final float speed = this.getFanSpeed();
        if (speed == 0.0F) {
            return;
        }

        final QueuedForceGroup group = this.propulsionGroup(subLevel);
        if (group == null) {
            return;
        }

        final Direction facing = this.getBlockState().getValue(ThrustNozzleBlock.FACING);
        final Vec3i normal = facing.getNormal();
        // 冲量 = 推力 × substep 时长；rpm 带符号，所以 magnitude 也带符号
        final double magnitude = (REVERSE_SIGN ? -1.0D : 1.0D) * THRUST_PER_RPM * speed * timeStep;

        // 力向量与作用点都必须每次新建：QueuedForceGroup.applyImpulseAtPoint 会就地改写传入的
        // position（减去质心求力臂），且 recordPointForce 保留引用（FlightDataRecorder 遥测会读），
        // 复用临时向量会污染已记录的点力。
        final Vector3d impulse = new Vector3d(-normal.getX(), -normal.getY(), -normal.getZ()).mul(magnitude);
        group.applyAndRecordPointForce(JOMLConversion.atCenterOf(this.worldPosition), impulse);
    }

    // ── 客户端粒子：沿喷口轴向喷出 create:air 气流 ──

    /**
     * 客户端每 tick 的粒子生成（由 {@link ThrustNozzleBlock#getTicker} 的客户端 ticker 驱动）。
     * <p>
     * 用 {@code create:air}（{@link AirParticleData}）而不是电扇自己用的 {@code create:air_flow}：
     * <ul>
     * <li>{@code create:air_flow}（{@code AirFlowParticle}）的方向/速度/bounds 全部由风扇 {@code AirCurrent}
     *     决定；喷管把气流挡在风扇前方，{@code AirCurrent.getFlowLimit()} 会把 {@code maxDistance}
     *     从 24 截到 ~1.03 格 ⇒ 速度掉到 ~0.06 格/tick，粒子几乎糊在喷口上
     *     （{@code AirFlowParticle.java:61-78}、{@code AirCurrent.java:229-257}）。
     *     要救只能把喷管加进 {@code create:fan_transparent} 标签，但那会让电扇的实体推力/批量加工
     *     也穿过喷管生效——实测观感并不好，已回退。</li>
     * <li>{@code create:air} 是同族的半透明气流粒子，位移由"起点 + 目标偏移"自行插值，
     *     而且 Sable 专门为它写了 {@code AirParticleMixin}（按子次元姿态重投影），
     *     所以能在运动的物理体上正常显示。</li>
     * </ul>
     */
    public void tickClient() {
        final Level level = this.level;

        if (level == null || !level.isClientSide) {
            return;
        }

        final float speed = this.getFanSpeed();

        if (speed == 0.0F) {
            return;
        }

        // 密度随转速线性变化；不足一颗就不生成
        final float throttle = Math.min(Math.abs(speed) / 256.0F, 1.0F);
        final int count = (int) Math.round(MAX_PARTICLES_PER_TICK * throttle);

        if (count <= 0) {
            return;
        }

        final Direction facing = this.getBlockState().getValue(ThrustNozzleBlock.FACING);
        final Vec3i normal = facing.getNormal();
        final Vec3 axis = new Vec3(normal.getX(), normal.getY(), normal.getZ());

        // 排气方向 = 机体受力方向的反向：
        // 受力 = -FACING · (REVERSE_SIGN ? -1 : 1) · 转速 ⇒ 排气 = +FACING · 同一个符号
        double flowSign = (REVERSE_SIGN ? -1.0D : 1.0D) * (speed > 0.0F ? 1.0D : -1.0D);

        if (INVERT_PARTICLE_FLOW) {
            flowSign = -flowSign;
        }

        // 喷口截面上的两个基向量：在口面上随机撒点
        final Vec3 seedVec = Math.abs(axis.y) > 0.5D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        final Vec3 v = axis.cross(seedVec).normalize();
        final Vec3 u = v.cross(axis).normalize();

        final Vec3 center = Vec3.atCenterOf(this.worldPosition);
        final Vec3 flowDir = axis.scale(flowSign);
        final Vec3 mouth = center.add(axis.scale(0.55D));

        // 本方块所在物理体：粒子坐标必须用它的姿态从 plot 局部系投到世界系（见下方 projectToWorld）
        final SubLevel own = Sable.HELPER.getContaining(this);

        // 与 create:encased_fan 一致：气流遇方块即止（自身物理体 + 其它物理体都算）。
        // 电扇那边是 AirCurrent.getFlowLimit() 把 maxDistance 截到第一个挡路方块的碰撞形状深度；
        // 这里等价地沿排气方向打一条碰撞射线，把粒子位移截断在命中点。
        final double clear = this.clearDistance(level, own, mouth, flowDir, PARTICLE_TRAVEL_MAX + 0.1D);

        if (clear < MIN_PARTICLE_TRAVEL) {
            return; // 喷口被挡死 → 不生成粒子
        }

        final RandomSource random = level.random;

        for (int i = 0; i < count; i++) {
            final double radius = 0.28D * Math.sqrt(random.nextDouble());
            final double theta = random.nextDouble() * Math.PI * 2.0D;
            final Vec3 spread = u.scale(Math.cos(theta) * radius).add(v.scale(Math.sin(theta) * radius));

            // AirParticleData 的 dx/dy/dz 不是速度而是"目标相对起点的位移"；
            // 寿命 = 位移长度 / PARTICLE_SPEED（上限 60 tick）
            final double wanted = PARTICLE_TRAVEL_MIN
                    + random.nextDouble() * (PARTICLE_TRAVEL_MAX - PARTICLE_TRAVEL_MIN);
            // 被挡就只喷到挡板前（短于 MIN_PARTICLE_TRAVEL 的干脆不要）
            final double travel = Math.min(wanted, clear);

            if (travel < MIN_PARTICLE_TRAVEL) {
                continue;
            }

            // 起点：喷口外一点（0.55 格）+ 口面随机偏移
            final Vec3 start = mouth.add(spread);
            final Vec3 flow = flowDir.scale(travel);

            // 投到世界系。必须投：AirParticleMixin 的 tick 每 tick 把"起点/终点"插值出的点
            // 再用当前姿态投出一次（Sable 的 plot → world 修正）。若这里传 plot 局部系坐标，
            // 整条轨迹就被钉在机体坐标系里 ⇒ 车身一动粒子跟着动，像喷管的子物体。
            final Vec3 startWorld = this.projectToWorld(level, own, start);
            final Vec3 flowWorld = this.projectToWorld(level, own, start.add(flow)).subtract(startWorld);

            if (USE_EXHAUST_PARTICLE) {
                this.spawnExhaust(level, random, own, startWorld, flowWorld, throttle);
            } else {
                level.addParticle(new AirParticleData(PARTICLE_DRAG, PARTICLE_SPEED),
                        startWorld.x, startWorld.y, startWorld.z,
                        flowWorld.x, flowWorld.y, flowWorld.z);
            }
        }
    }

    /**
     * 生成一颗排气粒子（{@code ccpe:exhaust}）。
     * <p>
     * 速度模型：轴向 = {@link #EXHAUST_SPEED} × 随机倍率 × 油门（转速 / 256），横向再加一点随机散开，
     * 之后由粒子自己每 tick 积分、阻尼衰减、并在撞到方块时"沿表面铺开 + 随机溅射"。
     * 粒子本身<b>不做</b>“起点→终点插值”，所以喷出去之后与喷管彻底解耦；
     * 也<b>不受重力</b>（{@code ExhaustParticle} 里 {@code gravity = 0}、没有任何向下加速度，
     * 只有微小的向上浮力 + 撞墙反弹，见该类常量）。
     *
     * @param startWorld 世界系的出生点（已由 {@link #projectToWorld} 投出）
     * @param flowWorld  世界系的排气位移方向（长度无关，内部会归一化）
     * @param throttle   油门 0~1（转速 / 256），影响初速度
     */
    private void spawnExhaust(final Level level, final RandomSource random, final @Nullable SubLevel own,
                              final Vec3 startWorld, final Vec3 flowWorld, final float throttle) {
        if (flowWorld.lengthSqr() < 1.0E-8D) {
            return;
        }

        final Vec3 direction = flowWorld.normalize();

        // 垂直于轴的平面上的两个基向量，用来撒横向散开
        final Vec3 seedVec = Math.abs(direction.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 sideX = direction.cross(seedVec).normalize();
        final Vec3 sideY = sideX.cross(direction).normalize();
        final double angle = random.nextDouble() * Math.PI * 2.0D;
        final double lateralAmount = EXHAUST_SPREAD * (0.4D + random.nextDouble());
        final Vec3 lateral = sideX.scale(Math.cos(angle)).add(sideY.scale(Math.sin(angle))).scale(lateralAmount);

        final double speedRandom = EXHAUST_SPEED_RANDOM_MIN
                + random.nextDouble() * (EXHAUST_SPEED_RANDOM_MAX - EXHAUST_SPEED_RANDOM_MIN);
        final double speed = EXHAUST_SPEED * speedRandom * (0.65D + 0.35D * throttle);
        final Vec3 velocity = direction.scale(speed).add(lateral);

        final float scale = EXHAUST_SCALE * (0.70F + random.nextFloat() * 0.60F);
        final int lifetime = EXHAUST_LIFETIME + random.nextInt(10);

        // 最后一个 0 = 分裂代数：喷管直喷的是第 0 代，撞地分裂出来的子粒子是第 1 代（见 ExhaustParticle）
        level.addParticle(new ExhaustParticleData(scale, lifetime, EXHAUST_RED, EXHAUST_GREEN, EXHAUST_BLUE,
                        own == null ? ExhaustParticleData.NO_OWNER : own.getUniqueId(), 0),
                startWorld.x, startWorld.y, startWorld.z,
                velocity.x, velocity.y, velocity.z);
    }

    /**
     * 把 plot 局部系的一个点投到世界系（Sable 的 plot → world 修正），与
     * {@code AirParticleMixin} 内部用的是同一个入口（{@link Sable#HELPER}.projectOutOfSubLevel），
     * 因此姿态/插值口径与粒子 tick 完全一致。
     * <p>
     * 为什么要投：{@code create:air} 粒子的起点与位移是<b>一次性写死</b>的，
     * 而 {@code AirParticleMixin#tick} 每 tick 会把"起点→终点"插值出的点再用<b>当前</b>姿态投出一次。
     * 若这里传 plot 局部系坐标，插值出来的点会随车身姿态一起变 ⇒ 轨迹钉在机体上，
     * 视觉上就像粒子是喷管的子物体。先投到世界系后，绝大多数情况下该点已不在 plot 区块里，
     * {@code projectOutOfSubLevel} 变成恒等变换，粒子便在世界上独立飞行。
     * <p>
     * 没有所在物理体（{@code own == null}）时原样返回。
     */
    private Vec3 projectToWorld(final Level level, final @Nullable SubLevel own, final Vec3 local) {
        return own == null ? local : Sable.HELPER.projectOutOfSubLevel(level, local);
    }

    /**
     * 沿排气方向测"最远能走多远"——遇到方块（自身物理体 / 其它物理体 / 主世界）就停在它的碰撞形状上。
     * <p>
     * 语义对齐 {@code create:encased_fan}：{@code AirCurrent.getFlowLimit()}
     * （{@code AirCurrent.java:229-257}）用 {@code state.getCollisionShape()} 找出第一处挡路
     * 方块并把 {@code maxDistance} 截到那里，所以电扇的气流/粒子到障碍物为止。这里用
     * {@link ClipContext.Block#COLLIDER} + {@link ClipContext.Fluid#NONE} 复现同一语义
     * （只挡实体碰撞形状，不挡水/空气）。
     * <p>
     * <b>射线必须在世界系里打</b>（旧版在 plot 局部系里打，主世界的方块挡不住）：
     * Sable 的 {@code BlockGetterMixin#clip}（{@code mixin/clip_overwrite/BlockGetterMixin.java:48-134}）
     * 一旦发现 {@code from} 落在某个 plot 里，就会把 {@code from} <b>投到世界系</b>，再对主世界做
     * 一次 {@code originalClip}（命中返回的 {@link BlockHitResult#getLocation()} 是世界系），
     * 然后才逐个物理体把射线反投回各自的局部系（这一支返回的 location 是<b>那个物理体</b>的局部系）。
     * 也就是说：在 plot 系里发射线时，主世界命中的 location 是世界系，和 plot 系的起点相减
     * 得到几十上百格的距离 ⇒ 遮挡被静默跳过（正是"物理体挡得住、静态方块挡不住"的原因）。
     * 而且 {@code to} 只要跑出 plot 区块就不会被投出，射线直接变形。
     * <p>
     * 所以这里先把起点与方向都投到世界系再发射线，命中点也统一换算到世界系量距离
     * （刚体变换保长度 ⇒ 与 plot 系的长度等价，粒子的 travel 参数不用改）。
     */
    private double clearDistance(final Level level, final @Nullable SubLevel own,
                                 final Vec3 start, final Vec3 flowDir, final double wanted) {
        final Vec3 startWorld = this.projectToWorld(level, own, start);
        final Vec3 worldFlow = this.projectToWorld(level, own, start.add(flowDir)).subtract(startWorld);

        if (worldFlow.lengthSqr() < 1.0E-8D) {
            return 0.0D;
        }

        final Vec3 endWorld = startWorld.add(worldFlow.normalize().scale(wanted));
        final ClipContext clip = new ClipContext(startWorld, endWorld,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        final BlockHitResult hit = level.clip(clip);

        if (hit.getType() == HitResult.Type.MISS) {
            return wanted;
        }

        // 命中点坐标系判别：
        // - 主世界命中 ⇒ clip 补丁返回的就是世界系坐标，查 plot 一般查不到（null）；
        // - 物理体命中 ⇒ 返回的是那个物理体的 plot 局部系，反查正好能查到它。
        final SubLevel hitBody = Sable.HELPER.getContaining(level, hit.getLocation());

        if (hitBody != null && !PARTICLES_BLOCKED_BY_OTHER_BODIES && hitBody != own) {
            return wanted; // 忽略"别的物理体"造成的遮挡
        }

        final Vec3 worldHit = hitBody == null
                ? hit.getLocation()
                : this.projectToWorld(level, hitBody, hit.getLocation());

        // 留 0.03 格余量，避免粒子正好卡在方块表面
        final double distance = worldHit.distanceTo(startWorld) - 0.03D;

        if (distance >= wanted) {
            return wanted; // 远于射线本身 ⇒ 坐标系判断不可信，当作没挡住
        }

        return Math.max(0.0D, distance);
    }

    /** 背后的气流源（电扇）；缺失 / 朝向不匹配 → null */
    private @Nullable IAirCurrentSource fanSource() {
        final Level level = this.level;

        if (level == null) {
            return null;
        }

        final Direction facing = this.getBlockState().getValue(ThrustNozzleBlock.FACING);
        final BlockEntity behind = level.getBlockEntity(this.worldPosition.relative(facing.getOpposite()));

        if (!(behind instanceof final IAirCurrentSource source) || source.getAirflowOriginSide() != facing) {
            return null;
        }

        return source;
    }

    /** 背后电扇的转速（RPM，带符号）；没有合法气流源 / 未转 → 0 */
    private float getFanSpeed() {
        final IAirCurrentSource source = this.fanSource();
        return source == null ? 0.0F : source.getSpeed();
    }

    /** 取 {@code sable:propulsion} 力组在本物理体上的队列 */
    private @Nullable QueuedForceGroup propulsionGroup(final ServerSubLevel subLevel) {
        if (this.cachedPropulsion == null) {
            try {
                final Registry<ForceGroup> registry = subLevel.getLevel().registryAccess()
                        .registry(FORCE_GROUP_REGISTRY).orElse(null);

                if (registry == null) {
                    return null;
                }

                this.cachedPropulsion = registry.get(PROPULSION_ID);
            } catch (final RuntimeException ignored) {
                return null;
            }

            if (this.cachedPropulsion == null) {
                return null;
            }
        }

        return subLevel.getOrCreateQueuedForceGroup(this.cachedPropulsion);
    }
}
