package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import com.zzy205.myfirstmod.compat.create.CreateRedstoneCompat;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Receiver 的 CC:Tweaked 外设实现。
 * <p>
 * 支持 {@code peripheral.wrap("right")} 和 {@code peripheral.find("ccnavigation:receiver")}。
 * Lua 端可读取 Receiver 的 banner 频道配置和幽灵物品。
 */
public class ReceiverPeripheral implements IPeripheral {

    private final RedstoneTransceiverBlockEntity be;

    public ReceiverPeripheral(RedstoneTransceiverBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccnavigation:receiver";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof ReceiverPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public RedstoneTransceiverBlockEntity getBlockEntity() {
        return be;
    }

    // ════════════════════ Lua API ════════════════════

    /**
     * 获取 receiver 当前的 banner 数量。
     */
    @LuaFunction(mainThread = true)
    public final int getBannerCount() {
        CompoundTag data = be.getBannerData();
        return data.contains("Count") ? data.getInt("Count") : 0;
    }

    /**
     * 获取指定索引 banner 的频道号。
     *
     * @param index banner 索引（1-based，与 GUI 保持一致）
     * @return 频道号，索引越界返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Integer getBannerChannel(int index) {
        CompoundTag data = be.getBannerData();
        ListTag channels = data.getList("Channels", Tag.TAG_INT);
        int i = index - 1;
        if (i < 0 || i >= channels.size()) return null;
        return ((net.minecraft.nbt.IntTag) channels.get(i)).getAsInt();
    }

    /**
     * 获取指定 banner 的幽灵槽物品信息。
     *
     * @param bannerIndex banner 索引（1-based）
     * @param slotIndex   幽灵槽索引（1-based，1 或 2）
     * @return 物品信息 table {@code {id="minecraft:stone", count=1}}，空槽返回空 table
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getBannerItem(int bannerIndex, int slotIndex) {
        CompoundTag data = be.getBannerData();
        ListTag ghosts = data.getList("Ghosts", Tag.TAG_COMPOUND);
        int bi = bannerIndex - 1;
        int si = slotIndex - 1;
        if (bi < 0 || bi >= ghosts.size() || si < 0 || si > 1) return Collections.emptyMap();

        CompoundTag itemData = ghosts.getCompound(bi);
        String key = "G" + si;
        if (!itemData.contains(key)) return Collections.emptyMap();

        CompoundTag item = itemData.getCompound(key);
        if (item.isEmpty()) return Collections.emptyMap();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getString("id"));
        result.put("count", item.contains("Count") ? item.getInt("Count") : 1);
        if (item.contains("tag")) {
            result.put("nbt", item.getString("tag"));
        }
        return result;
    }

    /**
     * 获取 receiver 所有频道的列表（按索引顺序）。
     *
     * @return 频道号数组表，如 {@code {1, 5, 7}}
     */
    @LuaFunction(mainThread = true)
    public final List<Integer> getChannels() {
        CompoundTag data = be.getBannerData();
        ListTag channels = data.getList("Channels", Tag.TAG_INT);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            result.add(((net.minecraft.nbt.IntTag) channels.get(i)).getAsInt());
        }
        return result;
    }

    // ════════════════════ Create 红石信号查询 ════════════════════

    /**
     * 根据频道号找到对应的 banner，读取其幽灵槽物品，
     * 以这两个物品作为 Create Redstone Link 的频率键，查询该频率网络的当前红石信号强度。
     *
     * <pre>{@code
     * -- 查询频道 7 对应的 Create 红石信号
     * local signal = receiver.getRedstoneSignal(7)
     * print("频道 7 的 Create 红石信号: " .. signal)
     * }</pre>
     *
     * @param channel 频道号
     * @return 0-15 的红石信号强度，频道不存在或 Create 未加载时返回 0
     */
    @LuaFunction(mainThread = true)
    public final int getRedstoneSignal(int channel) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return 0;

        // 1. 根据频道号找到对应 banner 的幽灵物品
        ItemStack[] ghosts = getGhostItemsByChannel(channel);
        if (ghosts == null) return 0;

        // 2. 查询 Create 红石网络
        return CreateRedstoneCompat.getNetworkSignal(level, ghosts[0], ghosts[1]);
    }

    /**
     * 向指定频道对应的 Create Redstone Link 网络发送红石信号。
     * <p>
     * 根据频道号找到对应的 banner，读取其幽灵槽中的两个物品作为频率键，
     * 创建一个虚拟发送端加入该频率的 Create 红石网络。
     * 同频网络中的其他接收端（Redstone Link Receiver 模式）将收到此信号。
     *
     * <pre>{@code
     * -- 向频道 7 对应的 Create 红石网络发送满信号
     * receiver.setRedstoneSignal(7, 15)
     *
     * -- 关闭信号
     * receiver.setRedstoneSignal(7, 0)
     * }</pre>
     *
     * @param channel 频道号
     * @param signal  0-15 的红石信号强度（自动钳位）
     */
    @LuaFunction(mainThread = true)
    public final void setRedstoneSignal(int channel, int signal) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;

        ItemStack[] ghosts = getGhostItemsByChannel(channel);
        if (ghosts == null) return;

        CreateRedstoneCompat.setNetworkSignal(
                level, be.getBlockPos(), ghosts[0], ghosts[1], signal);
    }

    /**
     * 根据频道号获取 banner 的两个幽灵物品。
     *
     * @return ItemStack[2]，找不到时返回 null
     */
    private @Nullable ItemStack[] getGhostItemsByChannel(int channel) {
        CompoundTag data = be.getBannerData();
        if (data.isEmpty()) return null;

        ListTag channels = data.getList("Channels", Tag.TAG_INT);
        ListTag ghosts = data.getList("Ghosts", Tag.TAG_COMPOUND);

        // 查找匹配频道号的 banner 索引
        int bannerIndex = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (((net.minecraft.nbt.IntTag) channels.get(i)).getAsInt() == channel) {
                bannerIndex = i;
                break;
            }
        }
        if (bannerIndex < 0 || bannerIndex >= ghosts.size()) return null;

        // 解析两个幽灵槽的物品
        HolderLookup.Provider registries = be.getLevel().registryAccess();
        CompoundTag itemData = ghosts.getCompound(bannerIndex);
        ItemStack slot0 = ItemStack.parseOptional(registries, itemData.getCompound("G0"));
        ItemStack slot1 = ItemStack.parseOptional(registries, itemData.getCompound("G1"));

        return new ItemStack[]{slot0, slot1};
    }
}
