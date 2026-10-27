package com.zzy205.myfirstmod.client.particle;

import com.zzy205.myfirstmod.particle.IntakeParticleData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * 进气粒子（{@code ccpe:intake}）：喷管<b>产生吸力（负转速）时</b>被吸进口面的空气。
 * <p>
 * 为什么不是"排气粒子反向喷"：{@link ExhaustParticle} 的整条曲线都是<b>往外炸</b>的
 * （随寿命膨胀、阻尼变慢、撞墙反弹、越老越大），而进气需要的是<b>向内收</b>的
 * （加速收拢、越老越小、到口面消失），两者的每一条都是反的 ⇒ 单独一个轻量粒子更干净，
 * 也便于单独调尺寸/密度（配置见 {@code Config.INTAKE_*}）。
 * <p>
 * 运动模型（<b>不用速度积分，用参数插值</b>）：喷管传进来的 {@code xd/yd/zd} 不是速度，而是
 * 「出生点 → 口面」的<b>位移向量</b>（与 Create 的 {@code AirParticleData} 同一约定），于是
 * <pre>
 * 位置(t) = 出生点 + 位移 · t²        // t = age / lifetime
 * </pre>
 * 平方曲线 = 起步慢、越靠近喷口越快（"被吸住"的加速感），而且在 {@code t = 1} 时位置<b>精确</b>落在
 * 口面中心 —— 死亡那一刻正好被"吞掉"，不需要额外的吸附/删除判定。
 * <p>
 * 三条随寿命演变的曲线（与排气相反，全部单调收）：
 * <ul>
 * <li>位置：{@code t²} 加速靠近（见上）；</li>
 * <li>尺寸：{@code baseScale × (1.0 → 0.30)} 缩小；</li>
 * <li>透明度：短淡入后一路衰减到 0（{@code (1−t)^0.7}），在口面上彻底看不见。</li>
 * </ul>
 * <b>不碰撞</b>（{@code hasPhysics = false}，只 {@code setPos}）：进气粒子是"空气被卷进来"的视觉，
 * 若撞到车身/方块反弹反而会露馅；前方是否被堵由喷管侧的一条射线决定（没有可吸的空气就不生成）。
 * <p>
 * 渲染：继承 {@link SimpleAnimatedParticle} 拿到半透明渲染，并像排气一样<b>全亮</b>渲染
 * （见 {@link #getLightColor}）—— 否则躲在车底/阴影里的进气粒子会被光图压成深灰。
 */
public class IntakeParticle extends SimpleAnimatedParticle {

    /** 不透明度上限（进气比排气更"稀"，别盖住喷口） */
    private static final float ALPHA_MAX = 0.55F;

    /**
     * 淡入长度（<b>固定 tick</b>，不是寿命的比例）。
     * 教训来自 {@code ContrailParticle}（memo §9.7）：按寿命比例写，寿命一变就会被放大几十倍。
     */
    private static final int FADE_IN_TICKS = 2;

    /** 尺寸曲线：出生 {@code baseScale × SIZE_START} → 消失 {@code baseScale × SIZE_END} */
    private static final float SIZE_START = 1.0F;
    private static final float SIZE_END = 0.30F;

    /** 尺寸收小的曲线指数：&gt;1 = 前段保持大、临近口面才迅速收缩 */
    private static final float SIZE_FALLOFF = 0.6F;

    /** 位置曲线指数：{@code 2} = 起步慢、临近口面加速（被吸住）；1 = 匀速直线。 */
    private static final float ACCEL = 2.0F;

    /** 全亮光照（天空 15 + 方块 15），与 {@code ExhaustParticle} 的 IGNORE_LIGHTMAP 同口径 */
    private static final int FULL_BRIGHT = 0xF000F0;

    /** 出生点（世界系）；每 tick 的位置由它 + 位移插值算出来 */
    private final double spawnX;
    private final double spawnY;
    private final double spawnZ;

    /** 出生点 → 口面中心的位移向量（<b>不是速度</b>，见类注释） */
    private final double towardX;
    private final double towardY;
    private final double towardZ;

    /** 基准尺寸（配置 {@code intakeScale} × 随机倍率，由喷管算好传进来） */
    private final float baseScale;

    protected IntakeParticle(final ClientLevel level, final double x, final double y, final double z,
                             final double xd, final double yd, final double zd,
                             final IntakeParticleData data, final SpriteSet sprites) {
        super(level, x, y, z, sprites, 0.0F);

        this.baseScale = Math.max(0.01F, data.scale());
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.towardX = xd;
        this.towardY = yd;
        this.towardZ = zd;

        this.lifetime = Math.max(2, data.lifetime());
        this.quadSize = this.baseScale * SIZE_START;

        // 每颗粒子一点明暗差，整团进气才不像一块均匀的白斑
        final float tint = 0.92F + this.random.nextFloat() * 0.12F;
        this.rCol = data.r() * tint;
        this.gCol = data.g() * tint;
        this.bCol = data.b() * tint;

        this.alpha = 0.0F;       // 出生透明，靠 tick 里的淡入拉起来
        this.gravity = 0.0F;     // 无重力（进气完全由参数插值决定）
        this.friction = 1.0F;    // 不用速度，基类摩擦不参与
        this.hasPhysics = false; // 绝不碰撞（见类注释）

        // 出生时随机定死一张精灵图（不是动画帧），避免同一团粒子同时跳帧
        this.setSprite(sprites.get(this.random));
    }

    /**
     * 每 tick：先按 {@code t²} 把位置插值到「出生点 → 口面」这条线上，再更新尺寸/透明度。
     * <p>
     * 位置必须<b>从出生点算起</b>而不是"当前位置 + 速度"，否则浮点累积会让粒子在寿命末尾
     * 落不到口面上（memo §14 的老教训：排气那边正是因为"每 tick 悄悄挪一点"导致穿墙）。
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

        // 尺寸：随寿命单调缩小（越靠近口面越小）。
        // 先算尺寸再 setPos：setPos 会顺带用当前 quadSize 重建包围盒（视锥剔除用）。
        final float shrink = (float) Math.pow(life, SIZE_FALLOFF);
        this.quadSize = this.baseScale * (SIZE_START + (SIZE_END - SIZE_START) * shrink);

        // 位置：t² 加速收拢，t=1 时精确落在口面中心
        final float progress = (float) Math.pow(life, ACCEL);
        this.setPos(
                this.spawnX + this.towardX * progress,
                this.spawnY + this.towardY * progress,
                this.spawnZ + this.towardZ * progress);

        // 透明度：2 tick 淡入，之后一路衰减到 0（t=1 时归零，口面上不会留下一颗硬切的白点）
        final float fadeIn = smoothstep(0.0F, 1.0F, this.age / (float) FADE_IN_TICKS);
        this.alpha = ALPHA_MAX * fadeIn * (float) Math.pow(1.0F - life, 0.7F);
    }

    /** 全亮渲染：进气粒子常在车底/阴影里，吃 lightmap 会变成一团深灰 */
    @Override
    public int getLightColor(final float partialTick) {
        return FULL_BRIGHT;
    }

    private static float smoothstep(final float edge0, final float edge1, final float value) {
        final float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public static class Provider implements ParticleProvider<IntakeParticleData> {

        private final SpriteSet sprites;

        public Provider(final SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Nullable
        @Override
        public IntakeParticle createParticle(final IntakeParticleData data, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double xd, final double yd, final double zd) {
            return new IntakeParticle(level, x, y, z, xd, yd, zd, data, this.sprites);
        }
    }
}
