package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import com.zzy205.myfirstmod.network.RemoveModulePayload;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 显示器方块 — 实体部件。14×12 屏幕网格，手持模块物品右键直接装配。
 */
public class MonitorBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<MonitorBlock> CODEC = simpleCodec(MonitorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 屏幕表面在模型空间的位置（case_exterior 前脸: box(1,2,4,15,14,9)） */
    public static final float SCREEN_Z = 4f;
    public static final float SCREEN_X_MIN = 1f;
    public static final float SCREEN_X_MAX = 15f;
    public static final float SCREEN_Y_MIN = 2f;
    public static final float SCREEN_Y_MAX = 14f;

    /** VoxelShaper.forHorizontal 旋转原点：Y=8, 水平中心=8 */
    public static final float ROT_ORIGIN = 8f;

    /** 选择框（北向基准）：base + bracket_exterior + case_exterior + box_back */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Shapes.or(
                    Block.box(0, 0, 3, 16, 2, 13),
                    Block.box(0, 2, 6, 16, 10, 10),
                    Block.box(1, 2, 4, 15, 14, 9),
                    Block.box(3, 4, 9, 13, 12, 12)
            ),
            Direction.NORTH
    );

    public MonitorBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE.get(state.getValue(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE.get(state.getValue(FACING));
    }

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    /**
     * 世界空间命中点 → 屏幕网格坐标（客户端使用）。
     * 注意：射线命中方块外表面，非凹入的屏幕面，故不校验 Z。
     * @return [gridX, gridY] 或 null 表示未命中屏幕区域。
     */
    @Nullable
    public static int[] worldHitToGrid(BlockPos pos, Direction facing, double hitX, double hitY, double hitZ) {
        double lx = hitX - pos.getX();
        double ly = hitY - pos.getY();
        double lz = hitZ - pos.getZ();

        double rx, ry = ly;
        float c = ROT_ORIGIN / 16f;
        switch (facing) {
            case NORTH: rx = lx;     break;
            case SOUTH: rx = 2*c-lx; break;
            case EAST:  rx = lz;     break;
            case WEST:  rx = 2*c-lz; break;
            default: return null;
        }
        rx *= 16.0; ry *= 16.0;

        // 不检查 Z —— 射线打在方块外表面而非凹入的屏幕
        if (rx < SCREEN_X_MIN - 0.5 || rx > SCREEN_X_MAX + 0.5) return null;
        if (ry < SCREEN_Y_MIN - 0.5 || ry > SCREEN_Y_MAX + 0.5) return null;

        int gx = (int) Math.floor(rx - SCREEN_X_MIN);
        int gy = (int) Math.floor(ry - SCREEN_Y_MIN);
        if (gx < 0 || gx >= 14 || gy < 0 || gy >= 12) return null;
        return new int[]{gx, gy};
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (!level.isClientSide) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        Direction facing = state.getValue(FACING);
        int[] gp = worldHitToGrid(pos, facing,
                hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z);

        // ── 扳手拆卸 ──
        if (stack.getItem().toString().equals("create:wrench") && gp != null) {
            var be = level.getBlockEntity(pos);
            if (be instanceof MonitorBlockEntity monitorBE) {
                int cellId = monitorBE.getGridState().getCell(gp[0], gp[1]);
                if (cellId >= 0) {
                    PacketDistributor.sendToServer(new RemoveModulePayload(pos, cellId));
                    return ItemInteractionResult.SUCCESS;
                }
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // ── 模块放置 ──
        ModuleType type = ModuleType.fromItem(stack);
        if (type != null && gp != null) {
            PacketDistributor.sendToServer(new PlaceModulePayload(pos, gp[0], gp[1], type.name));
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
