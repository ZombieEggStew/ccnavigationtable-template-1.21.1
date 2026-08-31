package com.zzy205.myfirstmod.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 短程信号链接器菜单。当前阶段（GUI 只显示背景）仅传递链接器方块坐标；
 * 频道 / 体内占用快照 / bodyLoad 将在后续阶段通过 extraData 传入。
 */
public class ShortRangeLinkerMenu extends AbstractContainerMenu {

    private final BlockPos linkerPos;

    // ── 服务端构造（openMenu 时创建）──
    public ShortRangeLinkerMenu(int containerId, BlockPos linkerPos, Inventory playerInv) {
        super(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), containerId);
        this.linkerPos = linkerPos;
    }

    // ── 客户端构造（网络端由 IContainerFactory 创建）──
    public ShortRangeLinkerMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), containerId);
        this.linkerPos = extraData.readBlockPos();
    }

    public BlockPos getLinkerPos() {
        return linkerPos;
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
