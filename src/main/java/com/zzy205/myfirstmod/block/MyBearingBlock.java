package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 自研风帆轴承（aero_bearing）方块。
 * <p>
 * 与 simulated:swivel_bearing 的差异：
 * <ul>
 *   <li>无 ironcog / ExtraKinetics：动力直接轴向输入（同 Create {@code BearingBlock}），
 *       传动轴/应力网络直接接在轴承轴上，不需要侧面齿轮啮合；</li>
 *   <li>不贯通应力：从动物理体（sub-level）只被 RotaryConstraint 带动旋转，不参与应力网络；</li>
 *   <li>空手右键 = 装配/拆卸（{@code assembleNextTick = true}，同 swivel 66-73 行）。</li>
 * </ul>
 * 参考来源：{@code references/Simulated-Project-main/.../swivel_bearing/SwivelBearingBlock.java}、
 * {@code references/Create-mc1.21.1-dev/.../bearing/BearingBlock.java}。
 */
public class MyBearingBlock extends DirectionalKineticBlock implements IBE<MyBearingBlockEntity>, IWrenchable, BlockSubLevelAssemblyListener {

    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");

    public MyBearingBlock(final Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(ASSEMBLED, false));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(ASSEMBLED));
    }

    // ═══════════════ 放置朝向 ═══════════════

    /**
     * 放置朝向 = 被点击的面（FACING 指向被点击的方块面）：
     * <ul>
     *   <li>点击<b>地板</b>（+Y 面）→ FACING=UP → 模型竖直（默认模型）；</li>
     *   <li>点击<b>天花板</b>（-Y 面）→ FACING=DOWN → 模型上下颠倒；</li>
     *   <li>点击<b>墙</b>（水平面）→ FACING 水平 → 模型躺倒。</li>
     * </ul>
     * 不用 Create 默认的「玩家视线反方向」（{@code getNearestLookingDirection}），
     * 因为视线俯仰判定不可控，且会受相邻 Create 轴方块「自动对齐」（getPreferredFacing）干扰。
     */
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    // ═══════════════ 轴向动力输入（无齿轮） ═══════════════

    @Override
    public Direction.Axis getRotationAxis(final BlockState blockState) {
        return blockState.getValue(FACING).getAxis();
    }

    /**
     * 轴向输入：未装配时轴方向双面可接（同 swivel 93-96 行）；
     * 装配后仅背面可接轴（正面被从动物理体占据）。
     */
    @Override
    public boolean hasShaftTowards(final LevelReader world, final BlockPos pos, final BlockState state, final Direction face) {
        final Direction facing = state.getValue(FACING);
        return state.getValue(ASSEMBLED) ? face == facing.getOpposite() : face.getAxis() == facing.getAxis();
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

        // 空手右键 = 装配/拆卸（同 swivel 66-73 行）
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
            this.withBlockEntityDo(level, pos, MyBearingBlockEntity::disassemble);
        }

        // blockstate could have changed from disassembly
        rotated = this.getRotatedBlockState(level.getBlockState(pos), context.getClickedFace());
        KineticBlockEntity.switchToBlockState(level, pos, this.updateAfterWrenched(rotated, context));

        if (level.getBlockState(pos) != state)
            IWrenchable.playRotateSound(level, pos);

        return InteractionResult.SUCCESS;
    }

    /**
     * 扳手旋转语义：Create 标准（同 swivel，模型绕旋转轴对称，无需 ROTATION 属性）——
     * 点击面与 FACING 轴<b>垂直</b>：FACING 绕被点击面的轴转 90°（{@code getClockWise}，
     * 即 {@code IWrenchable.getRotatedBlockState} 默认行为）；点击面与 FACING 轴<b>平行</b>：
     * 模型绕旋转轴自转 90°，对称模型无视觉差异 → 无操作。故不覆写，直接用 IWrenchable 默认。
     */

    // ═══════════════ BE 绑定 ═══════════════

    @Override
    public Class<MyBearingBlockEntity> getBlockEntityClass() {
        return MyBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MyBearingBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.aero_bearing_entity.get();
    }

    @Override
    protected VoxelShape getShape(final BlockState blockState, final BlockGetter blockGetter, final BlockPos blockPos, final CollisionContext collisionContext) {
        // 装配后基座用专属 shape（y 0-11.9，顶部让给 plate，两者分离可分别点选），同 swivel
        return blockState.getValue(ASSEMBLED)
                ? MyBearingShapes.BEARING_ASSEMBLED.get(blockState.getValue(FACING))
                : Shapes.block();
    }

    // ═══════════════ Sable 装配移动回调 ═══════════════

    @Override
    public void beforeMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(originLevel, oldPos, MyBearingBlockEntity::beforeAssembly);
    }

    @Override
    public void afterMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(resultingLevel, newPos, MyBearingBlockEntity::associatePlateWithParent);
    }
}
