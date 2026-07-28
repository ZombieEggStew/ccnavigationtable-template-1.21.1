package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class MySensorBlockEntity extends BlockEntity {

    private CompoundTag cachedAttachedNBT = new CompoundTag();

    public MySensorBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.my_sensor_entity.get(), pos, state);
    }

    /**
     * 对外暴露的读取接口：刷新并返回附着方块的最新 NBT。
     * 仅在调用时读取，不会后台自动 tick。
     */
    public CompoundTag refreshAndGet(Level level, BlockState state) {
        this.cachedAttachedNBT = MySensorBlock.getAttachedBlockNBT(level, state, this.getBlockPos());
        this.setChanged();
        level.sendBlockUpdated(this.getBlockPos(), state, state, 3);
        return this.cachedAttachedNBT;
    }

    /**
     * 获取上次缓存的 NBT（不触发刷新）。
     */
    public CompoundTag getCachedAttachedNBT() {
        return cachedAttachedNBT;
    }

    /**
     * 由网络包处理时设置缓存（仅客户端调用）。
     */
    public void setCachedAttachedNBT(CompoundTag nbt) {
        this.cachedAttachedNBT = nbt;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        return tag;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("AttachedNBT")) {
            cachedAttachedNBT = tag.getCompound("AttachedNBT");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
    }
}
