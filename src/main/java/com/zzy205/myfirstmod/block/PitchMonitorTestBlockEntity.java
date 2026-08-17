package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores the test monitor's pitch / yaw angles and forward/backward offset,
 * synchronizing them through normal block-entity updates. Pitch / offset affect
 * the case; yaw affects the case + bearing.
 */
public class PitchMonitorTestBlockEntity extends BlockEntity {

    private static final String PITCH_TAG = "PitchAngle";
    private static final String YAW_TAG = "YawAngle";
    private static final String OFFSET_TAG = "Offset";

    private float pitchAngle;
    private float yawAngle;
    /** 前后偏移（像素，-6..6），相对 facing 前后移动 case + bearing */
    private int offset;

    public PitchMonitorTestBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.pitch_monitor_test_entity.get(), pos, state);
    }

    public float getPitchAngle() {
        return pitchAngle;
    }

    public float getYawAngle() {
        return yawAngle;
    }

    public int getOffset() {
        return offset;
    }

    public void setAngles(float pitch, float yaw, int offset) {
        this.pitchAngle = clamp(pitch, -90f, 90f);
        this.yawAngle = clamp(yaw, -180f, 180f);
        this.offset = Math.max(-6, Math.min(6, offset));
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat(PITCH_TAG, pitchAngle);
        tag.putFloat(YAW_TAG, yawAngle);
        tag.putInt(OFFSET_TAG, offset);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pitchAngle = tag.getFloat(PITCH_TAG);
        yawAngle = tag.getFloat(YAW_TAG);
        offset = tag.getInt(OFFSET_TAG);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
