package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
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
import org.jetbrains.annotations.Nullable;

/**
 * 显示器方块 — 实体部件。12×10 屏幕网格（14×12 面板四周留 1 格边框），手持模块物品右键直接装配。
 */
public class MonitorBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<MonitorBlock> CODEC = simpleCodec(MonitorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 屏幕表面在模型空间的位置（case_exterior 前脸: box(1,3,4,15,15,9)） */
    public static final float SCREEN_Z = 4f;
    public static final float SCREEN_X_MIN = 1f;
    public static final float SCREEN_X_MAX = 15f;
    public static final float SCREEN_Y_MIN = 3f;
    public static final float SCREEN_Y_MAX = 15f;

    /** 面板（screen 元素: box(1,3,5,15,15,5)）在模型中的 z 坐标（1/16 格单位） */
    public static final float PANEL_Z = 5f;
    /** 背景 quad 相对面板向前（朝向玩家）的偏移量，覆盖原面板并避免 z-fighting（0.01px） */
    public static final float BACKGROUND_Z_OFFSET = 0.01f;
    /** 棋盘网格相对屏幕面板每侧的内缩（格），形成 1 格边框（14×12 面板 → 12×10 网格） */
    public static final float GRID_INSET = 1f;

    /** VoxelShaper.forHorizontal 旋转原点：Y=8, 水平中心=8 */
    public static final float ROT_ORIGIN = 8f;
    /** 俯仰铰链（绕 X 轴，模型像素），位于 case 侧轴承中心 */
    public static final float HINGE_Y = 9f;
    public static final float HINGE_Z = 8f;
    /** 偏航颈部（绕 Y 轴，模型像素），位于 bearing 水平中心 */
    public static final float NECK_X = 8f;
    public static final float NECK_Z = 8f;

    /** 底座碰撞体（静态，不随 pitch/yaw/offset 变化） */
    private static final VoxelShape BASE_SHAPE = Block.box(0, 0, 0, 16, 2, 16);
    private static final VoxelShaper BASE_SHAPER = VoxelShaper.forHorizontal(BASE_SHAPE, Direction.NORTH);

    /** 选择框（北向基准）：base + bracket_exterior + case_exterior + box_back */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Shapes.or(
                    Block.box(0, 0, 0, 16, 2, 16),
                    Block.box(0, 2, 6, 16, 11, 10),
                    Block.box(1, 3, 3, 15, 15, 9),
                    Block.box(3, 5, 9, 13, 13, 12)
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
        // 可动 case 不参与碰撞，实体碰撞仅由静态底座承担
        return BASE_SHAPER.get(state.getValue(FACING));
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

    // ═══════════════ 可动变换纯数学 + 动态射线命中 ═══════════════
    // 与 client.MonitorTransform 的渲染正向严格互逆；这里只放纯数学，服务端/客户端均可调用。

    /** 绕水平中心 (cx, cz) 的 Y 轴旋转点（x/z 分量，y 不变）。 */
    private static void rotateYPoint(double[] p, double cx, double cz, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double rx = p[0] - cx, rz = p[2] - cz;
        p[0] = cx + (rx * cos + rz * sin);
        p[2] = cz + (-rx * sin + rz * cos);
    }

    /** 绕 Y 轴旋转方向（x/z 分量，y 不变）。 */
    private static void rotateYDir(double[] d, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double dx = d[0], dz = d[2];
        d[0] = dx * cos + dz * sin;
        d[2] = -dx * sin + dz * cos;
    }

    /**
     * 把「块局部空间」的射线反变换回「模型空间」（平铺、朝北）。
     * 逆变换顺序与渲染正向相反：facing逆 → offset逆 → yaw逆 → pitch逆，旋转取负。
     *
     * @param origin 块局部坐标（world - blockPos），长度 3 数组，就地修改
     * @param dir    视线方向，长度 3 数组，就地修改
     */
    public static void inverseToModel(double[] origin, double[] dir, Direction facing, float yaw, float pitch, int offset) {
        double center = ROT_ORIGIN / 16.0;
        rotateYPoint(origin, center, center, Math.toRadians(facing.getOpposite().toYRot()));
        rotateYDir(dir, Math.toRadians(facing.getOpposite().toYRot()));

        origin[2] += offset / 16.0;

        rotateYPoint(origin, NECK_X / 16.0, NECK_Z / 16.0, Math.toRadians(-yaw));
        rotateYDir(dir, Math.toRadians(-yaw));

        double pr = Math.toRadians(pitch);
        double cos = Math.cos(pr), sin = Math.sin(pr);
        double hingeY = HINGE_Y / 16.0, hingeZ = HINGE_Z / 16.0;
        double ly = origin[1] - hingeY, lz = origin[2] - hingeZ;
        origin[1] = hingeY + ly * cos + lz * sin;
        origin[2] = hingeZ - ly * sin + lz * cos;
        double dy = dir[1], dz = dir[2];
        dir[1] = dy * cos + dz * sin;
        dir[2] = -dy * sin + dz * cos;
    }

    /**
     * 把「模型空间」点（平铺、朝北，块单位）正向变换到「块局部空间」（不含 facing 与方块偏移）。
     * 顺序：pitch → yaw → offset（渲染 PoseStack 为 facing→offset→yaw→pitch，此为其互逆的点变换）。
     */
    public static void transformPointToLocal(double[] point, float yaw, float pitch, int offset) {
        double pr = Math.toRadians(pitch);
        double cos = Math.cos(pr), sin = Math.sin(pr);
        double hingeY = HINGE_Y / 16.0, hingeZ = HINGE_Z / 16.0;
        double ly = point[1] - hingeY, lz = point[2] - hingeZ;
        point[1] = hingeY + ly * cos - lz * sin;
        point[2] = hingeZ + ly * sin + lz * cos;

        rotateYPoint(point, NECK_X / 16.0, NECK_Z / 16.0, Math.toRadians(yaw));

        point[2] -= offset / 16.0;
    }

    /**
     * 视线射线（块局部 / plot 空间，含方向）→ NORTH 基准屏幕局部坐标 [sx, sy]（1/16 格单位）。
     * @return [sx, sy]；射线与屏幕平面平行或相交于视线后方时返回 null。
     */
    @Nullable
    public static float[] rayToScreenLocal(BlockPos pos, Direction facing, float yaw, float pitch, int offset,
                                           Vec3 origin, Vec3 dir) {
        Vec3 block = Vec3.atLowerCornerOf(pos);
        double[] o = { origin.x - block.x, origin.y - block.y, origin.z - block.z };
        double[] d = { dir.x, dir.y, dir.z };
        inverseToModel(o, d, facing, yaw, pitch, offset);

        double planeZ = PANEL_Z / 16.0;
        if (Math.abs(d[2]) < 1e-6) return null;
        double t = (planeZ - o[2]) / d[2];
        if (t < 0) return null;
        return new float[]{(float) ((o[0] + t * d[0]) * 16.0), (float) ((o[1] + t * d[1]) * 16.0)};
    }

    /**
     * 视线射线 → 屏幕网格坐标 [gridX, gridY]；未命中屏幕网格区域时返回 null。
     */
    @Nullable
    public static int[] rayToGrid(BlockPos pos, Direction facing, float yaw, float pitch, int offset,
                                  Vec3 origin, Vec3 dir) {
        float[] local = rayToScreenLocal(pos, facing, yaw, pitch, offset, origin, dir);
        if (local == null) return null;
        float rx = local[0], ry = local[1];

        // 命中区域为内缩后的 12×10 网格（四周 1 格边框不属于可放置区域）
        if (rx < SCREEN_X_MIN + GRID_INSET || rx > SCREEN_X_MAX - GRID_INSET) return null;
        if (ry < SCREEN_Y_MIN + GRID_INSET || ry > SCREEN_Y_MAX - GRID_INSET) return null;

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

        // 模块放置统一由 MonitorGridOverlay（客户端）用动态射线求交处理；
        // 这里仅消费右键，避免原版继续处理模块物品。
        if (ModuleType.fromItem(stack) != null) {
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
