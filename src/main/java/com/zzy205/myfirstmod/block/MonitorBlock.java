package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
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
import net.minecraft.world.phys.Vec3;
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
    /** 背景绘制平面的模型空间 z 坐标（1/16 格单位）。射线与 Monitor 求交须使用同一平面，保证命中点与所绘制背景对齐。 */
    public static final float BACKGROUND_PLANE_Z = PANEL_Z - BACKGROUND_Z_OFFSET;
    /** 棋盘网格相对屏幕面板每侧的内缩（格），形成 1 格边框（14×12 面板 → 12×10 网格） */
    public static final float GRID_INSET = 1f;

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

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /**
     * 局部/世界命中点 → 屏幕局部坐标（1/16 格单位，不裁剪边界）。
     * @return [screenX, screenY]（1/16 格单位）或 null（朝向非法）
     */
    @Nullable
    public static double[] worldHitToScreenLocal(BlockPos pos, Direction facing, double hitX, double hitY, double hitZ) {
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
        return new double[]{ rx * 16.0, ry * 16.0 };
    }

    /**
     * 局部/世界命中点 → 屏幕网格坐标。
     * 命中区域为内缩后的 12×10 网格（四周 1 格边框不属于可放置区域）。
     * @return [gridX, gridY] 或 null 表示未命中屏幕区域。
     */
    @Nullable
    public static int[] worldHitToGrid(BlockPos pos, Direction facing, double hitX, double hitY, double hitZ) {
        double[] sc = worldHitToScreenLocal(pos, facing, hitX, hitY, hitZ);
        if (sc == null) return null;
        double rx = sc[0], ry = sc[1];

        if (rx < SCREEN_X_MIN + GRID_INSET - 0.5 || rx > SCREEN_X_MAX - GRID_INSET + 0.5) return null;
        if (ry < SCREEN_Y_MIN + GRID_INSET - 0.5 || ry > SCREEN_Y_MAX - GRID_INSET + 0.5) return null;

        int gx = (int) Math.floor(rx - SCREEN_X_MIN - GRID_INSET);
        int gy = (int) Math.floor(ry - SCREEN_Y_MIN - GRID_INSET);
        if (gx < 0 || gx >= GridState.GRID_WIDTH || gy < 0 || gy >= GridState.GRID_HEIGHT) return null;
        return new int[]{gx, gy};
    }

    /**
     * 玩家视线 → 背景平面交点 → 网格坐标。
     * 求交平面与背景绘制平面（{@link #BACKGROUND_PLANE_Z}）一致，保证命中点与所绘制背景对齐。
     * eyePos/lookVec 须处于同一坐标系（普通世界 = 世界坐标；Sable 子次元 = 局部坐标）。
     * @return [gridX, gridY] 或 null
     */
    @Nullable
    public static int[] rayToGrid(BlockPos pos, Direction facing, Vec3 eyePos, Vec3 lookVec) {
        Vec3 hit = intersectBackgroundPlane(pos, facing, eyePos, lookVec);
        if (hit == null) return null;
        return worldHitToGrid(pos, facing, hit.x, hit.y, hit.z);
    }

    /**
     * 玩家视线 → 背景平面交点 → 屏幕局部坐标（1/16 格单位，不裁剪边界）。
     * 用于旋钮拖拽等需要连续坐标的场景。
     * @return [screenX, screenY] 或 null
     */
    @Nullable
    public static double[] rayToScreenLocal(BlockPos pos, Direction facing, Vec3 eyePos, Vec3 lookVec) {
        Vec3 hit = intersectBackgroundPlane(pos, facing, eyePos, lookVec);
        if (hit == null) return null;
        return worldHitToScreenLocal(pos, facing, hit.x, hit.y, hit.z);
    }

    /** 射线与背景平面求交，返回交点（eyePos/lookVec 同坐标系），未命中返回 null。 */
    @Nullable
    private static Vec3 intersectBackgroundPlane(BlockPos pos, Direction facing, Vec3 origin, Vec3 dir) {
        float c = ROT_ORIGIN / 16f;
        // 快速测试：反转探测平面方向（内凹 4.99 → 外凸 3.01），确认偏移方向后再定最终值
        float planeZ = (2 * SCREEN_Z - BACKGROUND_PLANE_Z) / 16f;

        // 背景平面在模型空间中的法向量和一点
        Vec3 normal, point;
        switch (facing) {
            case NORTH:
                normal = new Vec3(0, 0, 1);
                point = new Vec3(pos.getX() + c, pos.getY() + c, pos.getZ() + planeZ);
                break;
            case SOUTH:
                normal = new Vec3(0, 0, -1);
                point = new Vec3(pos.getX() + c, pos.getY() + c, pos.getZ() + (1 - planeZ));
                break;
            case EAST:
                normal = new Vec3(-1, 0, 0);
                point = new Vec3(pos.getX() + (1 - planeZ), pos.getY() + c, pos.getZ() + c);
                break;
            case WEST:
                normal = new Vec3(1, 0, 0);
                point = new Vec3(pos.getX() + planeZ, pos.getY() + c, pos.getZ() + c);
                break;
            default: return null;
        }

        // 射线-平面求交: t = ((point - origin) · normal) / (dir · normal)
        double denom = dir.dot(normal);
        if (Math.abs(denom) < 1e-6) return null;
        double t = point.subtract(origin).dot(normal) / denom;
        if (t < 0) return null;

        return origin.add(dir.scale(t));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (!level.isClientSide) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        Direction facing = state.getValue(FACING);
        // 在子次元局部空间自算射线与背景平面求交（与 Aeroworks/control-panels 同款做法），
        // 不依赖 hitResult.getLocation() 的坐标，正常世界与 Sable 物理体内都消除斜视视差。
        var subLevel = SableCompat.getContainingSubLevel(level, pos);
        Vec3 eyeLocal = SableCompat.toLocalPosition(subLevel, 0f, player.getEyePosition());
        Vec3 lookLocal = SableCompat.toLocalDirection(subLevel, 0f, player.getViewVector(1.0f));
        int[] gp = rayToGrid(pos, facing, eyeLocal, lookLocal);

        // ── 模块放置 ──
        ModuleType type = ModuleType.fromItem(stack);
        if (type != null && gp != null) {
            PacketDistributor.sendToServer(new PlaceModulePayload(pos, gp[0], gp[1], type.name));
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
