package com.zzy205.myfirstmod.block;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 引擎运转音效实例：循环播放 + keepAlive/fadeOut 生命周期。
 * <p>
 * 音效使用 Create 蒸汽引擎的声音（{@code AllSoundEvents.STEAM}，steam 嘶声）；
 * 生命周期逻辑参考 CDG {@code EngineSoundInstance}（引擎运转时每 tick keepAlive，
 * 停止后淡出并停止）——"Create 蒸汽引擎的声音和相关逻辑"（方案见 memo/engine-module.md）。
 * <p>
 * 参考来源：CDG {@code EngineSoundInstance}；Create {@code AllSoundEvents.STEAM} /
 * {@code BoilerData}（蒸汽音效）。
 */
@OnlyIn(Dist.CLIENT)
public class EngineSoundInstance extends AbstractTickableSoundInstance {

    private boolean active;
    private int keepAlive;
    private float volumeTarget = 0.5f;
    private float pitchTarget = 0.5f;
    private final float pitchChangeSpeed;

    public EngineSoundInstance(SoundEvent soundEvent, SoundSource soundSource, Vec3 pos, float pitchChangeSpeed) {
        super(soundEvent, soundSource, SoundInstance.createUnseededRandom());
        this.pitchChangeSpeed = pitchChangeSpeed;
        looping = true;
        active = true;
        volume = 0.05f;
        pitch = 0.0f;
        delay = 0;
        keepAlive();
        x = pos.x;
        y = pos.y;
        z = pos.z;
    }

    public void fadeOut() {
        this.active = false;
    }

    public void keepAlive() {
        keepAlive = 2;
    }

    public void setPitch(float pitch) {
        this.pitchTarget = pitch;
    }

    public void setVolume(float vol) {
        this.volumeTarget = vol;
    }

    @Override
    public void tick() {
        if (active) {
            volume = Mth.lerp(0.3f, volume, volumeTarget);
            pitch = Mth.lerp(pitchChangeSpeed, pitch, pitchTarget);
            keepAlive--;
            if (keepAlive == 0)
                fadeOut();
            return;
        }
        volume = Math.max(0, volume - .04f);
        pitch = Math.max(0, pitch - .08f);
        if (volume == 0)
            stop();
    }

    public boolean active() {
        return active;
    }
}
