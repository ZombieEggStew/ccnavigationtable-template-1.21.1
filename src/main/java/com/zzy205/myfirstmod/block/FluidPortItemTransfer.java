package com.zzy205.myfirstmod.block;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.fluids.transfer.FillingRecipe;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import com.simibubi.create.content.fluids.transfer.GenericItemFilling;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import net.createmod.catnip.data.Pair;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * 流体端口（fluid_port）的手持流体容器 ↔ 附着储罐传输逻辑。
 * <p>
 * 移植自参考 mod CreateFluidLogistics 的
 * {@code HatchStyleItemTransfer} / {@code ItemFluidCapabilityTransfer} / {@code FillingRecipeTransfer}
 * （参考来源：{@code references/CreateFluidLogistics-master/src/main/java/com/yision/fluidlogistics/content/fluids/itemTransfer/}），
 * 做了两点简化：
 * <ul>
 *   <li>去掉 {@code FilteringBehaviour} 过滤参数（流体过滤器功能暂未实现，之后接入时再补）；</li>
 *   <li>去掉 {@code ItemFilling} 额外 handler 注册表（参考 mod 自身从未注册任何 handler，属死代码），
 *       直接回退 Create 的 {@link GenericItemFilling}。</li>
 * </ul>
 * <p>
 * 传输优先级：
 * <ul>
 *   <li>物品 → 罐（右键）：先走"物品自带流体能力"（Create 储罐物品等），再走 Create 桶类物品空桶配方；</li>
 *   <li>罐 → 物品（Shift 右键）：先走 Create 填充配方（瓶子类），再走"物品自带流体能力"，再走桶类灌装。</li>
 * </ul>
 */
public final class FluidPortItemTransfer {
    private FluidPortItemTransfer() {
    }

    // ── 入口（对齐参考 HatchStyleItemTransfer，去 filter） ──

    /** 尝试把手持物品中的流体抽入储罐；成功返回被移动的流体栈，否则 EMPTY。 */
    public static FluidStack tryEmptyItem(Level level, Player player, InteractionHand hand, ItemStack stack,
            IFluidHandler tank, boolean tankIsCreative, Runnable onChanged) {
        // 1) 物品自带流体能力（Create 储罐物品、各种流体容器物品）→ 直接抽入罐
        ItemStack transferredStack = stack.copy();
        TransferResult transfer = tryDrainItemToTank(transferredStack, tank);
        if (!transfer.isEmpty()) {
            onChanged.run();

            if (!player.isCreative() && !tankIsCreative)
                replaceItem(player, hand, transferredStack, transfer.result());
            return transfer.fluidStack();
        }

        // 2) 桶类物品（Create 空桶配方）
        if (!GenericItemEmptying.canItemBeEmptied(level, stack))
            return FluidStack.EMPTY;

        Pair<FluidStack, ItemStack> emptying = GenericItemEmptying.emptyItem(level, stack, true);
        FluidStack fluidStack = emptying.getFirst();

        if (fluidStack.getAmount() != tank.fill(fluidStack, FluidAction.SIMULATE))
            return FluidStack.EMPTY;
        if (level.isClientSide)
            return fluidStack;

        ItemStack copy = stack.copy();
        emptying = GenericItemEmptying.emptyItem(level, copy, false);

        int realFill = tank.fill(fluidStack.copy(), FluidAction.SIMULATE);
        if (realFill == 0)
            return fluidStack;
        tank.fill(fluidStack.copy(), FluidAction.EXECUTE);
        onChanged.run();

        if (!player.isCreative() && !tankIsCreative) {
            replaceItem(player, hand, copy, emptying.getSecond());
        }
        return fluidStack;
    }

    /** 尝试从储罐抽流体灌入手持物品；成功返回被移动的流体栈，否则 EMPTY。 */
    public static FluidStack tryFillItem(Level level, Player player, InteractionHand hand, ItemStack stack,
            IFluidHandler tank, boolean tankIsCreative, Runnable onChanged) {
        // 1) Create 填充配方（瓶子等不能直接拿流体能力的物品）
        FluidStack fluidStack = tryFillItemWithFillingRecipe(level, player, hand, stack, tank, tankIsCreative, onChanged);
        if (!fluidStack.isEmpty())
            return fluidStack;

        // 2) 物品自带流体能力 → 直接灌入物品
        ItemStack transferredStack = stack.copy();
        TransferResult transfer = tryFillItemFromTank(transferredStack, tank);
        if (!transfer.isEmpty()) {
            onChanged.run();

            if (!player.isCreative())
                replaceItem(player, hand, transferredStack, transfer.result());
            return transfer.fluidStack();
        }

        // 3) 桶类物品（Create 灌装配方）
        if (!GenericItemFilling.canItemBeFilled(level, stack))
            return FluidStack.EMPTY;

        for (int i = 0; i < tank.getTanks(); i++) {
            fluidStack = tank.getFluidInTank(i);
            if (fluidStack.isEmpty())
                continue;
            int requiredAmountForItem = GenericItemFilling.getRequiredAmountForItem(level, stack, fluidStack.copy());
            if (requiredAmountForItem == -1)
                continue;
            if (requiredAmountForItem > fluidStack.getAmount())
                continue;

            FluidStack fluidCopy = fluidStack.copy();
            fluidCopy.setAmount(requiredAmountForItem);

            FluidStack realDraw = tank.drain(fluidCopy, FluidAction.SIMULATE);
            if (realDraw.isEmpty() || realDraw.getAmount() != requiredAmountForItem)
                continue;

            if (level.isClientSide)
                return fluidCopy;

            ItemStack workingStack = player.isCreative() || tankIsCreative
                    ? stack.copy()
                    : stack;
            ItemStack result = GenericItemFilling.fillItem(level, requiredAmountForItem, workingStack, fluidStack.copy());
            if (result.isEmpty())
                continue;
            tank.drain(fluidCopy, FluidAction.EXECUTE);

            if (!player.isCreative())
                replaceItem(player, hand, workingStack, result);
            onChanged.run();
            return fluidCopy;
        }
        return FluidStack.EMPTY;
    }

    public static boolean canItemBeEmptied(Level level, ItemStack stack) {
        return GenericItemEmptying.canItemBeEmptied(level, stack)
                || canItemBeEmptiedByCapability(stack);
    }

    public static boolean canItemBeFilled(Level level, ItemStack stack) {
        return FillingRecipeTransfer.canItemBeFilled(level, stack)
                || canItemBeFilledByCapability(stack)
                || GenericItemFilling.canItemBeFilled(level, stack);
    }

    // ── Create 填充配方（瓶子类，对齐参考 FillingRecipeTransfer） ──

    private static FluidStack tryFillItemWithFillingRecipe(Level level, Player player, InteractionHand hand,
            ItemStack stack, IFluidHandler tank, boolean tankIsCreative, Runnable onChanged) {
        for (int i = 0; i < tank.getTanks(); i++) {
            FluidStack fluidStack = tank.getFluidInTank(i);
            if (fluidStack.isEmpty())
                continue;

            OptionalInt requiredAmount = FillingRecipeTransfer.getRequiredAmountForItem(level, stack, fluidStack.copy());
            if (requiredAmount.isEmpty())
                continue;
            int requiredAmountForItem = requiredAmount.getAsInt();
            if (requiredAmountForItem > fluidStack.getAmount())
                continue;

            FluidStack fluidCopy = fluidStack.copy();
            fluidCopy.setAmount(requiredAmountForItem);

            FluidStack realDraw = tank.drain(fluidCopy, FluidAction.SIMULATE);
            if (realDraw.isEmpty() || realDraw.getAmount() != requiredAmountForItem)
                continue;

            if (level.isClientSide)
                return fluidCopy;

            ItemStack workingStack = player.isCreative() || tankIsCreative
                    ? stack.copy()
                    : stack;
            Optional<ItemStack> result = FillingRecipeTransfer.fillItem(level, requiredAmountForItem, workingStack, fluidStack.copy());
            if (result.isEmpty())
                continue;

            tank.drain(fluidCopy, FluidAction.EXECUTE);

            if (!player.isCreative())
                replaceItem(player, hand, workingStack, result.get());
            onChanged.run();
            return fluidCopy;
        }
        return FluidStack.EMPTY;
    }

    /** Create 填充配方传输（对齐参考 FillingRecipeTransfer，原样移植）。 */
    private static final class FillingRecipeTransfer {
        private FillingRecipeTransfer() {
        }

        static boolean canItemBeFilled(Level level, ItemStack stack) {
            SingleRecipeInput input = new SingleRecipeInput(stack);
            if (SequencedAssemblyRecipe.getRecipe(level, input, AllRecipeTypes.FILLING.getType(), FillingRecipe.class)
                    .isPresent()) {
                return true;
            }
            return AllRecipeTypes.FILLING.find(input, level).isPresent();
        }

        static OptionalInt getRequiredAmountForItem(Level level, ItemStack stack, FluidStack availableFluid) {
            return findRecipe(level, stack, availableFluid)
                    .map(RecipeHolder::value)
                    .map(FillingRecipe::getRequiredFluid)
                    .map(SizedFluidIngredient::amount)
                    .map(OptionalInt::of)
                    .orElseGet(OptionalInt::empty);
        }

        static Optional<ItemStack> fillItem(Level level, int requiredAmount, ItemStack stack,
                FluidStack availableFluid) {
            FluidStack toFill = availableFluid.copyWithAmount(requiredAmount);
            return findRecipe(level, stack, toFill)
                    .map(RecipeHolder::value)
                    .map(recipe -> {
                        List<ItemStack> results = recipe.rollResults(level.random);
                        availableFluid.shrink(requiredAmount);
                        stack.shrink(1);
                        return results.isEmpty() ? ItemStack.EMPTY : results.getFirst();
                    });
        }

        private static Optional<RecipeHolder<FillingRecipe>> findRecipe(Level level, ItemStack stack,
                FluidStack availableFluid) {
            SingleRecipeInput input = new SingleRecipeInput(stack);
            Optional<RecipeHolder<FillingRecipe>> sequencedRecipe = SequencedAssemblyRecipe.getRecipe(level,
                    input, AllRecipeTypes.FILLING.getType(), FillingRecipe.class,
                    matchItemAndFluid(level, input, availableFluid));
            if (sequencedRecipe.isPresent()) {
                return sequencedRecipe;
            }

            for (RecipeHolder<Recipe<SingleRecipeInput>> recipe : level.getRecipeManager()
                    .getRecipesFor(AllRecipeTypes.FILLING.getType(), input, level)) {
                FillingRecipe fillingRecipe = (FillingRecipe) recipe.value();
                if (fillingRecipe.getRequiredFluid().ingredient().test(availableFluid)) {
                    return Optional.of(new RecipeHolder<>(recipe.id(), fillingRecipe));
                }
            }
            return Optional.empty();
        }

        private static Predicate<RecipeHolder<FillingRecipe>> matchItemAndFluid(Level level,
                SingleRecipeInput input, FluidStack availableFluid) {
            return recipe -> recipe.value().matches(input, level)
                    && recipe.value().getRequiredFluid().ingredient().test(availableFluid);
        }
    }

    // ── 物品自带流体能力传输（对齐参考 ItemFluidCapabilityTransfer，去 filter） ──

    private static TransferResult tryDrainItemToTank(ItemStack stack, IFluidHandler tankCapability) {
        IFluidHandlerItem itemCapability = getItemFluidHandler(stack, false);
        if (itemCapability == null)
            return TransferResult.EMPTY;

        for (int i = 0; i < itemCapability.getTanks(); i++) {
            FluidStack storedFluid = itemCapability.getFluidInTank(i);
            if (storedFluid.isEmpty())
                continue;

            FluidStack fluidToMove = getDrainableFluid(itemCapability, tankCapability, storedFluid);
            if (fluidToMove.isEmpty())
                continue;

            FluidStack drainedFluid = itemCapability.drain(fluidToMove, FluidAction.EXECUTE);
            if (drainedFluid.isEmpty())
                continue;

            int filled = tankCapability.fill(drainedFluid.copy(), FluidAction.EXECUTE);
            if (filled <= 0)
                continue;

            FluidStack movedFluid = drainedFluid.copy();
            movedFluid.setAmount(filled);
            stack.shrink(1);
            return new TransferResult(movedFluid, itemCapability.getContainer().copy());
        }
        return TransferResult.EMPTY;
    }

    private static TransferResult tryFillItemFromTank(ItemStack stack, IFluidHandler tankCapability) {
        IFluidHandlerItem itemCapability = getItemFluidHandler(stack, true);
        if (itemCapability == null)
            return TransferResult.EMPTY;

        for (int i = 0; i < tankCapability.getTanks(); i++) {
            FluidStack storedFluid = tankCapability.getFluidInTank(i);
            if (storedFluid.isEmpty())
                continue;

            FluidStack fluidToMove = getFillableFluid(itemCapability, tankCapability, storedFluid);
            if (fluidToMove.isEmpty())
                continue;

            int filled = itemCapability.fill(fluidToMove.copy(), FluidAction.EXECUTE);
            if (filled <= 0)
                continue;

            FluidStack movedFluid = fluidToMove.copy();
            movedFluid.setAmount(filled);
            FluidStack drainedFluid = tankCapability.drain(movedFluid.copy(), FluidAction.EXECUTE);
            if (drainedFluid.isEmpty())
                continue;

            movedFluid.setAmount(drainedFluid.getAmount());
            stack.shrink(1);
            return new TransferResult(movedFluid, itemCapability.getContainer().copy());
        }
        return TransferResult.EMPTY;
    }

    private static boolean canItemBeFilledByCapability(ItemStack stack) {
        IFluidHandlerItem itemCapability = getItemFluidHandler(stack, true);
        if (itemCapability == null)
            return false;
        for (int i = 0; i < itemCapability.getTanks(); i++) {
            if (itemCapability.getFluidInTank(i).getAmount() < itemCapability.getTankCapacity(i))
                return true;
        }
        return false;
    }

    private static boolean canItemBeEmptiedByCapability(ItemStack stack) {
        IFluidHandlerItem itemCapability = getItemFluidHandler(stack, false);
        if (itemCapability == null)
            return false;
        for (int i = 0; i < itemCapability.getTanks(); i++) {
            if (!itemCapability.getFluidInTank(i).isEmpty())
                return true;
        }
        return false;
    }

    private static FluidStack getDrainableFluid(
            IFluidHandlerItem itemCapability, IFluidHandler tankCapability, FluidStack storedFluid) {
        FluidStack availableFluid = storedFluid.copy();
        int acceptableAmount = tankCapability.fill(availableFluid, FluidAction.SIMULATE);
        if (acceptableAmount <= 0)
            return FluidStack.EMPTY;

        FluidStack requestedFluid = storedFluid.copy();
        requestedFluid.setAmount(acceptableAmount);
        FluidStack drainableFluid = itemCapability.drain(requestedFluid, FluidAction.SIMULATE);
        if (drainableFluid.isEmpty())
            return FluidStack.EMPTY;

        int realAcceptableAmount = tankCapability.fill(drainableFluid.copy(), FluidAction.SIMULATE);
        if (realAcceptableAmount <= 0)
            return FluidStack.EMPTY;
        if (realAcceptableAmount < drainableFluid.getAmount())
            drainableFluid.setAmount(realAcceptableAmount);
        return drainableFluid;
    }

    private static FluidStack getFillableFluid(
            IFluidHandlerItem itemCapability, IFluidHandler tankCapability, FluidStack storedFluid) {
        FluidStack availableFluid = storedFluid.copy();
        int fillableAmount = itemCapability.fill(availableFluid, FluidAction.SIMULATE);
        if (fillableAmount <= 0)
            return FluidStack.EMPTY;

        FluidStack requestedFluid = storedFluid.copy();
        requestedFluid.setAmount(fillableAmount);
        FluidStack drainableFluid = tankCapability.drain(requestedFluid, FluidAction.SIMULATE);
        if (drainableFluid.isEmpty())
            return FluidStack.EMPTY;

        int realFillableAmount = itemCapability.fill(drainableFluid.copy(), FluidAction.SIMULATE);
        if (realFillableAmount <= 0)
            return FluidStack.EMPTY;
        if (realFillableAmount < drainableFluid.getAmount())
            drainableFluid.setAmount(realFillableAmount);
        return drainableFluid;
    }

    private static IFluidHandlerItem getItemFluidHandler(ItemStack stack, boolean forFilling) {
        ItemStack split = stack.copy();
        split.setCount(1);
        IFluidHandlerItem itemCapability = split.getCapability(Capabilities.FluidHandler.ITEM);
        if (itemCapability == null)
            return null;
        if (forFilling && !GenericItemFilling.isFluidHandlerValid(split, itemCapability))
            return null;
        return itemCapability;
    }

    // ── 公共工具 ──

    private static void replaceItem(Player player, InteractionHand hand, ItemStack stack, ItemStack result) {
        if (stack.isEmpty()) {
            player.setItemInHand(hand, result);
        } else {
            player.setItemInHand(hand, stack);
            player.getInventory().placeItemBackInInventory(result);
        }
    }

    record TransferResult(FluidStack fluidStack, ItemStack result) {
        static final TransferResult EMPTY = new TransferResult(FluidStack.EMPTY, ItemStack.EMPTY);

        boolean isEmpty() {
            return fluidStack.isEmpty();
        }
    }
}
