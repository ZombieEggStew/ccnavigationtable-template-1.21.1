package com.zzy205.myfirstmod.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 短程信号链接器菜单。传递链接器方块坐标与「是否在物理体上」标志；
 * 频道 / 体内占用快照 / bodyLoad 将在后续阶段通过 extraData 传入。
 */
public class ShortRangeLinkerMenu extends AbstractContainerMenu {

    private final BlockPos linkerPos;
    /** 链接器是否在 Sable 物理体上（客户端 GUI 据此显示提示 / 控件区） */
    private final boolean onPhysicsBody;

    // ── 服务端构造（openMenu 时创建）──
    public ShortRangeLinkerMenu(int containerId, BlockPos linkerPos, Inventory playerInv, boolean onPhysicsBody) {
        super(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), containerId);
        this.linkerPos = linkerPos;
        this.onPhysicsBody = onPhysicsBody;
    }

    // ── 客户端构造（网络端由 IContainerFactory 创建）──
    public ShortRangeLinkerMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.SHORT_RANGE_LINKER_MENU.get(), containerId);
        this.linkerPos = extraData.readBlockPos();
        this.onPhysicsBody = extraData.readBoolean();
    }

    public BlockPos getLinkerPos() {
        return linkerPos;
    }

    public boolean isOnPhysicsBody() {
        return onPhysicsBody;
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
