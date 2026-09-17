package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Lua 舵机轴承（ccpe:servo_bearing）。
 * <p>
 * 复刻 {@code create:mechanical_bearing} 的旋转逻辑（{@code angle} 每 tick 累加 + 直接设定
 * {@code ControlledContraptionEntity} 角度），但做了三处简化/改动：
 * <ul>
 *   <li><b>无动力</b>：{@link #hasShaftTowards} 恒 false，完全不接入 Create 应力网络（无轴、无应力消耗）；</li>
 *   <li><b>无红石</b>：无 POWERED / 锁定逻辑，旋转角度完全由 CC:Tweaked Lua 外设控制（纯目标角舵机语义）；</li>
 *   <li><b>客户端视觉修复</b>：BE 客户端不再「指数追赶」服务端角度（原版 mechanical_bearing 的
 *       {@code clientAngleDiff} 机制导致末端最后几度视觉爬行），角度直接由服务端同步 + 帧间插值渲染。</li>
 * </ul>
 * 模型直接复用 Create 的 mechanical_bearing 资产（基座 {@code create:block/bearing/block}、
 * 顶部转盘 partial {@code create:block/bearing/top}、物品 {@code create:block/bearing/item}）。
 * <p>
 * 参考来源：{@code references/Create-mc1.21.1-dev/.../bearing/BearingBlock.java}、
 * {@code references/Create-mc1.21.1-dev/.../bearing/MechanicalBearingBlockEntity.java}。
 */
public class ServoBearingBlock extends DirectionalKineticBlock implements IBE<ServoBearingBlockEntity>, IWrenchable {

    public ServoBearingBlock(final Properties properties) {
        super(properties);
    }

    // ═══════════════ 无轴：不接入应力网络 ═══════════════

    /**
     * 恒 false：舵机无传动轴、无动力输入，完全由 Lua 控制，不进入 Create 应力网络。
     */
    @Override
    public boolean hasShaftTowards(final LevelReader world, final BlockPos pos, final BlockState state, final Direction face) {
        return false;
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState blockState) {
        return blockState.getValue(FACING).getAxis();
    }

    // ═══════════════ 放置朝向 ═══════════════

    /**
     * 放置朝向 = 被点击的面（同 {@code MyBearingBlock}）：点地板 → 竖直、点墙 → 躺倒。
     */
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    // ═══════════════ 装配交互 ═══════════════

    @Override
    protected ItemInteractionResult useItemOn(final ItemStack itemStack, final BlockState blockState, final Level level, final BlockPos blockPos, final Player player, final InteractionHand interactionHand, final BlockHitResult blockHitResult) {
        if (!player.mayBuild()) {
            return ItemInteractionResult.FAIL;
        }

        if (player.isShiftKeyDown()) {
            return ItemInteractionResult.FAIL;
        }

        // 空手右键 = 装配/拆卸（同 aero_bearing / swivel）
        if (player.getItemInHand(interactionHand).isEmpty()) {
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }

            this.withBlockEntityDo(level, blockPos, be -> be.assembleNextTick = true);
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public InteractionResult onWrenched(final BlockState state, final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        BlockState rotated = this.getRotatedBlockState(state, context.getClickedFace());
        if (!rotated.canSurvive(level, context.getClickedPos()))
            return InteractionResult.PASS;

        if (!level.isClientSide) {
            this.withBlockEntityDo(level, pos, ServoBearingBlockEntity::disassemble);
        }

        // blockstate could have changed from disassembly
        rotated = this.getRotatedBlockState(level.getBlockState(pos), context.getClickedFace());
        KineticBlockEntity.switchToBlockState(level, pos, this.updateAfterWrenched(rotated, context));

        if (level.getBlockState(pos) != state)
            IWrenchable.playRotateSound(level, pos);

        return InteractionResult.SUCCESS;
    }

    // ═══════════════ BE 绑定 ═══════════════

    @Override
    public Class<ServoBearingBlockEntity> getBlockEntityClass() {
        return ServoBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ServoBearingBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.servo_bearing_entity.get();
    }
}
