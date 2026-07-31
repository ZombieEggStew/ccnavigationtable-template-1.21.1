package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.network.SensorItemPayload;  // 骞界伒鐗╁搧宸叉敞锟?
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;  // 骞界伒鐗╁搧宸叉敞锟?
import net.minecraft.world.inventory.Slot;  // 鐜╁鐗╁搧鏍忓凡娉ㄩ噴
import net.minecraft.world.item.ItemStack;  // 鐜╁鐗╁搧鏍忓凡娉ㄩ噴
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 浼犳劅锟?NBT 鏌ョ湅鑿滃崟銆備笂鏂规樉锟?NBT 鏁版嵁锛屼笅鏂规樉绀虹帺瀹惰儗鍖咃拷?
 */
public class PeripheralExtenderMenu extends AbstractContainerMenu {

    // 鈺愨晲锟?妲戒綅甯冨眬锛堜笌 Create Redstone Link GUI 涓€鑷达級 鈺愨晲锟?
    // static final int SLOT_X = 48;
    // static final int SLOT_STEP = 18;
    // static final int INV_Y = 212;
    // static final int HOTBAR_Y = 270;

    // /** 骞界伒鐗╁搧妲戒綅缃紙瑕嗙洊灞傜獥鍙ｅ唴鍙充晶锟?*/ 
    // static final int GHOST_SLOT_X = 167;
    // static final int GHOST_SLOT_2_X = 188;
    // static final int GHOST_SLOT_Y = 36;
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    private final BlockPos sensorPos;
    private final CompoundTag attachedNBT;
    private final Level level;

    /** 褰撳墠浼犳劅鍣ㄧ殑棰戦亾鍙凤紙瀹㈡埛绔瀯閫犳椂锟?extraData 璇诲彇锟?*/
    private final int sensorChannel;
    /** 宸茶鍗犵敤鐨勯閬撳彿鍒楄〃锛堝鎴风鏋勯€犳椂锟?extraData 璇诲彇锟?*/
    private final int[] occupiedChannels;
    /** GUI 寮€鍏崇姸鎬侊紙瀹㈡埛绔瀯閫犳椂锟?extraData 璇诲彇锟?*/
    private final int loadMode;
    private final boolean onPhysicsBody;

    // 鈹€鈹€ 鏈嶅姟绔瀯锟?鈹€鈹€
    public PeripheralExtenderMenu(int containerId, BlockPos sensorPos, CompoundTag attachedNBT, Inventory playerInv) {
        super(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), containerId);
        this.sensorPos = sensorPos;
        this.attachedNBT = attachedNBT;
        this.level = playerInv.player.level();
        this.sensorChannel = -1;
        this.occupiedChannels = new int[0];
        this.loadMode = 0;
        this.onPhysicsBody = false;
        // addGhostSlot();
        // addPlayerSlots(playerInv);
    }

    // 鈹€鈹€ 瀹㈡埛绔瀯閫狅紙锟?IContainerFactory 鍦ㄧ綉缁滅鍒涘缓鏃惰皟鐢級鈹€鈹€
    public PeripheralExtenderMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), containerId);
        this.sensorPos = extraData.readBlockPos();
        this.attachedNBT = extraData.readNbt();
        this.level = inv.player.level();
        this.sensorChannel = extraData.readVarInt();
        int count = extraData.readVarInt();
        this.occupiedChannels = new int[count];
        for (int i = 0; i < count; i++) {
            this.occupiedChannels[i] = extraData.readVarInt();
        }
        this.loadMode = extraData.readVarInt();
        this.onPhysicsBody = extraData.readBoolean();
        // addGhostSlot();  // 骞界伒鐗╁搧宸叉敞锟?
        // addPlayerSlots(inv);  // 鐜╁鐗╁搧鏍忓凡娉ㄩ噴
    }

    // /** 娣诲姞骞界伒鐗╁搧妲斤紙妲戒綅 0 锟?1锛夛拷?*/  
    // private void addGhostSlot() {
    //     // 妲戒綅 0
    //     this.addSlot(new GhostItemSlot(0, GHOST_SLOT_X, GHOST_SLOT_Y,
    //             () -> getBE() != null ? getBE().getDisplayItem(0) : ItemStack.EMPTY,
    //             (id, stack) -> { if (getBE() != null) getBE().setDisplayItem(id, stack); }
    //     ));
    //     // 妲戒綅 1锛堝彸锟?3px 闂撮殧锟?
    //     this.addSlot(new GhostItemSlot(1, GHOST_SLOT_2_X, GHOST_SLOT_Y,
    //             () -> getBE() != null ? getBE().getDisplayItem(1) : ItemStack.EMPTY,
    //             (id, stack) -> { if (getBE() != null) getBE().setDisplayItem(id, stack); }
    //     ));
    // }

    // private PeripheralExtenderBlockEntity getBE() {  // 骞界伒鐗╁搧宸叉敞锟?
    //     BlockEntity be = level.getBlockEntity(sensorPos);
    //     return be instanceof PeripheralExtenderBlockEntity sensorBE ? sensorBE : null;
    // }

    // private void addPlayerSlots(Inventory playerInv) { ... }  // 鐜╁鐗╁搧鏍忓凡娉ㄩ噴

    public BlockPos getSensorPos() {
        return sensorPos;
    }

    public CompoundTag getAttachedNBT() {
        return attachedNBT;
    }

    /** 鑾峰彇褰撳墠浼犳劅鍣ㄧ殑棰戦亾鍙凤紙瀹㈡埛绔彲鐢級 */
    public int getSensorChannel() {
        return sensorChannel;
    }

    /** 鑾峰彇宸茶鍗犵敤鐨勯閬撳彿鏁扮粍锛堝鎴风鍙敤锟?*/
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    public int getLoadMode() { return loadMode; }
    public boolean isOnPhysicsBody() { return onPhysicsBody; }

    // @Override
    // public ItemStack quickMoveStack(Player player, int index) {
    //     return ItemStack.EMPTY;
    // }

    // /** 鎷︽埅骞界伒鐗╁搧妲界殑宸﹂敭/鍙抽敭鐐瑰嚮 */  // 骞界伒鐗╁搧宸叉敞锟?
    // @Override
    // public void clicked(int slotId, int button, ClickType clickType, Player player) {
    //     if (slotId >= 0 && slotId < 2 && (clickType == ClickType.PICKUP || clickType == ClickType.THROW)) {
    //         handleGhostSlotClick(slotId, button, clickType, player);
    //         return;
    //     }
    //     super.clicked(slotId, button, clickType, player);
    // }

    // private void handleGhostSlotClick(int slotId, int button, ClickType clickType, Player player) { ... }  // 骞界伒鐗╁搧宸叉敞锟?

    // private void sendItemUpdate(int slotIndex, ItemStack stack) { ... }  // 骞界伒鐗╁搧宸叉敞锟?

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * 澶勭悊瀹㈡埛绔彂鏉ョ殑鎸夐挳鐐瑰嚮锛坕d=0 琛ㄧず璇锋眰鍒锋柊 NBT锛夛拷?
     * 鏈嶅姟绔埛鏂版暟鎹悗閫氳繃鑷畾涔夌綉缁滃寘鐩存帴鎺ㄩ€佺粰瀹㈡埛绔拷?
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0 && player instanceof ServerPlayer serverPlayer && player.level() instanceof ServerLevel serverLevel) {
            BlockEntity be = serverLevel.getBlockEntity(sensorPos);
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                CompoundTag fresh = sensorBE.refreshAndGet(serverLevel, serverLevel.getBlockState(sensorPos));
                // 鐩存帴鎺ㄩ€佺粰璇ョ帺瀹讹紝涓嶄緷锟?sendBlockUpdated
                PacketDistributor.sendToPlayer(serverPlayer, new SensorNbtPayload(sensorPos, fresh));
                // 鍚屾椂鍒锋柊 occupiedChannels 蹇収
                sensorBE.refreshOccupiedChannels();
            }
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;  // 鏃犳Ы浣嶏紝Shift+鐐瑰嚮鏃犳搷锟?
    }
}
