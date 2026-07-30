package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.cc.SensorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class MySensorBlockEntity extends BlockEntity {

    private CompoundTag cachedAttachedNBT = new CompoundTag();
    private int scrolledValue = 0;
    /** 幽灵物品槽中展示的物品 */
    private ItemStack displayItem = ItemStack.EMPTY;
    /** 第二个幽灵物品槽 */
    private ItemStack displayItem2 = ItemStack.EMPTY;

    /** 所有已被占用的频道号快照（服务端设置，客户端通过 updateTag 同步） */
    private int[] occupiedChannels = new int[0];


    public MySensorBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.my_sensor_entity.get(), pos, state);
    }

    // ═══════════════ 频道注册 ═══════════════

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            int assigned = SensorRegistry.register(this.scrolledValue, this);
            if (assigned != this.scrolledValue) {
                this.scrolledValue = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            SensorRegistry.unregister(this.scrolledValue, this);
        }
        super.setRemoved();
    }

    /** 从注册表同步 occupiedChannels 快照到本 BE，并通知客户端 */
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        var channels = SensorRegistry.getOccupiedChannels();
        int[] arr = new int[channels.size()];
        int i = 0;
        for (int ch : channels) arr[i++] = ch;
        this.occupiedChannels = arr;
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    /** 获取被占用的频道号数组（客户端 GUI 用它跳过已占用的频道） */
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    /** 检查指定频道是否被其他传感器占用 */
    public boolean isChannelOccupiedByOther(int channel) {
        if (channel == this.scrolledValue) return false; // 自己占用不算
        for (int ch : occupiedChannels) {
            if (ch == channel) return true;
        }
        return false;
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

    public ItemStack getDisplayItem() {
        return displayItem;
    }

    public void setDisplayItem(ItemStack stack) {
        this.displayItem = stack.copy();
        if (!this.displayItem.isEmpty()) {
            this.displayItem.setCount(1);
        }
        this.setChanged();
    }

    public ItemStack getDisplayItem2() {
        return displayItem2;
    }

    public void setDisplayItem2(ItemStack stack) {
        this.displayItem2 = stack.copy();
        if (!this.displayItem2.isEmpty()) {
            this.displayItem2.setCount(1);
        }
        this.setChanged();
    }

    /** 按索引获取幽灵物品槽（0或1） */
    public ItemStack getDisplayItem(int slot) {
        return slot == 1 ? displayItem2 : displayItem;
    }

    /** 按索引设置幽灵物品槽（0或1） */
    public void setDisplayItem(int slot, ItemStack stack) {
        if (slot == 1) {
            setDisplayItem2(stack);
        } else {
            setDisplayItem(stack);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.save(registries));
        }
        if (!displayItem2.isEmpty()) {
            tag.put("DisplayItem2", displayItem2.save(registries));
        }
        return tag;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("AttachedNBT")) cachedAttachedNBT = tag.getCompound("AttachedNBT");
        if (tag.contains("ScrolledValue")) scrolledValue = tag.getInt("ScrolledValue");
        if (tag.contains("OccupiedChannels")) occupiedChannels = tag.getIntArray("OccupiedChannels");
        if (tag.contains("DisplayItem")) {
            displayItem = ItemStack.parse(registries, tag.getCompound("DisplayItem")).orElse(ItemStack.EMPTY);
        }
        if (tag.contains("DisplayItem2")) {
            displayItem2 = ItemStack.parse(registries, tag.getCompound("DisplayItem2")).orElse(ItemStack.EMPTY);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.save(registries));
        }
        if (!displayItem2.isEmpty()) {
            tag.put("DisplayItem2", displayItem2.save(registries));
        }
    }
}
