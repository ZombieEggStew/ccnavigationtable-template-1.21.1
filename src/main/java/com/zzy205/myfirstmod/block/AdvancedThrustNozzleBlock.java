package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.zzy205.myfirstmod.screen.AdvancedThrustNozzleMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 矢量喷管（{@code ccpe:advanced_thrust_nozzle}）—— 定向喷管 {@link ThrustNozzleBlock} 的加强版。
 * <p>
 * 与定向喷管的唯一结构差别：<b>固定部分只剩底板</b>，喷口本体（筒身 + 十字臂 + 顶环）可以在一个锥角范围内
 * 绕枢轴 {@code (8,2,8)px} 转动，四个小活塞随动（动态模型见
 * {@code assets/ccpe/models/block/advanced_thrust_nozzle/}，渲染见 {@link AdvancedThrustNozzleRenderer}）。
 * 参考实现：{@code aeronautics:gyroscopic_propeller_bearing}（固定体 + 可倾斜顶盘 + 四个动态小活塞）。
 * <p>
 * 除此之外<b>一切都继承</b> {@link ThrustNozzleBlock}：贴在 {@code create:encased_fan} 出风面
 * （{@link #canSurvive}）、六向 {@code FACING}、染料右键染尾迹、扳手语义、推力/气压/粒子全部同款
 * （推力逻辑在 {@link ThrustNozzleBlockEntity}，矢量喷管的 BE 直接继承它）。
 */
public class AdvancedThrustNozzleBlock extends ThrustNozzleBlock {

    public static final MapCodec<AdvancedThrustNozzleBlock> CODEC = simpleCodec(AdvancedThrustNozzleBlock::new);

    /**
     * 选择框 / 碰撞箱基准（{@code facing=up} 未旋转的模型坐标系，单位 = 1/16 格）。
     * <p>
     * 用户定稿：<b>底板 {@code (0,0,0,16,4,16)} + 喷口包络 {@code (2,4,2,14,16,14)}</b>。
     * 两个盒都是"静止姿态的最大包络"，<b>不随倾斜 / 活塞动画改变</b>：喷口本体在锥角内连续倾斜，
     * VoxelShape 表达不了连续旋转，而 15° 锥角带来的横向偏移只有约 1.5px，包络盒已经覆盖得住。
     * <p>
     * 盒子在模型空间不对称（下半是整块、上半内缩），所以<b>必须按朝向旋转</b>：
     * 六向盒由 {@link #buildShapes()} 照 {@code blockstates/advanced_thrust_nozzle.json} 的 x/y 旋转生成
     * （复用父类的 {@code rotateX}/{@code rotateY}，与 {@link ThrustNozzleBlock} 同一套手法）。
     */
    private static final VoxelShape BASE_SHAPE =
            Shapes.or(Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D),
                      Block.box(2.0D, 4.0D, 2.0D, 14.0D, 16.0D, 14.0D));

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    /**
     * 与 blockstate 变体一致的六向盒：up = 原样；down = x180；north = x90；
     * east/south/west = north 再绕 Y 转 90/180/270（同 {@link ThrustNozzleBlock}）。
     */
    private static Map<Direction, VoxelShape> buildShapes() {
        final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        final VoxelShape north = rotateX(BASE_SHAPE, 1);

        shapes.put(Direction.UP, BASE_SHAPE);
        shapes.put(Direction.DOWN, rotateX(BASE_SHAPE, 2));
        shapes.put(Direction.NORTH, north);
        shapes.put(Direction.EAST, rotateY(north, 1));
        shapes.put(Direction.SOUTH, rotateY(north, 2));
        shapes.put(Direction.WEST, rotateY(north, 3));
        return shapes;
    }

    public AdvancedThrustNozzleBlock(final Properties properties) {
        super(properties);
    }

    @Override
    protected @NotNull MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape shapeFor(final Direction facing) {
        return SHAPES.get(facing);
    }

    @Override
    protected BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new AdvancedThrustNozzleBlockEntity(pos, state);
    }

    @Override
    protected BlockEntityType<?> entityType() {
        return MyModBlockEntities.advanced_thrust_nozzle_entity.get();
    }

    // ═══════════════ ticker：客户端粒子（继承父类）+ 服务端频道复核 ═══════════════

    private static final BlockEntityTicker<AdvancedThrustNozzleBlockEntity> SERVER_TICKER =
            AdvancedThrustNozzleBlockEntity::serverTick;

    /**
     * 客户端照父类走（喷排气/进气/尾迹粒子）；服务端挂频道复核 ticker（每 20 tick 重注册频道，
     * 轴承连/断后约束链变化时同链频道冲突要顺延，照 {@code short_range_linker}）。
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level, final BlockState state,
            final BlockEntityType<T> type) {
        if (type != this.entityType()) {
            return null;
        }

        if (level.isClientSide) {
            return super.getTicker(level, state, type);
        }

        @SuppressWarnings("unchecked")
        final BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) SERVER_TICKER;
        return ticker;
    }

    // ═══════════════ 右键菜单：频道选择 ═══════════════

    private static final Component GUI_TITLE =
            Component.translatable("block.ccpe.advanced_thrust_nozzle");

    /**
     * 空手右键打开频道 GUI（潜行右键放行，留给扳手拆除）。
     * <p>
     * 手持染料右键仍然优先走父类的 {@code useItemOn}（染尾迹），两者互不影响。
     */
    @Override
    protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos,
            final Player player, final BlockHitResult hitResult) {
        if (player.isCrouching()) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide && player instanceof final ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof final AdvancedThrustNozzleBlockEntity nozzle) {
            // 打开 GUI 前刷新同链占用快照，保证滚轮「跳过同链占用」用的是最新数据
            nozzle.refreshOccupiedChannels();
            final boolean onPhysicsBody = nozzle.isOnPhysicsBody();
            final int channel = nozzle.getChannel();
            final int[] occupiedChannels = nozzle.getOccupiedChannels();

            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inv, p) -> new AdvancedThrustNozzleMenu(containerId, pos, inv, onPhysicsBody),
                            GUI_TITLE
                    ),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeBoolean(onPhysicsBody);
                        buf.writeVarInt(channel);
                        buf.writeVarInt(occupiedChannels.length);
                        for (final int ch : occupiedChannels) {
                            buf.writeVarInt(ch);
                        }
                    }
            );
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
