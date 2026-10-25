package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.foundation.particle.AirParticleData;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.particle.ContrailBudget;
import com.zzy205.myfirstmod.particle.ContrailParticleData;
import com.zzy205.myfirstmod.particle.ExhaustParticleData;
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
     * <li>喷口 {@code facing=down}（装在车底、喷口朝下）时：正转速 = 向上推（上升、刹车），
     *     负转速 = 向下吸（贴地 / 倒挂吸附）——与旧 {@code create:nozzle} 的"正推反吸"手感一致；</li>
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

    // ── 高空尾迹（contrail）：只在足够冷的空气里出现，由"移动距离"而不是时间触发 ──

    /**
     * 尾迹总开关（<b>现行 true</b>）。{@code false} = 完全不出尾迹，喷口只有排气。
     * <p>
     * 尾迹的现实依据（memo §8.2）：飞机尾迹是<b>排气水汽在高空冷空气里结成的冰晶云</b>，不是烟。
     * 三个独立物理量各管一件事 —— 温度阈值决定<b>有没有</b>、环境过饱和决定<b>多长命</b>、
     * 光学厚度决定<b>多浓多大</b>。本项目不用湿度，全部退化成"高度 → 环境温度"的纯函数
     * （{@link #TRAIL_T_START} / {@link #TRAIL_T_FULL}），并遵守两条设计规则：
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
     * 理由：地狱按 {@link EngineCoreBlockEntity#T_AMB_NETHER}=155°C 恒温，适宜度天然为 0，本来就没有；
     * 末地恒 {@link EngineCoreBlockEntity#T_AMB_END}=0°C ⇒ 适宜度 ≈ 0.10，会蹭出一层淡淡的尾迹。
     * 这个开关把末地也直接关掉，语义干净。置 {@code false} 则末地也会有 10% 强度的淡尾迹。
     */
    public static final boolean CONTRAIL_OVERWORLD_ONLY = true;

    /**
     * 尾迹<b>适宜度曲线</b>：从"单调爬升"改成<b>峰值曲线</b>（用户定稿）。
     * <p>
     * 现实依据：尾迹要冷（会结冰）+ 要有水汽（能持久）。冷这条随高度单调，但水汽随高度是<b>先增后减</b>
     * —— 到了对流层顶以上（平流层）空气极干，反而很难有尾迹。所以"越高越浓"是错的，
     * 正确的形状是：<b>某个高度带最浓，往上变稀、往下消失</b>。
     * <p>
     * 本曲线的三段（全部以环境温度 { 用温度而不是裸 Y，好处是以后改引擎的
     * {@link EngineCoreBlockEntity#T_AMB_*} 曲线时，尾迹带会自动跟着平移 }）：
     * <pre>
     * 上升段  s_rise = smoothstep01((T_START − T) / (T_START − T_FULL))
     * 变稀段  s_fade = smoothstep01((T_FADE_START − T) / (T_FADE_START − T_FADE_END))
     * s = s_rise × (1 − (1 − S_CEILING) · s_fade)        // 峰值 = 1.0，世界顶 = S_CEILING
     * </pre>
     * 落点（主世界，Y ↔ 温度见 {@code T_AMB_*} 分段线性）：
     * <table border="1">
     * <tr><th>节点</th><th>温度</th><th>Y</th><th>作用</th></tr>
     * <tr><td>{@code TRAIL_T_START}</td><td>+10 °C</td><td>≈132</td><td>上升段起点（实际被下面的 Y 硬门挡住）</td></tr>
     * <tr><td>{@code TRAIL_Y_MIN}</td><td>+5.8 °C</td><td><b>160</b></td><td><b>硬门</b>：以下完全没有尾迹；一过 160 就有 s≈0.58（≈42 s 寿命）</td></tr>
     * <tr><td>{@code TRAIL_T_FULL}</td><td>+2.5 °C</td><td>≈183</td><td>上升段拉满 ⇒ 从云层下方开始就是最浓</td></tr>
     * <tr><td>{@code TRAIL_T_FADE_START}</td><td>−8 °C</td><td>≈224</td><td>峰带结束（云层 ~200 正好落在峰内），开始变稀</td></tr>
     * <tr><td>{@code TRAIL_T_FADE_END}</td><td>−40 °C</td><td>320</td><td>世界顶，只剩 {@code S_CEILING}</td></tr>
     * </table>
     * 用户要求"Y160 左右就要看见大概清晰的尾迹"：靠 <b>上升段陡 + Y 硬门</b> 实现 ——
     * 159 → 完全没有；160 → s=0.58（间距 2.1 格、寿命 42 s）；175 → s=0.94。
     */
    public static final float TRAIL_T_START = 10.0F;
    /** 上升段拉满的温度（≈Y183）；见 {@link #TRAIL_T_START} 的表格 */
    public static final float TRAIL_T_FULL = 2.5F;
    /** 高空变稀段的起点温度（≈Y224，云层 ~200 之上一小段）：越往上越干 ⇒ 尾迹越淡 */
    public static final float TRAIL_T_FADE_START = -8.0F;
    /** 变稀段终点温度（= 世界顶 −40 °C） */
    public static final float TRAIL_T_FADE_END = -40.0F;
    /** 世界顶（Y≈320）残余强度：不归零，避免爬到限高时尾迹"突然消失" */
    public static final float TRAIL_S_CEILING = 0.15F;

    /**
     * 尾迹的<b>高度硬门</b>（Y，现 160）：低于它完全不出尾迹（用户定稿：低空 Y&lt;160 没有尾迹）。
     * <p>
     * 硬门是"阶跃"而不是平滑过渡：因为用户要的是"160 左右就有清晰尾迹"，
     * 而平滑曲线做不到"0 和明显"在同一高度同时成立 —— 所以让它 160 以下为 0、一过 160 立刻 s≈0.58。
     */
    public static final double TRAIL_Y_MIN = 160.0D;

    /**
     * 尾迹的<b>发射间距</b>（格）：机体每飞这么远吐一颗（在适宜度 s=1 时）。
     * <p>
     * <b>为什么按移动距离而不是按 tick 发射</b>：现实里尾迹浓度由"单位航程排了多少水汽"决定，
     * 悬停不动的飞机不会在原地堆出一团云。按距离发射顺带解决性能问题（静止时零发射），
     * 也是用户定稿（悬停完全不出尾迹）。
     * <p>
     * 温度不够冷时间距被放大：{@code 实际间距 = trailSpacing() / max(s, TRAIL_SPACING_S_FLOOR)}。
     */
    private static double trailSpacing() {
        return Config.CONTRAIL_SPACING.get();
    }

    /**
     * 计算间距时给适宜度 s 的<b>下限</b>（现 0.35）。
     * <p>
     * 原来的公式是 {@code trailSpacing() / s}：Y≈200 处 s≈0.1 ⇒ <b>12 格才吐一颗</b>，
     * 视觉上就是"断断续续、分布很散"。但"不够冷"本来就该靠<b>寿命/透明度</b>体现，
     * 不该靠抽走粒子（粒子少了只会看起来断线，而不是变淡）。
     * 加下限后密度最多稀到 1/0.35 ≈ 2.9 倍（间距 ≤ 3.4 格），强度差异交给寿命。
     */
    public static final float TRAIL_SPACING_S_FLOOR = 0.35F;

    /**
     * 适宜度 s 决定尾迹寿命（tick）：s=0 用 MIN、s=1 用 MAX（<b>越冷越耐久</b>，对齐"过饱和"那条现实规律）。
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
     * 云层<b>以下</b>（Y 200 → {@link #TRAIL_Y_MIN}=160）飘荡距离的放大倍数上限（现 2.5）。
     * <p>
     * 物理：对流层的湍流/垂直对流随高度下降而增强（云就是这样长起来的），所以越低空，
     * 尾迹被搅得越散。线性映射：
     * <pre>
     * 系数(Y) = 1 + (本值 − 1) · (CLOUD_Y − Y) / (CLOUD_Y − TRAIL_Y_MIN)     // 200 → 1.0，160 → 本值
     * </pre>
     * 于是 Y≈160 处粒子能飘 2×2.5 = 5 格，Y≈200 以上只飘 2 格 —— 低空尾迹显得蓬松、云层以上紧实。
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

        tooltip.add(Component.literal("      ")
                .append(Component.translatable("tooltip.ccpe.thrust_nozzle.trail_dye_hint").withStyle(ChatFormatting.DARK_GRAY)));

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
        // 冲量 = 推力 × 气压 × substep 时长；rpm 带符号，所以 magnitude 也带符号
        final double pressure = this.airPressureAt(subLevel);
        final double magnitude = (REVERSE_SIGN ? -1.0D : 1.0D) * THRUST_PER_RPM * speed * pressure * timeStep;

        // 力向量与作用点都必须每次新建：QueuedForceGroup.applyImpulseAtPoint 会就地改写传入的
        // position（减去质心求力臂），且 recordPointForce 保留引用（FlightDataRecorder 遥测会读），
        // 复用临时向量会污染已记录的点力。
        final Vector3d impulse = new Vector3d(-normal.getX(), -normal.getY(), -normal.getZ()).mul(magnitude);
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
        final Vec3i normal = facing.getNormal();
        final Vec3 axis = new Vec3(normal.getX(), normal.getY(), normal.getZ());

        // 排气方向 = 机体受力方向的反向：
        // 受力 = -FACING · (REVERSE_SIGN ? -1 : 1) · 转速 ⇒ 排气 = +FACING · 同一个符号
        double flowSign = (REVERSE_SIGN ? -1.0D : 1.0D) * (speed > 0.0F ? 1.0D : -1.0D);

        if (INVERT_PARTICLE_FLOW) {
            flowSign = -flowSign;
        }

        final Vec3 center = Vec3.atCenterOf(this.worldPosition);
        final Vec3 flowDir = axis.scale(flowSign);
        final Vec3 mouth = center.add(axis.scale(0.55D));

        // 本方块所在物理体：粒子坐标必须用它的姿态从 plot 局部系投到世界系（见下方 projectToWorld）
        final SubLevel own = Sable.HELPER.getContaining(this);

        // 高空尾迹：与排气<b>互相独立</b>的一条链路（只在冷空气里有、按位移发射、不做碰撞），见 tickContrail
        this.tickContrail(level, own, mouth, flowDir);

        // 密度随转速线性变化；不足一颗就不生成
        final float throttle = Math.min(Math.abs(speed) / 256.0F, 1.0F);
        final int count = (int) Math.round(exhaustParticlesPerTick() * throttle);

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
     * 坑位提醒（都是本 memo 踩过的）：
     * <ul>
     * <li>出生点必须走 {@link #projectToWorld}，否则粒子钉在机体坐标系里跟着车跑（§3.5）；</li>
     * <li>必须用 <b>9 参数</b> {@code addAlwaysVisibleParticle(..., ignoreRange = true)}，
     *     7 参数那个照样被原版 32 格限制丢掉（§3.7）；</li>
     * <li>瞬移（装配/传送/刚加载）时单 tick 位移极大，必须丢弃，否则会拖出一条笔直的假尾迹。</li>
     * </ul>
     */
    private void tickContrail(final Level level, final @Nullable SubLevel own,
                              final Vec3 mouth, final Vec3 flowDir) {
        if (!useContrail()) {
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

        final float s = contrailSuitability(level, mouthWorld.y);

        if (s <= 0.0F) {
            this.contrailAccum = 0.0D; // 不够冷 ⇒ 完全不出尾迹（也顺便把累积量清掉）
            return;
        }

        if (!this.withinParticleDistance(level, own, mouth, contrailMaxDistance())) {
            return; // 太远只累积不发射；下面的钳位保证不会攒出一大串
        }

        // 存活上限（安全阀）：粒子数 = 密度 × 寿命，长寿命必须有个总数兜底，否则半透明 quad 会堆爆
        if (contrailMaxLive() > 0 && ContrailBudget.live() >= contrailMaxLive()) {
            return;
        }

        // 温度不够冷 ⇒ 间距被放大（尾迹更稀），但有下限：s 太小只是"该淡、该短命"，
        // 不能靠抽走粒子来表达，否则中低空看起来就是"断断续续、分布很散"
        final double spacing = Math.max(0.01D,
                trailSpacing() / Math.max(s, Math.max(TRAIL_SPACING_S_FLOOR, 1.0E-4F)));
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

        final float width = trailWidth() * (0.75F + random.nextFloat() * 0.5F);
        // 染色：未染色时 trailRgb() 返回 TRAIL_RED/GREEN/BLUE 打包的默认色
        final int rgb = this.trailRgb();

        // 9 参数 overload（ignoreRange = true）：7 参数那个的第二个布尔是 decreased 不是 force，
        // 照样被原版 32 格限制丢掉 —— 见 exhaustMaxDistance() 的注释与 memo §3.7。
        level.addAlwaysVisibleParticle(
                new ContrailParticleData(width, lifetime,
                        dyeChannel(rgb, 16), dyeChannel(rgb, 8), dyeChannel(rgb, 0)),
                true,
                start.x, start.y, start.z,
                vx, vy, vz);
    }

    /**
     * 飘荡距离的<b>高度湍流系数</b>（≥1）：云层（{@link #TRAIL_TURB_CLOUD_Y}=200）及以上为 1.0，
     * 往下线性放大到 {@link #TRAIL_Y_MIN}=160 处的 {@link #TRAIL_TURB_LOW_GAIN}。
     * <p>
     * 物理依据：对流层越往下湍流/垂直对流越强（云本身就是这么长出来的），
     * 所以低空的尾迹更容易被搅散；再往上（云层以上）没有观测依据支持继续变化，就保持 1.0 ——
     * 高空"变淡"那件事已经由 {@link #contrailSuitability} 的变稀段负责了，两件事不要混在一起。
     */
    public static double trailTurbulence(final double y) {
        if (TRAIL_TURB_LOW_GAIN <= 1.0D || y >= TRAIL_TURB_CLOUD_Y) {
            return 1.0D;
        }

        final double span = TRAIL_TURB_CLOUD_Y - TRAIL_Y_MIN;

        if (span <= 0.0D) {
            return 1.0D;
        }

        final double t = Mth.clamp((TRAIL_TURB_CLOUD_Y - y) / span, 0.0D, 1.0D);
        return 1.0D + (TRAIL_TURB_LOW_GAIN - 1.0D) * t;
    }

    /**
     * 尾迹适宜度 s（0~1，纯高度函数）：用户定稿"不计算湿度"，所以温度阈值就是唯一的判据。
     * <p>
     * <b>峰值曲线</b>（用户定稿，取代原来的单调爬升）：上升段 × 高空变稀段，
     * 三段节点与落点见 {@link #TRAIL_T_START} 上方的表格，Y160 另有硬门 {@link #TRAIL_Y_MIN}。
     * 温度 T 直接复用引擎那条"高度 → 环境温度"曲线（{@link EngineCoreBlockEntity#ambientTempAt}），
     * 所以以后改 {@code T_AMB_*} 时整条尾迹带会跟着平移，不用同步改两处。
     * <p>
     * 现实对应：真实判据是 Schmidt-Appleman（冷 + 湿）。冷随高度单调，湿是<b>先增后减</b>
     * （对流层顶最湿、平流层极干）⇒ 所以"越高越浓"是错的，尾迹本来就有一个最浓的高度带。
     * <p>
     * 注意<b>不要</b>用 {@code SensorSystemAPI.getPressureForEngine} 当强度：气压曲线是 1.5 → 0 单调，
     * 越高越小，和"有没有尾迹"不是一回事。
     */
    public static float contrailSuitability(final Level level, final double y) {
        // 硬门：Y < TRAIL_Y_MIN 完全不出（用户定稿的低空规则）
        if (y < TRAIL_Y_MIN) {
            return 0.0F;
        }

        final float temperature = EngineCoreBlockEntity.ambientTempAt(level, (float) y);

        // 上升段：温度够冷才开始（s_rise 0 → 1）
        final float riseSpan = TRAIL_T_START - TRAIL_T_FULL;
        final float rise = riseSpan <= 0.0F
                ? 1.0F
                : smoothstep01((TRAIL_T_START - temperature) / riseSpan);

        // 变稀段：越往上空气越干（s_fade 0 → 1），把峰值往下压到 S_CEILING
        final float fadeSpan = TRAIL_T_FADE_START - TRAIL_T_FADE_END;
        final float fade = fadeSpan <= 0.0F
                ? 0.0F
                : smoothstep01((TRAIL_T_FADE_START - temperature) / fadeSpan);

        return rise * (1.0F - (1.0F - TRAIL_S_CEILING) * fade);
    }

    /** 0~1 的 smoothstep（超出范围自动钳位） */
    private static float smoothstep01(final float value) {
        final float t = Mth.clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
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
