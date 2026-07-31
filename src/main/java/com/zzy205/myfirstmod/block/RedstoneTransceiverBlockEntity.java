package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.cc.ReceiverRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RedstoneTransceiverBlockEntity extends BlockEntity {

    /** 鎵€锟?banner 鐨勬暟鎹紙棰戦亾锟?+ 骞界伒鐗╁搧锛夛紝锟?CompoundTag 鏁翠綋瀛樺偍 */
    private CompoundTag bannerData = new CompoundTag();

    /** 鍔犺浇妯″紡锟?=鍏抽棴, 1=鍔犺浇鍖哄潡, 2=鍔犺浇鐗╃悊锟?*/
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

    /** 鑾峰彇褰撳墠鎵€锟?receiver 鍗犵敤鐨勯閬撳彿鏁扮粍 */
    public int[] getOccupiedChannels() {
        return com.zzy205.myfirstmod.compat.cc.ReceiverRegistry.getOccupiedChannels()
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
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            ReceiverRegistry.unregisterBE(this);
            // 娓呯悊锟?Receiver 鍒涘缓鐨勬墍鏈夎櫄锟?Create 绾㈢煶鍙戦€佺
            com.zzy205.myfirstmod.compat.create.CreateRedstoneCompat.cleanupFor(worldPosition);
        }
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

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?鍔犺浇妯″紡 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    public int getLoadMode() { return loadMode; }

    public void setLoadMode(int mode) {
        int clamped = Math.clamp(mode, 0, 2);
        if (this.loadMode == clamped) return;
        this.loadMode = clamped;
        this.setChanged();
    }

    /** 妫€锟?receiver 鏄惁锟?Sable 鐗╃悊浣撲笂 */
    public boolean isOnPhysicsBody() {
        if (level == null || level.isClientSide) return false;
        return com.zzy205.myfirstmod.compat.sable.SableCompat.getContainingSubLevel(this) != null;
    }
}
