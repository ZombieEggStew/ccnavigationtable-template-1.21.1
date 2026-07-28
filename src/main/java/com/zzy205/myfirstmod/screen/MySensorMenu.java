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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 只读菜单：用于显示传感器所附着方块的 NBT 数据。
 * 没有物品槽位，仅作为 Screen 的数据载体。
 */
public class MySensorMenu extends AbstractContainerMenu {

    private final BlockPos sensorPos;
    private final CompoundTag attachedNBT;

    // ── 服务端构造 ──
    public MySensorMenu(int containerId, BlockPos sensorPos, CompoundTag attachedNBT) {
        super(MyModMenus.SENSOR_MENU.get(), containerId);
        this.sensorPos = sensorPos;
        this.attachedNBT = attachedNBT;
    }

    // ── 客户端构造（由 IContainerFactory 在网络端创建时调用）──
    public MySensorMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf extraData) {
        this(containerId, extraData.readBlockPos(), extraData.readNbt());
    }

    public BlockPos getSensorPos() {
        return sensorPos;
    }

    public CompoundTag getAttachedNBT() {
        return attachedNBT;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
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
