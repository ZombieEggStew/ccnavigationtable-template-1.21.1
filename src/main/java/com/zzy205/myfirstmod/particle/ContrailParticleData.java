package com.zzy205.myfirstmod.particle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.NotNull;

/**
 * 高空尾迹粒子（{@code ccpe:contrail}）的参数。
 * <p>
 * 为什么不复用 {@link ExhaustParticleData}：那个 record 的 {@link ExhaustParticleData#getType()}
 * <b>写死</b>返回 {@code ccpe:exhaust}，而 {@code ParticleEngine} 是按
 * {@code options.getType()} 找 provider 的 ⇒ 拿它去生成 {@code ccpe:contrail} 只会生成排气粒子。
 * 尾迹也用不到 {@code ownerId}/{@code gen}（不做碰撞、不做分裂），所以单独建一个更瘦的 record。
 * <p>
 * 字段语义（其余形状/漂移参数都是粒子里或喷管里的常量，走的不是网络）：
 * <ul>
 * <li>{@code width} —— 出生宽度（格，即 quad 的<b>半</b>边长）；之后按
 *     {@code width · sqrt(1 + age/tao)} 做湍流扩散，见 {@code client.particle.ContrailParticle}；</li>
 * <li>{@code lifetime} —— 寿命（tick），由温度适宜度决定（越冷越长，见
 *     {@code ThrustNozzleBlockEntity#tickContrail}）；</li>
 * <li>{@code r/g/b} —— 冰晶白偏冷，出生时再乘一点随机明暗差。</li>
 * <li>{@code burstX/burstY/burstZ} —— <b>喷出初速度</b>（格/tick，世界系）：沿排气方向、
 *     大小与方向在配置范围内随机，粒子每 tick 按 {@code contrailBurstDamp} 指数衰减到 0，
 *     叠加在 via velocity 参数传进来的<b>恒定飘荡速度</b>之上（见 {@code ContrailParticle#tick}）。</li>
 * </ul>
 */
public record ContrailParticleData(float width, int lifetime, float r, float g, float b,
                                   float burstX, float burstY, float burstZ)
        implements ParticleOptions {

    public static final MapCodec<ContrailParticleData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.FLOAT.fieldOf("width").forGetter(ContrailParticleData::width),
            Codec.INT.fieldOf("lifetime").forGetter(ContrailParticleData::lifetime),
            Codec.FLOAT.fieldOf("r").forGetter(ContrailParticleData::r),
            Codec.FLOAT.fieldOf("g").forGetter(ContrailParticleData::g),
            Codec.FLOAT.fieldOf("b").forGetter(ContrailParticleData::b),
            Codec.FLOAT.fieldOf("burstX").forGetter(ContrailParticleData::burstX),
            Codec.FLOAT.fieldOf("burstY").forGetter(ContrailParticleData::burstY),
            Codec.FLOAT.fieldOf("burstZ").forGetter(ContrailParticleData::burstZ)
    ).apply(instance, ContrailParticleData::new));

    /** 手写编解码（与 {@link ExhaustParticleData} 同一写法，避免依赖 composite 重载上限） */
    public static final StreamCodec<RegistryFriendlyByteBuf, ContrailParticleData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                buf.writeFloat(data.width());
                buf.writeVarInt(data.lifetime());
                buf.writeFloat(data.r());
                buf.writeFloat(data.g());
                buf.writeFloat(data.b());
                buf.writeFloat(data.burstX());
                buf.writeFloat(data.burstY());
                buf.writeFloat(data.burstZ());
            },
            buf -> new ContrailParticleData(
                    buf.readFloat(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat())
    );

    @Override
    public @NotNull ParticleType<?> getType() {
        return MyModParticles.CONTRAIL.get();
    }
}
