package com.zzy205.myfirstmod.client.particle;

import com.zzy205.myfirstmod.block.ThrustNozzleBlockEntity;
import com.zzy205.myfirstmod.particle.ContrailBudget;
import com.zzy205.myfirstmod.particle.ContrailParticleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 高空尾迹粒子（{@code ccpe:contrail}）：<b>冰晶云，不是烟</b>。
 * <p>
 * 与 {@link ExhaustParticle}（喷口排气）的区别，也是本类的三条硬约束：
 * <ul>
 * <li><b>不碰撞</b>：尾迹是空气里的冰晶，{@link ExhaustCollision} 那套扫掠<b>完全不用</b>
 *     （{@code hasPhysics = false}，自己直接 {@code setPos} 推进，不走 {@code move()}）；</li>
 * <li><b>无重力</b>：{@code gravity = 0}，只有极小的向上浮力；</li>
 * <li><b>原地随机飘逸</b>：用户明确不要风场平流 ⇒ 出生给一个很小的随机初速，之后只有"缓慢抖动的
 *     随机游走 + 速度钳位"，<b>整体平流 = 0</b>（不随风飘，但也绝不死沉不动，否则像贴纸）。</li>
 * </ul>
 * 形状与透明度<b>成对</b>演变（质量守恒，现实里"前段细亮、后段宽淡"就是这个）：
 * <pre>
 * 宽度 spread(t) = sqrt(1 + age / TRAIL_WIDTH_TAU)        // 湍流扩散的 sqrt(t) 律
 * quadSize       = baseWidth × spread(t)
 * alpha          = TRAIL_ALPHA0 / spread(t)              // 摊开多少倍就淡多少倍
 * </pre>
 * 两条曲线都直接读 {@code ThrustNozzleBlockEntity} 上的 {@code TRAIL_*} 常量，改那边即可热重载看效果
 * （旋钮集中在喷管里，方便和发射率/寿命一起调）。
 * <p>
 * 渲染：继承 {@link SimpleAnimatedParticle} 顺带拿到 {@code PARTICLE_SHEET_TRANSLUCENT} 与
 * 全亮 {@code getLightColor}（{@code 15728880} = 天空 15 + 方块 15）—— 高空本来就没光，
 * 冰晶也不该被暖色光源染黄。精灵图在<b>出生时随机定死一张</b>（不像排气那样逐帧播放），
 * 免得 1000 颗粒子同时跳帧。
 */
public class ContrailParticle extends SimpleAnimatedParticle {

    /**
     * 出生淡入长度（<b>固定 tick 数</b>，不是寿命的比例）。
     * <p>
     * 原来是 {@code 0.05 × 寿命}：寿命 300 时只有 15 tick（0.75 s，无感），
     * 但寿命调到 18000 后就变成 <b>900 tick = 45 秒</b> —— 这就是"要等一段才显现出来"的根因。
     * 改成固定 6 tick（0.3 s）后，无论寿命多长都是出生即显。
     */
    private static final int FADE_IN_TICKS = 6;
    /** 寿命末段淡出起点（寿命的比例）：从这条线开始淡到 0，避免到寿命硬切消失 */
    private static final float FADE_OUT_START = 0.80F;

    /** 每 tick 的随机抖动上限（块/tick）：这是"原地随机飘逸"的唯一驱动力 */
    private static final double JITTER = 0.0016D;
    /** 速度阻尼：把抖动积分出来的漂移速度往回收，避免长期朝一个方向跑偏 */
    private static final double DRIFT_DAMP = 0.985D;
    /** 极小的向上浮力（冰晶云整体略微升高、变松） */
    private static final double BUOYANCY = 0.0002D;
    /** 缓慢的正弦摆动幅度，让飘逸看起来像"缓缓翻滚"而不是纯白噪声 */
    private static final double SWIRL = 0.0006D;
    /** 正弦摆动的时间频率（tick⁻¹） */
    private static final double SWIRL_FREQ = 0.07D;

    /** 基准宽度（格，quad 的<b>半</b>边长）；之后只随 age 扩散、不随时间不变 */
    private final float baseWidth;
    /** 正弦摆动的初相位（让每颗粒子的摆动不同步） */
    private final double phase;
    /** 出生点（世界系）：用来把飘逸限制在 {@link ThrustNozzleBlockEntity#TRAIL_MAX_DRIFT_RADIUS} 半径内 */
    private final Vec3 spawnCenter;

    protected ContrailParticle(final ClientLevel level, final double x, final double y, final double z,
                               final double xd, final double yd, final double zd,
                               final ContrailParticleData data, final SpriteSet sprites) {
        super(level, x, y, z, sprites, 0.0F);

        this.baseWidth = Math.max(0.02F, data.width());
        this.phase = this.random.nextDouble() * 40.0D;
        this.spawnCenter = new Vec3(x, y, z);
        ContrailBudget.onSpawn();

        this.xd = xd;
        this.yd = yd;
        this.zd = zd;

        this.lifetime = Math.max(4, data.lifetime());
        this.quadSize = this.baseWidth;

        // 每颗粒子一点明暗差，整片尾迹才不像一块均匀的白板
        final float tint = 0.94F + this.random.nextFloat() * 0.06F;
        this.rCol = data.r() * tint;
        this.gCol = data.g() * tint;
        this.bCol = data.b() * tint;

        this.alpha = 0.0F;      // 出生透明，靠 tick 里的淡入拉起来
        this.gravity = 0.0F;    // 无重力
        this.friction = 1.0F;   // 阻尼自己算（按寿命/漂移钳位），不交给基类
        this.hasPhysics = false; // 绝不碰撞

        // 出生时随机定死一张精灵图（不是动画帧）
        this.setSprite(sprites.get(this.random));
    }

    /**
     * 每 tick：先按寿命更新"宽度 / 透明度"这一对曲线，再做原地随机飘逸，最后直接 {@code setPos} 推进。
     * <p>
     * 为什么不用 {@code move()}：{@code Particle#move} 会走 {@code Entity.collideBoundingBox}
     * （只要 {@code hasPhysics}）并做 y 方向的贴地判停；尾迹既不该被挡、也不该有"站住"的概念，
     * 直接 {@code setPos} 最干净（{@code setPos} 会顺带重建包围盒）。
     */
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

        // 扩散：sqrt(t) 律（湍流扩散的标准形式）
        final float spread = Mth.sqrt(1.0F
                + this.age / (float) Math.max(1, ThrustNozzleBlockEntity.TRAIL_WIDTH_TAU));

        // 尺寸与透明度成对：摊开多少倍就淡多少倍，再叠上淡入/淡出
        this.quadSize = this.baseWidth * spread;

        // 淡入按【tick】而不是寿命比例：长寿命时前 5% 生命几乎全透明（18000 tick ⇒ 45 秒），
        // 表现得像"尾迹要等半天才出现"。这里固定 FADE_IN_TICKS tick 内拉满。
        final float fadeIn = smoothstep(0.0F, 1.0F, this.age / (float) FADE_IN_TICKS);

        this.alpha = ThrustNozzleBlockEntity.TRAIL_ALPHA0 / spread
                * fadeIn
                * (1.0F - smoothstep(FADE_OUT_START, 1.0F, life));

        // 原地随机飘逸：抖动 + 缓慢正弦 + 极小浮力，然后把速度钳回 TRAIL_DRIFT 以内
        final double swirl = Math.sin((this.age + this.phase) * SWIRL_FREQ) * SWIRL;
        this.xd += (this.random.nextDouble() - 0.5D) * 2.0D * JITTER + swirl;
        this.yd += (this.random.nextDouble() - 0.5D) * 2.0D * JITTER + BUOYANCY;
        this.zd += (this.random.nextDouble() - 0.5D) * 2.0D * JITTER - swirl;

        this.xd *= DRIFT_DAMP;
        this.yd *= DRIFT_DAMP;
        this.zd *= DRIFT_DAMP;
        this.clampDrift();

        this.setPos(this.x + this.xd, this.y + this.yd, this.z + this.zd);
    }

    /**
     * 飘逸的两道约束，缺一不可：
     * <ol>
     * <li><b>速度上限</b>：漂移速度钳到 {@code TRAIL_DRIFT} 以内；</li>
     * <li><b>离出生点的半径上限</b>（{@code TRAIL_MAX_DRIFT_RADIUS}）：超出去就把"朝外"的速度分量消掉。</li>
     * </ol>
     * 第 2 条是关键：随机游走是<b>无界</b>的 —— 寿命 18000 tick、漂移 0.2 格/tick 时，单颗粒子能
     * 游走出几十格，整条尾迹就散成一团稀薄的云（用户实测"分布很散"）。加了半径约束后，
     * 无论寿命和漂移调多大，每颗粒子都只在出生点附近蠕动 ⇒ 尾迹始终是一条连贯的带子。
     */
    private void clampDrift() {
        final double max = Math.max(0.0D, ThrustNozzleBlockEntity.TRAIL_DRIFT);
        final double speed = Math.sqrt(this.xd * this.xd + this.yd * this.yd + this.zd * this.zd);

        if (speed > max && speed > 1.0E-9D) {
            final double k = max / speed;
            this.xd *= k;
            this.yd *= k;
            this.zd *= k;
        }

        final double radius = ThrustNozzleBlockEntity.TRAIL_MAX_DRIFT_RADIUS;

        if (radius <= 0.0D) {
            return;
        }

        final double dx = this.x - this.spawnCenter.x;
        final double dy = this.y - this.spawnCenter.y;
        final double dz = this.z - this.spawnCenter.z;
        final double dist2 = dx * dx + dy * dy + dz * dz;

        if (dist2 <= radius * radius) {
            return;
        }

        final double dist = Math.sqrt(dist2);
        final double nx = dx / dist;
        final double ny = dy / dist;
        final double nz = dz / dist;
        // 只去掉朝外的分量：仍然允许沿切向/切向内侧蠕动，看起来才像"缓缓翻滚"而不是钉死
        final double outward = this.xd * nx + this.yd * ny + this.zd * nz;

        if (outward > 0.0D) {
            this.xd -= nx * outward;
            this.yd -= ny * outward;
            this.zd -= nz * outward;
        }
    }

    /**
     * 死亡时归还预算配额（见 {@link ContrailBudget}）。
     * 带 {@code removed} 判断：{@code remove()} 可能被调用多次，重复归还会让计数失真。
     */
    @Override
    public void remove() {
        if (!this.removed) {
            ContrailBudget.onDeath();
        }

        super.remove();
    }

    /**
     * 客户端每 tick 的记账（由 {@code ClientTickEvent.Post} 调，注册见 {@code CCPeripheralExtenderClient}）：
     * 换维度/换世界时清空存活计数 —— 否则旧世界的粒子被直接丢弃不走 {@link #remove()}，
     * 计数会一直卡在上限，尾迹就永远不再生成。
     */
    public static void onClientTick() {
        final ClientLevel current = Minecraft.getInstance().level;

        if (current != lastLevel) {
            lastLevel = current;
            ContrailBudget.reset();
        }
    }

    /** 上次记账时的客户端世界（用来发现换维度/换世界）；只被上面的 onClientTick 读写 */
    private static ClientLevel lastLevel;

    private static float smoothstep(final float edge0, final float edge1, final float value) {
        final float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public static class Provider implements ParticleProvider<ContrailParticleData> {

        private final SpriteSet sprites;

        public Provider(final SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Nullable
        @Override
        public ContrailParticle createParticle(final ContrailParticleData data, final ClientLevel level,
                                               final double x, final double y, final double z,
                                               final double xd, final double yd, final double zd) {
            return new ContrailParticle(level, x, y, z, xd, yd, zd, data, this.sprites);
        }
    }
}
