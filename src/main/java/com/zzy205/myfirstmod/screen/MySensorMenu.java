package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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
    // ═══════════════════════════════════════

    private final BlockPos sensorPos;
    private final CompoundTag attachedNBT;

    // ── 服务端构造 ──
    public MySensorMenu(int containerId, BlockPos sensorPos, CompoundTag attachedNBT, Inventory playerInv) {
        super(MyModMenus.SENSOR_MENU.get(), containerId);
        this.sensorPos = sensorPos;
        this.attachedNBT = attachedNBT;
        addPlayerSlots(playerInv);
    }

    // ── 客户端构造（由 IContainerFactory 在网络端创建时调用）──
    public MySensorMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        this(containerId, extraData.readBlockPos(), extraData.readNbt(), inv);
    }

    private void addPlayerSlots(Inventory playerInv) {
        // 背包 3×9（槽位 0~26）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        SLOT_X + col * SLOT_STEP, INV_Y + row * SLOT_STEP));
            }
        }
        // 快捷栏 1×9（槽位 27~35）
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            ItemStack copy = stackInSlot.copy();
            // 0~26: 背包 → 移到快捷栏; 27~35: 快捷栏 → 移到背包
            if (index < 27) {
                if (!this.moveItemStackTo(stackInSlot, 27, 36, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stackInSlot, 0, 27, false)) {
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
            }
        }
        return true;
    }
}
