package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.placement.PoleHelper;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 发动机核心（aero_engine / engine_core）：Create 动力源方块骨架 + 多方块组网（P0，方案见 memo/engine-module.md）。
 * <p>
 * 轴向设计（方案 3，同 Create 传动轴 / 齿轮箱 / 本项目 transmission_peripheral）：
 * <ul>
 *   <li>使用 {@link RotatedPillarKineticBlock} 的 {@code AXIS}（X/Y/Z）三向状态，blockstate 恰好 3 个 state；
 *       blockstate 模型映射见 {@code assets/ccpe/blockstates/engine_core.json}（模型南北对称，x/z 共用，y 用 x 旋转）。</li>
 *   <li>{@code hasShaftTowards} 沿 AXIS 前后两面 → <b>贯通传动杆</b>（模型中心预留 6×6 轴孔，匹配 Create SHAFT_HALF 截面）；
 *       相邻核心排成一排时轴耦合成一个应力网络，controller 发电、整条同转。</li>
 *   <li>扳手默认处理来自 {@code IRotate}/{@code IWrenchable}：右键在轴之间切换（X↔Z，Y 轴不转）、潜行右键拆除，无需覆写。</li>
 *   <li>放置默认继承 {@link RotatedPillarKineticBlock#getStateForPlacement}：优先对齐相邻传动轴，其次按点击面/视线方向定轴。</li>
 * </ul>
 * P0 组网机制（参考 CDG {@code ModularDieselEngineBlock}）：
 * <ul>
 *   <li>{@code onPlace} → {@code updateConnectivity} → {@code ConnectivityHandler.formMulti}（沿 AXIS 组网）；</li>
 *   <li>{@code onRemove} → {@code ConnectivityHandler.splitMulti}（拆中段：全体解体后下一 tick 各自重新抱团）；</li>
 *   <li><b>延伸放置</b>：{@link PlacementHelper}（{@link PoleHelper}）——拿引擎物品右键已有引擎，沿 AXIS 自动延伸放置；</li>
 *   <li>{@code SpecialBlockItemRequirement}：Create 蓝图按单方块消耗引擎物品。</li>
 * </ul>
 * 参考来源：CDG {@code ModularDieselEngineBlock}；Create {@code ConnectivityHandler} / {@code PoleHelper}。
 */
public class EngineCoreBlock extends RotatedPillarKineticBlock implements IBE<EngineCoreBlockEntity>, SpecialBlockItemRequirement, IProxyHoveringInformation {

    /** 延伸放置助手 id：拿引擎物品右键已有引擎，沿 AXIS 自动延伸放置（组网成一条） */
    private static final int placementHelperId = PlacementHelpers.register(new PlacementHelper());

    public EngineCoreBlock(Properties properties) {
        super(properties);
    }

    // ── 选择框（三轴，与 blockstate 变体旋转一致，做法照抄 ThrustNozzleBlock / WaterCoolingModuleBlock） ──

    /**
     * 选择框基准（{@code axis=z} 未旋转变体的模型坐标系，单位 = 1/16 格）。
     * <p>
     * 用户定稿：(5,0,0,11,16,16) 竖梁 + (0,5,0,16,11,16) 横梁 = 「十字」截面沿 Z 贯通
     * （模型的横向/纵向延伸臂跨度都是满 16，只有四个角是空的）。
     */
    private static final VoxelShape BASE_SHAPE =
            Shapes.or(Block.box(5.0D, 0.0D, 0.0D, 11.0D, 16.0D, 16.0D),
                      Block.box(0.0D, 5.0D, 0.0D, 16.0D, 11.0D, 16.0D));

    private static final Map<Direction.Axis, VoxelShape> SHAPES = buildShapes();

    /**
     * 三轴选择框的旋转与 {@code blockstates/engine_core.json} 的变体逐一对应：
     * {@code axis=z} 无旋转（基准盒）；{@code axis=y} 用 {@code x:90} → {@link #rotateX} 1 圈；
     * {@code axis=x} 用 {@code y:90} → {@link #rotateY} 1 圈。
     */
    private static Map<Direction.Axis, VoxelShape> buildShapes() {
        final Map<Direction.Axis, VoxelShape> shapes = new EnumMap<>(Direction.Axis.class);

        shapes.put(Direction.Axis.Z, BASE_SHAPE);
        shapes.put(Direction.Axis.Y, rotateX(BASE_SHAPE, 1));
        shapes.put(Direction.Axis.X, rotateY(BASE_SHAPE, 1));
        return shapes;
    }

    /** 绕 X 轴 90° 的整数倍旋转：1 圈映射 (x,y,z) → (x, z, 1−y)（由 x=90 的模型旋转反推，同 {@link ThrustNozzleBlock}） */
    private static VoxelShape rotateX(VoxelShape shape, int quarterTurns) {
        final int turns = ((quarterTurns % 4) + 4) % 4;

        if (turns == 0) {
            return shape;
        }

        final VoxelShape[] result = new VoxelShape[]{Shapes.empty()};

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double y1 = minY;
            double y2 = maxY;
            double z1 = minZ;
            double z2 = maxZ;

            for (int i = 0; i < turns; i++) {
                final double nextY1 = z1;
                final double nextY2 = z2;
                final double nextZ1 = 1.0D - y2;
                final double nextZ2 = 1.0D - y1;
                y1 = nextY1;
                y2 = nextY2;
                z1 = nextZ1;
                z2 = nextZ2;
            }

            result[0] = Shapes.or(result[0], Shapes.box(minX, y1, z1, maxX, y2, z2));
        });

        return result[0].optimize();
    }

    /** 绕 Y 轴 90° 的整数倍旋转：1 圈映射 (x,y,z) → (1−z, y, x)（照抄 {@link ThrustNozzleBlock#rotateY}） */
    private static VoxelShape rotateY(VoxelShape shape, int quarterTurns) {
        final int turns = ((quarterTurns % 4) + 4) % 4;

        if (turns == 0) {
            return shape;
        }

        final VoxelShape[] result = new VoxelShape[]{Shapes.empty()};

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double x1 = minX;
            double x2 = maxX;
            double z1 = minZ;
            double z2 = maxZ;

            for (int i = 0; i < turns; i++) {
                final double nextX1 = 1.0D - z2;
                final double nextX2 = 1.0D - z1;
                final double nextZ1 = x1;
                final double nextZ2 = x2;
                x1 = nextX1;
                x2 = nextX2;
                z1 = nextZ1;
                z2 = nextZ2;
            }

            result[0] = Shapes.or(result[0], Shapes.box(x1, minY, z1, x2, maxY, z2));
        });

        return result[0].optimize();
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
            @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(AXIS));
    }

    // ================= 多方块组网触发点（P0） =================

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (oldState.getBlock() == state.getBlock() || isMoving)
            super.onPlace(state, level, pos, oldState, isMoving);
        withBlockEntityDo(level, pos, EngineCoreBlockEntity::updateConnectivity);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.hasBlockEntity() && (state.getBlock() != newState.getBlock() || !newState.hasBlockEntity()) &&
                level.getBlockEntity(pos) instanceof EngineCoreBlockEntity be) {
            level.removeBlockEntity(pos);
            ConnectivityHandler.splitMulti(be);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * 邻居方块变化（放置/拆除/状态改变）→ 让整条引擎 controller 去抖重建源列表
     * （罐/燃料箱贴到核心或燃烧室旁时立即被发现；P2.5 源列表事件化，见
     * {@code EngineCoreBlockEntity#onModuleNeighborChanged}）。
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        EngineCoreBlockEntity.onModuleNeighborChanged(level, pos, neighborPos);
    }

    // ================= 延伸放置（P0） =================

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        IPlacementHelper placementHelper = PlacementHelpers.get(placementHelperId);
        if (!player.isShiftKeyDown() && player.mayBuild()) {
            if (placementHelper.matchesItem(stack)) {
                placementHelper.getOffset(player, level, state, pos, hitResult)
                        .placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hitResult);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    // ================= 蓝图物品需求（P0） =================

    @Override
    public ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
        List<ItemStack> list = new ArrayList<>();
        list.add(new ItemStack(MyModBlocks.engine_core.get()));
        return new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, list);
    }

    // ================= IBE =================

    @Override
    public Class<EngineCoreBlockEntity> getBlockEntityClass() {
        return EngineCoreBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EngineCoreBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.engine_core_entity.get();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EngineCoreBlockEntity(pos, state);
    }

    /** 贯通传动杆：沿 AXIS 的前后两面都能接轴（相邻核心排成一排时轴耦合成一个应力网络） */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    /**
     * goggle tooltip 代理：悬停任意核心节 → tooltip 源统一到整条引擎 controller（与模块方块同模式，
     * 悬停非 controller 成员也显示整条引擎状态）。代理时在 controller 上记录悬停方块
     * （{@link EngineCoreBlockEntity#markHoveredModule}），供 {@code addToGoggleTooltip} 按悬停方块分流——
     * 核心自身记录自己 → 核心 tooltip（同时覆盖模块记录，消除跨帧残留）。
     */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        BlockPos controller = EngineCoreBlockEntity.engineControllerPos(level, pos);
        if (controller != null && level.isClientSide
                && level.getBlockEntity(controller) instanceof EngineCoreBlockEntity core) {
            core.markHoveredModule(this);
        }
        return controller != null ? controller : pos;
    }

    // ================= 延伸放置助手 =================

    private static class PlacementHelper extends PoleHelper<Direction.Axis> {

        public PlacementHelper() {
            super(state -> state.is(MyModBlocks.engine_core.get()),
                    state -> state.getValue(EngineCoreBlock.AXIS),
                    EngineCoreBlock.AXIS);
        }

        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.is(MyModBlocks.engine_core.get().asItem());
        }
    }
}
