package com.zzy205.myfirstmod.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Receiver 右键菜单 —— 上方 banner 窗口 + 下方玩家物品栏。
 */
public class RedstoneTransceiverMenu extends AbstractContainerMenu {

    // ════════════════ 槽位布局 ════════════════
    /** 物品栏左上角 X */
    static final int SLOT_X = 16;
    /** 物品栏第一行 Y */
    static final int INV_Y = 183;
    /** 快捷栏 Y */
    static final int HOTBAR_Y = 241;
    private static final int SLOT_W = 18;

    private final BlockPos receiverPos;
    private final CompoundTag bannerData;
    /** 已被占用的频道号快照（服务端打开时发送，客户端用于跳过） */
    private final int[] occupiedChannels;
    private final int loadMode;
    private final boolean onPhysicsBody;

    // ── 服务端构造 ──
    public RedstoneTransceiverMenu(int containerId, BlockPos receiverPos, CompoundTag bannerData,
                          int[] occupiedChannels, int loadMode, boolean onPhysicsBody, Inventory playerInv) {
        super(MyModMenus.REDSTONE_TRANSCEIVER_MENU.get(), containerId);
        this.receiverPos = receiverPos;
        this.bannerData = bannerData;
        this.occupiedChannels = occupiedChannels;
        this.loadMode = loadMode;
        this.onPhysicsBody = onPhysicsBody;
        addPlayerSlots(playerInv);
    }

    // ── 客户端构造（由 IContainerFactory 从网络包创建）──
    public RedstoneTransceiverMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.REDSTONE_TRANSCEIVER_MENU.get(), containerId);
        this.receiverPos = extraData.readBlockPos();
        this.bannerData = extraData.readNbt();
        int count = extraData.readVarInt();
        this.occupiedChannels = new int[count];
        for (int i = 0; i < count; i++) {
            this.occupiedChannels[i] = extraData.readVarInt();
        }
        this.loadMode = extraData.readVarInt();
        this.onPhysicsBody = extraData.readBoolean();
        addPlayerSlots(inv);
    }

    private void addPlayerSlots(Inventory playerInv) {
        // 物品栏（3 行 × 9 格）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        SLOT_X + col * SLOT_W, INV_Y + row * SLOT_W));
            }
        }
        // 快捷栏（1 行 × 9 格）
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col,
                    SLOT_X + col * SLOT_W, HOTBAR_Y));
        }
    }

    public BlockPos getReceiverPos() {
        return receiverPos;
    }

    public CompoundTag getBannerData() {
        return bannerData;
    }

    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    public int getLoadMode() { return loadMode; }
    public boolean isOnPhysicsBody() { return onPhysicsBody; }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // shift 快速移动：背包 ↔ 快捷栏
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int totalSlots = 36; // 0-8 快捷栏, 9-35 背包
        if (index < totalSlots) {
            // 玩家物品栏 → 无处可去（无容器槽位），不做移动
            return ItemStack.EMPTY;
        }

        if (!this.moveItemStackTo(stack, 0, totalSlots, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return copy;
    }
}
