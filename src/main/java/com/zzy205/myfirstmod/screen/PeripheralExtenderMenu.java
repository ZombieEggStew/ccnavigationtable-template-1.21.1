package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.network.SensorNbtPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 传感器 NBT 查看菜单。上方显示 NBT 数据，下方显示玩家背包。
 */
public class PeripheralExtenderMenu extends AbstractContainerMenu {

    private final BlockPos sensorPos;
    private final CompoundTag attachedNBT;
    private final Level level;

    /** 当前传感器的频道号（客户端构造时从 extraData 读取） */
    private final int sensorChannel;
    /** 已被占用的频道号列表（客户端构造时从 extraData 读取） */
    private final int[] occupiedChannels;
    /** GUI 开关状态（客户端构造时从 extraData 读取） */
    private final int loadMode;
    private final boolean onPhysicsBody;

    // ── 服务端构造 ──
    public PeripheralExtenderMenu(int containerId, BlockPos sensorPos, CompoundTag attachedNBT, Inventory playerInv) {
        super(MyModMenus.PERIPHERAL_EXTENDER_MENU.get(), containerId);
        this.sensorPos = sensorPos;
        this.attachedNBT = attachedNBT;
        this.level = playerInv.player.level();
        this.sensorChannel = -1;
        this.occupiedChannels = new int[0];
        this.loadMode = 0;
        this.onPhysicsBody = false;
    }

    // ── 客户端构造（由 IContainerFactory 在网络端创建时调用）──
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

    public int getLoadMode() { return loadMode; }
    public boolean isOnPhysicsBody() { return onPhysicsBody; }


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
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
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
