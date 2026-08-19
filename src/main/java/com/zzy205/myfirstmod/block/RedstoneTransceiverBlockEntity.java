package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import com.zzy205.myfirstmod.compat.cc.RedstoneTransceiverRegistry;
import com.zzy205.myfirstmod.compat.create.CreateRedstoneCompat;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class RedstoneTransceiverBlockEntity extends BlockEntity implements PartialSafeNBT {

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
        // 同步客户端（quill 保存读的是客户端 BE，客户端陈旧会保存出旧配置）
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ════════════════════ Banner 操作（CC API 服务端入口） ════════════════════

    /**
     * 设置指定频道的两个频率物品（幽灵槽）。频道不存在时自动新建 banner。
     *
     * @return 是否成功（服务端且 level 存在时返回 true）
     */
    public boolean setBannerFrequency(int channel, ItemStack g0, ItemStack g1) {
        if (level == null || level.isClientSide) return false;
        if (g0 == null) g0 = ItemStack.EMPTY;
        if (g1 == null) g1 = ItemStack.EMPTY;

        HolderLookup.Provider registries = level.registryAccess();
        ListTag channels = bannerData.getList("Channels", Tag.TAG_INT);
        ListTag ghosts = bannerData.getList("Ghosts", Tag.TAG_COMPOUND);

        CompoundTag pair = new CompoundTag();
        if (!g0.isEmpty()) pair.put("G0", g0.save(registries));
        if (!g1.isEmpty()) pair.put("G1", g1.save(registries));

        int index = findChannelIndex(channels, channel);
        if (index >= 0) {
            ghosts.set(index, pair);
        } else {
            channels.add(IntTag.valueOf(channel));
            ghosts.add(pair);
        }

        bannerData.put("Channels", channels);
        bannerData.put("Ghosts", ghosts);
        commitBannerChange();
        return true;
    }

    /**
     * 删除指定频道的 banner。
     *
     * @return 是否删除成功（频道不存在返回 false）
     */
    public boolean removeBanner(int channel) {
        if (level == null || level.isClientSide) return false;

        ListTag channels = bannerData.getList("Channels", Tag.TAG_INT);
        ListTag ghosts = bannerData.getList("Ghosts", Tag.TAG_COMPOUND);

        int index = findChannelIndex(channels, channel);
        if (index < 0) return false;

        channels.remove(index);
        if (index < ghosts.size()) ghosts.remove(index);

        bannerData.put("Channels", channels);
        bannerData.put("Ghosts", ghosts);
        commitBannerChange();
        return true;
    }

    /** 列出当前所有 banner 的频道号 */
    public int[] getBannerChannels() {
        ListTag channels = bannerData.getList("Channels", Tag.TAG_INT);
        int[] arr = new int[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            arr[i] = ((IntTag) channels.get(i)).getAsInt();
        }
        return arr;
    }

    /** 读取指定频道的两个频率物品（幽灵槽）。频道不存在或 level 为空返回 null。 */
    public @Nullable ItemStack[] getBannerGhosts(int channel) {
        if (level == null) return null;

        ListTag channels = bannerData.getList("Channels", Tag.TAG_INT);
        ListTag ghosts = bannerData.getList("Ghosts", Tag.TAG_COMPOUND);

        int index = findChannelIndex(channels, channel);
        if (index < 0 || index >= ghosts.size()) return null;

        HolderLookup.Provider registries = level.registryAccess();
        CompoundTag pair = ghosts.getCompound(index);
        ItemStack g0 = pair.contains("G0")
                ? ItemStack.parseOptional(registries, pair.getCompound("G0"))
                : ItemStack.EMPTY;
        ItemStack g1 = pair.contains("G1")
                ? ItemStack.parseOptional(registries, pair.getCompound("G1"))
                : ItemStack.EMPTY;
        return new ItemStack[]{g0, g1};
    }

    private static int findChannelIndex(ListTag channels, int channel) {
        for (int i = 0; i < channels.size(); i++) {
            if (((IntTag) channels.get(i)).getAsInt() == channel) return i;
        }
        return -1;
    }

    /** banner 数据变更后统一提交：标记脏 + 更新频道注册表 + 推送客户端 */
    private void commitBannerChange() {
        this.setChanged();
        if (level != null && !level.isClientSide) {
            RedstoneTransceiverRegistry.updateChannels(this, bannerData);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 获取当前所有 receiver 占用的频道号数组 */
    public int[] getOccupiedChannels() {
        return RedstoneTransceiverRegistry.getOccupiedChannels()
                .stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            RedstoneTransceiverRegistry.registerBE(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            RedstoneTransceiverRegistry.unregisterBE(this);
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

    /**
     * Create 原理图 / 装置搬运时的「安全 NBT」。
     * 不实现此接口的话，{@code BlockHelper.prepareBlockEntityData} 会返回 null，
     * 导致 Schematicannon 打印时 banner 配置丢失。
     */
    @Override
    public void writeSafe(CompoundTag compound, HolderLookup.Provider registries) {
        if (!bannerData.isEmpty()) {
            compound.put("BannerData", bannerData);
        }
        compound.putInt("LoadMode", loadMode);
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
        if (!bannerData.isEmpty()) {
            tag.put("BannerData", bannerData);
        }
        tag.putInt("LoadMode", loadMode);
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（默认返回 null 会导致客户端快照陈旧，quill 保存读的是客户端 BE）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ════════════════════ 加载模式 ════════════════════

    public int getLoadMode() { return loadMode; }

    public void setLoadMode(int mode) {
        int clamped = Math.clamp(mode, 0, 2);
        if (this.loadMode == clamped) return;
        this.loadMode = clamped;
        this.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 检查 receiver 是否在 Sable 物理体上 */
    public boolean isOnPhysicsBody() {
        if (level == null || level.isClientSide) return false;
        return SableCompat.getContainingSubLevel(this) != null;
    }
}
