package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.network.SensorItemPayload;  // 幽灵物品已注释
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;  // 幽灵物品已注释
import net.minecraft.world.inventory.Slot;  // 玩家物品栏已注释
import net.minecraft.world.item.ItemStack;  // 玩家物品栏已注释
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 传感器 NBT 查看菜单。上方显示 NBT 数据，下方显示玩家背包。
 */
public class MySensorMenu extends AbstractContainerMenu {

    // ═══ 槽位布局（与 Create Redstone Link GUI 一致） ═══
    // static final int SLOT_X = 48;
    // static final int SLOT_STEP = 18;
    // static final int INV_Y = 212;
    // static final int HOTBAR_Y = 270;

    // /** 幽灵物品槽位置（覆盖层窗口内右侧） */ 
    // static final int GHOST_SLOT_X = 167;
    // static final int GHOST_SLOT_2_X = 188;
    // static final int GHOST_SLOT_Y = 36;
    // ═══════════════════════════════════════

    private final BlockPos sensorPos;
    private final CompoundTag attachedNBT;
    private final Level level;

    /** 当前传感器的频道号（客户端构造时从 extraData 读取） */
    private final int sensorChannel;
    /** 已被占用的频道号列表（客户端构造时从 extraData 读取） */
    private final int[] occupiedChannels;

    // ── 服务端构造 ──
    public MySensorMenu(int containerId, BlockPos sensorPos, CompoundTag attachedNBT, Inventory playerInv) {
        super(MyModMenus.SENSOR_MENU.get(), containerId);
        this.sensorPos = sensorPos;
        this.attachedNBT = attachedNBT;
        this.level = playerInv.player.level();
        this.sensorChannel = -1;
        this.occupiedChannels = new int[0];
        // addGhostSlot();
        // addPlayerSlots(playerInv);
    }

    // ── 客户端构造（由 IContainerFactory 在网络端创建时调用）──
    public MySensorMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(MyModMenus.SENSOR_MENU.get(), containerId);
        this.sensorPos = extraData.readBlockPos();
        this.attachedNBT = extraData.readNbt();
        this.level = inv.player.level();
        this.sensorChannel = extraData.readVarInt();
        int count = extraData.readVarInt();
        this.occupiedChannels = new int[count];
        for (int i = 0; i < count; i++) {
            this.occupiedChannels[i] = extraData.readVarInt();
        }
        // addGhostSlot();  // 幽灵物品已注释
        // addPlayerSlots(inv);  // 玩家物品栏已注释
    }

    // /** 添加幽灵物品槽（槽位 0 和 1）。 */  
    // private void addGhostSlot() {
    //     // 槽位 0
    //     this.addSlot(new GhostItemSlot(0, GHOST_SLOT_X, GHOST_SLOT_Y,
    //             () -> getBE() != null ? getBE().getDisplayItem(0) : ItemStack.EMPTY,
    //             (id, stack) -> { if (getBE() != null) getBE().setDisplayItem(id, stack); }
    //     ));
    //     // 槽位 1（右边 3px 间隔）
    //     this.addSlot(new GhostItemSlot(1, GHOST_SLOT_2_X, GHOST_SLOT_Y,
    //             () -> getBE() != null ? getBE().getDisplayItem(1) : ItemStack.EMPTY,
    //             (id, stack) -> { if (getBE() != null) getBE().setDisplayItem(id, stack); }
    //     ));
    // }

    // private MySensorBlockEntity getBE() {  // 幽灵物品已注释
    //     BlockEntity be = level.getBlockEntity(sensorPos);
    //     return be instanceof MySensorBlockEntity sensorBE ? sensorBE : null;
    // }

    // private void addPlayerSlots(Inventory playerInv) { ... }  // 玩家物品栏已注释

    public BlockPos getSensorPos() {
        return sensorPos;
    }

    public CompoundTag getAttachedNBT() {
        return attachedNBT;
    }

    /** 获取当前传感器的频道号（客户端可用） */
    public int getSensorChannel() {
        return sensorChannel;
    }

    /** 获取已被占用的频道号数组（客户端可用） */
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    // @Override
    // public ItemStack quickMoveStack(Player player, int index) {
    //     return ItemStack.EMPTY;
    // }

    // /** 拦截幽灵物品槽的左键/右键点击 */  // 幽灵物品已注释
    // @Override
    // public void clicked(int slotId, int button, ClickType clickType, Player player) {
    //     if (slotId >= 0 && slotId < 2 && (clickType == ClickType.PICKUP || clickType == ClickType.THROW)) {
    //         handleGhostSlotClick(slotId, button, clickType, player);
    //         return;
    //     }
    //     super.clicked(slotId, button, clickType, player);
    // }

    // private void handleGhostSlotClick(int slotId, int button, ClickType clickType, Player player) { ... }  // 幽灵物品已注释

    // private void sendItemUpdate(int slotIndex, ItemStack stack) { ... }  // 幽灵物品已注释

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * 处理客户端发来的按钮点击（id=0 表示请求刷新 NBT）。
     * 服务端刷新数据后通过自定义网络包直接推送给客户端。
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0 && player instanceof ServerPlayer serverPlayer && player.level() instanceof ServerLevel serverLevel) {
            BlockEntity be = serverLevel.getBlockEntity(sensorPos);
            if (be instanceof MySensorBlockEntity sensorBE) {
                CompoundTag fresh = sensorBE.refreshAndGet(serverLevel, serverLevel.getBlockState(sensorPos));
                // 直接推送给该玩家，不依赖 sendBlockUpdated
                PacketDistributor.sendToPlayer(serverPlayer, new SensorNbtPayload(sensorPos, fresh));
                // 同时刷新 occupiedChannels 快照
                sensorBE.refreshOccupiedChannels();
            }
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;  // 无槽位，Shift+点击无操作
    }
}
