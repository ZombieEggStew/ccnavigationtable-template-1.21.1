package com.zzy205.myfirstmod.screen;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 幽灵物品槽 —— 参考 Create Redstone Link GUI 的 GhostRecipeSlot。
 * 左键手持物品点击 → 放置物品（显示在槽位中）
 * 右键点击 → 清空槽位
 * 物品不会从光标上消耗（JEI 幽灵槽模式）。
 */
public class GhostItemSlot extends Slot {

    private static final SimpleContainer DUMMY = new SimpleContainer(0);

    private final int slotIndex;
    private final Supplier<ItemStack> getter;
    private final BiConsumer<Integer, ItemStack> setter;

    public GhostItemSlot(int slotIndex, int x, int y,
                         Supplier<ItemStack> getter,
                         BiConsumer<Integer, ItemStack> setter) {
        super(DUMMY, 0, x, y);
        this.slotIndex = slotIndex;
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    public ItemStack getItem() {
        return this.getter.get();
    }

    @Override
    public void set(ItemStack stack) {
        if (!stack.isEmpty()) {
            ItemStack copy = stack.copy();
            copy.setCount(1);
            this.setter.accept(this.slotIndex, copy);
        } else {
            this.setter.accept(this.slotIndex, ItemStack.EMPTY);
        }
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
