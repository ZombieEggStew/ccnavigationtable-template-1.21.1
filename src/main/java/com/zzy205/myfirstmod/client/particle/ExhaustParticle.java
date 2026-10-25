package com.zzy205.myfirstmod.client.particle;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.block.ThrustNozzleBlockEntity;
import com.zzy205.myfirstmod.particle.ExhaustParticleData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 定向喷管的排气粒子（{@code ccpe:exhaust}）：自带速度积分 + <b>扫掠碰撞</b> +
 * <b>撞墙向四周扩散</b>，视觉上像火箭排气冲刷地面/墙面。
 * <p>
 * 与 {@code create:air}（{@code AirParticle}）的区别：
 * <ul>
 * <li>{@code create:air} 是"起点→终点插值"的纯视觉粒子（{@code hasPhysics = false}，永不碰撞），
 *     而且被 Sable 的 {@code AirParticleMixin} 每 tick 重投影，行为不完全可控；</li>
 * <li>本粒子自己积分速度、自己做碰撞，用的是世界坐标（喷管在 {@code tickClient} 里已经投过），
 *     所以既不会被车身"带着跑"，也能真的撞到方块。</li>
 * </ul>
 * 参考来源（只读）：{@code references/create-propulsion-simulated-main/.../particles/}
 * <ul>
 * <li>{@code ThrusterParticle#move/collide} —— 扫掠碰撞 + 撞墙后"法向只留 10%、切向按入射角保留、
 *     再加一圈随机方位散射"的火箭冲刷公式；</li>
 * <li>{@code smoke/ThrusterSmokeParticle#tick} —— 淡入淡出（smoothstep）、随寿命膨胀、
 *     水平 0.935 / 垂直 0.965 阻尼 + 微小浮力 + 正弦摆动。</li>
 * </ul>
 * 与参考实现的差异：参考实现用 Sable 的 "tracking sublevel" 让粒子先跟着机体走再脱钩，
 * 我们用纯世界坐标（喷管粒子的需求本来就是"喷出来就留在世界上"），逻辑少一半。
 */
public class ExhaustParticle extends SimpleAnimatedParticle {

    // ── 视觉常量（对应参考实现的 ThrusterSmokeParticle，可按观感调） ──

    /**
     * 不透明度上限：<b>出生时</b>最不透明的值，之后随寿命单调变透明、死亡时归零。
     * <p>
     * 比旧值（0.46）高，是因为再也没有"中间一大段恒定 0.46"的平台期了 —— 整条寿命都在衰减，
     * 平均不透明度更低，上限得抬上来才不至于整片排气看不见。
     */
    private static final float ALPHA_MAX = 0.62F;
    /** 寿命最前面这一小段做淡入（避免出生瞬间硬切出一颗实心方块） */
    private static final float FADE_IN_END = 0.08F;
    /**
     * 透明度衰减曲线：{@code (1 - life)^ALPHA_FALLOFF}。
     * 1 = 线性（越老越透明，最直观）；&gt;1 = 前半段偏浓、后段快速消失；&lt;1 = 一直偏淡。
     */
    private static final float ALPHA_FALLOFF = 1.0F;

    /**
     * 尺寸曲线：出生 = {@code baseScale × SIZE_START}，死亡 = {@code baseScale × (SIZE_START + SIZE_GROWTH)}。
     * 线性随寿命变大（0.35 → 1.50，约 4.3 倍）。
     */
    private static final float SIZE_START = 0.35F;
    private static final float SIZE_GROWTH = 1.15F;

    /** 阻尼：水平 0.935（快减速、铺开），垂直 0.965（比水平略慢，像热气流） */
    private static final double HORIZONTAL_FRICTION = 0.935D;
    private static final double VERTICAL_FRICTION = 0.965D;
    /**
     * 随寿命追加的减速比例：每 tick 的阻尼再乘 {@code (1 − SPEED_SLOW × life)}。
     * <p>
     * 光靠固定阻尼时，年轻/年老粒子的每 tick 减速率一样，看上去只是"整体偏快"；
     * 乘上这一项才是"越老越慢"。1.0 = 死亡时每 tick 额外损失 100%（几乎停住）。
     */
    private static final double SPEED_SLOW = 0.28D;

    /** 浮力：基础 + 随寿命增长的额外量（热气体上升） */
    private static final double BUOYANCY = 0.0008D;
    private static final double BUOYANCY_GROWTH = 0.0009D;

    /** 正弦摆动幅度：让排气看起来在翻滚而不是笔直飞 */
    private static final double SWIRL = 0.0018D;

    /** 撞墙时把粒子从接触面推开一点点，避免下一 tick 立刻又判成碰撞 */
    private static final double CONTACT_EPSILON = 1.0E-3D;

    /**
     * 出生点保护半径（块）：离出生点这么近的命中一律忽略。
     * <p>
     * 只保护喷口周围一小圈就够（粒子出生位置离喷管方块面很近，不保护会一出膛就被自己方块的
     * 膨胀包围盒判成碰撞）。<b>不能</b>像早期版本那样"整个喷管所在物理体都不算" ——
     * 那会让车体/舱壁自己的方块也挡不住粒子，出现"大量粒子穿到墙后面"（用户实测反馈）。
     */
    private static final double SPAWN_GRACE_RADIUS = 0.75D;

    /**
     * 是否让粒子忽略世界光照（直接按全亮渲染）。
     * <p>DEBUG_TINT_BY_GEN 
     * 粒子默认吃 lightmap：站在火把/岩浆/熔炉这些<b>暖色光源</b>旁边，白光烟团会被光图染成
     * 黄/橙色（"怎么出现了黄黄的粒子"）。置 {@code true} 就按 {@code 0xF000F0}（天空 15 + 方块 15）
     * 全亮渲染，颜色只由 {@code EXHAUST_RED/GREEN/BLUE} 决定。
     */
    private static final boolean IGNORE_LIGHTMAP = true;

    /** 全亮的光照值：天空光 15 | 方块光 15 */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * <b>临时调试开关</b>：按分裂代数强行上色（0 代 = 红、1 代 = 绿），用来判断"黄色粒子到底是不是我们喷的"。
     * 打开后如果撞墙后仍有<b>黄色</b>粒子，那它就不来自本模组。正常游玩请保持 false。
     */
    private static final boolean DEBUG_TINT_BY_GEN = false;

    // ── 临时调试：定位"喷口斜着喷时粒子穿过平面漏到下面"（用户实测：90° 正常，越小越糟） ──
    //   · 抽样跟踪：每 DEBUG_SAMPLE_EVERY 颗 0 代粒子里挑 1 颗，逐 tick 打位置/速度/半径/扫掠结果
    //   · 穿墙事件：粒子中心【从空气进入实心方块】时必打一行（起点在实心方块内的不算，避免喷管自身刷屏）
    //   · 读到结论后把 DEBUG_TRACE 改回 false（或整块删掉）即可，日志量有预算上限，不会爆日志
    private static final boolean DEBUG_TRACE = true;
    private static final int DEBUG_SAMPLE_EVERY = 60;
    private static final int DEBUG_TRACE_LINE_BUDGET = 6000;
    private static final int DEBUG_ANOMALY_LINE_BUDGET = 120;
    private static int debugSpawnCounter;
    private static int debugTraceLineBudget = DEBUG_TRACE_LINE_BUDGET;
    private static int debugAnomalyLineBudget = DEBUG_ANOMALY_LINE_BUDGET;

    // ── 撞墙反应（纯反弹 + 铺开，不涉及任何重力） ──

    /** 法向反弹系数：入射法向速度的 10% 反向弹回（0 = 完全不弹、贴着表面滑；1 = 完全弹性） */
    private static final double BOUNCE = 0.1D;
    /** 切向保留：入射切向速度的 90% 继续沿表面滑 */
    private static final double TANGENT_KEEP = 0.9D;
    /** 随机溅射：散射速度 = 入射法向速度 × 本系数（调大会变成"炸开"，调小则更贴面滑行） */
    private static final double SPREAD_KEEP = 0.8D;
    /**
     * 散射的**扇形半角**（弧度）。π = 整圈随机（不限制方向，现行）；
     * 想只往切向前方扇出去就调小（例如 0.96 ≈ 55°）。
     */
    private static final double SPREAD_ANGLE = Math.PI;
    /**
     * 碰撞冷却（tick）：一次有效碰撞后的这段时间内，再贴到表面只做"贴着滑"，<b>不再随机改方向</b>。
     * <p>
     * 这是"撞完像炸开、四面八方乱飞"的真正原因：粒子贴着地面/墙面时几乎每 tick 都会再次命中，
     * 若每次都重新随机一次方向，它就会随机游走成均匀四散。加了冷却就变成稳定的沿面滑行。
     */
    private static final int COLLISION_COOLDOWN = 3;
    /** 撞后整体速度保留比例（撞一下掉 20% 动能感，避免越弹越快） */
    private static final double ENERGY_KEEP = 0.8D;

    // ── 撞地变大 / 分裂 ──

    /** 撞到东西就变大（烟团拍在地面上摊开） */
    private static final boolean GROW_ON_IMPACT = true;
    /** 每次有效碰撞把尺寸乘这个系数 */
    private static final float IMPACT_GROWTH = 2.0F;
    /** 撞地膨胀上限倍数（防止一路弹一路涨） */
    private static final float MAX_IMPACT_GROWTH = 4.0F;

    /**
     * 撞到东西就分裂出小粒子（尾焰拍地溅开）。
     * <p>
     * <b>已关闭</b>（用户实测后不要这个效果）。分裂相关代码、ExhaustParticleData.gen 字段、
     * PENDING_SPLITS 队列与客户端 flush 都还在，想再开把这里改回 true 即可，不用重写。
     */
    private static final boolean SPLIT_ON_IMPACT = false;
    /** 每颗粒子一生只分裂一次（避免贴地状态每 tick 都分裂） */
    private static final int SPLIT_COUNT = 1;
    /** 最多分裂几代：0 = 喷管直喷，它的子粒子是 1 代，1 代不再分裂（防无限增殖） */
    private static final int MAX_SPLIT_GEN = 1;
    /** 子粒子尺寸 = 父粒子基准尺寸 × 本系数 */
    private static final float SPLIT_SCALE = 1.25F;
    /** 子粒子寿命 = 父粒子剩余寿命 × 本系数（最少 6 tick） */
    private static final double SPLIT_LIFETIME_KEEP =1.0D;
    /** 子粒子初速 = 父粒子当前速度 × (0.55~0.95) × 本系数 */
    private static final double SPLIT_SPEED_KEEP = 0.9D;
    /** 入射法向速度低于它就当"轻轻蹭了一下"，不触发变大/分裂 */
    private static final double MIN_SPLIT_IMPACT = 0.12D;
    /** 待生成队列上限（极端情况下防止堆积） */
    private static final int MAX_QUEUED_SPLITS = 512;

    /**
     * 待生成的分裂粒子队列。
     * <p>
     * 为什么不在 {@code move()} 里直接 {@code level.addParticle}：那是在
     * {@code ParticleEngine#tick} 遍历粒子列表的过程中往列表里加元素，属于典型的并发修改风险。
     * 这里先入队，再由客户端 {@code ClientTickEvent.Post} 统一 flush（见 {@link #flushPendingSplits()}），
     * 延迟 1 tick（50 ms），肉眼无感但绝对安全。
     */
    private static final List<PendingSplit> PENDING_SPLITS = new ArrayList<>();

    /**
     * 碰撞半径 = quadSize × 本系数（再夹到 {@link #MIN_COLLISION_RADIUS}~{@link #MAX_COLLISION_RADIUS}）。
     * <p>
     * 不能直接用 quadSize/2：烟团看着大，但视觉尺寸最大到 ~2.6 格，用半边长当半径会让粒子在离墙
     * 1 格多的地方就"撞住"，看起来像撞了空气墙。参考实现也一样把碰撞半径和视觉尺寸分开
     * （它用 {@code setSize(0.2 × scale)} 单独维护包围盒）。
     */
    private static final double COLLISION_RADIUS_FACTOR = 0.2D;
    private static final double MIN_COLLISION_RADIUS = 0.08D;
    private static final double MAX_COLLISION_RADIUS = 0.35D;

    private final SpriteSet sprites;
    private final float baseScale;
    private final UUID ownerId;
    private final double swirlPhase;
    /** 分裂代数（0 = 喷管喷出；>=1 = 撞墙分裂出来的子粒子） */
    private final int gen;
    /** 撞地膨胀倍数（初始 1，每次有效碰撞 × {@link #IMPACT_GROWTH}，上限 {@link #MAX_IMPACT_GROWTH}） */
    private float impactGrowth = 1.0F;
    /** 是否已经分裂过（一颗只分裂一次） */
    private boolean hasSplit;
    /** 出生点（世界系），用于 {@link #SPAWN_GRACE_RADIUS} 出生点保护 */
    private final Vec3 spawnCenter;
    /** 上一次"有效碰撞（会随机改方向）"发生时的 age，用于 {@link #COLLISION_COOLDOWN} */
    private int lastImpactAge = -100;
    /** 临时调试：本颗粒子是否被抽样跟踪，以及它的编号（只在 {@link #DEBUG_TRACE} 时有意义） */
    private final boolean debugTrace;
    private final int debugIndex;

    protected ExhaustParticle(final ClientLevel level, final double x, final double y, final double z,
                             final double xd, final double yd, final double zd,
                             final ExhaustParticleData data, final SpriteSet sprites) {
        super(level, x, y, z, sprites, 0.0F);
        this.sprites = sprites;
        this.baseScale = Math.max(0.01F, data.scale());
        this.ownerId = data.ownerId();
        this.gen = data.gen();
        this.spawnCenter = new Vec3(x, y, z);
        this.swirlPhase = this.random.nextDouble() * 40.0D;
        this.debugIndex = debugSpawnCounter++;
        this.debugTrace = DEBUG_TRACE && data.gen() == 0 && this.debugIndex % DEBUG_SAMPLE_EVERY == 0;

        if (this.debugTrace) {
            dbg("#%d SPAWN pos=(%.3f,%.3f,%.3f) vel=(%.3f,%.3f,%.3f) life=%d scale=%.3f",
                    this.debugIndex, x, y, z, xd, yd, zd, data.lifetime(), data.scale());
        }

        this.xd = xd;
        this.yd = yd;
        this.zd = zd;

        this.lifetime = Math.max(2, data.lifetime());
        this.quadSize = this.baseScale * (SIZE_START + this.random.nextFloat() * 0.22F);

        final float tint = 0.88F + this.random.nextFloat() * 0.16F;

        if (DEBUG_TINT_BY_GEN) {
            // 调试：0 代染红、1 代染绿，用来排除"黄色粒子是不是本模组喷的"
            this.rCol = this.gen == 0 ? 1.0F : 0.2F;
            this.gCol = this.gen == 0 ? 0.2F : 1.0F;
            this.bCol = 0.2F;
        } else {
            this.rCol = data.r() * tint;
            this.gCol = data.g() * tint;
            this.bCol = data.b() * tint;
        }

        this.alpha = 0.0F;
        // 无重力：gravity = 0，且 tick 里没有任何向下加速度（只有微小的向上浮力 BUOYANCY）。
        // 烟团怎么飞完全由出生速度 + 阻尼 + 撞墙反弹决定。
        this.gravity = 0.0F;
        this.friction = 1.0F;
        // 碰撞完全由下面的 move() 自己处理，别让原版 Particle#move 再算一遍
        this.hasPhysics = false;

        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        final float life = this.age / (float) this.lifetime;

        // 透明度：短淡入之后随寿命 单调变透明（旧版是 10%~75% 恒定 0.46 再突然淡出，
        // 中间那一大段完全看不出"存在越久越淡"）。
        this.alpha = ALPHA_MAX
                * smoothstep(0.0F, FADE_IN_END, life)
                * (float) Math.pow(1.0F - life, ALPHA_FALLOFF);
        // 尺寸：随寿命 单调变大
        this.quadSize = this.baseScale * (SIZE_START + life * SIZE_GROWTH) * this.impactGrowth;

        final double swirl = Math.sin((this.age + this.swirlPhase) * 0.13D) * SWIRL;
        this.xd += swirl;
        this.zd -= swirl;
        this.yd += BUOYANCY + life * BUOYANCY_GROWTH;

        this.move(this.xd, this.yd, this.zd);

        // 速度：越老越慢 —— 固定阻尼之外，再按寿命追加减速（SPEED_SLOW）
        final double ageSlow = 1.0D - SPEED_SLOW * life;

        this.xd *= HORIZONTAL_FRICTION * ageSlow;
        this.yd *= VERTICAL_FRICTION * ageSlow;
        this.zd *= HORIZONTAL_FRICTION * ageSlow;

        this.setSpriteFromAge(this.sprites);
    }

    /**
     * 自己实现移动：先做扫掠碰撞，撞到就把位移截到接触面、并让速度"沿表面铺开 + 随机溅射"，
     * 再按结果移动。位移与包围盒都保持在世界系。
     * <p>
     * 注意：{@code particle} 的 x/y/z 是粒子中心；扫掠半径见 {@link #COLLISION_RADIUS_FACTOR}
     * （视觉尺寸与碰撞半径是分开的）。
     */
    @Override
    public void move(final double dx, final double dy, final double dz) {
        Vec3 movement = new Vec3(dx, dy, dz);
        double impact = 0.0D;
        Vec3 hitNormal = null;
        // 只为调试日志保留；逻辑与原来完全一致
        ExhaustCollision.Hit hit = null;

        if (movement.lengthSqr() > 1.0E-8D) {
            final double radius = Mth.clamp(this.quadSize * COLLISION_RADIUS_FACTOR,
                    MIN_COLLISION_RADIUS, MAX_COLLISION_RADIUS);
            hit = ExhaustCollision.sweep(this.level,
                    new Vec3(this.x, this.y, this.z), movement, radius,
                    this.spawnCenter, SPAWN_GRACE_RADIUS,
                    ThrustNozzleBlockEntity.PARTICLES_BLOCKED_BY_OTHER_BODIES);

            if (hit != null) {
                // push > 0 ⇒ 起点本来就嵌在碰撞壳层里（见 ExhaustCollision#slab 的①分支）：
                // 除了沿法线抬一点点，还要把"嵌进去的深度"整体推出来，
                // 否则粒子会一直留在壳层里、后续每 tick 都拿不到有效碰撞（这就是斜喷穿墙的根因）。
                movement = hit.movement().add(hit.normal().scale(hit.push() + CONTACT_EPSILON));

                if (this.age - this.lastImpactAge > COLLISION_COOLDOWN) {
                    impact = this.scatter(hit.normal());
                    hitNormal = hit.normal();
                    this.lastImpactAge = this.age;
                } else {
                    // 冷却中：只把朝表面里的速度分量去掉，保持沿面滑行（不再随机换方向）
                    this.slide(hit.normal());
                }
            }
        }

        final double half = Math.max(0.05D, this.quadSize * 0.5D);
        final double nx = this.x + movement.x;
        final double ny = this.y + movement.y;
        final double nz = this.z + movement.z;

        if (DEBUG_TRACE) {
            this.debugSweep(nx, ny, nz, movement, hit);
        }

        // ⚠️⚠️ 这里**绝对不能**调用 {@code Particle#setLocationFromBoundingbox()}（踩过大坑）：
        //     1.21.1 的实现在 Particle.java:223-228 是
        //         this.x = (aabb.minX + aabb.maxX) / 2.0;
        //         this.y = aabb.minY;                    // ← 原版把位置 y 当"包围盒底边"
        //         this.z = (aabb.minZ + aabb.maxZ) / 2.0;
        //     （见 Particle#setPos：minY = y、maxY = y + bbHeight）
        //     而本粒子把 y 当**中心**用（渲染 quad 与扫掠碰撞都用中心）。所以先建一个
        //     "以 ny 为中心"的对称盒再调它，等于每 tick 把 y 往下挪 quadSize/2 格，
        //     而且这段下坠**完全不在扫掠的 displacement 里** ⇒ 静默穿墙。
        //     实测后果：出生时每 tick 沉 0.05，撞一下变大后翻倍，寿命末期能到 0.5 格/tick。
        //     90° 时靠反弹的上抛分量（~0.3-0.45/tick）压得住；角度越平法向分量越小（~0.05-0.09）
        //     ⇒ 净位移朝下 ⇒ 粒子沉穿平面。**这就是"斜喷漏粒子"的真正根因。**
        //     正确做法：直接写 x/y/z（核心位置），包围盒只用于剔除，围绕中心对称即可。
        this.setBoundingBox(new AABB(nx - half, ny - half, nz - half, nx + half, ny + half, nz + half));
        this.x = nx;
        this.y = ny;
        this.z = nz;

        if (impact > 0.0D && hitNormal != null) {
            this.onImpact(impact, hitNormal);
        }
    }

    // ── 临时调试（定位"斜喷穿地"，读完即可删除） ──

    private static void dbg(final String fmt, final Object... args) {
        CCPeripheralExtender.LOGGER.info("[EXHDBG] " + String.format(Locale.ROOT, fmt, args));
    }

    /** 该方块是否有实体碰撞形状（与扫掠碰撞的判据一致：只看 collision shape） */
    private static boolean isSolidAt(final ClientLevel level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty();
    }

    /**
     * 打一条本 tick 的扫掠轨迹；另外在"粒子中心从空气进入实心方块"时打一条<b>穿墙事件</b>。
     * <p>
     * 法线是不是轴对齐的很重要：主世界方块的碰撞面法线只可能是 ±X/±Y/±Z，
     * 出现非轴对齐法线就说明这次命中来自某个<b>被旋转的物理体</b>（路径②）。
     */
    private void debugSweep(final double nx, final double ny, final double nz,
                            final Vec3 movement, @Nullable final ExhaustCollision.Hit hit) {
        // 注意：这里必须带 this.debugTrace —— 上一版漏了它，结果每颗粒子每 tick 都打，
        // 6000 行预算 0.7 秒就烧光了（"抽样"等于没生效）。
        if (this.debugTrace && debugTraceLineBudget > 0) {
            debugTraceLineBudget--;
            dbg("#%d t=%d pos=(%.2f,%.2f,%.2f) v=(%.2f,%.2f,%.2f) qs=%.3f grow=%.1f mv=(%.2f,%.2f,%.2f) -> %s",
                    this.debugIndex, this.age, this.x, this.y, this.z,
                    this.xd, this.yd, this.zd, this.quadSize, this.impactGrowth,
                    movement.x, movement.y, movement.z,
                    hit == null ? "MISS" : String.format(Locale.ROOT, "HIT f=%.3f n=(%.2f,%.2f,%.2f) push=%.3f",
                            hit.fraction(), hit.normal().x, hit.normal().y, hit.normal().z, hit.push()));
        }

        if (debugAnomalyLineBudget > 0
                && !isSolidAt(this.level, BlockPos.containing(this.x, this.y, this.z))
                && isSolidAt(this.level, BlockPos.containing(nx, ny, nz))) {
            debugAnomalyLineBudget--;
            final BlockPos end = BlockPos.containing(nx, ny, nz);
            dbg("!! 穿墙 #%d t=%d (%.3f,%.3f,%.3f) -> (%.3f,%.3f,%.3f) 终点方块=%s 本tick=%s v=(%.2f,%.2f,%.2f) qs=%.3f grow=%.1f",
                    this.debugIndex, this.age, this.x, this.y, this.z, nx, ny, nz,
                    this.level.getBlockState(end),
                    hit == null ? "MISS" : String.format(Locale.ROOT, "HIT f=%.3f n=(%.2f,%.2f,%.2f) push=%.3f",
                            hit.fraction(), hit.normal().x, hit.normal().y, hit.normal().z, hit.push()),
                    this.xd, this.yd, this.zd, this.quadSize, this.impactGrowth);
        }
    }

    /**
     * 撞墙后的速度处理：<b>法向反弹</b>（{@link #BOUNCE}）+ <b>切向滑移</b>（{@link #TANGENT_KEEP}）
     * + 在"法线垂面"上随机方位的<b>溅射</b>（{@link #SPREAD_KEEP}），最后整体按
     * {@link #ENERGY_KEEP} 保速。
     * <p>
     * 和参考实现 {@code ThrusterParticle#collide}（法向只留 10%、几乎不弹）的区别：这里法向是
     * 反向<b>弹回</b>的，所以正面砸地面/墙面能明显看到粒子被弹开并四散，而不是"贴着表面继续下坠"。
     * <p>
     * 结果的法向分量恒为正（溅射项与切向项都在法线垂面上，只有反弹项带法向分量），
     * 所以粒子撞完一定会离开表面，不会陷进去。
     *
     * @return 入射法向速度（正数）；没有有效碰撞时为 0，用于驱动撞地变大/分裂
     */
    private double scatter(final Vec3 normal) {
        final Vec3 velocity = new Vec3(this.xd, this.yd, this.zd);
        final double dot = velocity.dot(normal);

        if (dot >= 0.0D) {
            return 0.0D; // 已经在离开表面
        }

        final double speed = velocity.length();

        if (speed < 1.0E-6D) {
            return 0.0D;
        }

        final double impact = -dot; // 法向入射速度（正数）
        final Vec3 rebound = normal.scale(impact * BOUNCE);
        final Vec3 tangent = velocity.subtract(normal.scale(dot)).scale(TANGENT_KEEP);

        // 散射中心 = 本粒子的切向前进方向（不是随机方位），只在 ±SPREAD_ANGLE 的扇形内偏
        final Vec3 tangentDir = this.tangentDirection(normal, velocity);
        final Vec3 sideDir = normal.cross(tangentDir).normalize();
        final double angle = (this.random.nextDouble() * 2.0D - 1.0D) * SPREAD_ANGLE;
        final Vec3 spread = tangentDir.scale(Math.cos(angle)).add(sideDir.scale(Math.sin(angle)))
                .scale(impact * SPREAD_KEEP);

        final Vec3 result = rebound.add(tangent).add(spread);
        final double resultLength = result.length();

        if (resultLength < 1.0E-6D) {
            return 0.0D;
        }

        final double newSpeed = Math.max(0.02D, speed * ENERGY_KEEP);
        final Vec3 unit = result.scale(1.0D / resultLength);

        this.xd = unit.x * newSpeed;
        this.yd = unit.y * newSpeed;
        this.zd = unit.z * newSpeed;

        return impact;
    }

    /**
     * 冷却期内的贴面滑行：只消掉朝表面里的速度分量（多消 5% 轻微推开），方向不再随机变化。
     */
    private void slide(final Vec3 normal) {
        final double dot = this.xd * normal.x + this.yd * normal.y + this.zd * normal.z;

        if (dot < 0.0D) {
            this.xd -= normal.x * dot * 1.05D;
            this.yd -= normal.y * dot * 1.05D;
            this.zd -= normal.z * dot * 1.05D;
        }
    }

    /**
     * 取"速度在命中面法线垂面上的方向"（即切向前进方向）。速度几乎垂直于表面时退化，
     * 就随便取表面上一个方向，保证结果非零。
     */
    private Vec3 tangentDirection(final Vec3 normal, final Vec3 velocity) {
        Vec3 tangent = velocity.subtract(normal.scale(velocity.dot(normal)));

        if (tangent.lengthSqr() < 1.0E-6D) {
            final Vec3 seed = Math.abs(normal.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            tangent = seed.cross(normal);
        }

        return tangent.normalize();
    }

    /**
     * 撞到东西之后的额外反应：
     * <ul>
     * <li><b>变大</b>（{@link #GROW_ON_IMPACT}）：尺寸 × {@link #IMPACT_GROWTH}，上限 {@link #MAX_IMPACT_GROWTH}；</li>
     * <li><b>分裂</b>（{@link #SPLIT_ON_IMPACT}）：在接触点附近生成 {@link #SPLIT_COUNT} 颗更小、
     *     更短命、<b>只在切向随机偏转</b>的子粒子（代数 +1，最多 {@link #MAX_SPLIT_GEN} 代）。</li>
     * </ul>
     * 入射太轻（< {@link #MIN_SPLIT_IMPACT}）就当"蹭了一下"，不触发 —— 否则贴着地面滑行的粒子
     * 会每 tick 都涨一次尺寸、炸一次子粒子。
     *
     * @param normal 命中面法线（子粒子只在它的垂面内偏转，不带法向分量）
     */
    private void onImpact(final double impact, final Vec3 normal) {
        if (impact < MIN_SPLIT_IMPACT) {
            return;
        }

        if (GROW_ON_IMPACT) {
            this.impactGrowth = Math.min(MAX_IMPACT_GROWTH, this.impactGrowth * IMPACT_GROWTH);
        }

        if (!SPLIT_ON_IMPACT || this.hasSplit || this.gen >= MAX_SPLIT_GEN) {
            return;
        }

        this.hasSplit = true;

        if (PENDING_SPLITS.size() >= MAX_QUEUED_SPLITS) {
            return;
        }

        final Vec3 velocity = new Vec3(this.xd, this.yd, this.zd);
        final double speed = velocity.length();

        if (speed < 1.0E-6D) {
            return;
        }

        final int remainingLifetime = Math.max(6, (int) ((this.lifetime - this.age) * SPLIT_LIFETIME_KEEP));
        final float childScale = Math.max(0.15F, this.baseScale * SPLIT_SCALE);

        // 切向基准方向：把父粒子方向投到法线垂面上
        Vec3 tangent = velocity.subtract(normal.scale(velocity.dot(normal)));

        if (tangent.lengthSqr() < 1.0E-6D) {
            // 父方向几乎垂直于表面（正面砸地）⇒ 随便取表面上一个方向
            final Vec3 seed = Math.abs(normal.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            tangent = seed.cross(normal);
        }

        tangent = tangent.normalize();
        // 垂面上的正交基：绕法线任意角度旋转切向方向，就能得到表面上的一整圈方向
        final Vec3 side = normal.cross(tangent).normalize();

        for (int i = 0; i < SPLIT_COUNT; i++) {
            // 只在切向随机偏转（不加法向分量），且限制在父粒子切向前方的扇形内
            final double angle = (this.random.nextDouble() * 2.0D - 1.0D) * SPREAD_ANGLE;
            final Vec3 childDir = tangent.scale(Math.cos(angle)).add(side.scale(Math.sin(angle))).normalize();
            final double childSpeed = speed * (0.55D + this.random.nextDouble() * 0.4D) * SPLIT_SPEED_KEEP;

            // 出生点：沿法线抬起一点（别埋在面里），横向小抖动仍留在切向上
            final double jitter = 0.06D;
            final Vec3 offset = tangent.scale((this.random.nextDouble() - 0.5D) * jitter)
                    .add(side.scale((this.random.nextDouble() - 0.5D) * jitter))
                    .add(normal.scale(0.02D));

            PENDING_SPLITS.add(new PendingSplit(this.level,
                    new ExhaustParticleData(childScale, remainingLifetime,
                            this.rCol, this.gCol, this.bCol, this.ownerId, this.gen + 1),
                    this.x + offset.x, this.y + offset.y, this.z + offset.z,
                    childDir, childSpeed));
        }
    }

    /**
     * 把本 tick 撞地产生的子粒子真正加进世界。
     * 由客户端 {@code ClientTickEvent.Post} 调用（注册见 {@code CCPeripheralExtenderClient}）——
     * 不能在 {@code ParticleEngine#tick} 遍历粒子列表的过程中 addParticle。
     */
    public static void flushPendingSplits() {
        if (PENDING_SPLITS.isEmpty()) {
            return;
        }

        final List<PendingSplit> batch = new ArrayList<>(PENDING_SPLITS);
        PENDING_SPLITS.clear();

        for (final PendingSplit split : batch) {
            split.spawn();
        }
    }

    /** 一颗待生成的子粒子（撞地分裂用） */
    private record PendingSplit(ClientLevel level, ExhaustParticleData data, double x, double y, double z,
                                Vec3 direction, double speed) {

        void spawn() {
            this.level.addParticle(this.data, this.x, this.y, this.z,
                    this.direction.x * this.speed,
                    this.direction.y * this.speed,
                    this.direction.z * this.speed);
        }
    }

    /**
     * 光照：{@link #IGNORE_LIGHTMAP} 为 true 时按全亮渲染，不受火把/岩浆等暖色光源染色
     * （否则白光烟团在暖色光源附近会看起来发黄）。
     */
    @Override
    public int getLightColor(final float partialTick) {
        return IGNORE_LIGHTMAP ? FULL_BRIGHT : super.getLightColor(partialTick);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    private static float smoothstep(final float edge0, final float edge1, final float value) {
        final float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public static class Provider implements ParticleProvider<ExhaustParticleData> {

        private final SpriteSet sprites;

        public Provider(final SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Nullable
        @Override
        public ExhaustParticle createParticle(final ExhaustParticleData data, final ClientLevel level,
                                              final double x, final double y, final double z,
                                              final double xd, final double yd, final double zd) {
            return new ExhaustParticle(level, x, y, z, xd, yd, zd, data, this.sprites);
        }
    }
}
