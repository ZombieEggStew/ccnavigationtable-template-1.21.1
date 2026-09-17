package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 快速装填燃料箱（quick_fill_fuel_vault）方块实体：单一物品类型库存（无 GUI）。
 * <p>
 * 整个燃料箱<b>只能存一种物品</b>，最多 {@value CAPACITY} 个（16 组 × 64）。
 * 不实现 {@code Container}（无手动 GUI 交互需求，仅右键存取）；
 * 但暴露 {@link IItemHandler} 能力（{@code ItemHandler.BLOCK}，注册见
 * {@code CCPeripheralExtender#registerBlockCapabilities}）——供引擎蒸汽室在流体燃料
 * 不可用时自动抽取固体燃料（只走 capability，绝不直接改容器 BE）。
 * <p>
 * <b>存储表示</b>：类型用 count=1 的 {@link ItemStack} 占位（{@link #storedItem}），
 * 数量单独存 {@link #storedCount}（0..CAPACITY）。不能直接用 count&gt;64 的单个 ItemStack
 * —— 1.21.1 的 {@code ItemStack.save} 把 count 序列化为 byte（范围 [1;99]），
 * 曾因此 count=128 时存档抛 "Value must be within range [1;99]" 导致 BE 无法持久化。
 * <p>
 * 开盖动画由 {@link QuickFillFuelVaultBlock} 的方块调度 tick 驱动
 * （对齐 create:item_hatch / fluid_port 模式），本 BE 不追踪开盖时长。
 */
public class QuickFillFuelVaultBlockEntity extends BlockEntity {

    /** 总容量：16 组 × 64 = 1024 个同种物品 */
    public static final int CAPACITY = 1024;

    /** 库存类型占位（count 恒为 1，仅取种类/components）；EMPTY 表示空仓 */
    private ItemStack storedItem = ItemStack.EMPTY;

    /** 实际库存数量（0..CAPACITY），可超过物品最大堆叠数 */
    private int storedCount = 0;

    /**
     * ItemHandler 能力视图（单槽）：槽内栈 = 库存类型占位（数量钳到最大堆叠，真实数量在 {@link #storedCount}）。
     * 存入遵守单物品类型语义（异种拒绝、超容量拒绝）；取出直接扣 {@link #storedCount}（可连续取出超过 64 个）。
     * 供引擎蒸汽室固体燃料自动抽取（{@code extractItem(slot, 1, false)}）等外部读取，不经手动右键交互。
     */
    private final IItemHandler itemHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot != 0 || storedCount == 0)
                return ItemStack.EMPTY;
            return storedItem.copyWithCount(Math.min(storedCount, storedItem.getMaxStackSize()));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty())
                return stack;
            // 单物品类型：已有库存时异种拒绝（同 canDeposit 语义）
            if (storedCount > 0 && !ItemStack.isSameItemSameComponents(storedItem, stack))
                return stack;
            int space = CAPACITY - storedCount;
            if (space <= 0)
                return stack;
            int inserted = Math.min(stack.getCount(), space);
            if (!simulate) {
                if (storedCount == 0) {
                    storedItem = stack.copy();
                    storedItem.setCount(1);
                }
                storedCount += inserted;
                setChanged();
            }
            if (inserted >= stack.getCount())
                return ItemStack.EMPTY;
            ItemStack remainder = stack.copy();
            remainder.shrink(inserted);
            return remainder;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || storedCount == 0 || amount <= 0)
                return ItemStack.EMPTY;
            int take = Math.min(amount, Math.min(storedCount, storedItem.getMaxStackSize()));
            ItemStack result = storedItem.copyWithCount(take);
            if (!simulate) {
                storedCount -= take;
                if (storedCount == 0)
                    storedItem = ItemStack.EMPTY;
                setChanged();
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return storedItem.isEmpty() ? 64 : storedItem.getMaxStackSize();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && (stack.isEmpty() || canDeposit(stack));
        }
    };

    public QuickFillFuelVaultBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.quick_fill_fuel_vault_entity.get(), pos, state);
    }

    /** ItemHandler 能力实例（{@code ItemHandler.BLOCK} 查询返回；引擎蒸汽室固体燃料自动抽取用）。 */
    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    /** 库存类型占位（count=1）；EMPTY 表示空仓。 */
    public ItemStack getStored() {
        return storedItem;
    }

    public boolean isEmpty() {
        return storedCount == 0;
    }

    public int getStoredCount() {
        return storedCount;
    }

    /** 能否整组存入手持堆叠：空仓（容量恒够）或同种且总量不超容量。 */
    public boolean canDeposit(ItemStack stack) {
        if (stack.isEmpty())
            return false;
        if (storedCount == 0)
            return stack.getCount() <= CAPACITY;
        return ItemStack.isSameItemSameComponents(storedItem, stack)
                && storedCount + stack.getCount() <= CAPACITY;
    }

    /** 存入手持堆叠（调用方保证 {@link #canDeposit}），返回实际存入数量；调用方负责 shrink 手持。 */
    public int deposit(ItemStack stack) {
        if (!canDeposit(stack))
            return 0;
        int count = stack.getCount();
        if (storedCount == 0) {
            storedItem = stack.copy();
            storedItem.setCount(1);
        }
        storedCount += count;
        setChanged();
        return count;
    }

    /** 取出一组（该物品最大堆叠数，不足取全部）并从库存扣除；空仓返回 EMPTY。 */
    public ItemStack withdrawGroup() {
        if (storedCount == 0)
            return ItemStack.EMPTY;
        int take = Math.min(storedCount, storedItem.getMaxStackSize());
        ItemStack result = storedItem.copyWithCount(take);
        storedCount -= take;
        if (storedCount == 0)
            storedItem = ItemStack.EMPTY;
        setChanged();
        return result;
    }

    /** 方块移除掉包：取走全部库存并清空（配合 {@code QuickFillFuelVaultBlock#onRemove}，防止重复掉落）。 */
    public ItemStack takeAllForDrop() {
        ItemStack result = storedItem.copyWithCount(storedCount);
        storedItem = ItemStack.EMPTY;
        storedCount = 0;
        return result;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 类型（count=1，可序列化）+ 数量（int），避免 count 超出 ItemStack 的 byte 序列化范围
        if (storedCount > 0) {
            tag.put("storedItem", storedItem.save(registries));
            tag.putInt("storedCount", storedCount);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ItemStack type = ItemStack.parseOptional(registries, tag.getCompound("storedItem"));
        int count = tag.getInt("storedCount");
        if (!type.isEmpty() && count > 0) {
            storedItem = type.copyWithCount(1);
            storedCount = Math.min(count, CAPACITY);
        } else {
            storedItem = ItemStack.EMPTY;
            storedCount = 0;
        }
    }
}
