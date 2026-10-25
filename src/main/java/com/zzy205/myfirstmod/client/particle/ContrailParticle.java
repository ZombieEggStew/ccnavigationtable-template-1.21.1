package com.zzy205.myfirstmod.client.particle;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.ThrustNozzleBlockEntity;
import com.zzy205.myfirstmod.particle.ContrailBudget;
import com.zzy205.myfirstmod.particle.ContrailParticleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * 高空尾迹粒子（{@code ccpe:contrail}）：<b>冰晶云，不是烟</b>。
 * <p>
 * 与 {@link ExhaustParticle}（喷口排气）的区别，也是本类的硬约束：
 * <ul>
 * <li><b>不碰撞</b>：尾迹是空气里的冰晶，{@link ExhaustCollision} 那套扫掠完全不用
 *     （{@code hasPhysics = false}，直接 {@code setPos} 推进，不走 {@code move()}）；</li>
 * <li><b>无重力</b>：{@code gravity = 0}，没有浮力，不随风；</li>
 * <li><b>匀速直线飘荡</b>（v5，用户定稿）：出生时拿到一个<b>恒定</b>速度，方向随机，大小 =
 *     <b>总飘荡距离 ÷ 寿命</b>（喷管算好传进来）。于是「30 s 寿命、2 格飘荡距离」的粒子
 *     在死亡那一刻恰好离出生点 2 格 —— 位移完全由这两个可见的量决定，不再有随机游走、
 *     阻尼、边界这些间接环节。</li>
 * </ul>
 * 形状与透明度<b>成对</b>演变（质量守恒，现实里"前段细亮、后段宽淡"就是这个）：
 * <pre>
 * 宽度 spread(t) = sqrt(1 + age / widthTau)              // 湍流扩散的 sqrt(t) 律
 * quadSize       = baseWidth × spread(t)
 * alpha          = alpha0 / spread(t)                    // 摊开多少倍就淡多少倍
 * </pre>
 * {@code widthTau} / {@code alpha0} 来自客户端配置（{@code contrailWidthTau} / {@code contrailAlpha}），
 * 在<b>构造时读一次缓存进实例字段</b>：这个 tick 每颗粒子每 tick 都要跑，不能每 tick 去查配置。
 * <p>
 * 渲染：继承 {@link SimpleAnimatedParticle} 顺带拿到 {@code PARTICLE_SHEET_TRANSLUCENT} 与
 * 全亮 {@code getLightColor}（{@code 15728880} = 天空 15 + 方块 15）—— 高空本来就没光，
 * 冰晶也不该被暖色光源染黄。精灵图在<b>出生时随机定死一张</b>（不像排气那样逐帧播放），
 * 免得上千颗粒子同时跳帧。
 */
public class ContrailParticle extends SimpleAnimatedParticle {

    /**
     * 出生淡入长度（<b>固定 tick 数</b>，不是寿命的比例）。
     * <p>
     * 曾经写成 {@code 0.05 × 寿命}：寿命 300 时只有 15 tick（0.75 s，无感），
     * 但寿命调到 18000 后就变成 <b>900 tick = 45 秒</b> ⇒ "要等一段才显现出来"。
     * 固定 6 tick（0.3 s）后，无论寿命多长都是出生即显。
     */
    private static final int FADE_IN_TICKS = 6;
    /** 寿命末段淡出起点（寿命的比例）：从这条线开始淡到 0，避免到寿命硬切消失 */
    private static final float FADE_OUT_START = 0.80F;

    /** 基准宽度（格，quad 的<b>半</b>边长）；之后随 age 按 sqrt(t) 扩散变宽 */
    private final float baseWidth;

    /** 出生不透明度上限（配置 {@code contrailAlpha}，构造时缓存） */
    private final float alpha0;

    /** 扩散时间常数（配置 {@code contrailWidthTau}，构造时缓存；越大散得越慢） */
    private final int widthTau;

    protected ContrailParticle(final ClientLevel level, final double x, final double y, final double z,
                               final double xd, final double yd, final double zd,
                               final ContrailParticleData data, final SpriteSet sprites) {
        super(level, x, y, z, sprites, 0.0F);

        this.baseWidth = Math.max(0.02F, data.width());
        this.alpha0 = Config.CONTRAIL_ALPHA.get().floatValue();
        this.widthTau = Math.max(1, Config.CONTRAIL_WIDTH_TAU.get());
        ContrailBudget.onSpawn();

        // 恒定速度：喷管按「总飘荡距离 ÷ 寿命」算好，方向随机。之后永远不改（匀速直线）。
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

        this.alpha = 0.0F;       // 出生透明，靠 tick 里的淡入拉起来
        this.gravity = 0.0F;     // 无重力
        this.friction = 1.0F;    // 不衰减（匀速），基类的摩擦不参与
        this.hasPhysics = false; // 绝不碰撞

        // 出生时随机定死一张精灵图（不是动画帧）
        this.setSprite(sprites.get(this.random));
    }

    /**
     * 每 tick：更新"宽度 / 透明度"这对曲线，然后<b>以恒定速度直线推进</b>。
     * <p>
     * 为什么不用 {@code move()}：{@code Particle#move} 会走 {@code Entity.collideBoundingBox}
     * （只要 {@code hasPhysics}）并做 y 方向的贴地判停；尾迹既不该被挡、也不该有"站住"的概念，
     * 直接 {@code setPos} 最干净（{@code setPos} 会顺带重建包围盒）。
     * <p>
     * 速度<b>刻意不做任何衰减/抖动</b>：位移必须严格等于「速度 × 寿命 = 预定的飘荡距离」，
     * 否则"2 格飘荡距离"这个可读参数会失真。想要"越老越散"的观感，靠的是
     * <ul>
     * <li>每颗粒子的飘荡<b>方向与距离都随机</b>（有的 1 格、有的 3 格、方向各异）⇒ 尾迹整体越铺越宽；</li>
     * <li>粒子自身尺寸随 age 按 sqrt(t) 变大、同时 alpha 同步变淡。</li>
     * </ul>
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
        final float spread = Mth.sqrt(1.0F + this.age / (float) this.widthTau);

        // 尺寸与透明度成对：摊开多少倍就淡多少倍，再叠上淡入/淡出
        this.quadSize = this.baseWidth * spread;

        // 淡入按【tick】而不是寿命比例（长寿命时按比例会让尾迹"等半天才出现"）
        final float fadeIn = smoothstep(0.0F, 1.0F, this.age / (float) FADE_IN_TICKS);

        this.alpha = this.alpha0 / spread
                * fadeIn
                * (1.0F - smoothstep(FADE_OUT_START, 1.0F, life));

        // 恒定速度 ⇒ 直接线性推进。累计位移在寿命结束时 = 速度 × 寿命 = 预定的飘荡距离。
        this.setPos(this.x + this.xd, this.y + this.yd, this.z + this.zd);
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
