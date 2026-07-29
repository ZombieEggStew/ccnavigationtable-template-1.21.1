package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class MySensorBlockEntity extends BlockEntity {

    private CompoundTag cachedAttachedNBT = new CompoundTag();
    private int scrolledValue = 0;
    private int selectIndex = 0;


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

    /** 文本输入框内容，持久化并同步到客户端 */
    public int getScrolledValue() { return scrolledValue; }

    public void setScrolledValue(int val) {
        this.scrolledValue = val;
        this.setChanged();
    }

    public int getSelectIndex() { return selectIndex; }

    public void setSelectIndex(int idx) {
        this.selectIndex = idx;
        this.setChanged();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putInt("SelectIndex", selectIndex);
        return tag;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("AttachedNBT")) cachedAttachedNBT = tag.getCompound("AttachedNBT");
        if (tag.contains("ScrolledValue")) scrolledValue = tag.getInt("ScrolledValue");
        if (tag.contains("SelectIndex")) selectIndex = tag.getInt("SelectIndex");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putInt("SelectIndex", selectIndex);
    }
}
