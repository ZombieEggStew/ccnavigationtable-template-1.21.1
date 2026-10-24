package com.zzy205.myfirstmod.client.particle;

import com.zzy205.myfirstmod.block.ThrustNozzleBlockEntity;
import com.zzy205.myfirstmod.particle.ExhaustParticleData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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

    /** 不透明度上限（参考实现 0.46） */
    private static final float ALPHA_MAX = 0.46F;
    /** 寿命前 18% 淡入、后 38% 淡出 */
    private static final float FADE_IN_END = 0.18F;
    private static final float FADE_OUT_START = 0.62F;

    /** 尺寸：出生 = baseScale × 0.42，死亡 = baseScale × (0.42 + 0.95) */
    private static final float SIZE_START = 0.42F;
    private static final float SIZE_GROWTH = 0.95F;

    /** 阻尼：水平 0.935（快减速、铺开），垂直 0.965（比水平略慢，像热气流） */
    private static final double HORIZONTAL_FRICTION = 0.935D;
    private static final double VERTICAL_FRICTION = 0.965D;

    /** 浮力：基础 + 随寿命增长的额外量（热气体上升） */
    private static final double BUOYANCY = 0.0008D;
    private static final double BUOYANCY_GROWTH = 0.0009D;

    /** 正弦摆动幅度：让排气看起来在翻滚而不是笔直飞 */
    private static final double SWIRL = 0.0018D;

    /** 撞墙时把粒子从接触面推开一点点，避免下一 tick 立刻又判成碰撞 */
    private static final double CONTACT_EPSILON = 1.0E-3D;

    // ── 撞墙反应（纯反弹 + 铺开，不涉及任何重力） ──

    /** 法向反弹系数：入射法向速度的 45% 反向弹回（0 = 完全不弹、贴着表面滑；1 = 完全弹性） */
    private static final double BOUNCE = 0.45D;
    /** 切向保留：入射切向速度的 90% 继续沿表面滑 */
    private static final double TANGENT_KEEP = 0.9D;
    /** 随机溅射：在法线垂面上随机方位的散射速度 = 入射法向速度 × 本系数 */
    private static final double SPREAD_KEEP = 0.8D;
    /** 撞后整体速度保留比例（撞一下掉 15% 动能感，避免越弹越快） */
    private static final double ENERGY_KEEP = 0.85D;

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

    protected ExhaustParticle(final ClientLevel level, final double x, final double y, final double z,
                             final double xd, final double yd, final double zd,
                             final ExhaustParticleData data, final SpriteSet sprites) {
        super(level, x, y, z, sprites, 0.0F);
        this.sprites = sprites;
        this.baseScale = Math.max(0.01F, data.scale());
        this.ownerId = data.ownerId();
        this.swirlPhase = this.random.nextDouble() * 40.0D;

        this.xd = xd;
        this.yd = yd;
        this.zd = zd;

        this.lifetime = Math.max(2, data.lifetime());
        this.quadSize = this.baseScale * (SIZE_START + this.random.nextFloat() * 0.22F);

        final float tint = 0.88F + this.random.nextFloat() * 0.16F;
        this.rCol = data.r() * tint;
        this.gCol = data.g() * tint;
        this.bCol = data.b() * tint;

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

        this.alpha = ALPHA_MAX
                * smoothstep(0.0F, FADE_IN_END, life)
                * (1.0F - smoothstep(FADE_OUT_START, 1.0F, life));
        this.quadSize = this.baseScale * (SIZE_START + life * SIZE_GROWTH);

        final double swirl = Math.sin((this.age + this.swirlPhase) * 0.13D) * SWIRL;
        this.xd += swirl;
        this.zd -= swirl;
        this.yd += BUOYANCY + life * BUOYANCY_GROWTH;

        this.move(this.xd, this.yd, this.zd);

        this.xd *= HORIZONTAL_FRICTION;
        this.yd *= VERTICAL_FRICTION;
        this.zd *= HORIZONTAL_FRICTION;

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

        if (movement.lengthSqr() > 1.0E-8D) {
            final double radius = Mth.clamp(this.quadSize * COLLISION_RADIUS_FACTOR,
                    MIN_COLLISION_RADIUS, MAX_COLLISION_RADIUS);
            final ExhaustCollision.Hit hit = ExhaustCollision.sweep(this.level,
                    new Vec3(this.x, this.y, this.z), movement, radius, this.ownerId,
                    ThrustNozzleBlockEntity.PARTICLES_BLOCKED_BY_OTHER_BODIES);

            if (hit != null) {
                movement = hit.movement().add(hit.normal().scale(CONTACT_EPSILON));
                this.scatter(hit.normal());
            }
        }

        final double half = Math.max(0.05D, this.quadSize * 0.5D);
        final double nx = this.x + movement.x;
        final double ny = this.y + movement.y;
        final double nz = this.z + movement.z;

        this.setBoundingBox(new AABB(nx - half, ny - half, nz - half, nx + half, ny + half, nz + half));
        this.setLocationFromBoundingbox();
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
     */
    private void scatter(final Vec3 normal) {
        final Vec3 velocity = new Vec3(this.xd, this.yd, this.zd);
        final double dot = velocity.dot(normal);

        if (dot >= 0.0D) {
            return; // 已经在离开表面
        }

        final double speed = velocity.length();

        if (speed < 1.0E-6D) {
            return;
        }

        final double impact = -dot; // 法向入射速度（正数）
        final Vec3 rebound = normal.scale(impact * BOUNCE);
        final Vec3 tangent = velocity.subtract(normal.scale(dot)).scale(TANGENT_KEEP);

        final Vec3 axisSeed = Math.abs(normal.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 side = normal.cross(axisSeed).normalize();
        final Vec3 axis = side.cross(normal).normalize();
        final double angle = this.random.nextDouble() * Math.PI * 2.0D;
        final Vec3 spread = axis.scale(Math.cos(angle)).add(side.scale(Math.sin(angle)))
                .scale(impact * SPREAD_KEEP);

        final Vec3 result = rebound.add(tangent).add(spread);
        final double resultLength = result.length();

        if (resultLength < 1.0E-6D) {
            return;
        }

        final double newSpeed = Math.max(0.02D, speed * ENERGY_KEEP);
        final Vec3 unit = result.scale(1.0D / resultLength);

        this.xd = unit.x * newSpeed;
        this.yd = unit.y * newSpeed;
        this.zd = unit.z * newSpeed;
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
