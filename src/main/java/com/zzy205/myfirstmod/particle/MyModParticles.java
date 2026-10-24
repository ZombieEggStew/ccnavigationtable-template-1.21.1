package com.zzy205.myfirstmod.particle;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.NotNull;

import static com.zzy205.myfirstmod.CCPeripheralExtender.MOD_ID;

/**
 * 本模组的粒子类型注册表。
 * <p>
 * 目前只有一个：{@code ccpe:exhaust} —— 定向喷管的排气粒子（自研粒子类型，
 * 拥有自己的扫掠碰撞 + 撞墙扩散逻辑，见 {@code client.particle.ExhaustParticle}）。
 * <p>
 * 参考来源：{@code references/create-propulsion-simulated-main/.../particles/ParticleTypes.java}
 * （同样的 {@code DeferredRegister<ParticleType<?>>} + 客户端 {@code RegisterParticleProvidersEvent} 写法；
 * 这里不用 Create 的 {@code ICustomParticleData} 包装，直接走 NeoForge 原生
 * {@link ParticleType} + {@code event.registerSpriteSet}，少一层反射式工厂）。
 */
public final class MyModParticles {

    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);

    /** 喷管排气粒子；客户端 provider 在 {@code CCPeripheralExtenderClient#registerParticles} 注册 */
    public static final DeferredHolder<ParticleType<?>, ParticleType<ExhaustParticleData>> EXHAUST =
            PARTICLE_TYPES.register("exhaust", () -> new ParticleType<ExhaustParticleData>(false) {
                @Override
                public @NotNull MapCodec<ExhaustParticleData> codec() {
                    return ExhaustParticleData.CODEC;
                }

                @Override
                public @NotNull StreamCodec<? super RegistryFriendlyByteBuf, ExhaustParticleData> streamCodec() {
                    return ExhaustParticleData.STREAM_CODEC;
                }
            });

    private MyModParticles() {
    }

    public static void register(IEventBus modEventBus) {
        PARTICLE_TYPES.register(modEventBus);
    }
}
