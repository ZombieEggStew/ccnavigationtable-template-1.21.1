package com.zzy205.myfirstmod.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Receiver 右键菜单 —— 最简单的空容器，仅用于打开 GUI 窗口。
 */
public class MyReceiverMenu extends AbstractContainerMenu {

    private final BlockPos receiverPos;

    // ── 服务端构造 ──
    public MyReceiverMenu(int containerId, BlockPos receiverPos, Inventory playerInv) {
        super(MyModMenus.RECEIVER_MENU.get(), containerId);
        this.receiverPos = receiverPos;
    }

    // ── 客户端构造（由 IContainerFactory 从网络包创建）──
    public MyReceiverMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.RECEIVER_MENU.get(), containerId);
        this.receiverPos = extraData.readBlockPos();
    }

    public BlockPos getReceiverPos() {
        return receiverPos;
    }

    @Override
    public boolean stillValid(net.minecraft.world.entity.player.Player player) {
        return true;
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
