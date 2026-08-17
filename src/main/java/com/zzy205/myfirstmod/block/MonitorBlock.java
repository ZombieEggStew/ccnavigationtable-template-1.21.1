package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
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
 * 显示器方块 — 实体部件。12×10 屏幕网格（14×12 面板四周留 1 格边框），手持模块物品右键直接装配。
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

    /** 面板（screen 元素: box(1,2,5,15,14,5)）在模型中的 z 坐标（1/16 格单位） */
    public static final float PANEL_Z = 5f;
    /** 背景 quad 相对面板向前（朝向玩家）的偏移量，覆盖原面板并避免 z-fighting（0.01px） */
    public static final float BACKGROUND_Z_OFFSET = 0.01f;
    /** 棋盘网格相对屏幕面板每侧的内缩（格），形成 1 格边框（14×12 面板 → 12×10 网格） */
    public static final float GRID_INSET = 1f;

    /** VoxelShaper.forHorizontal 旋转原点：Y=8, 水平中心=8 */
    public static final float ROT_ORIGIN = 8f;

    /** 选择框（北向基准）：base + bracket_exterior + case_exterior + box_back */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Shapes.or(
                    Block.box(0, 0, 3, 16, 2, 13),
                    Block.box(0, 2, 6, 16, 10, 10),
                    Block.box(1, 2, 4.9, 15, 14, 9),
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

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /**
     * 世界(子次元局部)空间命中点 → NORTH 基准的屏幕局部坐标（1/16 格单位）。
     * 按 facing 反旋转，供网格命中与旋钮拖拽共用同一套坐标口径。
     * @return [sx, sy]（sx 为屏幕横向、sy 为屏幕纵向）；facing 非水平时返回 null。
     */
    @Nullable
    public static float[] hitToScreenLocal(BlockPos pos, Direction facing, double hitX, double hitY, double hitZ) {
        double lx = hitX - pos.getX();
        double ly = hitY - pos.getY();
        double lz = hitZ - pos.getZ();

        double rx;
        float c = ROT_ORIGIN / 16f;
        switch (facing) {
            case NORTH: rx = lx;        break;
            case SOUTH: rx = 2*c - lx;  break;
            case EAST:  rx = lz;        break;
            case WEST:  rx = 2*c - lz;  break;
            default: return null;
        }
        return new float[]{(float) (rx * 16.0), (float) (ly * 16.0)};
    }

    /**
     * 世界空间命中点 → 屏幕网格坐标（客户端使用）。
     * 注意：射线命中方块外表面，非凹入的屏幕面，故不校验 Z。
     * @return [gridX, gridY] 或 null 表示未命中屏幕区域。
     */
    @Nullable
    public static int[] worldHitToGrid(BlockPos pos, Direction facing, double hitX, double hitY, double hitZ) {
        float[] local = hitToScreenLocal(pos, facing, hitX, hitY, hitZ);
        if (local == null) return null;
        float rx = local[0], ry = local[1];

        // 不检查 Z —— 射线打在方块外表面而非凹入的屏幕
        // 命中区域为内缩后的 12×10 网格（四周 1 格边框不属于可放置区域）
        if (rx < SCREEN_X_MIN + GRID_INSET - 0.5 || rx > SCREEN_X_MAX - GRID_INSET + 0.5) return null;
        if (ry < SCREEN_Y_MIN + GRID_INSET - 0.5 || ry > SCREEN_Y_MAX - GRID_INSET + 0.5) return null;

        int gx = (int) Math.floor(rx - SCREEN_X_MIN - GRID_INSET);
        int gy = (int) Math.floor(ry - SCREEN_Y_MIN - GRID_INSET);
        if (gx < 0 || gx >= GridState.GRID_WIDTH || gy < 0 || gy >= GridState.GRID_HEIGHT) return null;
        return new int[]{gx, gy};
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (!level.isClientSide) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        Direction facing = state.getValue(FACING);
        // 直接使用 hitResult.getLocation()：它在 Sable 子次元中已经是局部（plot）坐标系，
        // 与 pos 同空间，避免了自算射线-平面求交带来的内凹平面视差。
        int[] gp = worldHitToGrid(pos, facing,
                hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z);

        // ── 模块放置 ──
        ModuleType type = ModuleType.fromItem(stack);
        if (type != null && gp != null) {
            PacketDistributor.sendToServer(new PlaceModulePayload(pos, gp[0], gp[1], type.name));
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
