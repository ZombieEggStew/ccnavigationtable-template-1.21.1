package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 风帆轴承的 plate（link block）方块。
 * <p>
 * 对应 simulated 的 {@code swivel_bearing_link_block}（SwivelBearingPlateBlock）：
 * 玩家把它放在轴承 facing 前方，装配时它连同前方结构一起进入 sub-level，
 * 由 Sable 渲染并随从动物理体旋转（模型 = {@code my_bearing_plate.json}）。
 * 空手右键 plate 会把装配请求转给父轴承（{@code setParentAssembleNextTick}）。
 * <p>
 * 参考来源：{@code references/Simulated-Project-main/.../swivel_bearing/link_block/SwivelBearingPlateBlock.java}。
 */
public class MyBearingPlateBlock extends DirectionalKineticBlock implements IBE<MyBearingPlateBlockEntity>, BlockSubLevelAssemblyListener {

    /**
     * 顶部（head）绕 y 轴的旋转档位：0/1/2/3 = 0°/90°/180°/270°。
     * <p>
     * 与 {@link MyBearingBlock#ROTATION} 同名同义：装配时由
     * {@code MyBearingBlockEntity.assemble()} 从轴承 blockstate 继承，
     * 使 plate 顶部朝向与轴承顶部一致（plate 模型绕 y 轴不对称，
     * 不同于 swivel 的 plate 模型绕 y 轴对称、无需 rotation）。
     */
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    public MyBearingPlateBlock(final Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(ROTATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(ROTATION));
    }

    @Override
    public boolean hasShaftTowards(final LevelReader world, final BlockPos pos, final BlockState state, final Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public Class<MyBearingPlateBlockEntity> getBlockEntityClass() {
        return MyBearingPlateBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MyBearingPlateBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.my_bearing_plate_entity.get();
    }

    @Override
    protected ItemInteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final BlockHitResult hitResult) {
        if (!player.mayBuild()) {
            return ItemInteractionResult.FAIL;
        }

        if (player.isShiftKeyDown()) {
            return ItemInteractionResult.FAIL;
        }

        if (player.getItemInHand(hand).isEmpty()) {
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }

            this.withBlockEntityDo(level, pos, MyBearingPlateBlockEntity::setParentAssembleNextTick);
        }

        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected VoxelShape getCollisionShape(final BlockState blockState, final BlockGetter blockGetter, final BlockPos blockPos, final CollisionContext collisionContext) {
        // 同 swivel：plate 碰撞框（y 12-16、xz 3-13）
        return MyBearingShapes.PLATE_COLLISION.get(blockState.getValue(FACING));
    }

    @Override
    protected VoxelShape getShape(final BlockState blockState, final BlockGetter blockGetter, final BlockPos blockPos, final CollisionContext collisionContext) {
        // 同 swivel：plate 选中框（y 12.1-16），与基座选择框（y 0-11.9）分离、可分别点选
        return MyBearingShapes.PLATE.get(blockState.getValue(FACING));
    }

    @Override
    protected VoxelShape getBlockSupportShape(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return MyBearingShapes.PLATE.get(state.getValue(FACING));
    }

    @Override
    public InteractionResult onWrenched(final BlockState state, final UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public ItemStack getCloneItemStack(final LevelReader level, final BlockPos pos, final BlockState state) {
        // 与 swivel 一致：拾取时给主轴承物品
        return new ItemStack(MyModBlocks.my_bearing.get());
    }

    // ═══════════════ Sable 装配移动回调 ═══════════════

    @Override
    public void beforeMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(originLevel, oldPos, MyBearingPlateBlockEntity::beforeAssembly);
    }

    @Override
    public void afterMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(resultingLevel, newPos, MyBearingPlateBlockEntity::fixParentLinkingWhenMoved);
    }
}
