package com.zzy205.myfirstmod.block;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Predicate;

/**
 * 燃烧室贴附放置助手：手持燃烧室物品（流体/蒸汽）对准引擎核心时，显示虚影。
 * <p>
 * 虚影位置 = 核心点击面的相邻格；变换 = {@code FACING} 指向点击面，与
 * {@code getStateForPlacement}（FACING = 点击面）一致 → 放置后"背贴核心"，正好命中
 * {@link EngineCoreBlockEntity#countCombustionChambers()} 的贴附判定。
 * <p>
 * 虚影渲染由 catnip {@code PlacementClient} 自动驱动（与引擎核心延长虚影同一机制：
 * 匹配手持物品 + 准星方块 → getOffset → GhostBlocks）；右键放置无需额外代码，
 * 默认 BlockItem 放置即走 {@code getStateForPlacement}。
 * <p>
 * 参考来源：Create {@code PoleHelper} / catnip {@code IPlacementHelper}（P0 引擎延长助手同款机制）。
 */
public class ChamberAttachPlacementHelper implements IPlacementHelper {

    private final Predicate<ItemStack> itemPredicate;

    /**
     * @param itemPredicate 燃烧室物品谓词。调用方应传 lambda（如 {@code stack -> stack.is(MyModBlocks.xxx.get().asItem())}），
     *                      使 {@code .get()} 惰性求值——不能在方块类静态初始化时急切解引用（注册期间 DeferredHolder 未绑定会 NPE）。
     */
    public ChamberAttachPlacementHelper(Predicate<ItemStack> itemPredicate) {
        this.itemPredicate = itemPredicate;
    }

    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return itemPredicate;
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return state -> state.is(MyModBlocks.engine_core.get());
    }

    @Override
    public PlacementOffset getOffset(Player player, Level world, BlockState state, BlockPos pos, BlockHitResult ray) {
        Direction face = ray.getDirection();
        BlockPos newPos = pos.relative(face);
        if (world.getBlockState(newPos).canBeReplaced())
            return PlacementOffset.success(newPos, s -> s.setValue(DirectionalBlock.FACING, face));
        return PlacementOffset.fail();
    }
}
