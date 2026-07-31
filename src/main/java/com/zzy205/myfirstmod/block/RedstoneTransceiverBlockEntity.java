package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.cc.ReceiverRegistry;
import com.zzy205.myfirstmod.compat.create.CreateRedstoneCompat;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RedstoneTransceiverBlockEntity extends BlockEntity {

    /** 所有 banner 的数据（频道号 + 幽灵物品），以 CompoundTag 整体存储 */
    private CompoundTag bannerData = new CompoundTag();

    /** 加载模式：0=关闭, 1=加载区块, 2=加载物理体 */
    private int loadMode = 0;

    public RedstoneTransceiverBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.redstone_transceiver_entity.get(), pos, state);
    }

    public CompoundTag getBannerData() {
        return bannerData;
    }

    public void setBannerData(CompoundTag data) {
        this.bannerData = data.copy();
        setChanged();
    }

    /** 获取当前所有 receiver 占用的频道号数组 */
    public int[] getOccupiedChannels() {
        return ReceiverRegistry.getOccupiedChannels()
                .stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            ReceiverRegistry.registerBE(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            ReceiverRegistry.unregisterBE(this);
            // 清理此 Receiver 创建的所有虚拟 Create 红石发送端
            CreateRedstoneCompat.cleanupFor(worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!bannerData.isEmpty()) {
            tag.put("BannerData", bannerData);
        }
        tag.putInt("LoadMode", loadMode);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("BannerData")) {
            bannerData = tag.getCompound("BannerData");
        }
        if (tag.contains("LoadMode")) {
            loadMode = Math.clamp(tag.getInt("LoadMode"), 0, 2);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("LoadMode", loadMode);
        return tag;
    }

    // ════════════════════ 加载模式 ════════════════════

    public int getLoadMode() { return loadMode; }

    public void setLoadMode(int mode) {
        int clamped = Math.clamp(mode, 0, 2);
        if (this.loadMode == clamped) return;
        this.loadMode = clamped;
        this.setChanged();
    }

    /** 检查 receiver 是否在 Sable 物理体上 */
    public boolean isOnPhysicsBody() {
        if (level == null || level.isClientSide) return false;
        return SableCompat.getContainingSubLevel(this) != null;
    }
}
