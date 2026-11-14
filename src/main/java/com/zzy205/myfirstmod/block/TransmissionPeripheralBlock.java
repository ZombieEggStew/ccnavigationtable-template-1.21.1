package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.AbstractEncasedShaftBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.NotNull;

/**
 * 电子变速箱（传动外设）方块。
 * <p>
 * <b>扳手逻辑与 aero_bearing 对齐</b>：完全使用 {@link IWrenchable} 默认实现，不自定义：
 * <ul>
 *   <li>右键扳手 = Create 标准旋转：{@code getRotatedBlockState}（立柱方块改 AXIS）→
 *       {@code canSurvive} 失败则 PASS → {@code KineticBlockEntity.switchToBlockState} →
 *       {@link IWrenchable#playRotateSound}；</li>
 *   <li>蹲下右键扳手 = {@code onSneakWrenched} 默认实现：走 {@code BlockEvent.BreakEvent} 保护判定、
 *       按战利品表 {@code Block.getDrops} 直接进背包、{@link IWrenchable#playRemoveSound}。</li>
 * </ul>
 * 放置音效 = {@code SoundType.NETHERITE_BLOCK}（同 aero_bearing / swivel_bearing）。
 */
public class TransmissionPeripheralBlock extends AbstractEncasedShaftBlock
        implements IBE<TransmissionPeripheralBlockEntity>, IWrenchable {

    public TransmissionPeripheralBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<TransmissionPeripheralBlockEntity> getBlockEntityClass() {
        return TransmissionPeripheralBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TransmissionPeripheralBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.transmission_peripheral_entity.get();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TransmissionPeripheralBlockEntity(pos, state);
    }

    // ═══════════════ 放置朝向 ═══════════════

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);  // 注册 AXIS 属性
    }

    @Override
    public @NotNull BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction.Axis preferredAxis = RotatedPillarKineticBlock.getPreferredAxis(context);
        Direction.Axis axis;

        Player player = context.getPlayer();
        if (preferredAxis != null && (player == null || !player.isShiftKeyDown())) {
            axis = preferredAxis;
        } else {
            // 蹲下放置时反转：水平→垂直、垂直→水平
            if (context.getNearestLookingDirection().getAxis().isVertical()) {
                axis = context.getHorizontalDirection().getAxis();
            } else {
                axis = Direction.Axis.Y;
            }
        }
        return this.defaultBlockState().setValue(AXIS, axis);
    }

    // 扳手：不覆写 onWrenched / onSneakWrenched / getRotatedBlockState —— 沿用 IWrenchable 默认实现
    // （与 aero_bearing 的扳手行为一致：旋转走标准流程 + 扳手音效，蹲下扳手按战利品表拆除进背包）。
}
