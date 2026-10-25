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
 * 进气粒子（{@code ccpe:intake}）的参数：<b>喷管产生吸力（负转速）时</b>被吸进口面的空气。
 * <p>
 * 为什么单独建一个 record（不复用 {@link ExhaustParticleData} / {@link ContrailParticleData}）：
 * <ul>
 * <li>两个已有的 record 的 {@link ParticleOptions#getType()} 都<b>写死</b>返回自己的类型，而
 *     {@code ParticleEngine} 是按 {@code options.getType()} 找 provider 的 ⇒ 拿它们去生成进气粒子
 *     只会生成排气/尾迹；</li>
 * <li>进气既不需要 {@code ownerId}/{@code gen}（不碰撞、不分裂），也不需要尾迹的"宽度"语义
 *     （它的尺寸是<b>由大变小</b>的，与尾迹相反）。</li>
 * </ul>
 * 字段语义（其余视觉曲线是 {@code client.particle.IntakeParticle} 里的常量）：
 * <ul>
 * <li>{@code scale} —— 出生尺寸（格，quad 边长），随寿命缩小到 {@code 0.3 × scale} 后消失；</li>
 * <li>{@code lifetime} —— 寿命（tick）。喷管按「出生距离 ÷ 进气速度」算好传进来，所以
 *     一颗粒子恰好在<b>死亡那一刻</b>到达喷口中心；</li>
 * <li>{@code r/g/b} —— 与排气同一族的冷白（方向靠运动表达，不靠颜色区分）。</li>
 * </ul>
 * <b>位移走 vx/vy/vz</b>：和 Create 的 {@code AirParticleData} 一样，这里传的不是速度而是
 * 「出生点 → 口面」的<b>位移向量</b>（粒子自己按 {@code t²} 走完），所以不需要额外的数据字段。
 */
public record IntakeParticleData(float scale, int lifetime, float r, float g, float b)
        implements ParticleOptions {

    public static final MapCodec<IntakeParticleData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.FLOAT.fieldOf("scale").forGetter(IntakeParticleData::scale),
            Codec.INT.fieldOf("lifetime").forGetter(IntakeParticleData::lifetime),
            Codec.FLOAT.fieldOf("r").forGetter(IntakeParticleData::r),
            Codec.FLOAT.fieldOf("g").forGetter(IntakeParticleData::g),
            Codec.FLOAT.fieldOf("b").forGetter(IntakeParticleData::b)
    ).apply(instance, IntakeParticleData::new));

    /** 手写编解码（与 {@link ContrailParticleData} 同一写法，避免依赖 composite 重载上限） */
    public static final StreamCodec<RegistryFriendlyByteBuf, IntakeParticleData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                buf.writeFloat(data.scale());
                buf.writeVarInt(data.lifetime());
                buf.writeFloat(data.r());
                buf.writeFloat(data.g());
                buf.writeFloat(data.b());
            },
            buf -> new IntakeParticleData(
                    buf.readFloat(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat())
    );

    @Override
    public @NotNull ParticleType<?> getType() {
        return MyModParticles.INTAKE.get();
    }
}
