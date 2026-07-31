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

import javax.annotation.Nullable;
import java.util.*;

/**
 * Receiver 锟?CC:Tweaked 澶栬瀹炵幇锟?
 * <p>
 * 鏀寔 {@code peripheral.wrap("right")} 锟?{@code peripheral.find("ccnavigation:receiver")}锟?
 * Lua 绔彲璇诲彇 Receiver 锟?banner 棰戦亾閰嶇疆鍜屽菇鐏电墿鍝侊拷?
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

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?Lua API 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    /**
     * 鑾峰彇 receiver 褰撳墠锟?banner 鏁伴噺锟?
     */
    @LuaFunction(mainThread = true)
    public final int getBannerCount() {
        CompoundTag data = be.getBannerData();
        return data.contains("Count") ? data.getInt("Count") : 0;
    }

    /**
     * 鑾峰彇鎸囧畾绱㈠紩 banner 鐨勯閬撳彿锟?
     *
     * @param index banner 绱㈠紩锟?-based锛屼笌 GUI 淇濇寔涓€鑷达級
     * @return 棰戦亾鍙凤紝绱㈠紩瓒婄晫杩斿洖 nil
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
     * 鑾峰彇鎸囧畾 banner 鐨勫菇鐏垫Ы鐗╁搧淇℃伅锟?
     *
     * @param bannerIndex banner 绱㈠紩锟?-based锟?
     * @param slotIndex   骞界伒妲界储寮曪紙1-based锟? 锟?2锟?
     * @return 鐗╁搧淇℃伅 table {@code {id="minecraft:stone", count=1}}锛岀┖妲借繑鍥炵┖ table
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
     * 鑾峰彇 receiver 鎵€鏈夐閬撶殑鍒楄〃锛堟寜绱㈠紩椤哄簭锛夛拷?
     *
     * @return 棰戦亾鍙锋暟缁勮〃锛屽 {@code {1, 5, 7}}
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

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?Create 绾㈢煶淇″彿鏌ヨ 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    /**
     * 鏍规嵁棰戦亾鍙锋壘鍒板搴旂殑 banner锛岃鍙栧叾骞界伒妲界墿鍝侊紝
     * 浠ヨ繖涓や釜鐗╁搧浣滀负 Create Redstone Link 鐨勯鐜囬敭锛屾煡璇㈣棰戠巼缃戠粶鐨勫綋鍓嶇孩鐭充俊鍙峰己搴︼拷?
     *
     * <pre>{@code
     * -- 鏌ヨ棰戦亾 7 瀵瑰簲锟?Create 绾㈢煶淇″彿
     * local signal = receiver.getRedstoneSignal(7)
     * print("棰戦亾 7 锟?Create 绾㈢煶淇″彿: " .. signal)
     * }</pre>
     *
     * @param channel 棰戦亾锟?
     * @return 0-15 鐨勭孩鐭充俊鍙峰己搴︼紝棰戦亾涓嶅瓨鍦ㄦ垨 Create 鏈姞杞芥椂杩斿洖 0
     */
    @LuaFunction(mainThread = true)
    public final int getRedstoneSignal(int channel) {
        if (be.getLevel() == null || be.getLevel().isClientSide) return 0;

        // 1. 鏍规嵁棰戦亾鍙锋壘鍒板锟?banner 鐨勫菇鐏电墿锟?
        ItemStack[] ghosts = getGhostItemsByChannel(channel);
        if (ghosts == null) return 0;

        // 2. 鏌ヨ Create 绾㈢煶缃戠粶
        return CreateRedstoneCompat.getNetworkSignal(be.getLevel(), ghosts[0], ghosts[1]);
    }

    /**
     * 鍚戞寚瀹氶閬撳搴旂殑 Create Redstone Link 缃戠粶鍙戦€佺孩鐭充俊鍙凤拷?
     * <p>
     * 鏍规嵁棰戦亾鍙锋壘鍒板搴旂殑 banner锛岃鍙栧叾骞界伒妲戒腑鐨勪袱涓墿鍝佷綔涓洪鐜囬敭锟?
     * 鍒涘缓涓€涓櫄鎷熷彂閫佺鍔犲叆璇ラ鐜囩殑 Create 绾㈢煶缃戠粶锟?
     * 鍚岄缃戠粶涓殑鍏朵粬鎺ユ敹绔紙Redstone Link Receiver 妯″紡锛夊皢鏀跺埌姝や俊鍙凤拷?
     *
     * <pre>{@code
     * -- 鍚戦锟?7 瀵瑰簲锟?Create 绾㈢煶缃戠粶鍙戦€佹弧淇″彿
     * receiver.setRedstoneSignal(7, 15)
     *
     * -- 鍏抽棴淇″彿
     * receiver.setRedstoneSignal(7, 0)
     * }</pre>
     *
     * @param channel 棰戦亾锟?
     * @param signal  0-15 鐨勭孩鐭充俊鍙峰己搴︼紙鑷姩閽充綅锟?
     */
    @LuaFunction(mainThread = true)
    public final void setRedstoneSignal(int channel, int signal) {
        if (be.getLevel() == null || be.getLevel().isClientSide) return;

        ItemStack[] ghosts = getGhostItemsByChannel(channel);
        if (ghosts == null) return;

        CreateRedstoneCompat.setNetworkSignal(
                be.getLevel(), be.getBlockPos(), ghosts[0], ghosts[1], signal);
    }

    /**
     * 鏍规嵁棰戦亾鍙疯幏锟?banner 鐨勪袱涓菇鐏电墿鍝侊拷?
     *
     * @return ItemStack[2]锛屾壘涓嶅埌鏃惰繑锟?null
     */
    private @Nullable ItemStack[] getGhostItemsByChannel(int channel) {
        CompoundTag data = be.getBannerData();
        if (data.isEmpty()) return null;

        ListTag channels = data.getList("Channels", Tag.TAG_INT);
        ListTag ghosts = data.getList("Ghosts", Tag.TAG_COMPOUND);

        // 鏌ユ壘鍖归厤棰戦亾锟?banner 绱㈠紩
        int bannerIndex = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (((net.minecraft.nbt.IntTag) channels.get(i)).getAsInt() == channel) {
                bannerIndex = i;
                break;
            }
        }
        if (bannerIndex < 0 || bannerIndex >= ghosts.size()) return null;

        // 瑙ｆ瀽涓や釜骞界伒妲界殑鐗╁搧
        HolderLookup.Provider registries = be.getLevel().registryAccess();
        CompoundTag itemData = ghosts.getCompound(bannerIndex);
        ItemStack slot0 = ItemStack.parseOptional(registries, itemData.getCompound("G0"));
        ItemStack slot1 = ItemStack.parseOptional(registries, itemData.getCompound("G1"));

        return new ItemStack[]{slot0, slot1};
    }
}
