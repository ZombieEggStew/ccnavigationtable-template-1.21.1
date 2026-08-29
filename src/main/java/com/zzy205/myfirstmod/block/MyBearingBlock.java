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
 * 自研风帆轴承（my_bearing）方块。
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

    // ═══════════════ BE 绑定 ═══════════════

    @Override
    public Class<MyBearingBlockEntity> getBlockEntityClass() {
        return MyBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MyBearingBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.my_bearing_entity.get();
    }

    @Override
    protected VoxelShape getShape(final BlockState blockState, final BlockGetter blockGetter, final BlockPos blockPos, final CollisionContext collisionContext) {
        // 阶段 1-2：先全方块碰撞，后续可换装配后专属 shape（同 swivel SimBlockShapes）
        return Shapes.block();
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
