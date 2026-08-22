package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.network.ModuleKnobRotatePayload;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** 旋钮的客户端拖拽状态机、角度计算、音效和网络同步。 */
final class KnobInteractionHandler {

    private static final int SEND_INTERVAL = 2;
    private static final float SOUND_STEP = 12f;

    private KnobInteractionHandler() {}

    static void begin(MonitorGridOverlay.InteractionState state, BlockPos pos, Direction facing,
                      float yaw, float pitch, int offset, Vec3[] ray, MonitorModule module,
                      MonitorBlockEntity monitorBE) {
        state.knobDragging = true;
        state.knobDragFacing = facing;
        state.knobDragModuleId = module.id();
        state.knobCenterX = MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET
                + module.gridX() + module.getWidth() / 2f;
        state.knobCenterY = MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET
                + module.gridY() + module.getHeight() / 2f;

        int detentStep = 0;
        if (monitorBE != null) {
            state.knobAccumAngle = monitorBE.getGridState().getKnobAngle(module.id());
            detentStep = monitorBE.getGridState().getDetentStep(module.id());
        }
        boolean physicalLimit = monitorBE != null
                && monitorBE.getGridState().getModuleConfig(module.id()).getBoolean("physical_limit");
        state.knobPrevRawAngle = computeCrosshairAngle(pos, facing, yaw, pitch, offset,
                ray[0], ray[1], state.knobCenterX, state.knobCenterY);
        state.knobUnwrappedDelta = 0f;
        state.knobLastSoundAngle = state.knobAccumAngle;
        state.knobDisplayAngle = physicalLimit
                ? state.knobAccumAngle : normalizeDisplayAngle(state.knobAccumAngle);
        state.knobLastDetent = detentStep > 0
                ? GridState.snapToDetent(state.knobAccumAngle, detentStep)
                : state.knobDisplayAngle;
        state.knobDetentStep = detentStep;
        state.knobVisualAngle = state.knobDisplayAngle;
        state.knobSendCooldown = 0;
    }

    static void end(MonitorGridOverlay.InteractionState state) {
        state.knobDragging = false;
        state.knobDragModuleId = -1;
        state.knobDragFacing = null;
    }

    static void tick(Minecraft mc, BlockPos pos, MonitorGridOverlay.InteractionState state) {
        if (mc.level == null || mc.player == null) return;

        var knobHit = MonitorHitDetector.find(mc.level, mc.player, 1.0f);
        if (knobHit == null || !knobHit.pos().equals(pos)) {
            end(state);
            return;
        }

        MonitorBlockEntity monitorBE = mc.level.getBlockEntity(pos) instanceof MonitorBlockEntity m ? m : null;
        float yaw = monitorBE != null ? monitorBE.getYawAngle() : 0f;
        float pitch = monitorBE != null ? monitorBE.getPitchAngle() : 0f;
        int offset = monitorBE != null ? monitorBE.getOffset() : 0;
        Vec3[] ray = crosshairRay(mc.level, pos, mc.player);
        float rawAngle = computeCrosshairAngle(pos, state.knobDragFacing, yaw, pitch, offset,
                ray[0], ray[1], state.knobCenterX, state.knobCenterY);
        float diff = rawAngle - state.knobPrevRawAngle;
        if (diff > Math.PI) diff -= (float) (2 * Math.PI);
        else if (diff < -Math.PI) diff += (float) (2 * Math.PI);
        state.knobUnwrappedDelta += diff;
        state.knobPrevRawAngle = rawAngle;

        float newAngle = state.knobAccumAngle + (float) Math.toDegrees(state.knobUnwrappedDelta);
        int detentStep = 0;
        boolean physicalLimit = false;
        if (monitorBE != null) {
            detentStep = monitorBE.getGridState().getDetentStep(state.knobDragModuleId);
            physicalLimit = monitorBE.getGridState().getModuleConfig(state.knobDragModuleId)
                    .getBoolean("physical_limit");
        }

        float sendAngle;
        float visualAngle;
        if (detentStep > 0) {
            float snapped = GridState.snapToDetent(newAngle, detentStep);
            if (monitorBE != null && physicalLimit) {
                snapped = monitorBE.getGridState().clampKnobAngle(state.knobDragModuleId, snapped);
            }
            state.knobDisplayAngle = physicalLimit ? snapped : normalizeDisplayAngle(snapped);
            if (snapped != state.knobLastDetent) {
                float soundAngle = normalizeDisplayAngle(snapped);
                float soundPitch = 0.5f + (soundAngle / 360f) * 1.5f;
                mc.player.playSound(SoundEvents.LEVER_CLICK, 0.1f, soundPitch);
                state.knobLastDetent = snapped;
            }
            float off = newAngle - snapped;
            if (!physicalLimit) {
                if (off > 180f) off -= 360f;
                else if (off < -180f) off += 360f;
            }
            visualAngle = snapped + off / 3f;
            state.knobVisualAngle = physicalLimit ? visualAngle : normalizeDisplayAngle(visualAngle);
            sendAngle = snapped;
        } else {
            sendAngle = physicalLimit && monitorBE != null
                    ? monitorBE.getGridState().clampKnobAngle(state.knobDragModuleId, newAngle)
                    : newAngle;
            state.knobDisplayAngle = physicalLimit ? sendAngle : normalizeDisplayAngle(sendAngle);
            float soundDiff = sendAngle - state.knobLastSoundAngle;
            int soundSteps = (int) (soundDiff / SOUND_STEP);
            if (soundSteps != 0) {
                float cycleAngle = sendAngle % 360f;
                if (cycleAngle < 0) cycleAngle += 360f;
                float soundPitch = 0.5f + (cycleAngle / 360f) * 1.5f;
                mc.player.playSound(SoundEvents.LEVER_CLICK, 0.1f, soundPitch);
                state.knobLastSoundAngle = sendAngle - (soundDiff - soundSteps * SOUND_STEP);
            }
            float overshoot = newAngle - sendAngle;
            visualAngle = sendAngle + overshoot / 3f;
            state.knobVisualAngle = physicalLimit ? visualAngle : normalizeDisplayAngle(visualAngle);
        }

        if (monitorBE != null) {
            sendAngle = monitorBE.getGridState().clampKnobAngle(state.knobDragModuleId, sendAngle);
            state.knobDisplayAngle = monitorBE.getGridState()
                    .clampKnobAngle(state.knobDragModuleId, state.knobDisplayAngle);
        }

        state.knobSendCooldown--;
        if (state.knobSendCooldown <= 0) {
            state.knobSendCooldown = SEND_INTERVAL;
            PacketDistributor.sendToServer(
                    new ModuleKnobRotatePayload(pos, state.knobDragModuleId, sendAngle));
        }
    }

    private static Vec3[] crosshairRay(Level level, BlockPos pos, Player player) {
        Vec3 origin = player.getEyePosition(1.0f);
        Vec3 dir = player.getViewVector(1.0f);
        SubLevel subLevel = SableCompat.getContainingSubLevel(level, pos);
        if (subLevel != null) {
            origin = SableCompat.toLocalPosition(subLevel, 1.0f, origin);
            dir = SableCompat.toLocalDirection(subLevel, 1.0f, dir);
        }
        return new Vec3[]{origin, dir};
    }

    private static float computeCrosshairAngle(BlockPos pos, Direction facing, float yaw, float pitch, int offset,
                                                Vec3 origin, Vec3 dir, float knobCx, float knobCy) {
        float[] local = MonitorBlock.rayToScreenLocal(pos, facing, yaw, pitch, offset, origin, dir);
        if (local == null) return 0f;
        return (float) Math.atan2(local[1] - knobCy, local[0] - knobCx);
    }

    private static float normalizeDisplayAngle(float angle) {
        float normalized = angle % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }
}