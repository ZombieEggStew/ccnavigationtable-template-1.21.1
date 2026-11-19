package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.foundation.particle.AirParticleData;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.particle.ContrailBudget;
import com.zzy205.myfirstmod.particle.ContrailParticleData;
import com.zzy205.myfirstmod.particle.ExhaustParticleData;
import com.zzy205.myfirstmod.particle.IntakeParticleData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.Locale;

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
 * <li><b>不做射线</b>：不需要命中任何表面，任意<b>距离</b>出力相同（原版喷嘴 6.4 格外归零）；
 *     高度仍然有关——通过下面那一项的气压；</li>
 * <li><b>乘气压项</b>（{@link #THRUST_SCALES_WITH_AIR_PRESSURE}，与 Sable 原版喷嘴一致）：
 *     出力 × {@code DimensionPhysicsData.getAirPressure(level, 喷管世界坐标)}，
 *     即海平面 1.0、越高越稀薄、地底最高 1.5、接近建筑限高平滑降到 0；</li>
 * <li><b>转速线性</b>：不做 Sable 的 {@code >128 RPM 软缩放}，保持 memo §6.5 已验证的"推力∝转速"手感；</li>
 * <li><b>力的大小</b>：{@link #THRUST_PER_RPM} × 转速 × 气压，而 {@code THRUST_PER_RPM = 1} ⇒
 *     <b>1 RPM = 1 pN</b>，气压 1.0 时 <b>256 RPM = 256 pN/喷管</b>（用户定稿，便于记）。</li>
 * </ul>
 * 力以点力的形式打进 Sable 的 {@code sable:propulsion} 力组：由 Sable 在物理步末统一施加
 * （{@code ServerSubLevel#applyQueuedForces}），并会被 {@code compat/cc/FlightDataRecorder} 的
 * 力组遥测列记录，方便进游戏标定。点力自带力臂，多个喷管的位置差自动产生力矩。
 */
public class ThrustNozzleBlockEntity extends BlockEntity
        implements BlockEntitySubLevelActor, IHaveGoggleInformation {

    /**
     * 每 RPM 的轴向推力（pN/RPM）。<b>取 1.0 ⇒ 1 RPM = 1 pN</b>，
     * 于是气压 1.0 时 <b>256 RPM（满速）= 256 pN/喷管</b>，出力数值直接等于转速、不用换算。
     * <p>
     * 用户定稿（第三次标定，为了公式简单可记）：初版 800 pN 实测"高达 750 多"、手感过猛；
     * 第二次试 200 pN 又偏软；最终取 1 : 1。
     * <p>
     * 口径：这是<b>气压 = 1.0（海平面附近）</b>的值。实际出力还要乘气压，
     * 所以 memo §2.2 的出力表在高空会按比例打折（见 {@link #THRUST_SCALES_WITH_AIR_PRESSURE}）。
     */
    public static final double THRUST_PER_RPM = 1.0D;

    /**
     * 符号约定。<b>已进游戏实测校定：false 为正确值</b>（用户实测确认，不要擅自改回 true）。
     * <p>
     * 当前生效的规则（{@code false}）：
     * <ul>
     * <li><b>正转速 ⇒ 机体沿 -FACING 受力</b>（即牛顿第三定律：气流从喷口喷出，机体被反推）；
     *     负转速 ⇒ 沿 +FACING；</li>
     * <li><b>⚠️ 2026-10 修正</b>：上面那条"正转速 = 沿 -FACING"只在水平朝向上成立。补上
     *     {@link #axisStep} 之后，竖直朝向的统一结论是 <b>负转速 = 向上推、正转速 = 向下推</b>
     *     （与朝上装还是朝下装无关）。旧注释里"facing=down 时正转速 = 上升"是漏了 step 的产物，
     *     已作废；旧的 {@code startup.lua} 里 {@code bl/br = +256} 才向上的接法要改成四台统一取负；</li>
     * <li>喷管随车身翻转，所以倒挂时同一个"负转速 = 吸向头顶赛道"的语义不变。</li>
     * </ul>
     * 改成 {@code true} 即整体反向。
     */
    public static final boolean REVERSE_SIGN = false;

    // ── 气压 ──

    /**
     * 出力是否随所处气压缩放（<b>现行 true</b>）：{@code 冲量 = 推力 · 转速 · 气压 · timeStep}。
     * <p>
     * 取数与 Sable 原版喷嘴完全一致（{@code NozzleHoveringHelper#getFanMagnitudeCalculation}）：
     * {@code DimensionPhysicsData.getAirPressure(level, 喷管方块中心的世界坐标)}。
     * 该值的语义（{@code DimensionPhysics#createDefault}，无 datapack 覆盖时）：
     * <ul>
     * <li>海平面 = <b>1.0</b>（{@link #THRUST_PER_RPM} 的标定基准就在这里）；</li>
     * <li>向上按 {@code e^(-0.004·Δy)} 指数稀薄：y+100 ⇒ ×0.67，y+200 ⇒ ×0.45；</li>
     * <li>地底最高夹到 <b>1.5</b>（约 y ≤ 海平面−101 起就饱和）；</li>
     * <li>接近建筑限高最后 40 格平滑降到 <b>0</b>（主世界约 y 280 → 320）。</li>
     * </ul>
     * 因此本喷管现在有"实用升限"：飞太高会明显掉推力（也正是原版喷嘴的手感）。
     * 置 {@code false} 即退回"出力与海拔无关"的旧行为。
     */
    public static final boolean THRUST_SCALES_WITH_AIR_PRESSURE = true;

    /**
     * 气压项的指数，只在 {@link #THRUST_SCALES_WITH_AIR_PRESSURE} 为 true 时生效：
     * {@code 气压^本值}。1.0 = 纯线性乘（对齐原版喷嘴）；2.0 = 气压低时掉得更狠（高空更难受利）；
     * 0.5 = 高层依然有力。没有特殊需求就保持 1.0。
     */
    public static final double AIR_PRESSURE_EXPONENT = 1.0D;

    // ── 粒子特效（create:air 气流粒子，电扇同族，密度更高） ──

    /**
     * 满转速（256 RPM）时每 tick 生成的粒子数，按 {@code |转速| / 256} 线性缩放。
     * 原版电扇自身只有 {@code AllConfigs.client().fanParticleDensity}（默认 0.5/tick）的密度；
     * 嫌密/稀就改这一个数（同屏粒子数 ≈ 本值 × 寿命，见下面 {@link #PARTICLE_SPEED}）。
     */
    private static int exhaustParticlesPerTick() {
        return Config.EXHAUST_PARTICLES_PER_TICK.get();
    }

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
    private static float exhaustScale() {
        return Config.EXHAUST_SCALE.get().floatValue();
    }

    /** 基准寿命（tick），实际探测时再加 0~9 的随机 */
    private static int exhaustLifetime() {
        return Config.EXHAUST_LIFETIME.get();
    }

    /** 出生速度（块/tick，沿排气方向）；之后按水平 0.935 / 垂直 0.965 阻尼衰减 */
    private static double exhaustSpeed() {
        return Config.EXHAUST_SPEED.get();
    }

    /** 出生速度的随机倍率区间（让排气有快慢差，不像整齐的一束） */
    public static final double EXHAUST_SPEED_RANDOM_MIN = 0.85D;
    public static final double EXHAUST_SPEED_RANDOM_MAX = 1.15D;

    /**
     * <b>临时调试开关</b>（定位"喷口与平面成角度时粒子穿墙/漏到平面下方"）：每秒打一行喷口世界坐标、
     * 世界系排气方向、遮挡射线长度 {@code clear} 与本次颗粒数。定位完改回 {@code false}。
     */
    public static final boolean DEBUG_EXHAUST_LOG = false;

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

    /**
     * 粒子生成的额外距离上限（格，0 = 不限）。
     * <p>
     * <b>为什么需要它</b>：原版 {@code LevelRenderer#addParticleInternal} 里有一条硬判定 ——
     * 出生点离<b>相机</b>超过 1024（= 32 格，{@code distanceToSqr > 1024.0}）就<b>直接不创建粒子</b>
     * （{@code neoforge-21.1.235-sources.jar} / {@code LevelRenderer.java:2714-2717}），
     * 只有 {@code force = true} 能绕过。
     * <p>
     * 这就是"离喷管约 1 区块就不再出粒子"的根因：不是我们没生成，是<b>原版把粒子丢了</b>。
     * 证据：① Sable 的 {@code mixin/particle/LevelRendererMixin} 正是 {@code @Redirect} 了
     * {@code addParticleInternal} 里那个 {@code Vec3.distanceToSqr}，把它换成考虑子次元的版本；
     * ② Create 自己的电扇也这样 —— {@code AirCurrent.java:69} 与 {@code AirFlowParticle.java:149}
     * 用的都是裸 {@code addParticle}，所以 {@code create:encased_fan} 有一模一样的现象。
     * <p>
     * 修法：改用<b>9 参数</b>的 {@code addAlwaysVisibleParticle(data, ignoreRange = true, ...)}
     * 绕过原版 32 格限制，再由本常量给一个更远、但仍有上限的可见距离
     * （避免在看不见的远处白喷：每颗粒子每 tick 都要做一次扫掠碰撞）。
     * <p>
     * <b>踩过的坑</b>：7 参数的 {@code addAlwaysVisibleParticle(data, x, y, z, vx, vy, vz)}
     * <b>不绕过</b>限制 —— {@code ClientLevel.java:733-737} 里它传的是
     * {@code addParticle(data, false, true, ...)}，第二个布尔是 {@code decreased}（粒子设置调低时减量），
     * 不是 {@code force}。只有 {@code ClientLevel.java:739-754} 的 9 参数版传
     * {@code data.getType().getOverrideLimiter() || ignoreRange}。
     * <p>
     * 等价路线：把 {@code ParticleType} 注册成 {@code overrideLimiter = true}
     * （{@code new ParticleType<>(true, ...)}），那样连裸 {@code addParticle} 都不会被丢；
     * 现在选在调用点显式传 {@code ignoreRange}，语义更局部。
     */
    private static double exhaustMaxDistance() {
        return Config.EXHAUST_MAX_DISTANCE.get();
    }

    // ── 进气粒子 ccpe:intake（负转速 = 吸力时，空气被吸进口面） ──

    /**
     * 进气粒子总开关（配置 {@code intakeEnabled}）。
     * <p>
     * <b>为什么要单独一条链路</b>：负转速时"排气方向"按 {@code flowSign} 翻到 {@code -FACING}
     * （朝喷管背后、朝电扇那一侧），而排气粒子是从 {@code mouth}（喷口外 0.55 格）沿该方向打遮挡射线后
     * 才生成的 —— 那条射线会立刻撞到<b>喷管自己的碰撞箱</b>（方块边界最远到 15/16，起点离它只有约
     * 0.11 格），{@code clear ≈ 0.08 < MIN_PARTICLE_TRAVEL(0.2)} ⇒ 旧版负转速下<b>一颗粒子都不生成</b>。
     * 所以吸力不是"参数没调好"，而是缺一条独立链路。
     * <p>
     * 现在的语义（用户定稿）：吸力 = 空气被<b>吸进口面</b>，粒子在喷口<b>前方</b>
     * （{@code +FACING} 那一侧）几格远处出生，加速收拢到口面中心、同时缩小淡出（{@code IntakeParticle}）。
     */
    private static boolean useIntake() {
        return Config.INTAKE_ENABLED.get();
    }

    /** 满转速时每 tick 的进气颗数（按 {@code |转速| / 256} 线性缩放），配置 {@code intakeParticlesPerTick} */
    private static int intakeParticlesPerTick() {
        return Config.INTAKE_PARTICLES_PER_TICK.get();
    }

    /**
     * 进气<b>漏斗长度</b>（格）：<b>满转速 256 RPM 时</b>的取值（配置 {@code intakeFunnelLength}，默认 8.0）。
     * <p>
     * 实际长度按<b>转速线性缩放</b>（{@code × |转速|/256}，见 {@code tickIntake}）：
     * 转速越高，吸力越强、能"够到"的空气越远，漏斗就越长；转速降一半漏斗长度也降一半。
     * <p>
     * 一个顺带的好性质：寿命同样是「距离 ÷ 速度」，所以「每单位长度的粒子密度」与转速无关
     * （粒子数 = 密度 × 寿命，两者都随转速线性变）——长漏斗不会因为变长而变稀。
     */
    private static double intakeFunnelLength() {
        return Config.INTAKE_FUNNEL_LENGTH.get();
    }

    /** 进气速度（块/tick），配置 {@code intakeSpeed}：寿命 = 出生距离 ÷ 本值 */
    private static double intakeSpeed() {
        return Config.INTAKE_SPEED.get();
    }

    /** 进气粒子出生尺寸（格），配置 {@code intakeScale} */
    private static float intakeScale() {
        return Config.INTAKE_SCALE.get().floatValue();
    }

    /** 进气粒子发射距离上限（格，0 = 不限），配置 {@code intakeMaxDistance} */
    private static double intakeMaxDistance() {
        return Config.INTAKE_MAX_DISTANCE.get();
    }

    /** 进气锥半角的正切：横截面半径 = 出生距离 × 本值（越远越宽）。0 = 一根细线，调大 = 张开成漏斗 */
    public static final double INTAKE_CONE_RATIO = 0.45D;

    /**
     * 进气漏斗横截面半径上限（格）：别让远处的粒子撒得比车还宽。
     * <p>
     * 漏斗长度已经随转速放大到 8 格（满转速），这个上限原来 1.6 会让长漏斗变成"细吸管"
     * （8 格处半角只有 11°），所以抬到 2.5（8 格处半角约 17°）。嫌太胖就调小。
     */
    public static final double INTAKE_MAX_CONE_RADIUS = 2.5D;

    /** 最近出生距离（格）：太贴近口面会像"贴脸冒出来"，留一点空间 */
    public static final double INTAKE_MIN_DISTANCE = 0.7D;

    /**
     * 漏斗长度的下限（格）：漏斗长度按 {@code × 转速/256} 缩放，转速极低时会缩到几乎为零
     * （8 RPM ⇒ 0.25 格），粒子就贴在口面上冒出来。给个 1.0 的下限，让"低速但仍在吸"也有个形态。
     */
    public static final double INTAKE_MIN_REACH = 1.0D;

    /** 寿命夹取（tick）：太短看不清、太长会变成"慢悠悠漂过来" */
    public static final int INTAKE_MIN_LIFETIME = 6;
    public static final int INTAKE_MAX_LIFETIME = 40;

    /**
     * 颜色：与排气同一族的冷白（现 0.86/0.90/0.96）。
     * 方向靠<b>运动</b>表达（向内收 vs 向外喷），颜色刻意不区分，免得看着像两种不同的气体。
     */
    public static final float INTAKE_RED = 0.86F;
    public static final float INTAKE_GREEN = 0.90F;
    public static final float INTAKE_BLUE = 0.96F;

    // ── 高空尾迹（contrail）：只在足够冷的空气里出现，由"移动距离"而不是时间触发 ──

    /**
     * 尾迹总开关（<b>现行 true</b>）。{@code false} = 完全不出尾迹，喷口只有排气。
     * <p>
     * 尾迹的现实依据（memo §8.2）：飞机尾迹是<b>排气水汽在高空冷空气里结成的冰晶云</b>，不是烟。
     * 三个独立物理量各管一件事 —— 温度阈值决定<b>有没有</b>、环境过饱和决定<b>多长命</b>、
     * 光学厚度决定<b>多浓多大</b>。本项目不用湿度，全部退化成<b>纯高度</b>的适宜度曲线
     * （{@link #TRAIL_Y_RISE_START} 起的三段线性，2026-11 起不再经过温度模型），并遵守两条设计规则：
     * <ul>
     * <li>气压<b>不参与</b>判定（气压只是高度的代理变量，且现有曲线是 1.5→0 单调，不能当尾迹强度）；</li>
     * <li>尺寸与透明度<b>必须成对</b>算（{@code alpha ∝ 1/宽度}），否则"散开"和"变淡"会互相打架。</li>
     * </ul>
     */
    private static boolean useContrail() {
        return Config.CONTRAIL_ENABLED.get();
    }

    /**
     * 只在主世界出尾迹（<b>现行 true</b>，用户定稿）。
     * <p>
     * 理由：现在适宜度是<b>纯高度</b>曲线（见 {@link #TRAIL_Y_RISE_START}），末地地形基本在 Y&lt;100
     * ⇒ 本来就不会出尾迹；这个开关仍然保留，是为了将来末地出现高 Y 结构时也不会蹭出尾迹。
     * 地狱更直接：地形在 Y≈32~120，绝大部分低于 100 ⇒ 天然没有。
     * 置 {@code false} 则末地高 Y 处也会有尾迹。
     */
    public static final boolean CONTRAIL_OVERWORLD_ONLY = true;

    /**
     * 尾迹<b>适宜度曲线</b>（2026-11 用户定稿：<b>纯高度、分段线性</b>，不再走温度模型）。
     * <pre>
     * Y ≤ 100     ⇒ s = 0                    完全没有尾迹
     * 100 → 180   ⇒ s 由 0 线性升到 1       密度（单位航程的粒子数）= s ÷ 间距 ⇒ <b>严格线性增大</b>
     * 180 → 240   ⇒ s = 1                   峰带（60 格宽，主世界云层 200 正落在中间）
     * 240 → 320   ⇒ s 由 1 线性降到 0       密度线性减少，世界顶正好归零
     * Y ≥ 320     ⇒ s = 0
     * </pre>
     * 原文（温度模型，已被本轮取代）：s 由"引擎高度→温度"曲线 + 两个 smoothstep 组成，
     * 峰值带约 Y183~224、Y160 有硬门、世界顶留 0.15 残余。用户要的是可读的线性高度区间，
     * 所以改成上面这张表 —— <b>不要再把温度塞回来</b>（两个模型混用会很难解释"密度为什么不是线性"）。
     * <p>
     * 适宜度 s 同时决定两件事（口径一致）：
     * <ul>
     * <li><b>发射密度</b>：间距 = {@link #trailSpacing()} ÷ s ⇒ 每格粒子数与 s 成正比；</li>
     * <li><b>寿命</b>：{@link #trailLifeMin()} → {@link #trailLifeMax()} 按 s 线性插值 ⇒ 峰带最耐久。</li>
     * </ul>
     * 现实对应：真实判据是 Schmidt-Appleman（冷 + 湿）。冷随高度单调，湿是<b>先增后减</b>
     * （对流层顶最湿、平流层极干）⇒ 所以"越高越浓"是错的，尾迹本来就有一个最浓的高度带。
     * <p>
     * 注意<b>不要</b>用 {@code SensorSystemAPI.getPressureForEngine} 当强度：气压曲线是 1.5 → 0 单调，
     * 越高越小，和"有没有尾迹"不是一回事。
     */
    public static final double TRAIL_Y_RISE_START = 100.0D;
    /** 密度拉满的高度（Y=180，用户定稿）：线性上升段的终点 */
    public static final double TRAIL_Y_FULL = 180.0D;
    /** 峰带结束、开始变稀的高度（Y=240，用户定稿） */
    public static final double TRAIL_Y_FADE_START = 240.0D;
    /** 密度归零的高度（Y=320 = 世界顶，用户定稿） */
    public static final double TRAIL_Y_FADE_END = 320.0D;

    /**
     * 尾迹的<b>发射间距</b>（格）：机体每飞这么远吐一颗（在适宜度 s=1 时）。
     * <p>
     * <b>为什么按移动距离而不是按 tick 发射</b>：现实里尾迹浓度由"单位航程排了多少水汽"决定，
     * 悬停不动的飞机不会在原地堆出一团云。按距离发射顺带解决性能问题（静止时零发射），
     * 也是用户定稿（悬停完全不出尾迹）。
     * <p>
     * 适宜度 s 低时间距被放大：{@code 实际间距 = trailSpacing() / s}（纯高度曲线，见
     * {@link #TRAIL_Y_RISE_START}）⇒ <b>密度与高度严格线性</b>；
     * 实际发射还会再按<b>有效油门</b>放大间距（见 {@code tickContrail}），两者是独立的两项。
     */
    private static double trailSpacing() {
        return Config.CONTRAIL_SPACING.get();
    }

    /**
     * 适宜度 s 决定尾迹寿命（tick）：s=0 用 MIN、s=1 用 MAX（<b>适宜度越高越耐久</b>，对齐"过饱和"那条现实规律；
     * 2026-11 起 s 是纯高度曲线 ⇒ 峰带 180~240 最耐久，往两端线性缩短）。
     * <p>
     * 迭代：60/300（3~15 s）→ 180/600（9~30 s）→ 用户自己试到 12000/18000（10~15 分钟）
     * → 现行 <b>360/1200（18~60 s，"1 分钟左右"）</b>。保留约 1:3.3 的 MIN/MAX 比例，
     * 所以"越冷越耐久"这条曲线形状不变，只是整体缩到 1 分钟量级。
     * <p>
     * 注意这是<b>性能主旋钮</b>：同屏粒子数 ≈ 发射密度 × 寿命。1200 tick、4 管满载、20 m/s 时约 4000 颗，
     * 已经在 {@link #contrailMaxLive()}=6000 的范围内，但再往上调寿命就会撞上限。
     */
    private static int trailLifeMin() {
        return Config.CONTRAIL_LIFE_MIN.get();
    }

    private static int trailLifeMax() {
        return Config.CONTRAIL_LIFE_MAX.get();
    }

    /**
     * 出生宽度（格，quad 的<b>半</b>边长；可见宽度约为 2 倍），之后按 sqrt(t) 扩散变宽。
     * <p>
     * 用户实测反馈「粒子需要放大一些」⇒ 0.6 → <b>1.0</b>（出生可见宽约 2 格、寿命末尾约 4 格）。
     */
    private static float trailWidth() {
        return Config.CONTRAIL_WIDTH.get().floatValue();
    }

    // 扩散时间常数 TRAIL_WIDTH_TAU 与出生不透明度 TRAIL_ALPHA0 现在是配置项
    // （contrailWidthTau / contrailAlpha），只在 client.particle.ContrailParticle 里用到，
    // 那里在粒子构造时读一次缓存进实例字段（避免每颗每 tick 都查配置）。



    /**
     * 单颗粒子的<b>总飘荡距离</b>基准（格）：粒子从出生到死亡，一共会离开出生点这么远。
     * <p>
     * v5 模型（用户定稿）：粒子的速度不再直接给，而是<b>反算</b>出来 ——
     * <pre>
     * 速度(块/tick) = 总飘荡距离 / 寿命(tick)
     * </pre>
     * 例如"寿命 30 s（600 tick）、飘荡距离 2 格" ⇒ 速度 = 2/600 块/tick。
     * 这样"这颗粒子会飘多远"是一个直接可读、可调的量，而且方向随机 ⇒ 同龄粒子向四面八方铺开，
     * 尾迹自然越铺越宽（这就是"随时间越来越散"）。
     * <p>
     * 老模型（随机游走 + 抖动 + 边界钳位）已经删掉：它的位移量只能靠调参间接控制，
     * 而且寿命一长就会失控（用户实测"分布很散"）。
     */
    private static double trailDriftDistance() {
        return Config.CONTRAIL_DRIFT_DISTANCE.get();
    }

    /** 每颗粒子飘荡距离的随机倍率区间（让尾迹有厚有薄，不是一个厚度均匀的管） */
    public static final double TRAIL_DRIFT_DISTANCE_MIN = 0.55D;
    public static final double TRAIL_DRIFT_DISTANCE_MAX = 1.60D;

    /**
     * 飘荡距离随高度变化的<b>参考高度</b>（云层高度 Y，现 200，用户世界的云层就在这）：
     * 云层及以上系数 = 1.0。
     */
    public static final double TRAIL_TURB_CLOUD_Y = 200.0D;

    /**
     * 云层<b>以下</b>（Y 200 → {@link #TRAIL_Y_RISE_START}=100）飘荡距离的放大倍数上限（现 2.5）。
     * <p>
     * 物理：对流层的湍流/垂直对流随高度下降而增强（云就是这样长起来的），所以越低空，
     * 尾迹被搅得越散。线性映射：
     * <pre>
     * 系数(Y) = 1 + (本值 − 1) · (CLOUD_Y − Y) / (CLOUD_Y − TRAIL_Y_RISE_START)
     * </pre>
     * 于是 Y≈100 处粒子能飘 2×2.5 = 5 格，Y≈200 以上只飘 2 格 —— 低空尾迹显得蓬松、云层以上紧实。
     * 下限跟着尾迹带一起从 160 挪到 100（2026-11 拓宽高度区间时同步改的）。
     * 置 1.0 = 与高度无关。
     */
    public static final double TRAIL_TURB_LOW_GAIN = 2.5D;

    /**
     * 出生点沿<b>排气下游</b>偏移（格）：真实尾迹不在喷口出现，要先经过一段喷流混合段才开始结冰。
     * <p>
     * 正值 = 沿排气方向往下游偏移（远离喷口，尾迹与尾焰之间留出一段看不见的喷流）；
     * 想要相反方向就把值改成负数。
     */
    public static final double TRAIL_START_OFFSET = 3.0D;

    /** 尾迹发射距离上限（格，0 = 不限）：比排气的 {@link #exhaustMaxDistance()}=64 更大，尾迹本来就要远处可见 */
    private static double contrailMaxDistance() {
        return Config.CONTRAIL_MAX_DISTANCE.get();
    }

    /** 每 tick 最多补发几颗尾迹粒子（防止低帧率/瞬移时一帧吐一堆） */
    private static int contrailMaxPerTick() {
        return Config.CONTRAIL_MAX_PER_TICK.get();
    }

    /**
     * 尾迹粒子的<b>存活总数上限</b>（安全阀，现 6000；0 = 不限）。
     * <p>
     * 同屏粒子数 ≈ 发射密度 × 寿命：寿命 600 tick 时 4 管满载约 2000 颗，寿命 18000 tick 时
     * <b>理论上是几万颗</b> —— 半透明 quad 能把帧率直接打死。超上限就停止发射，
     * 等旧粒子自然死亡腾出配额（表现为"尾迹长度到上限就不再延长"，而不是掉帧）。
     * 计数见 {@link com.zzy205.myfirstmod.particle.ContrailBudget}。
     */
    private static int contrailMaxLive() {
        return Config.CONTRAIL_MAX_LIVE.get();
    }

    /** 单 tick 位移超过它就当作"瞬移"（装配/传送/客户端刚加载），本 tick 不发射，避免拖出一条长直的假尾迹 */
    public static final double TRAIL_MAX_TICK_MOVE = 4.0D;

    /**
     * 尾迹颜色。<b>现为明显的冷青色</b>（用户要求"染一下色，看得清晰一些"）：
     * 原来的 0.92/0.94/0.97 是冰晶白，在高空蓝天背景上几乎与天空同色，很难判断有没有生成。
     * <p>
     * 想更接近真实（冰晶白）就改回 0.92/0.94/0.97；想更醒目还可以继续压 R（例如 0.30/0.85/1.00 变成饱和青）。
     */
    public static final float TRAIL_RED = 1.00F;
    public static final float TRAIL_GREEN = 1.00F;
    public static final float TRAIL_BLUE = 1.00F;

    // ── 尾迹染色（玩家手持染料右键喷口，只染【尾迹】不染排气粒子，用户定稿） ──

    /** 未染色的哨兵值（{@link DyeColor#getId()} 是 0~15，所以 -1 空着） */
    public static final int NO_DYE = -1;

    /**
     * 染料颜色的亮度补偿系数：{@link DyeColor#getTextureDiffuseColor()} 是"皮革染色"用的偏暗色
     * （红 = 0xB02E26、黑 = 0x1D1D21），直接喂给全亮的粒子会显得发闷，所以整体乘一个系数再钳到 1.0。
     * 想要更接近染料本来的颜色就调到 1.0。
     */
    public static final float TRAIL_DYE_BRIGHTNESS = 1.15F;

    private static final String TAG_TRAIL_DYE = "TrailDye";

    /** Sable 力组注册表 key（{@code sable:force_groups}）。不直接引用 {@code ForceGroups}：其静态字段
     *  类型引用 veil 的 {@code RegistryObject}，veil 不在编译 classpath（同 {@code FlightDataRecorder}）。 */
    private static final ResourceKey<Registry<ForceGroup>> FORCE_GROUP_REGISTRY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("sable", "force_groups"));
    private static final ResourceLocation PROPULSION_ID =
            ResourceLocation.fromNamespaceAndPath("sable", "propulsion");

    /** 运行时解析出的 {@code sable:propulsion} 力组（解析失败保持 null，下次再试） */
    private @Nullable ForceGroup cachedPropulsion;

    // ── 高空尾迹的客户端状态（只在 tickClient 里读写，不参与同步） ──

    /** 上一 tick 喷口的世界坐标；{@code null} = 还没锚定（刚加载/风扇刚启动），那一 tick 只记录不发射 */
    private @Nullable Vec3 contrailAnchorWorld;

    /** 自上次发射以来累积的位移（格）：够了才吐一颗（见 {@link #trailSpacing()}） */
    private double contrailAccum;

    /**
     * 尾迹染色用的 {@link DyeColor} id；{@link #NO_DYE} = 未染色（用 {@link #TRAIL_RED}/{@link #TRAIL_GREEN}/{@link #TRAIL_BLUE}）。
     * <p>
     * 服务端权威：右键染色后 {@code setChanged()} + {@code sendBlockUpdated} 推给客户端，
     * 客户端 {@code tickContrail} 用 {@link #trailRgb()} 生成粒子 ⇒ 必须同步，否则各客户端颜色不一致。
     */
    private int trailDye = NO_DYE;

    // ── 尾迹染色：状态 / 取色 / 持久化与同步 / goggle tooltip ──

    /** 当前染料 id（{@link #NO_DYE} = 未染色） */
    public int getTrailDye() {
        return this.trailDye;
    }

    /**
     * 设置尾迹染料（{@link DyeColor#getId()}，{@link #NO_DYE} = 清除染色）。
     * <p>
     * 服务端权威：改完立刻落盘 + 推一份 BE 数据给客户端（客户端 ticker 每 tick 用它取色喷粒子）。
     *
     * @return 是否真的改变了（未改变时不发包、不消耗染料）
     */
    public boolean setTrailDye(final int dyeId) {
        final int clamped = dyeId < 0 ? NO_DYE : Math.min(dyeId, DyeColor.values().length - 1);

        if (clamped == this.trailDye) {
            return false;
        }

        this.trailDye = clamped;
        this.setChanged();

        if (this.level != null && !this.level.isClientSide) {
            // 默认 getUpdatePacket() 返回 null 会导致客户端快照陈旧 ⇒ 颜色不同步（同 QuickFillFluidTank 的注释）
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 2);
        }

        return true;
    }

    /**
     * 当前尾迹颜色的打包 RGB（{@code 0xRRGGBB}）：未染色时返回默认常量色。
     * 染色后取 {@link DyeColor#getTextureDiffuseColor()}（染料在皮革/烟花上的标准色）。
     */
    public int trailRgb() {
        final int dye = this.trailDye;

        if (dye >= 0 && dye < DyeColor.values().length) {
            return DyeColor.byId(dye).getTextureDiffuseColor();
        }

        return (Math.round(TRAIL_RED * 255.0F) << 16)
                | (Math.round(TRAIL_GREEN * 255.0F) << 8)
                | Math.round(TRAIL_BLUE * 255.0F);
    }

    /** 取打包颜色的某个通道（0~1），乘上 {@link #TRAIL_DYE_BRIGHTNESS} 后钳位 */
    private static float dyeChannel(final int rgb, final int shift) {
        final float channel = ((rgb >> shift) & 0xFF) / 255.0F;
        return Math.min(1.0F, channel * TRAIL_DYE_BRIGHTNESS);
    }

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (this.trailDye != NO_DYE) {
            tag.putInt(TAG_TRAIL_DYE, this.trailDye);
        }
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.trailDye = tag.contains(TAG_TRAIL_DYE) ? tag.getInt(TAG_TRAIL_DYE) : NO_DYE;
    }

    // 客户端同步（goggle tooltip 与尾迹粒子都在客户端读染色，必须推）

    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
        final CompoundTag tag = super.getUpdateTag(registries);

        if (this.trailDye != NO_DYE) {
            tag.putInt(TAG_TRAIL_DYE, this.trailDye);
        }

        return tag;
    }

    @Override
    public void handleUpdateTag(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        this.trailDye = tag.contains(TAG_TRAIL_DYE) ? tag.getInt(TAG_TRAIL_DYE) : NO_DYE;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Create goggle tooltip ──

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("tooltip.ccpe.thrust_nozzle.header")
                .withStyle(ChatFormatting.WHITE)));

        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.thrust_nozzle.trail_dye").withStyle(ChatFormatting.GRAY))
                .append(this.trailDyeLabel()));

        return true;
    }

    /** 染色名称：未染色显示"默认（未染色）"，否则是染料名 + {@code #RRGGBB}，并按该颜色上色 */
    private Component trailDyeLabel() {
        final int dye = this.trailDye;

        if (dye < 0 || dye >= DyeColor.values().length) {
            return Component.translatable("tooltip.ccpe.thrust_nozzle.trail_dye_default")
                    .withStyle(ChatFormatting.DARK_GRAY);
        }

        final int rgb = this.trailRgb();
        final DyeColor color = DyeColor.byId(dye);

        return Component.translatable(DyeItem.byColor(color).getDescriptionId())
                // 深色染料（黑/棕）在深色 tooltip 背景上看不清 ⇒ 文字用提亮后的同色
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(readableRgb(rgb))))
                .append(Component.literal(String.format(Locale.ROOT, " #%06X", rgb))
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 把颜色提亮到"在深色 tooltip 背景上还看得清"的程度（保持色相，只抬亮度） */
    private static int readableRgb(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        final int max = Math.max(r, Math.max(g, b));

        if (max >= 0x90) {
            return rgb;
        }

        final double scale = 0x90 / (double) Math.max(1, max);
        final int nr = (int) Math.min(255.0D, Math.round(r * scale));
        final int ng = (int) Math.min(255.0D, Math.round(g * scale));
        final int nb = (int) Math.min(255.0D, Math.round(b * scale));
        return (nr << 16) | (ng << 8) | nb;
    }

    public ThrustNozzleBlockEntity(BlockPos pos, BlockState state) {
        this(MyModBlockEntities.thrust_nozzle_entity.get(), pos, state);
    }

    /**
     * 子类专用构造器：让 {@link AdvancedThrustNozzleBlockEntity} 注册成自己的方块实体类型。
     * <p>
     * 除类型以外的一切（推力 / 气压 / 排气·进气·尾迹粒子 / 遮挡射线 / 染料同步 / goggle tooltip）
     * 都由父类提供，子类不需要重写。
     */
    protected ThrustNozzleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Sable 物理：每 substep 沿喷口轴向施加恒定推力 ──

    /**
     * 朝向的<b>轴向符号</b>：{@code UP = +1}、{@code DOWN = -1}、四个水平方向恒为 {@code +1}
     * （{@link Direction.AxisDirection#getStep()}）。
     * <p>
     * <b>为什么必须有它（2026-10 修的一个真 bug）</b>：电扇自身的推力路径
     * （{@code references/sable-main/neoforge/.../fans_provide_force/EncasedFanBlockEntityMixin.java:44}）
     * 是
     * <pre>
     * propSpeed = FACING.getAxisDirection().getStep() * convertToAngular(getSpeed()) * (10 / 3)
     * getThrust() = 0.3 * propSpeed
     * getScaledThrust() = -getThrust() * airflowScaling * pressure
     * force = FACING.getNormal() * getScaledThrust() * timeStep
     *       = -FACING.getNormal() * step * 转速 * (气压/气流修正)
     * </pre>
     * 本喷管原先只写了 {@code -FACING * 转速}，<b>漏掉 step</b>：
     * <ul>
     * <li>水平朝向 step = +1 ⇒ 与电扇完全一致（所以横向一直是"对的"）；</li>
     * <li>{@code facing=DOWN} step = -1 ⇒ <b>推力整体反向</b>（用户实测：朝下的喷管方向是反的）；</li>
     * <li>{@code facing=UP} step = +1 ⇒ 与水平一样，本来就对。</li>
     * </ul>
     * 补上之后，<b>竖直朝向（UP / DOWN）的喷管，负转速恒为向上、正转速恒为向下</b>，
     * 与"朝上装还是朝下装"无关 —— 因为 {@code -normal * step} 对 UP 和 DOWN 都等于 {@code (0,-1,0)}。
     * 这也意味着原先"前后两对喷管镜像取反"的接线（{@code fl/fr=-256}、{@code bl/br=+256} 才全向上）
     * 要改成四台统一取负。
     */
    private int axisStep(final Direction facing) {
        return facing.getAxisDirection().getStep();
    }

    /**
     * 喷嘴实际轴向（<b>plot / 方块局部系</b>，单位向量）——推力与全部粒子链路的<b>唯一方向来源</b>。
     * <p>
     * 默认 = {@code FACING} 的法线（定向喷管）。矢量喷管
     * （{@link AdvancedThrustNozzleBlockEntity}）覆盖它返回倾斜后的轴，于是
     * {@link #sable$physicsTick}（推力）与 {@link #tickClient}（喷口位置 / 粒子基向量 / 遮挡射线 /
     * 进气漏斗 / 尾迹锚点）<b>一处改、全链路生效</b>。
     */
    protected Vec3 nozzleAxis(final BlockState state) {
        final Vec3i normal = state.getValue(ThrustNozzleBlock.FACING).getNormal();
        return new Vec3(normal.getX(), normal.getY(), normal.getZ());
    }

    /**
     * 额外出力系数（1.0 = 满出力）：由子类覆盖（矢量喷管的 Lua {@code setThrustScale}）。
     * <p>
     * 乘在「转速 × 气压」之上，0 = 完全不出力。默认 1.0，所以定向喷管行为不变。
     */
    protected double thrustScale() {
        return 1.0D;
    }

    /**
     * <b>粒子强度系数</b>（0..1）：由子类覆盖（矢量喷管的 Lua {@code setThrustScale}），默认 1.0。
     * <p>
     * 只乘在<b>排气</b>与<b>进气</b>这两条与出力同源的链路上（{@link #tickClient} 里的「有效油门」=
     * 油门 × 本系数），所以系数一变，<b>数量、排气速度、进气漏斗长度一起变</b>，0 = 完全不出粒子。
     * <p>
     * <b>高空尾迹也受它影响，但只影响发射密度</b>（{@link #tickContrail}：间距 ∝ 1/系数）：
     * 系数 0 = 完全不出尾迹（2026-11 用户定稿），系数 0.5 = 尾迹更稀；
     * 尾迹的<b>寿命</b>仍只由高度适宜度决定，不随出力缩短。
     */
    protected float particleIntensityScale() {
        return 1.0F;
    }

    /**
     * <b>出力方向系数</b>：{@code +1.0} = 正常方向，{@code -1.0} = 沿轴的力整体反向。
     * 由子类覆盖（矢量喷管的 Lua {@code setInverted}，推力 ↔ 吸力互换）。默认 {@code +1.0}，
     * 所以定向喷管行为不变。
     * <p>
     * 乘在<b>两处</b>，缺一处就会出现"力和气流不同向"的怪象：
     * <ul>
     * <li>{@link #sable$physicsTick} 的 {@code magnitude} ⇒ 力反向；</li>
     * <li>{@link #tickClient} 的 {@code thrustSign} ⇒ 排气 / 进气<b>链路自动互换</b>
     *     （气流方向必须始终与受力方向相反，见那里的注释）。</li>
     * </ul>
     */
    protected double thrustDirectionSign() {
        return 1.0D;
    }

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
        // 轴向从钩子取（矢量喷管会返回倾斜后的轴）；magnitude 里的 axisStep 仍按 FACING 定符号
        final Vec3 axis = this.nozzleAxis(this.getBlockState());
        // 冲量 = 推力 × 气压 × substep 时长；rpm 带符号，所以 magnitude 也带符号。
        // axisStep(facing) 是让"喷管的轴向语义"与电扇自身推力完全一致的关键一项（见 axisStep 的注释）：
        // 少了它，facing=DOWN 的喷管推力会整体反向（step = -1），水平/UP 则看不出问题。
        final double pressure = this.airPressureAt(subLevel);
        final double magnitude = (REVERSE_SIGN ? -1.0D : 1.0D) * this.axisStep(facing)
                * THRUST_PER_RPM * speed * pressure * timeStep * this.thrustScale()
                // 出力方向系数（矢量喷管的 Lua setInverted）：反转到"吸力"时整个冲量换符号
                * this.thrustDirectionSign();

        // 力向量与作用点都必须每次新建：QueuedForceGroup.applyImpulseAtPoint 会就地改写传入的
        // position（减去质心求力臂），且 recordPointForce 保留引用（FlightDataRecorder 遥测会读），
        // 复用临时向量会污染已记录的点力。
        final Vector3d impulse = new Vector3d(-axis.x, -axis.y, -axis.z).mul(magnitude);
        group.applyAndRecordPointForce(JOMLConversion.atCenterOf(this.worldPosition), impulse);
    }

    /**
     * 本喷管所在位置的气压（无量纲，海平面 = 1.0）。
     * <p>
     * 取数口径与 Sable 原版喷嘴一致（{@code NozzleHoveringHelper.java:173}）：
     * 把<b>喷管方块中心</b>从 plot 局部系投到<b>世界系</b>，再喂给
     * {@link DimensionPhysicsData#getAirPressure} —— 气压是 y 的函数，用 plot 坐标会取错高度。
     * <p>
     * 任何异常（维度数据缺失/解析失败）或非有限值都退回 1.0（＝气压不影响出力），
     * 不能让气压这一项把推力搞成 NaN 或负值：NaN 冲量进物理步会直接把物理体打飞。
     */
    private double airPressureAt(final ServerSubLevel subLevel) {
        if (!THRUST_SCALES_WITH_AIR_PRESSURE) {
            return 1.0D;
        }

        try {
            // 世界坐标：logicalPose 把 plot 局部系（worldPosition 就是 plot 坐标）投到世界系。
            // atCenterOf 每次新建向量，所以不会污染 worldPosition。
            final Vector3d worldCenter = subLevel.logicalPose()
                    .transformPosition(JOMLConversion.atCenterOf(this.worldPosition), new Vector3d());
            final double pressure = DimensionPhysicsData.getAirPressure(subLevel.getLevel(), worldCenter);

            if (!Double.isFinite(pressure) || pressure < 0.0D) {
                return 1.0D;
            }

            return AIR_PRESSURE_EXPONENT == 1.0D ? pressure : Math.pow(pressure, AIR_PRESSURE_EXPONENT);
        } catch (final RuntimeException e) {
            return 1.0D;
        }
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
            // 风扇停了：清掉尾迹锚点，下次启动不会因为"历史位移"补吐一串尾迹
            this.contrailAnchorWorld = null;
            this.contrailAccum = 0.0D;
            return;
        }

        final Direction facing = this.getBlockState().getValue(ThrustNozzleBlock.FACING);
        // 轴向从钩子取：矢量喷管倾斜后，喷口位置/粒子方向/遮挡射线全部自动跟着倾斜轴走
        final Vec3 axis = this.nozzleAxis(this.getBlockState());

        // 排气方向 = 机体受力方向的反向：
        // 受力 = -FACING · (REVERSE_SIGN ? -1 : 1) · axisStep · 转速 ⇒ 排气 = +FACING · 同一个符号。
        // axisStep 必须一起带上，否则竖直朝向喷管的粒子会与真实推力反向（力的方向改了，气流也得跟着改）。
        // thrustDirectionSign() 同理：矢量喷管 Lua 反转出力时，排气必须同时切成进气（否则排气会与受力同向）。
        final double thrustSign = (REVERSE_SIGN ? -1.0D : 1.0D) * this.axisStep(facing)
                * (speed > 0.0F ? 1.0D : -1.0D) * this.thrustDirectionSign();
        double flowSign = thrustSign;

        if (INVERT_PARTICLE_FLOW) {
            flowSign = -flowSign;
        }

        final Vec3 center = Vec3.atCenterOf(this.worldPosition);
        final Vec3 flowDir = axis.scale(flowSign);
        final Vec3 mouth = center.add(axis.scale(0.55D));

        // 本方块所在物理体：粒子坐标必须用它的姿态从 plot 局部系投到世界系（见下方 projectToWorld）
        final SubLevel own = Sable.HELPER.getContaining(this);

        // 有效油门 = 油门（|转速| / 256）× 子类粒子强度系数（矢量喷管的出力系数，默认 1.0）。
        // 它<b>喂给排气、进气与高空尾迹</b>三条链路：
        //   · 排气 / 进气 —— 数量、排气速度、进气漏斗长度都按这一个数缩放
        //     ⇒ 出力减半 ≈ 转速减半的观感、出力 0 = 完全不出粒子（用户定稿 2026-11）；
        //   · 高空尾迹 —— 只缩放发射密度（出力 0 = 不出尾迹，寿命仍看高度），见 tickContrail。
        final float particleThrottle = Mth.clamp(
                Math.min(Math.abs(speed) / 256.0F, 1.0F) * this.particleIntensityScale(), 0.0F, 1.0F);

        // 吸力（负转速）：机体被拉向 +FACING ⇒ 空气被吸进口面，走一条独立的进气链路。
        // 此时<b>不出排气、也不出尾迹</b>：
        //   · 排气方向会翻到 -FACING（喷管背后），射线一出膛就撞到喷管自己的碰撞箱 ⇒ 以前完全不出粒子；
        //   · 尾迹是"排气水汽在高空结的冰晶"，进气时没有排气，而且它会在机身内部 3 格外出生（用户定稿：不出）。
        if (thrustSign < 0.0D) {
            this.contrailAnchorWorld = null;
            this.contrailAccum = 0.0D;
            this.tickIntake(level, own, mouth, axis, particleThrottle);
            return;
        }

        // 高空尾迹：与排气<b>互相独立</b>的一条链路（只在冷空气里有、按位移发射、不做碰撞），见 tickContrail。
        // 但它的<b>发射密度</b>同样乘有效油门 ⇒ 风扇在转、出力系数为 0 时也不该有尾迹（2026-11 用户定稿）。
        this.tickContrail(level, own, mouth, flowDir, particleThrottle);

        // 密度随有效油门线性变化（= 转速 × 出力系数）；不足一颗就不生成
        final int count = (int) Math.round(exhaustParticlesPerTick() * particleThrottle);

        if (count <= 0) {
            return;
        }

        // 喷口截面上的两个基向量：在口面上随机撒点
        final Vec3 seedVec = Math.abs(axis.y) > 0.5D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        final Vec3 v = axis.cross(seedVec).normalize();
        final Vec3 u = v.cross(axis).normalize();

        // 距离上限（见 exhaustMaxDistance()）：太远就整段不生成，省下每颗每 tick 的扫掠碰撞
        if (!this.withinParticleDistance(level, own, mouth, exhaustMaxDistance())) {
            return;
        }

        // 与 create:encased_fan 一致：气流遇方块即止（自身物理体 + 其它物理体都算）。
        // 电扇那边是 AirCurrent.getFlowLimit() 把 maxDistance 截到第一个挡路方块的碰撞形状深度；
        // 这里等价地沿排气方向打一条碰撞射线，把粒子位移截断在命中点。
        final double clear = this.clearDistance(level, own, mouth, flowDir, PARTICLE_TRAVEL_MAX + 0.1D);

        if (DEBUG_EXHAUST_LOG && level.getGameTime() % 20L == 0L) {
            final Vec3 mouthWorld = this.projectToWorld(level, own, mouth);
            final Vec3 flowWorld = this.projectToWorld(level, own, mouth.add(flowDir)).subtract(mouthWorld);
            final Vec3 flowUnit = flowWorld.normalize();
            CCPeripheralExtender.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "[EXHDBG-N] %s mouth=(%.3f,%.3f,%.3f) flow=(%.3f,%.3f,%.3f) clear=%.3f count=%d rpm=%.1f own=%s",
                    this.worldPosition.toShortString(), mouthWorld.x, mouthWorld.y, mouthWorld.z,
                    flowUnit.x, flowUnit.y, flowUnit.z, clear, count, speed, own == null ? "null" : "yes"));
        }

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
                this.spawnExhaust(level, random, own, startWorld, flowWorld, particleThrottle);
            } else {
                // 必须用 9 参数的 addAlwaysVisibleParticle(..., ignoreRange = true)：
                // 7 参数那个重载的第二个布尔是 decreased 而不是 force（ClientLevel.java:733），
                // 照样会被原版 32 格限制丢掉。见 exhaustMaxDistance() 的注释。
                level.addAlwaysVisibleParticle(new AirParticleData(PARTICLE_DRAG, PARTICLE_SPEED), true,
                        startWorld.x, startWorld.y, startWorld.z,
                        flowWorld.x, flowWorld.y, flowWorld.z);
            }
        }
    }

    /**
     * 高空尾迹（contrail）的客户端发射逻辑：<b>按移动距离发射</b>，不做碰撞。
     * <p>
     * 三步：
     * <ol>
     * <li>把喷口中心投到<b>世界系</b>，与上一 tick 的锚点相减得到本 tick 的真实位移
     *     （不用物理体速度 API：客户端 BE 只认世界坐标，且这样对"没装 Sable 速度"的情况也成立）；</li>
     * <li>用世界高度算<b>尾迹适宜度</b> s（{@link #contrailSuitability}）：s=0 直接不出，
     *     s 同时决定"发射多稀"（间距 ÷s）与"活多久"（{@link #trailLifeMin()}~{@link #trailLifeMax()}）；</li>
     * <li>累积位移够了就吐一颗，单 tick 最多 {@link #contrailMaxPerTick()} 颗。</li>
     * </ol>
     * 与排气粒子<b>完全独立</b>：不查遮挡射线（尾迹在 3 格外才生成，且用户要的是"高空一条云带"），
     * 也不做扫掠碰撞（那是冰晶而不是烟，见 {@code ContrailParticle}）。
     * <p>
     * <b>发射密度 ∝ 有效油门</b>（{@code |转速| / 256 × 出力系数}）：出力的水汽才是尾迹的来源，
     * 所以出力系数 0（风扇在转但 {@code setThrustScale(0)}）⇒ 完全没有尾迹；系数 0.5 ⇒ 间距翻倍、尾迹更稀
     * （2026-11 用户定稿。此前尾迹刻意与出力解耦，导致"不出力却有尾迹"）。
     * <b>寿命只看高度 s</b>，不随油门缩短 —— 出力小只是"排得少"，不是"冰晶更快消失"。
     * <p>
     * 坑位提醒（都是本 memo 踩过的）：
     * <ul>
     * <li>出生点必须走 {@link #projectToWorld}，否则粒子钉在机体坐标系里跟着车跑（§3.5）；</li>
     * <li>必须用 <b>9 参数</b> {@code addAlwaysVisibleParticle(..., ignoreRange = true)}，
     *     7 参数那个照样被原版 32 格限制丢掉（§3.7）；</li>
     * <li>瞬移（装配/传送/刚加载）时单 tick 位移极大，必须丢弃，否则会拖出一条笔直的假尾迹。</li>
     * </ul>
     *
     * @param intensity 有效油门 0~1（{@code |转速| / 256 × 出力系数}）：只决定<b>发射密度</b>，
     *                  0 时整条链路不发射（寿命仍由高度 s 决定）
     */
    private void tickContrail(final Level level, final @Nullable SubLevel own,
                              final Vec3 mouth, final Vec3 flowDir, final float intensity) {
        if (!useContrail()) {
            return;
        }

        // 出力 0（风扇在转但 setThrustScale(0)）⇒ 不排任何水汽 ⇒ 高空也没有尾迹。
        // 必须清累积量，否则再开出力那一刻会把前面积累的位移一次性吐成一串粒子。
        if (intensity <= 0.0F) {
            this.contrailAnchorWorld = null;
            this.contrailAccum = 0.0D;
            return;
        }

        // 维度开关（用户定稿）：末地恒 0°C 会蹭出 10% 的淡尾迹，这里直接关掉
        if (CONTRAIL_OVERWORLD_ONLY && level.dimension() != Level.OVERWORLD) {
            this.contrailAnchorWorld = null;
            this.contrailAccum = 0.0D;
            return;
        }

        final Vec3 mouthWorld = this.projectToWorld(level, own, mouth);

        // 排气方向也投到世界系：机体翻转/倾斜时它和 plot 系方向不同
        final Vec3 flowWorld = this.projectToWorld(level, own, mouth.add(flowDir)).subtract(mouthWorld);

        final Vec3 previous = this.contrailAnchorWorld;
        this.contrailAnchorWorld = mouthWorld;

        if (flowWorld.lengthSqr() < 1.0E-8D || previous == null) {
            this.contrailAccum = 0.0D; // 第一 tick 只记录锚点
            return;
        }

        final double moved = mouthWorld.distanceTo(previous);

        if (moved > TRAIL_MAX_TICK_MOVE) {
            this.contrailAccum = 0.0D; // 瞬移，别拖出假尾迹
            return;
        }

        this.contrailAccum += moved;

        final float s = contrailSuitability(mouthWorld.y);

        if (s <= 0.0F) {
            this.contrailAccum = 0.0D; // Y 不在 100~320 区间 ⇒ 完全不出尾迹（也顺便清掉累积量）
            return;
        }

        if (!this.withinParticleDistance(level, own, mouth, contrailMaxDistance())) {
            return; // 太远只累积不发射；下面的钳位保证不会攒出一大串
        }

        // 存活上限（安全阀）：粒子数 = 密度 × 寿命，长寿命必须有个总数兜底，否则半透明 quad 会堆爆
        if (contrailMaxLive() > 0 && ContrailBudget.live() >= contrailMaxLive()) {
            return;
        }

        // 间距 = 基础间距 ÷ 适宜度 ÷ 有效油门：
        //   · 适宜度 s 是纯高度线性曲线（TRAIL_Y_RISE_START 起的四段，见 {@link #contrailSuitability}）
        //     ⇒ 密度（每格粒子数）与高度严格线性：100 格处 0、180 拉满、240 开始线性回落、320 归零；
        //     这里<b>不再给 s 低下限</b>（旧版有 TRAIL_SPACING_S_FLOOR，会把低空的线性压成台阶）；
        //   · 出力小 ⇒ 间距按比例放大（密度 ∝ 有效油门）；出力 0 已被上面的早退挡掉。
        final double spacing = Math.max(0.01D,
                trailSpacing() / Math.max(s, 1.0E-4F)
                        / Math.max(intensity, 1.0E-3F));
        final Vec3 dir = flowWorld.normalize();
        final RandomSource random = level.random;
        int emitted = 0;

        while (this.contrailAccum >= spacing && emitted < contrailMaxPerTick()) {
            this.contrailAccum -= spacing;
            emitted++;
            this.spawnContrail(level, random, mouthWorld, dir, s);
        }

        if (this.contrailAccum > spacing) {
            this.contrailAccum = spacing; // 累积量封顶一格
        }
    }

    /**
     * 生成一颗尾迹粒子（{@code ccpe:contrail}）。
     *
     * @param mouthWorld 喷口中心<b>世界坐标</b>（已投出）
     * @param dir        排气方向的单位向量（世界系）
     * @param s          尾迹适宜度 0~1：决定寿命
     */
    private void spawnContrail(final Level level, final RandomSource random,
                               final Vec3 mouthWorld, final Vec3 dir, final float s) {
        // 出生点：沿排气下游偏移 TRAIL_START_OFFSET 格（真实尾迹不在喷口出现，见该常量注释），
        // 再在垂直于排气的截面内随机散开，让尾迹天生就有宽度而不是一根细线
        final Vec3 seedVec = Math.abs(dir.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 sideX = dir.cross(seedVec).normalize();
        final Vec3 sideY = sideX.cross(dir).normalize();
        final double lateral = trailWidth() * 0.35D;
        final Vec3 start = mouthWorld
                .add(dir.scale(TRAIL_START_OFFSET))
                .add(sideX.scale((random.nextDouble() - 0.5D) * 2.0D * lateral))
                .add(sideY.scale((random.nextDouble() - 0.5D) * 2.0D * lateral));

        // 寿命随适宜度线性增长（越冷越耐久），再叠 ±15% 随机，避免整条尾迹同生共死
        final int baseLife = Math.round(trailLifeMin() + (trailLifeMax() - trailLifeMin()) * s);
        final int lifetime = Math.max(8, (int) (baseLife * (0.85D + random.nextDouble() * 0.3D)));

        // 飘荡：先定"一生总共飘多远"，再由速度 = 距离 / 寿命 反算出一个恒定速度，
        // 方向在球面上均匀随机（不随风、无方向偏好）。
        //   · 距离随机（MIN~MAX 倍）⇒ 有的粒子只飘 1 格、有的飘 3 格，尾迹才有厚薄
        //   · 再乘高度湍流系数 ⇒ 云层以下越低越散（见 TRAIL_TURB_LOW_GAIN）
        final double spread = TRAIL_DRIFT_DISTANCE_MIN
                + random.nextDouble() * (TRAIL_DRIFT_DISTANCE_MAX - TRAIL_DRIFT_DISTANCE_MIN);
        final double driftDistance = trailDriftDistance() * spread * trailTurbulence(mouthWorld.y);
        final double speed = driftDistance / lifetime; // 块/tick

        final double cosTheta = random.nextDouble() * 2.0D - 1.0D;
        final double phi = random.nextDouble() * Math.PI * 2.0D;
        final double ring = Math.sqrt(Math.max(0.0D, 1.0D - cosTheta * cosTheta));
        final double vx = ring * Math.cos(phi) * speed;
        final double vy = cosTheta * speed;
        final double vz = ring * Math.sin(phi) * speed;

        // 喷出初速度（2026-11）：在恒定飘荡之外，再沿<b>排气方向</b>给一个初速度，
        // 由粒子每 tick 乘 contrailBurstDamp 指数衰减（速度大小逐渐减小，最终只剩飘荡）。
        //   · 大小：均值 = contrailBurstSpeedMean、标准差 = contrailBurstSpeedSigma 的截断正态（§19）
        //   · 方向：排气方向周围 contrailBurstSpread 度的锥内均匀取（见 randomConeDirection）
        final double burstSpeed = sampleBurstSpeed(random,
                Config.CONTRAIL_BURST_SPEED_MEAN.get(), Config.CONTRAIL_BURST_SPEED_SIGMA.get());
        final Vec3 burst = burstSpeed <= 0.0D
                ? Vec3.ZERO
                : randomConeDirection(random, dir,
                        Math.toRadians(Config.CONTRAIL_BURST_SPREAD.get())).scale(burstSpeed);

        final float width = trailWidth() * (0.75F + random.nextFloat() * 0.5F);
        // 染色：未染色时 trailRgb() 返回 TRAIL_RED/GREEN/BLUE 打包的默认色
        final int rgb = this.trailRgb();

        // 9 参数 overload（ignoreRange = true）：7 参数那个的第二个布尔是 decreased 不是 force，
        // 照样被原版 32 格限制丢掉 —— 见 exhaustMaxDistance() 的注释与 memo §3.7。
        level.addAlwaysVisibleParticle(
                new ContrailParticleData(width, lifetime,
                        dyeChannel(rgb, 16), dyeChannel(rgb, 8), dyeChannel(rgb, 0),
                        (float) burst.x, (float) burst.y, (float) burst.z),
                true,
                start.x, start.y, start.z,
                vx, vy, vz);
    }

    /**
     * 在 {@code axis} 周围、半角 {@code halfAngleRad} 的<b>圆锥内</b>均匀随机取一个单位向量。
     * <p>
     * 用 {@code cosθ} 在 {@code [cos(半角), 1]} 上<b>均匀</b>取值（球冠面积均匀），
     * 而不是"角度均匀"—— 后者会让粒子都堆在轴线附近，看起来像一束光而不是一团。
     * 半角 ≤ 0 ⇒ 直接返回 axis；半角 ≥ π ⇒ 整个球面均匀随机。
     *
     * @param axis         单位向量（喷管传进来的是已归一化的排气方向）
     * @param halfAngleRad 锥的半角（弧度）
     */
    private static Vec3 randomConeDirection(final RandomSource random, final Vec3 axis, final double halfAngleRad) {
        final double cosMax = Mth.clamp(Math.cos(halfAngleRad), -1.0D, 1.0D);
        final double cosTheta = cosMax + (1.0D - cosMax) * random.nextDouble();
        final double sinTheta = Math.sqrt(Math.max(0.0D, 1.0D - cosTheta * cosTheta));
        final double phi = random.nextDouble() * Math.PI * 2.0D;

        // axis 的任意一对正交基（与 spawnContrail 里撒点用的是同一种取法）
        final Vec3 guide = Math.abs(axis.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 sideX = axis.cross(guide).normalize();
        final Vec3 sideY = sideX.cross(axis).normalize();

        return axis.scale(cosTheta)
                .add(sideX.scale(sinTheta * Math.cos(phi)))
                .add(sideY.scale(sinTheta * Math.sin(phi)))
                .normalize();
    }

    /** 喷出初速度截断在「均值 ± 本值 × σ」处：3σ ⇒ 边界处密度只剩峰值的 1%，肉眼看不到硬边 */
    private static final double BURST_SPEED_TRUNCATE_SIGMA = 3.0D;
    /** 拒绝采样尝试上限（3σ 截断的单次通过率 ≈ 99.7%，8 次绰绰有余） */
    private static final int BURST_SPEED_SAMPLE_ATTEMPTS = 8;

    /**
     * 取一个喷出初速度（格/tick）：均值 {@code mean}、标准差 {@code sigma} 的<b>截断正态</b>。
     * <p>
     * 为什么用正态而不是旧的均匀分布：均匀会把「速度区间」原样变成一条<b>密度恒定</b>的条带，
     * 两端是硬边 —— 玩家看到的"边界清晰的条带"就是它（memo §19）。正态的密度在两端趋近 0，
     * 边缘自然渐隐。
     * <p>
     * 为什么配置是<b>绝对量</b>（均值 + σ）而不是"区间比例"：区间比例下 min/max 一动，σ 的实际
     * 大小跟着变，"喷多远"和"多胖"耦合在一起。现在两者独立：{@code mean} 只管距离、
     * {@code sigma} 只管胖瘦（用户 2026-11-19 定稿的方案 B）。
     * <p>
     * 截断点固定为 {@code mean ± 3σ}（不是用户配置）：正态尾巴无界，不截断会偶发 4σ/5σ 的
     * 粒子飞出很远，像掉队的白点；而 3σ 处密度只剩 1%，视觉上等价于"没有上限"。
     * 下限还会被 0 夹住（速度不能为负）。
     * <p>
     * 用<b>拒绝采样</b>而不是把越界值夹到边界：夹边界会把被拒样本全堆在端点，
     * 又人为造出一条硬边（正是要消掉的东西）。
     *
     * @param mean  均值（格/tick）；≤ 0 视为<b>关闭喷出</b>
     * @param sigma 标准差（格/tick）；0 = 所有粒子同速
     */
    private static double sampleBurstSpeed(final RandomSource random, final double mean, final double sigma) {
        if (mean <= 0.0D) {
            return 0.0D; // 关闭喷出（回到旧版纯匀速飘荡）
        }

        if (sigma <= 0.0D) {
            return mean; // 没有散布 = 所有粒子同速
        }

        final double low = Math.max(0.0D, mean - BURST_SPEED_TRUNCATE_SIGMA * sigma);
        final double high = mean + BURST_SPEED_TRUNCATE_SIGMA * sigma;

        for (int attempt = 0; attempt < BURST_SPEED_SAMPLE_ATTEMPTS; attempt++) {
            final double sampled = mean + random.nextGaussian() * sigma;

            if (sampled >= low && sampled <= high) {
                return sampled;
            }
        }

        // 极端配置的兜底（均值很接近 0 时下限 0 会切掉约一半样本，但仍有一半通过率）
        return mean;
    }

    /**
     * 吸力（负转速）时的进气粒子：在喷口<b>前方</b>（{@code +FACING} 那一侧）的锥形区域里撒粒子，
     * 由 {@code ccpe:intake} 加速收拢到口面中心并缩小淡出（见 {@code client.particle.IntakeParticle}）。
     * <p>
     * 与排气链路的区别（所以没有复用 {@code spawnExhaust}）：
     * <ul>
     * <li>方向是<b>朝口面</b>的：粒子传的不是速度而是「出生点 → 口面」的位移（t² 走完，见粒子类注释）；</li>
     * <li>寿命由「距离 ÷ 速度」反算 ⇒ 每颗粒子恰好在死亡那一刻抵达口面，不需要额外的吸附判定；</li>
     * <li>不做扫掠碰撞（空气被卷进来不该被车身弹开），只用一条射线判断"前方有没有可吸的空气"。</li>
     * </ul>
     * 坐标：出生点与口面都在<b>世界系</b>算（先 {@link #projectToWorld} 把口面与轴投出来，
     * 再用正交基撒点）—— 与排气同一条规矩，见 memo §3.5，否则粒子会钉在机体坐标系里跟着车跑。
     * <p>
     * 坑位提醒：必须用 <b>9 参数</b> {@code addAlwaysVisibleParticle(..., ignoreRange = true)}，
     * 7 参数那个照样被原版 32 格限制丢掉（memo §3.7）。
     *
     * @param mouth    喷口面中心（plot 局部系，与排气链路同一个点）
     * @param axis     喷口朝向的法向（plot 局部系）：进气从这一侧来
     * @param throttle <b>有效</b>油门 0~1（|转速| / 256 × 出力系数）：决定发射密度、进气漏斗长度与进气速度
     */
    private void tickIntake(final Level level, final @Nullable SubLevel own, final Vec3 mouth,
                            final Vec3 axis, final float throttle) {
        if (!useIntake()) {
            return;
        }

        final int count = (int) Math.round(intakeParticlesPerTick() * throttle);

        if (count <= 0) {
            return;
        }

        if (!this.withinParticleDistance(level, own, mouth, intakeMaxDistance())) {
            return; // 太远就不生成（同排气的距离上限，省下远处看不见的粒子）
        }

        final Vec3 mouthWorld = this.projectToWorld(level, own, mouth);
        final Vec3 axisWorld = this.projectToWorld(level, own, mouth.add(axis)).subtract(mouthWorld);

        if (axisWorld.lengthSqr() < 1.0E-8D) {
            return;
        }

        final Vec3 dir = axisWorld.normalize();
        // 漏斗长度随<b>有效油门</b>（转速 × 出力系数）线性缩放：配置值就是「满油门 256 RPM、出力系数 1.0 时」
        // 的长度（默认 8.0 格）⇒ 128 RPM 吸 4 格、64 RPM 吸 2 格；出力减半同样把漏斗砍半。
        final double reach = Math.max(INTAKE_MIN_REACH, intakeFunnelLength() * throttle);

        // 前方被挡死就没有可吸的空气。射线在世界系里打（own = null ⇒ projectToWorld 退化为恒等），
        // 与排气用同一套语义：只挡实体碰撞形状，不挡水/空气（见 clearDistance）。
        final double clear = this.clearDistance(level, null, mouthWorld, dir, reach + 0.1D);

        if (clear < MIN_PARTICLE_TRAVEL) {
            return;
        }

        final double maxDistance = Math.min(reach, clear);
        // 近端至少留一点空隙；reach 调得很小时按比例收，避免 min > max
        final double minDistance = Math.min(INTAKE_MIN_DISTANCE, maxDistance * 0.5D);

        // 口面正交基（世界系）：进气锥的横截面
        final Vec3 seedVec = Math.abs(dir.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 u = dir.cross(seedVec).normalize();
        final Vec3 v = u.cross(dir).normalize();

        final RandomSource random = level.random;
        // 速度随油门提高（吸力越大吸得越快）；寿命 = 距离 ÷ 速度
        final double speed = Math.max(0.05D, intakeSpeed() * (0.6D + 0.4D * throttle));

        for (int i = 0; i < count; i++) {
            final double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);

            // 进气锥：越远截面越宽（空气从四面八方被收进来，靠近口面才收成一束）
            final double coneRadius = Math.min(INTAKE_MAX_CONE_RADIUS, INTAKE_CONE_RATIO * distance);
            final double radius = coneRadius * Math.sqrt(random.nextDouble());
            final double theta = random.nextDouble() * Math.PI * 2.0D;

            final Vec3 start = mouthWorld
                    .add(dir.scale(distance))
                    .add(u.scale(Math.cos(theta) * radius))
                    .add(v.scale(Math.sin(theta) * radius));

            // 位移 = 从出生点指向口面中心（粒子按 t² 走完，到寿那一刻正好落在口面）
            final Vec3 toward = mouthWorld.subtract(start);

            final int lifetime = Mth.clamp((int) Math.round(distance / speed),
                    INTAKE_MIN_LIFETIME, INTAKE_MAX_LIFETIME);
            final float scale = intakeScale() * (0.75F + random.nextFloat() * 0.5F);

            level.addAlwaysVisibleParticle(
                    new IntakeParticleData(scale, lifetime, INTAKE_RED, INTAKE_GREEN, INTAKE_BLUE),
                    true,
                    start.x, start.y, start.z,
                    toward.x, toward.y, toward.z);
        }
    }

    /**
     * 飘荡距离的<b>高度湍流系数</b>（≥1）：云层（{@link #TRAIL_TURB_CLOUD_Y}=200）及以上为 1.0，
     * 往下线性放大到 {@link #TRAIL_Y_RISE_START}=100 处的 {@link #TRAIL_TURB_LOW_GAIN}。
     * <p>
     * 物理依据：对流层越往下湍流/垂直对流越强（云本身就是这么长出来的），
     * 所以低空的尾迹更容易被搅散；再往上（云层以上）没有观测依据支持继续变化，就保持 1.0 ——
     * 高空"变淡"那件事已经由 {@link #contrailSuitability} 的变稀段负责了，两件事不要混在一起。
     */
    public static double trailTurbulence(final double y) {
        if (TRAIL_TURB_LOW_GAIN <= 1.0D || y >= TRAIL_TURB_CLOUD_Y) {
            return 1.0D;
        }

        final double span = TRAIL_TURB_CLOUD_Y - TRAIL_Y_RISE_START;

        if (span <= 0.0D) {
            return 1.0D;
        }

        final double t = Mth.clamp((TRAIL_TURB_CLOUD_Y - y) / span, 0.0D, 1.0D);
        return 1.0D + (TRAIL_TURB_LOW_GAIN - 1.0D) * t;
    }

    /**
     * 尾迹适宜度 s（0~1，<b>纯高度分段线性</b>）：2026-11 用户定稿，节点与密度口径见
     * {@link #TRAIL_Y_RISE_START} 上方的表。
     * <pre>
     * Y ≤ 100 ⇒ 0        100 → 180 ⇒ (Y−100)/80
     * 180 → 240 ⇒ 1      240 → 320 ⇒ (320−Y)/80       Y ≥ 320 ⇒ 0
     * </pre>
     * s 同时管<b>发射密度</b>（间距 = 基础间距 ÷ s）与<b>寿命</b>（MIN → MAX 线性插值）⇒
     * 用户要的"密度线性增大 / 线性减少"就是这一条，不用再乘别的系数。
     * <p>
     * 注意<b>不要</b>用 {@code SensorSystemAPI.getPressureForEngine} 当强度：气压曲线是 1.5 → 0 单调，
     * 越高越小，和"有没有尾迹"不是一回事。
     */
    public static float contrailSuitability(final double y) {
        final double riseSpan = TRAIL_Y_FULL - TRAIL_Y_RISE_START;
        final double fadeSpan = TRAIL_Y_FADE_END - TRAIL_Y_FADE_START;

        if (y <= TRAIL_Y_RISE_START || y >= TRAIL_Y_FADE_END) {
            return 0.0F;
        }

        if (y < TRAIL_Y_FULL) {
            return riseSpan <= 0.0D ? 1.0F : (float) ((y - TRAIL_Y_RISE_START) / riseSpan);
        }

        if (y <= TRAIL_Y_FADE_START) {
            return 1.0F;
        }

        return fadeSpan <= 0.0D ? 0.0F : (float) ((TRAIL_Y_FADE_END - y) / fadeSpan);
    }

    /**
     * 生成一颗排气粒子（{@code ccpe:exhaust}）。
     * <p>
     * 速度模型：轴向 = {@link #exhaustSpeed()} × 随机倍率 × 油门（转速 / 256），横向再加一点随机散开，
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
        final double speed = exhaustSpeed() * speedRandom * (0.65D + 0.35D * throttle);
        final Vec3 velocity = direction.scale(speed).add(lateral);

        final float scale = exhaustScale() * (0.70F + random.nextFloat() * 0.60F);
        final int lifetime = exhaustLifetime() + random.nextInt(10);

        // 最后一个 0 = 分裂代数：喷管直喷的是第 0 代，撞地分裂出来的子粒子是第 1 代（见 ExhaustParticle）
        //
        // 必须用【9 参数】的 addAlwaysVisibleParticle(..., ignoreRange = true)，原因（MC 源码）：
        //   LevelRenderer#addParticleInternal:
        //       if (force) ...createParticle()
        //       else if (camera.getPosition().distanceToSqr(x, y, z) > 1024.0) return null;   // 32 格硬墙
        //   ClientLevel#addAlwaysVisibleParticle(7 参):  -> addParticle(data, false, true, ...)  // force = false！
        //   ClientLevel#addAlwaysVisibleParticle(9 参):  -> addParticle(data, overrideLimiter || ignoreRange, true, ...)
        // 也就是说 7 参数那个重载的第二个布尔其实是 decreased（粒子设置降到最低时减量），不是 force，
        // 用了它照样会在 32 格外被静默丢弃。Create 的 AirCurrent/AirFlowParticle 用的是裸 addParticle，
        // 所以 create:encased_fan 有一模一样的问题；Create 里想远处可见的粒子都用 9 参数版
        // （例：Create-mc1.21.1-dev/.../LitBlazeBurnerBlock.java:94）。
        level.addAlwaysVisibleParticle(new ExhaustParticleData(scale, lifetime,
                        EXHAUST_RED, EXHAUST_GREEN, EXHAUST_BLUE,
                        own == null ? ExhaustParticleData.NO_OWNER : own.getUniqueId(), 0),
                true,
                startWorld.x, startWorld.y, startWorld.z,
                velocity.x, velocity.y, velocity.z);
    }

    /**
     * 喷口是否在"值得生成粒子"的距离内（{@code maxDistance} 由调用方给：排气用
     * {@link #exhaustMaxDistance()}=64，尾迹用 {@link #contrailMaxDistance()}=96；0 = 不限）。
     * <p>
     * 用最近玩家的世界坐标算，而不是相机的：相机位置属于纯客户端类（{@code Minecraft}），
     * 这里刻意不引用，保持 {@code tickClient()} 只依赖 {@code Level}。
     */
    private boolean withinParticleDistance(final Level level, final @Nullable SubLevel own, final Vec3 mouth,
                                         final double maxDistance) {
        if (maxDistance <= 0.0D) {
            return true;
        }

        final Vec3 mouthWorld = this.projectToWorld(level, own, mouth);
        double nearest = Double.MAX_VALUE;

        for (final Player player : level.players()) {
            nearest = Math.min(nearest, player.position().distanceTo(mouthWorld));
        }

        return nearest <= maxDistance || nearest == Double.MAX_VALUE;
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
