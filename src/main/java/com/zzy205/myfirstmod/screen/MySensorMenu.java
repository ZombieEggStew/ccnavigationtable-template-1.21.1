package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.network.SensorItemPayload;
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 传感器 NBT 查看菜单。上方显示 NBT 数据，下方显示玩家背包。
 */
public class MySensorMenu extends AbstractContainerMenu {

    // ═══ 槽位布局（与 Create Redstone Link GUI 一致） ═══
    static final int SLOT_X = 48;
    static final int SLOT_STEP = 18;
    static final int INV_Y = 212;       // 背包首行
    static final int HOTBAR_Y = 270;    // 快捷栏

    /** 幽灵物品槽位置（覆盖层窗口内右侧） */
    static final int GHOST_SLOT_X = 167;    // 槽位0 X
    static final int GHOST_SLOT_2_X = 188;  // 槽位1 X = 167 + 18 + 3（间隔3px）
    static final int GHOST_SLOT_Y = 36;     // Y 坐标
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
        addGhostSlot();
        addPlayerSlots(playerInv);
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
        addGhostSlot();
        addPlayerSlots(inv);
    }

    /** 添加幽灵物品槽（槽位 0 和 1）。 */
    private void addGhostSlot() {
        // 槽位 0
        this.addSlot(new GhostItemSlot(0, GHOST_SLOT_X, GHOST_SLOT_Y,
                () -> getBE() != null ? getBE().getDisplayItem(0) : ItemStack.EMPTY,
                (id, stack) -> { if (getBE() != null) getBE().setDisplayItem(id, stack); }
        ));
        // 槽位 1（右边 3px 间隔）
        this.addSlot(new GhostItemSlot(1, GHOST_SLOT_2_X, GHOST_SLOT_Y,
                () -> getBE() != null ? getBE().getDisplayItem(1) : ItemStack.EMPTY,
                (id, stack) -> { if (getBE() != null) getBE().setDisplayItem(id, stack); }
        ));
    }

    private MySensorBlockEntity getBE() {
        BlockEntity be = level.getBlockEntity(sensorPos);
        return be instanceof MySensorBlockEntity sensorBE ? sensorBE : null;
    }

    private void addPlayerSlots(Inventory playerInv) {
        // 背包 3×9（槽位 1~27）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        SLOT_X + col * SLOT_STEP, INV_Y + row * SLOT_STEP));
            }
        }
        // 快捷栏 1×9（槽位 28~36）
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col,
                    SLOT_X + col * SLOT_STEP, HOTBAR_Y));
        }
    }

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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 跳过幽灵槽（index 0, 1）
        if (index < 2) return ItemStack.EMPTY;

        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            ItemStack copy = stackInSlot.copy();
            // 2~28: 背包 → 移到快捷栏; 29~37: 快捷栏 → 移到背包
            if (index < 29) {
                if (!this.moveItemStackTo(stackInSlot, 29, 38, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stackInSlot, 2, 29, false)) {
                return ItemStack.EMPTY;
            }
            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return copy;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 拦截幽灵物品槽的左键/右键点击：
     * 左键手持物品 → 放置物品副本（数量=1）
     * 右键 → 清空槽位
     * Q键（丢弃）→ 清空槽位
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < 2 && (clickType == ClickType.PICKUP || clickType == ClickType.THROW)) {
            handleGhostSlotClick(slotId, button, clickType, player);
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void handleGhostSlotClick(int slotId, int button, ClickType clickType, Player player) {
        Slot slot = this.getSlot(slotId);

        // Q 键（丢弃）→ 清空
        if (clickType == ClickType.THROW) {
            slot.set(ItemStack.EMPTY);
            sendItemUpdate(slotId, ItemStack.EMPTY);
            return;
        }

        ItemStack carried = getCarried();
        if (!carried.isEmpty()) {
            // 手持物品：左键或右键 → 放入一个副本
            ItemStack target = carried.copy();
            target.setCount(1);
            slot.set(target);
            sendItemUpdate(slotId, target);
        } else {
            // 空手：左键或右键 → 清空
            slot.set(ItemStack.EMPTY);
            sendItemUpdate(slotId, ItemStack.EMPTY);
        }
    }

    private void sendItemUpdate(int slotIndex, ItemStack stack) {
        if (level.isClientSide()) {
            PacketDistributor.sendToServer(new SensorItemPayload(sensorPos, stack, slotIndex));
        }
    }

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
}
