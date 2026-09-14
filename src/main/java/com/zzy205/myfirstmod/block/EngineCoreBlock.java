package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.placement.PoleHelper;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 发动机核心（aero_engine / engine_core）：Create 动力源方块骨架 + 多方块组网（P0，方案见 memo/engine-module.md）。
 * <p>
 * 轴向设计（方案 3，同 Create 传动轴 / 齿轮箱 / 本项目 transmission_peripheral）：
 * <ul>
 *   <li>使用 {@link RotatedPillarKineticBlock} 的 {@code AXIS}（X/Y/Z）三向状态，blockstate 恰好 3 个 state；
 *       blockstate 模型映射见 {@code assets/ccpe/blockstates/engine_core.json}（模型南北对称，x/z 共用，y 用 x 旋转）。</li>
 *   <li>{@code hasShaftTowards} 沿 AXIS 前后两面 → <b>贯通传动杆</b>（模型中心预留 6×6 轴孔，匹配 Create SHAFT_HALF 截面）；
 *       相邻核心排成一排时轴耦合成一个应力网络，controller 发电、整条同转。</li>
 *   <li>扳手默认处理来自 {@code IRotate}/{@code IWrenchable}：右键在轴之间切换（X↔Z，Y 轴不转）、潜行右键拆除，无需覆写。</li>
 *   <li>放置默认继承 {@link RotatedPillarKineticBlock#getStateForPlacement}：优先对齐相邻传动轴，其次按点击面/视线方向定轴。</li>
 * </ul>
 * P0 组网机制（参考 CDG {@code ModularDieselEngineBlock}）：
 * <ul>
 *   <li>{@code onPlace} → {@code updateConnectivity} → {@code ConnectivityHandler.formMulti}（沿 AXIS 组网）；</li>
 *   <li>{@code onRemove} → {@code ConnectivityHandler.splitMulti}（拆中段：全体解体后下一 tick 各自重新抱团）；</li>
 *   <li><b>延伸放置</b>：{@link PlacementHelper}（{@link PoleHelper}）——拿引擎物品右键已有引擎，沿 AXIS 自动延伸放置；</li>
 *   <li>{@code SpecialBlockItemRequirement}：Create 蓝图按单方块消耗引擎物品。</li>
 * </ul>
 * 参考来源：CDG {@code ModularDieselEngineBlock}；Create {@code ConnectivityHandler} / {@code PoleHelper}。
 */
public class EngineCoreBlock extends RotatedPillarKineticBlock implements IBE<EngineCoreBlockEntity>, SpecialBlockItemRequirement, IProxyHoveringInformation {

    /** 延伸放置助手 id：拿引擎物品右键已有引擎，沿 AXIS 自动延伸放置（组网成一条） */
    private static final int placementHelperId = PlacementHelpers.register(new PlacementHelper());

    public EngineCoreBlock(Properties properties) {
        super(properties);
    }

    // ================= 多方块组网触发点（P0） =================

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (oldState.getBlock() == state.getBlock() || isMoving)
            super.onPlace(state, level, pos, oldState, isMoving);
        withBlockEntityDo(level, pos, EngineCoreBlockEntity::updateConnectivity);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.hasBlockEntity() && (state.getBlock() != newState.getBlock() || !newState.hasBlockEntity()) &&
                level.getBlockEntity(pos) instanceof EngineCoreBlockEntity be) {
            level.removeBlockEntity(pos);
            ConnectivityHandler.splitMulti(be);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    // ================= 延伸放置（P0） =================

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        IPlacementHelper placementHelper = PlacementHelpers.get(placementHelperId);
        if (!player.isShiftKeyDown() && player.mayBuild()) {
            if (placementHelper.matchesItem(stack)) {
                placementHelper.getOffset(player, level, state, pos, hitResult)
                        .placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hitResult);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    // ================= 蓝图物品需求（P0） =================

    @Override
    public ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
        List<ItemStack> list = new ArrayList<>();
        list.add(new ItemStack(MyModBlocks.engine_core.get()));
        return new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, list);
    }

    // ================= IBE =================

    @Override
    public Class<EngineCoreBlockEntity> getBlockEntityClass() {
        return EngineCoreBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EngineCoreBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.engine_core_entity.get();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EngineCoreBlockEntity(pos, state);
    }

    /** 贯通传动杆：沿 AXIS 的前后两面都能接轴（相邻核心排成一排时轴耦合成一个应力网络） */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    /**
     * goggle tooltip 代理：悬停任意核心节 → tooltip 源统一到整条引擎 controller（与模块方块同模式，
     * 悬停非 controller 成员也显示整条引擎状态）。代理时在 controller 上记录悬停方块
     * （{@link EngineCoreBlockEntity#markHoveredModule}），供 {@code addToGoggleTooltip} 按悬停方块分流——
     * 核心自身记录自己 → 核心 tooltip（同时覆盖模块记录，消除跨帧残留）。
     */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        BlockPos controller = EngineCoreBlockEntity.engineControllerPos(level, pos);
        if (controller != null && level.isClientSide
                && level.getBlockEntity(controller) instanceof EngineCoreBlockEntity core) {
            core.markHoveredModule(this);
        }
        return controller != null ? controller : pos;
    }

    // ================= 延伸放置助手 =================

    private static class PlacementHelper extends PoleHelper<Direction.Axis> {

        public PlacementHelper() {
            super(state -> state.is(MyModBlocks.engine_core.get()),
                    state -> state.getValue(EngineCoreBlock.AXIS),
                    EngineCoreBlock.AXIS);
        }

        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.is(MyModBlocks.engine_core.get().asItem());
        }
    }
}
