package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.RedstoneTransceiverBlockEntity;
import com.zzy205.myfirstmod.compat.create.CreateRedstoneCompat;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Receiver 的 CC:Tweaked 外设实现。
 * <p>
 * 支持 {@code peripheral.wrap("right")} 和 {@code peripheral.find("ccpe:redstone_transceiver")}。
 * Lua 端可读取 Receiver 的 banner 频道配置和幽灵物品。
 */
public class RedstoneTransceiverPeripheral implements IPeripheral {

    private final RedstoneTransceiverBlockEntity be;

    public RedstoneTransceiverPeripheral(RedstoneTransceiverBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccpe:redstone_transceiver";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof RedstoneTransceiverPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public RedstoneTransceiverBlockEntity getBlockEntity() {
        return be;
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
    @LuaFunction
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

    // ════════════════════ 频率 / banner 管理 ════════════════════

    /**
     * 设置指定频道的 Create 红石链接频率物品（幽灵槽）。
     *
     * <pre>{@code
     * -- 频道 7 的频率设为 (红石, 红石)
     * r.setFrequency(7, "minecraft:redstone")
     *
     * -- 频道 7 的频率设为 (红石, 石头)
     * r.setFrequency(7, "minecraft:redstone", "minecraft:stone")
     *
     * -- 只新建一个空 banner（频道 7）
     * r.setFrequency(7)
     * }</pre>
     *
     * @param channel 频道号
     * @param freq1   槽位 1 的物品 ID（如 "minecraft:redstone"，裸名默认 minecraft 命名空间；nil/空串 = 空槽）
     * @param freq2   槽位 2 的物品 ID；留空时与槽位 1 相同
     * @return 是否成功（物品 ID 非法返回 false）
     */
    @LuaFunction(mainThread = true)
    public final boolean setFrequency(int channel, Optional<String> freq1, Optional<String> freq2) {
        ItemStack g0 = parseFrequencyItem(freq1.orElse(null));
        if (g0 == null) return false;

        ItemStack g1;
        if (freq2.isEmpty()) {
            g1 = g0; // 参数三留空：槽位 2 与槽位 1 相同
        } else {
            g1 = parseFrequencyItem(freq2.get());
            if (g1 == null) return false;
        }
        return be.setBannerFrequency(channel, g0, g1);
    }

    /**
     * 删除指定频道的 banner。
     *
     * @return 是否删除成功（频道不存在返回 false）
     */
    @LuaFunction(mainThread = true)
    public final boolean removeChannel(int channel) {
        return be.removeBanner(channel);
    }

    /**
     * 读取指定频道的两个频率物品 ID。
     *
     * @return {@code {freq1="minecraft:redstone", freq2="minecraft:stone"}}；空槽对应的键省略；频道不存在返回 nil
     */
    @LuaFunction
    public final @Nullable Map<String, Object> getFrequency(int channel) {
        ItemStack[] ghosts = be.getBannerGhosts(channel);
        if (ghosts == null) return null;

        Map<String, Object> result = new HashMap<>();
        if (!ghosts[0].isEmpty()) result.put("freq1", itemIdString(ghosts[0]));
        if (!ghosts[1].isEmpty()) result.put("freq2", itemIdString(ghosts[1]));
        return result;
    }

    /** 列出当前所有 banner 的频道号（返回 Lua 数组表） */
    @LuaFunction
    public final List<Integer> getChannels() {
        int[] channels = be.getBannerChannels();
        List<Integer> list = new ArrayList<>(channels.length);
        for (int ch : channels) list.add(ch);
        return list;
    }

    /**
     * 解析物品 ID 字符串为 ItemStack。
     *
     * @return ItemStack；nil/空串 → ItemStack.EMPTY；非法 ID → null（调用方应判错）
     */
    private static @Nullable ItemStack parseFrequencyItem(@Nullable String str) {
        if (str == null || str.isBlank()) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(str);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item == null) return null;
        return new ItemStack(item);
    }

    private static String itemIdString(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 根据频道号获取 banner 的两个幽灵物品。
     *
     * @return ItemStack[2]，找不到时返回 null
     */
    private @Nullable ItemStack[] getGhostItemsByChannel(int channel) {
        return be.getBannerGhosts(channel);
    }
}
