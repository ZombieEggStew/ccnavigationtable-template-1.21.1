package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.List;
import java.util.Locale;

/**
 * 水冷模块方块实体：<b>8000mb 单槽冷却液存储</b>（无 GUI），结构照 {@code create:fluid_tank}
 * 与项目里的 {@link QuickFillFluidTankBlockEntity}。
 * <p>
 * <b>只接冷却液</b>：储罐用 {@link FluidTank} 的 {@code validator} 接 {@link EngineCoolants#isCoolant}，
 * 于是<b>所有</b>填充途径（流体管道/泵走 {@code FluidHandler} 能力、玩家手持容器走
 * {@link FluidPortItemTransfer}）都自动被拦在 {@code fill()} 里——表外流体装不进去，已装一种冷却液后
 * 也装不进第二种（{@link FluidTank} 基类保证单流体）。
 * <p>
 * 冷却效率（{@code data/<ns>/coolant/*.json} 的 {@code efficiency}）目前<b>只用于 goggle 显示</b>，
 * 尚未接入引擎温度模型（接入时在 {@code EngineCoreBlockEntity} 的温度计算里消费）。
 * <p>
 * <b>客户端同步坑</b>（两处，踩过一次）：goggle tooltip 是<b>客户端</b>渲染的，而 {@link EngineCoolants} 表只在服务端
 * 数据包重载时加载（{@code AddReloadListenerEvent} 是服务端事件）→ 客户端查表永远为空。因此效率值<b>由服务端算好、
 * 随 BE 数据写进 NBT</b>（{@code CoolantEfficiency} 字段），客户端只读 {@link #syncedCoolantEfficiency}
 * （同 memo 踩坑 10「客户端不重算」）。<b>注意两条下发路径都要读这个字段</b>：
 * <ul>
 *   <li><b>区块首次下发</b> → 客户端 {@code handleUpdateTag}；</li>
 *   <li><b>BE 更新包</b>（{@code ClientboundBlockEntityDataPacket}）→ NeoForge
 *       {@code IBlockEntityExtension#onDataPacket} → {@code loadWithComponents} → <b>{@code loadAdditional}</b>，
 *       不是 {@code handleUpdateTag}（只写 handleUpdateTag 会出现「液体同步了、效率却显示无冷却液」的错位）。</li>
 * </ul>
 * 另：{@code ServerLevel#sendBlockUpdated} 在状态没变时只标脏区块、不发 BE 包，所以客户端能拿到的更新
 * 来自上层（如 {@code ClientboundBlockEntityDataPacket} 直发）或区块重载——上面两条路径覆盖即可。
 * <p>
 * goggle tooltip：首段复用 {@link #containedFluidTooltip}（与 {@code create:fluid_tank} 同款：
 * 流体名 + 当前量/容量，空罐只显示总容量），再追加一行「冷却效率：×N」。
 */
public class WaterCoolingModuleBlockEntity extends BlockEntity implements IHaveGoggleInformation {

    /** 总容量：8000mb（8 桶） */
    public static final int CAPACITY = 8000;

    /** 客户端：服务端同步过来的冷却效率（0 = 空罐 / 表外流体 / 还没同步） */
    private float syncedCoolantEfficiency;

    /**
     * 内部冷却液槽（单槽 8000mb，只接受 {@link EngineCoolants} 表内流体）。
     * 覆写 {@code onContentsChanged()}：任何来源的流体变化（玩家容器 / 流体管道）都推送客户端 BE 更新包——
     * 客户端 goggle tooltip 直读本槽（{@code TankContent} 同步），不推则显示旧量。
     * 参考 {@code create:fluid_tank}：{@code SmartFluidTank} 变更回调 → {@code onFluidStackChanged}
     * → {@code setChanged()} + {@code sendData()}。
     */
    private final FluidTank tank = new FluidTank(CAPACITY, EngineCoolants::isCoolant) {
        @Override
        protected void onContentsChanged() {
            if (level != null && !level.isClientSide) {
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
            }
        }
    };

    public WaterCoolingModuleBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.water_cooling_module_entity.get(), pos, state);
    }

    /** 内部冷却液槽（8000mb 单槽，只收冷却液） */
    public FluidTank getTank() {
        return tank;
    }

    /** 当前冷却液的冷却效率倍率；空罐 / 表外流体返回 0（客户端读同步值，服务端现算） */
    public float coolantEfficiency() {
        return level != null && level.isClientSide ? syncedCoolantEfficiency : EngineCoolants.efficiencyOf(tank.getFluid());
    }

    // ── Create goggle tooltip（流体段同 create:fluid_tank，另加冷却效率行） ──

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!containedFluidTooltip(tooltip, isPlayerSneaking, tank))
            return false;

        // 空罐 = 没有冷却液；有液体但表里没有（正常流程进不来）→ 同样按"未知"处理
        final float efficiency = coolantEfficiency();
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.water_cooling_module.efficiency")
                        .withStyle(ChatFormatting.GRAY))
                .append(efficiency > 0f
                        ? Component.literal(String.format(Locale.ROOT, "×%.2f", efficiency))
                                .withStyle(ChatFormatting.AQUA)
                        : Component.translatable("tooltip.ccpe.water_cooling_module.efficiency_none")
                                .withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    // ── NBT（持久化 + 客户端同步：goggle tooltip 在客户端读 TankContent） ──

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("TankContent", tank.writeToNBT(registries, new CompoundTag()));
        // 服务端现算写盘（客户端不调 saveAdditional）；区块下发/存档都带上，客户端才有值可读
        tag.putFloat("CoolantEfficiency", EngineCoolants.efficiencyOf(tank.getFluid()));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tank.readFromNBT(registries, tag.getCompound("TankContent"));
        // ★ 这里也必须读效率：BE 更新包走 onDataPacket → loadWithComponents → loadAdditional
        //   （不是 handleUpdateTag），漏了就会「液体同步了、效率行还是无冷却液」
        syncedCoolantEfficiency = tag.getFloat("CoolantEfficiency");
    }

    /** 区块下发 / 数据包都带上 saveAdditional 的全部字段（tank + 冷却效率），避免两处各写一遍判断不一致 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    /** 区块首次下发路径（同 {@code MonitorSlabBlockEntity}：直接落 loadAdditional） */
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    /** BE 更新包路径（{@code ClientboundBlockEntityDataPacket}）。显式覆写、不依赖 NeoForge 默认的
     *  {@code onDataPacket → loadWithComponents}，照 {@code MonitorSlabBlockEntity}：两入口都落 loadAdditional
     *  ——只写 handleUpdateTag 就会出现「液体同步了、效率行还是无冷却液」。 */
    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        if (tag != null)
            loadAdditional(tag, registries);
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（默认返回 null 会导致客户端快照陈旧，goggle tooltip 不同步）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 供流体管道/泵等外部读取（能力注册见 {@code CCPeripheralExtender#registerBlockCapabilities}） */
    public FluidStack getFluidStack() {
        return tank.getFluid();
    }
}
