package com.zzy205.myfirstmod.compat.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create Redstone Link 兼容层。
 * <p>
 * 直接使用 Create API，无需 Mixin。
 * <ul>
 *   <li>{@link #getNetworkSignal(Level, ItemStack, ItemStack)} — 读取红石信号</li>
 *   <li>{@link #setNetworkSignal(Level, BlockPos, ItemStack, ItemStack, int)} — 写入红石信号（虚拟发送端）</li>
 * </ul>
 */
public final class CreateRedstoneCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ccpe:CreateRedstoneCompat");

    private static final RedstoneLinkNetworkHandler HANDLER = Create.REDSTONE_LINK_NETWORK_HANDLER;

    // ════════════════════ 虚拟发送端追踪 ════════════════════
    /** BlockPos -> (FrequencyCouple -> VirtualLinkableEntry) */
    private static final Map<BlockPos, Map<Couple<Frequency>, VirtualLinkableEntry>> activeLinkables =
            new ConcurrentHashMap<>();

    private static class VirtualLinkableEntry {
        final IRedstoneLinkable proxy;
        int signal;

        VirtualLinkableEntry(IRedstoneLinkable proxy, int signal) {
            this.proxy = proxy;
            this.signal = signal;
        }
    }

    private CreateRedstoneCompat() {}

    // ════════════════════ 读取 ════════════════════

    /**
     * 查询指定物品对（频率键）对应的 Create 红石网络当前信号强度。
     */
    public static int getNetworkSignal(Level level, ItemStack first, ItemStack second) {
        if (level == null || level.isClientSide) return 0;

        Couple<Frequency> couple = makeFrequencyCouple(first, second);
        Map<Couple<Frequency>, Set<IRedstoneLinkable>> networks = HANDLER.networksIn(level);
        Set<IRedstoneLinkable> network = networks.get(couple);
        if (network == null || network.isEmpty()) return 0;

        int maxPower = 0;
        for (IRedstoneLinkable linkable : network) {
            if (maxPower >= 15) break;
            if (!linkable.isAlive()) continue;
            int strength = linkable.getTransmittedStrength();
            if (strength > maxPower) maxPower = strength;
        }
        return maxPower;
    }

    // ════════════════════ 写入（虚拟发送端） ════════════════════

    /**
     * 向指定物品对对应的 Create 红石网络发送信号。
     * <p>
     * 通过 {@code Proxy} 创建一个虚拟 {@link IRedstoneLinkable} 发送端注册到网络中。
     * 网络中的信号值取所有发送端的最大值。
     *
     * @param signal 0-15，0 表示关闭并移除虚拟发送端
     */
    public static void setNetworkSignal(Level level, BlockPos pos, ItemStack first, ItemStack second, int signal) {
        if (level == null || level.isClientSide) return;

        signal = Math.clamp(signal, 0, 15);
        Couple<Frequency> couple = makeFrequencyCouple(first, second);

        Map<Couple<Frequency>, VirtualLinkableEntry> posEntries =
                activeLinkables.computeIfAbsent(pos.immutable(), k -> new ConcurrentHashMap<>());

        VirtualLinkableEntry existing = posEntries.get(couple);

        if (signal == 0) {
            if (existing != null) {
                HANDLER.removeFromNetwork(level, existing.proxy);
                posEntries.remove(couple);
                if (posEntries.isEmpty()) activeLinkables.remove(pos.immutable());
            }
            return;
        }

        if (existing != null) {
            existing.signal = signal;
            // 同步更新虚拟发送端的实际信号值，确保 getTransmittedStrength() 反映最新值
            if (existing.proxy instanceof VirtualRedstoneLinkable vrl) {
                vrl.signal = signal;
            }
        } else {
            IRedstoneLinkable proxy = makeVirtualLinkable(pos, couple, signal);
            VirtualLinkableEntry entry = new VirtualLinkableEntry(proxy, signal);
            posEntries.put(couple, entry);
            HANDLER.addToNetwork(level, proxy);
        }

        VirtualLinkableEntry current = posEntries.get(couple);
        if (current != null) {
            HANDLER.updateNetworkOf(level, current.proxy);
        }
    }

    /**
     * 移除指定位置的所有虚拟发送端。应在 Receiver BE 被移除时调用。
     */
    public static void cleanupFor(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        Map<Couple<Frequency>, VirtualLinkableEntry> posEntries = activeLinkables.remove(immutable);
        if (posEntries == null || posEntries.isEmpty()) return;

        for (VirtualLinkableEntry entry : posEntries.values()) {
            try {
                // removeFromNetwork 内部操作 connections map，level 为 null 也可维护
                HANDLER.removeFromNetwork(null, entry.proxy);
            } catch (Exception ignored) {}
        }
    }

    // ════════════════════ 内部工具 ════════════════════

    private static Couple<Frequency> makeFrequencyCouple(ItemStack first, ItemStack second) {
        return Couple.create(
                Frequency.of(first != null ? first : ItemStack.EMPTY),
                Frequency.of(second != null ? second : ItemStack.EMPTY));
    }

    /** 直接实现 IRedstoneLinkable */
    private static IRedstoneLinkable makeVirtualLinkable(BlockPos pos, Couple<Frequency> couple, int signal) {
        return new VirtualRedstoneLinkable(pos, couple, signal);
    }

    /**
     * IRedstoneLinkable 的虚拟实现，行为为发送端（isListening=false），持有可变信号值。
     */
    private static class VirtualRedstoneLinkable implements IRedstoneLinkable {
        private final BlockPos pos;
        private final Couple<Frequency> couple;
        private int signal;

        VirtualRedstoneLinkable(BlockPos pos, Couple<Frequency> couple, int signal) {
            this.pos = pos;
            this.couple = couple;
            this.signal = signal;
        }

        @Override
        public int getTransmittedStrength() { return signal; }

        @Override
        public void setReceivedStrength(int strength) { }

        @Override
        public boolean isListening() { return false; }

        @Override
        public boolean isAlive() { return true; }

        @Override
        public Couple<Frequency> getNetworkKey() { return couple; }

        @Override
        public BlockPos getLocation() { return pos; }

        @Override
        public String toString() { return "VirtualLinkable[pos=" + pos + "]"; }
    }
}
