package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.List;

/**
 * 快速装填流体储罐（quick_fill_fluid_tank）方块实体：4000mb 单槽流体存储（无 GUI）。
 * <p>
 * 存储用 NeoForge {@link FluidTank}（单槽、容量 {@value CAPACITY} mb）；
 * 供 {@link QuickFillFluidTankBlock} 的右键交互（手持流体容器存入 / 空桶装满 1 桶）。
 * <p>
 * 实现 Create {@link IHaveGoggleInformation}：goggle tooltip 复用
 * {@link #containedFluidTooltip}（与 create:fluid_tank 同款：流体名 + 当前量/容量，
 * 空罐显示总容量），见 {@link #addToGoggleTooltip}。
 * <p>
 * 开盖动画由 {@link QuickFillFluidTankBlock} 的方块调度 tick 驱动
 * （对齐 create:item_hatch / fluid_port / quick_fill_fuel_vault 模式），本 BE 不追踪开盖时长。
 */
public class QuickFillFluidTankBlockEntity extends BlockEntity implements IHaveGoggleInformation {

    /** 总容量：4000mb（4 桶） */
    public static final int CAPACITY = 4000;

    /**
     * 内部流体槽（单槽 4000mb）。覆写 {@code onContentsChanged()}：任何来源的流体变化
     * （玩家交互 / 引擎 drain / 流体管道）都推送客户端 BE 更新包——客户端 goggle tooltip
     * 直读本槽（TankContent 同步），不推则显示旧量。
     * 参考 create:fluid_tank：{@code SmartFluidTank} 变更回调 → {@code onFluidStackChanged}
     * → {@code setChanged()} + {@code sendData()}（见
     * {@code references/Create-mc1.21.1-dev/.../FluidTankBlockEntity.java}）。
     */
    private final FluidTank tank = new FluidTank(CAPACITY) {
        @Override
        protected void onContentsChanged() {
            if (level != null && !level.isClientSide) {
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
            }
        }
    };

    public QuickFillFluidTankBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.quick_fill_fluid_tank_entity.get(), pos, state);
    }

    /** 内部流体槽（单槽 4000mb）。 */
    public FluidTank getTank() {
        return tank;
    }

    // ── Create goggle tooltip（create:fluid_tank 同款） ──

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return containedFluidTooltip(tooltip, isPlayerSneaking, tank);
    }

    // ── NBT（持久化 + 客户端同步：goggle tooltip 在客户端读 TankContent） ──

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("TankContent", tank.writeToNBT(registries, new CompoundTag()));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tank.readFromNBT(registries, tag.getCompound("TankContent"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("TankContent", tank.writeToNBT(registries, new CompoundTag()));
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        tank.readFromNBT(registries, tag.getCompound("TankContent"));
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（默认返回 null 会导致客户端快照陈旧，goggle tooltip 不同步）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
