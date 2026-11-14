package com.zzy205.myfirstmod.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 矢量喷管菜单。经 extraData 传递喷嘴坐标、「是否在物理体上」标志、当前频道、
 * 同链（物理体内）占用频道快照。
 * <p>
 * 照 {@link ShortRangeLinkerMenu} 模式（服务端构造存默认值，客户端构造从 extraData 读取，
 * 与服务端打开 GUI 同一帧到达保证最新）。**不含「加载物理体」开关**（用户定稿：喷嘴不持有 ticket）。
 */
public class AdvancedThrustNozzleMenu extends AbstractContainerMenu {

    private final BlockPos nozzlePos;
    /** 喷嘴是否在 Sable 物理体上（客户端 GUI 据此显示提示 / 控件区） */
    private final boolean onPhysicsBody;
    /** 当前频道号（客户端构造时从 extraData 读取；-1 表示未知，Screen 会回退读客户端 BE） */
    private final int channel;
    /** 同链已占用频道号数组（GUI 滚轮跳过占用用） */
    private final int[] occupiedChannels;

    // ── 服务端构造（openMenu 时创建，真实值由方块写入 extraData）──
    public AdvancedThrustNozzleMenu(final int containerId, final BlockPos nozzlePos, final Inventory playerInv,
                                    final boolean onPhysicsBody) {
        super(MyModMenus.ADVANCED_THRUST_NOZZLE_MENU.get(), containerId);
        this.nozzlePos = nozzlePos;
        this.onPhysicsBody = onPhysicsBody;
        this.channel = -1;
        this.occupiedChannels = new int[0];
    }

    // ── 客户端构造（网络端由 IContainerFactory 创建）──
    public AdvancedThrustNozzleMenu(final int containerId, final Inventory inv,
                                    final RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.ADVANCED_THRUST_NOZZLE_MENU.get(), containerId);
        this.nozzlePos = extraData.readBlockPos();
        this.onPhysicsBody = extraData.readBoolean();
        this.channel = extraData.readVarInt();
        final int count = extraData.readVarInt();
        this.occupiedChannels = new int[count];
        for (int i = 0; i < count; i++) {
            this.occupiedChannels[i] = extraData.readVarInt();
        }
    }

    public BlockPos getNozzlePos() {
        return this.nozzlePos;
    }

    public boolean isOnPhysicsBody() {
        return this.onPhysicsBody;
    }

    /** 当前频道号（-1 = 未知，Screen 回退读客户端 BE） */
    public int getChannel() {
        return this.channel;
    }

    public int[] getOccupiedChannels() {
        return this.occupiedChannels;
    }

    @Override
    public boolean stillValid(final Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY; // 无槽位，Shift+点击无操作
    }
}
