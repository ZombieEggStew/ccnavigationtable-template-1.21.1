package com.zzy205.myfirstmod.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 短程信号链接器菜单。经 extraData 传递链接器方块坐标、「是否在物理体上」标志、
 * 当前频道、链内（物理体内）占用频道快照与当前「加载物理体」开关值。
 * 照 {@link PeripheralExtenderMenu} 模式：服务端构造存默认值，客户端构造从 extraData 读取
 * （与服务端打开 GUI 同一帧到达，保证最新）。
 */
public class ShortRangeLinkerMenu extends AbstractContainerMenu {

    private final BlockPos linkerPos;
    /** 链接器是否在 Sable 物理体上（客户端 GUI 据此显示提示 / 控件区） */
    private final boolean onPhysicsBody;
    /** 当前频道号（客户端构造时从 extraData 读取） */
    private final int linkerChannel;
    /** 链内已占用频道号数组（客户端构造时从 extraData 读取，GUI 滚轮跳过占用用） */
    private final int[] occupiedChannels;
    /** 当前「加载物理体」开关值（客户端构造时从 extraData 读取） */
    private final boolean bodyLoad;

    // ── 服务端构造（openMenu 时创建，真实值由方块写入 extraData）──
    public ShortRangeLinkerMenu(int containerId, BlockPos linkerPos, Inventory playerInv, boolean onPhysicsBody) {
        super(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), containerId);
        this.linkerPos = linkerPos;
        this.onPhysicsBody = onPhysicsBody;
        this.linkerChannel = -1;
        this.occupiedChannels = new int[0];
        this.bodyLoad = false;
    }

    // ── 客户端构造（网络端由 IContainerFactory 创建）──
    public ShortRangeLinkerMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), containerId);
        this.linkerPos = extraData.readBlockPos();
        this.onPhysicsBody = extraData.readBoolean();
        this.linkerChannel = extraData.readVarInt();
        int count = extraData.readVarInt();
        this.occupiedChannels = new int[count];
        for (int i = 0; i < count; i++) {
            this.occupiedChannels[i] = extraData.readVarInt();
        }
        this.bodyLoad = extraData.readBoolean();
    }

    public BlockPos getLinkerPos() {
        return linkerPos;
    }

    public boolean isOnPhysicsBody() {
        return onPhysicsBody;
    }

    /** 当前频道号（客户端可用；-1 表示未知，Screen 回退读客户端 BE） */
    public int getLinkerChannel() {
        return linkerChannel;
    }

    /** 链内已占用频道号数组（客户端可用） */
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    /** 当前「加载物理体」开关值（客户端可用） */
    public boolean isBodyLoad() {
        return bodyLoad;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // 无槽位，Shift+点击无操作
    }
}
