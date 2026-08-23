package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 控制台方块实体 — 保存已安装控件（踏板一对 / 操纵杆）。
 * NBT 持久化 + 同步（兼容 Create 蓝图，参考 RedstoneTransceiverBlockEntity）。
 */
public class ControlDeskBlockEntity extends BlockEntity implements PartialSafeNBT {

    /** 可安装到控制台的控件类型 */
    public enum ControlType {
        PEDAL, JOYSTICK
    }

    private static final String TAG_PEDAL = "PedalInstalled";
    private static final String TAG_JOYSTICK = "JoystickInstalled";

    private boolean pedalInstalled;
    private boolean joystickInstalled;

    public ControlDeskBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.control_desk_entity.get(), pos, state);
    }

    public boolean isInstalled(ControlType type) {
        return switch (type) {
            case PEDAL -> pedalInstalled;
            case JOYSTICK -> joystickInstalled;
        };
    }

    /** 安装控件；已安装返回 false（不覆盖）。服务端调用。 */
    public boolean install(ControlType type) {
        if (isInstalled(type)) return false;
        switch (type) {
            case PEDAL -> pedalInstalled = true;
            case JOYSTICK -> joystickInstalled = true;
        }
        notifyChange();
        return true;
    }

    /** 卸载控件；未安装返回 false。服务端调用。 */
    public boolean remove(ControlType type) {
        if (!isInstalled(type)) return false;
        switch (type) {
            case PEDAL -> pedalInstalled = false;
            case JOYSTICK -> joystickInstalled = false;
        }
        notifyChange();
        return true;
    }

    private void notifyChange() {
        this.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ════════════════════ NBT / 同步（Create 蓝图兼容） ════════════════════

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pedalInstalled = tag.getBoolean(TAG_PEDAL);
        joystickInstalled = tag.getBoolean(TAG_JOYSTICK);
    }

    /** Create 原理图 / 装置搬运时的「安全 NBT」（Schematicannon 打印保留控件配置）。 */
    @Override
    public void writeSafe(CompoundTag compound, HolderLookup.Provider registries) {
        compound.putBoolean(TAG_PEDAL, pedalInstalled);
        compound.putBoolean(TAG_JOYSTICK, joystickInstalled);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（quill 保存读的是客户端 BE，蓝图兼容必须）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
