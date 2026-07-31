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
 * Receiver 鍙抽敭鑿滃崟 鈥旓拷?涓婃柟 banner 绐楀彛 + 涓嬫柟鐜╁鐗╁搧鏍忥拷?
 */
public class RedstoneTransceiverMenu extends AbstractContainerMenu {

    // 鈺愨晲锟?妲戒綅甯冨眬 鈺愨晲锟?
    /** 鐗╁搧鏍忓乏涓婅 X */
    static final int SLOT_X = 16;
    /** 鐗╁搧鏍忕涓€锟?Y */
    static final int INV_Y = 183;
    /** 蹇嵎锟?Y */
    static final int HOTBAR_Y = 241;
    private static final int SLOT_W = 18;

    private final BlockPos receiverPos;
    private final CompoundTag bannerData;
    /** 宸茶鍗犵敤鐨勯閬撳彿蹇収锛堟湇鍔＄鎵撳紑鏃跺彂閫侊紝瀹㈡埛绔敤浜庤烦杩囷級 */
    private final int[] occupiedChannels;
    private final int loadMode;
    private final boolean onPhysicsBody;

    // 鈹€鈹€ 鏈嶅姟绔瀯锟?鈹€鈹€
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

    // 鈹€鈹€ 瀹㈡埛绔瀯閫狅紙锟?IContainerFactory 浠庣綉缁滃寘鍒涘缓锛夆攢鈹€
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
        // 鐗╁搧鏍忥紙3 锟?脳 9 鏍硷級
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        SLOT_X + col * SLOT_W, INV_Y + row * SLOT_W));
            }
        }
        // 蹇嵎鏍忥紙1 锟?脳 9 鏍硷級
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

    // shift 蹇€熺Щ鍔細鑳屽寘 锟?蹇嵎锟?
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int totalSlots = 36; // 0-8 蹇嵎锟? 9-35 鑳屽寘
        if (index < totalSlots) {
            // 鐜╁鐗╁搧锟?锟?鏃犲鍙幓锛堟棤瀹瑰櫒妲戒綅锛夛紝涓嶅仛绉诲姩
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
