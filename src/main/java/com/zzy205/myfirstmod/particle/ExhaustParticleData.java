package com.zzy205.myfirstmod.particle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 定向喷管的排气粒子参数（{@code ccpe:exhaust}）。
 * <p>
 * 与 {@code create:air}（{@code AirParticleData}，只有 drag/speed 两个参数、颜色尺寸全写死）不同，
 * 这个粒子是自研类型，参数全部由喷管自己给：
 * <ul>
 * <li>{@code scale} —— 基准尺寸（块），粒子会随寿命从 0.42× 膨胀到 1.37×；</li>
 * <li>{@code lifetime} —— 寿命（tick）；</li>
 * <li>{@code r/g/b} —— 颜色，喷管按"离轴距离"做冷热渐变用；</li>
 * <li>{@code ownerId} —— 喷管所在物理体的 {@link net.minecraft.world.level.Level} 无关心智 UUID，
 *     粒子碰撞时要<b>忽略这个物理体自己的方块</b>（否则粒子一出膛就被喷管方块自己的
 *     膨胀包围盒判成碰撞，直接糊在喷口上）。{@link #NO_OWNER} 表示不在物理体里。</li>
 * <li>{@code gen} —— 分裂代数。喷管直接喷出的是 0；撞墙分裂出来的子粒子是父粒子 +1，
 *     用来防止"撞地→分裂→再撞地→再分裂"的无限增殖（上限见 ExhaustParticle#MAX_SPLIT_GEN）。</li>
 * </ul>
 * 参考来源：{@code references/create-propulsion-simulated-main/.../particles/smoke/ThrusterSmokeParticleData.java}
 * （同一套 scale/lifetime/rgb 参数化思路，这里再补一个 ownerId 以支持"忽略自身物理体"）。
 * <p>
 * 注册见 {@link MyModParticles}，客户端实现见
 * {@code com.zzy205.myfirstmod.client.particle.ExhaustParticle}。
 */
public record ExhaustParticleData(float scale, int lifetime, float r, float g, float b, UUID ownerId, int gen)
        implements ParticleOptions {

    /** 不在任何物理体里时用的哨兵 UUID（零 UUID） */
    public static final UUID NO_OWNER = new UUID(0L, 0L);

    public static final MapCodec<ExhaustParticleData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.FLOAT.fieldOf("scale").forGetter(ExhaustParticleData::scale),
            Codec.INT.fieldOf("lifetime").forGetter(ExhaustParticleData::lifetime),
            Codec.FLOAT.fieldOf("r").forGetter(ExhaustParticleData::r),
            Codec.FLOAT.fieldOf("g").forGetter(ExhaustParticleData::g),
            Codec.FLOAT.fieldOf("b").forGetter(ExhaustParticleData::b),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(ExhaustParticleData::ownerId),
            Codec.INT.fieldOf("gen").forGetter(ExhaustParticleData::gen)
    ).apply(instance, ExhaustParticleData::new));

    /** 7 个字段超过 {@code StreamCodec.composite} 的重载上限，手写编解码（同参考实现的写法） */
    public static final StreamCodec<RegistryFriendlyByteBuf, ExhaustParticleData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                buf.writeFloat(data.scale());
                buf.writeVarInt(data.lifetime());
                buf.writeFloat(data.r());
                buf.writeFloat(data.g());
                buf.writeFloat(data.b());
                UUIDUtil.STREAM_CODEC.encode(buf, data.ownerId());
                buf.writeVarInt(data.gen());
            },
            buf -> new ExhaustParticleData(
                    buf.readFloat(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    buf.readVarInt())
    );

    @Override
    public @NotNull ParticleType<?> getType() {
        return MyModParticles.EXHAUST.get();
    }
}
