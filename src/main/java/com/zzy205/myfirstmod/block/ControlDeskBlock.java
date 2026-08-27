package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.monitor.ModuleType;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台方块 — 底座由 blockstate 静态模型渲染；踏板/操纵杆为可安装控件物品（pedal/joystick）。
 * 手持控件物品右键安装到北面（模型空间 -Z 侧，随 FACING 旋转）；扳手蹲下右键卸载；破坏时控件掉落。
 * 扳手普通右键 / 空手蹲下右键 → 消费右键，配置菜单由客户端 ControlDeskPlacementOverlay 打开（不再旋转方块）。
 * 模型：models/block/control_desk_1/my_control_desk_base.json
 */
public class ControlDeskBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<ControlDeskBlock> CODEC = simpleCodec(ControlDeskBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 拓展坞已装：模型切换为 slab（16×8×16 整块桌面）、选择框同步、桌顶网格变 14×14、禁装 PEDAL/JOYSTICK */
    public static final BooleanProperty DOCKED = BooleanProperty.create("docked");
    /** 挡板已装：模型切换为 3/4 楼梯（北侧 z0..8 全高立墙 + 南侧下半桌面）、选择框同步、禁装 PEDAL/JOYSTICK（与 DOCKED 互斥）；桌顶棋盘网格模块不受影响 */
    public static final BooleanProperty BAFFLED = BooleanProperty.create("baffled");

    /** 北向基准形状（对应模型元素 from/to）：仅桌体一块；选择框/碰撞箱均使用，安装控件不改变形状 */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Block.box(0, 0, 8, 16, 8, 16),
            Direction.NORTH
    );
    /** 拓展坞（slab）形态形状：16×8×16 整块桌面（北侧空区 z0..8 也被桌面覆盖） */
    private static final VoxelShaper DOCKED_SHAPE = VoxelShaper.forHorizontal(
            Block.box(0, 0, 0, 16, 8, 16),
            Direction.NORTH
    );
    /** 挡板（3/4 楼梯）形态形状：北侧 z0..8 全高立墙 + 南侧 z8..16 下半桌面（3/4 块体积，与 stair 模型经 blockstate y+180 渲染一致） */
    private static final VoxelShaper BAFFLED_SHAPE = VoxelShaper.forHorizontal(
            Shapes.or(Block.box(0, 0, 0, 16, 16, 8), Block.box(0, 0, 8, 16, 8, 16)),
            Direction.NORTH
    );

    // ── 控件安装位（北向基准 0..16 模型空间，随 FACING 旋转；供安装/拆除预览框与拆除判定使用） ──
    // 北侧空区 z0..8 分成左/中/右：左踏板 x11..16、操纵杆 x5..11、右踏板 x0..5（操作者面朝南，左=东=+X）
    // （monitor_2 / throttle / joystick_2 的原后缘插槽已移除，改桌顶 6×14 棋盘网格自由放置 —— 显示先行，放置逻辑待做）
    private static final VoxelShape PEDAL_LEFT_SHAPE = Block.box(12, 1, 1, 16, 7, 8);
    private static final VoxelShape PEDAL_RIGHT_SHAPE = Block.box(0, 1, 1, 4, 7, 8);
    private static final VoxelShape JOYSTICK_SHAPE = Block.box(5, 0, 0, 11, 8, 8);
    private static final VoxelShaper PEDAL_LEFT_SHAPER = VoxelShaper.forHorizontal(PEDAL_LEFT_SHAPE, Direction.NORTH);
    private static final VoxelShaper PEDAL_RIGHT_SHAPER = VoxelShaper.forHorizontal(PEDAL_RIGHT_SHAPE, Direction.NORTH);
    private static final VoxelShaper JOYSTICK_SHAPER = VoxelShaper.forHorizontal(JOYSTICK_SHAPE, Direction.NORTH);

    public ControlDeskBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, DOCKED, BAFFLED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(DOCKED, false)
                .setValue(BAFFLED, false);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShaper shape = state.getValue(BAFFLED) ? BAFFLED_SHAPE
                : (state.getValue(DOCKED) ? DOCKED_SHAPE : SHAPE);
        return shape.get(state.getValue(FACING));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControlDeskBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // 服务端每 tick 模拟操纵杆轴动力学（输入租约 + 自由/档位模式），客户端不 tick
        if (level.isClientSide) return null;
        return createTickerHelper(type, MyModBlockEntities.control_desk_entity.get(),
                ControlDeskBlockEntity::tickServer);
    }

    // ════════════════════ 控件安装 / 卸载 ════════════════════

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        ControlDeskBlockEntity.ControlType type = controlTypeOf(stack);
        if (type != null) {
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }
            boolean docked = state.getValue(DOCKED);
            boolean baffled = state.getValue(BAFFLED);
            // 拓展坞已装：禁止再装 PEDAL / JOYSTICK / 挡板（北侧空区已被桌面覆盖 / 形态互斥）
            if (docked && (type == ControlDeskBlockEntity.ControlType.PEDAL
                    || type == ControlDeskBlockEntity.ControlType.JOYSTICK
                    || type == ControlDeskBlockEntity.ControlType.BAFFLE)) {
                if (player != null) {
                    player.displayClientMessage(
                            Component.translatable("gui.ccpe.control_desk.cannot_install_on_dock"), true);
                }
                return ItemInteractionResult.SUCCESS;
            }
            // 挡板已装：禁止再装北侧控件 PEDAL / JOYSTICK 与同为形态安装的 DOCK（北侧区域已被立墙占据 / 形态互斥）；
            // 桌顶棋盘网格模块（joystick_2 / throttle / throttle_2 / monitor_2）不受影响，可与挡板共存
            if (baffled && (type == ControlDeskBlockEntity.ControlType.PEDAL
                    || type == ControlDeskBlockEntity.ControlType.JOYSTICK
                    || type == ControlDeskBlockEntity.ControlType.DOCK)) {
                if (player != null) {
                    player.displayClientMessage(
                            Component.translatable("gui.ccpe.control_desk.cannot_install_on_baffle"), true);
                }
                return ItemInteractionResult.SUCCESS;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ControlDeskBlockEntity desk)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            // 安装成功：throttle / joystick_2 按「桌体→玩家的水平方向」记录旋转（throttle 只能 0°/180°、
            // joystick_2 90° 间隔，均让模型 -Z 面向玩家）；monitor_2 不面向玩家（不记录旋转，只随桌体 FACING）。
            // 放置中心：四个自由放置模块均吸附后钳制到「占地完全位于网格内」的合法中心范围
            // （预览只能在可放置区域内移动；throttle / throttle_2 / monitor_2 为 14×6，joystick_2 为 4×4）
            int placeX = 8, placeZ = 8;
            Direction toPlayer = null;
            if (type == ControlDeskBlockEntity.ControlType.JOYSTICK_2) {
                int[] c = snappedBoxCenterClamped(pos, state.getValue(FACING), hitResult.getLocation(),
                        state.getValue(DOCKED),
                        ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF, ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF);
                placeX = c[0];
                placeZ = c[1];
                toPlayer = directionFromDeskTo(player, pos);
            } else if (type == ControlDeskBlockEntity.ControlType.THROTTLE) {
                int[] c = snappedBoxCenterClamped(pos, state.getValue(FACING), hitResult.getLocation(),
                        state.getValue(DOCKED),
                        ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X, ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z);
                placeX = c[0];
                placeZ = c[1];
                toPlayer = directionFromDeskTo(player, pos);
            } else if (type == ControlDeskBlockEntity.ControlType.THROTTLE_2) {
                int[] c = snappedBoxCenterClamped(pos, state.getValue(FACING), hitResult.getLocation(),
                        state.getValue(DOCKED),
                        ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X, ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z);
                placeX = c[0];
                placeZ = c[1];
                toPlayer = directionFromDeskTo(player, pos);
            } else if (type == ControlDeskBlockEntity.ControlType.MONITOR_2) {
                int[] c = snappedBoxCenterClamped(pos, state.getValue(FACING), hitResult.getLocation(),
                        state.getValue(DOCKED),
                        ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X, ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z);
                placeX = c[0];
                placeZ = c[1];
                // toPlayer 保持 null：monitor_2 不做面向玩家的旋转
            }
            if (!desk.install(type, placeX, placeZ, toPlayer)) {
                // 已安装 / 位置被占用 / 与 PEDAL·JOYSTICK 互斥（DOCK）/ 挡板需先拆北侧控件（PEDAL·JOYSTICK·DOCK）：不消耗物品，提示玩家
                if (player != null) {
                    Component msg = desk.isInstalled(type)
                            ? Component.translatable("gui.ccpe.control_desk.already_installed")
                            : (type == ControlDeskBlockEntity.ControlType.BAFFLE
                                    ? Component.translatable("gui.ccpe.control_desk.baffle_remove_modules_first")
                                    : Component.translatable("gui.ccpe.control_desk.position_occupied"));
                    player.displayClientMessage(msg, true);
                }
                return ItemInteractionResult.SUCCESS;
            }
            // 拓展坞安装成功：切换 blockstate DOCKED（模型/选择框/桌顶网格同步换 slab 形态；同 block 换 property，BE 保留）
            if (type == ControlDeskBlockEntity.ControlType.DOCK) {
                level.setBlock(pos, state.setValue(DOCKED, true), 3);
                desk.setChanged();
            }
            // 挡板安装成功：切换 blockstate BAFFLED（模型/选择框同步换 3/4 楼梯形态；同 block 换 property，BE 保留）
            if (type == ControlDeskBlockEntity.ControlType.BAFFLE) {
                level.setBlock(pos, state.setValue(BAFFLED, true), 3);
                desk.setChanged();
            }
            // 安装成功：非创造模式消耗 1 个物品
            if (player != null && !player.isCreative()) {
                stack.shrink(1);
            }
            return ItemInteractionResult.SUCCESS;
        }

        // 空手 + 蹲下右键：消费右键（控制台配置菜单由客户端 ControlDeskPlacementOverlay 打开）
        if (stack.isEmpty() && player != null && player.isShiftKeyDown()) {
            return ItemInteractionResult.SUCCESS;
        }
        // Monitor 模块物品（toggle_switch / knob / button / screen）右键：monitor_2 表面放置由客户端
        // Monitor2GridOverlay 用动态命中检测 + payload 处理；这里仅消费右键，避免原版继续放置物品
        // （对齐 MonitorBlock.useItemOn 的处理）。
        if (ModuleType.fromItem(stack) != null || stack.is(MyModItems.MODULE_SCREEN.get())) {
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** 扳手普通右键（不蹲下）：一律消费右键（配置菜单由客户端 overlay 打开），不再旋转方块。 */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    /** 扳手蹲下右键：按点击位置拆除对应的单个模块（掉落物品）；点击不在任何安装位时不拆方块；光桌（无模块）走默认拆方块行为。 */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level instanceof ServerLevel)) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ControlDeskBlockEntity desk) {
            boolean anyInstalled = desk.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.DOCK)
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.BAFFLE);
            if (!anyInstalled) {
                // 光桌：没有模块可拆，走默认拆方块
                return IWrenchable.super.onSneakWrenched(state, context);
            }
            // 对准模块：按点击位置命中安装位，只拆对应的那个
            Direction facing = state.getValue(FACING);
            ControlDeskBlockEntity.ControlType hit = hitControlType(desk, facing, pos, context.getClickLocation());
            if (hit != null) {
                // 拆除拓展坞前必须先把「多余区域」（装 dock 后新增的北侧桌面）上的桌顶模块全部拆掉
                if (hit == ControlDeskBlockEntity.ControlType.DOCK && desk.hasModuleOnDockExtension()) {
                    if (context.getPlayer() != null) {
                        context.getPlayer().displayClientMessage(
                                Component.translatable("gui.ccpe.control_desk.dock_remove_blocked"), true);
                    }
                    return InteractionResult.SUCCESS;
                }
                // 拆除 monitor_2 前必须先把其表面的模块/屏幕全部拆掉
                if (hit == ControlDeskBlockEntity.ControlType.MONITOR_2 && desk.hasMonitor2Modules()) {
                    if (context.getPlayer() != null) {
                        context.getPlayer().displayClientMessage(
                                Component.translatable("gui.ccpe.control_desk.monitor2_remove_blocked"), true);
                    }
                    return InteractionResult.SUCCESS;
                }
                if (desk.remove(hit)) {
                    Block.popResource(level, pos, new ItemStack(controlItem(hit)));
                    IWrenchable.playRemoveSound(level, pos);
                    // 拆除拓展坞：blockstate DOCKED 复位（模型/选择框/桌顶网格回到 base 形态）
                    if (hit == ControlDeskBlockEntity.ControlType.DOCK && state.getValue(DOCKED)) {
                        level.setBlock(pos, state.setValue(DOCKED, false), 3);
                        desk.setChanged();
                    }
                    // 拆除挡板：blockstate BAFFLED 复位（模型/选择框回到 base 形态）
                    if (hit == ControlDeskBlockEntity.ControlType.BAFFLE && state.getValue(BAFFLED)) {
                        level.setBlock(pos, state.setValue(BAFFLED, false), 3);
                        desk.setChanged();
                    }
                }
            }
            // 无论是否命中安装位都消费交互，避免误拆方块
            return InteractionResult.SUCCESS;
        }
        return IWrenchable.super.onSneakWrenched(state, context);
    }

    /**
     * 点击位置命中的已安装控件类型（PEDAL 左右两框任一命中即算；JOYSTICK_2 / THROTTLE / MONITOR_2 命中各自放置盒）；
     * 未命中返回 null。
     */
    private static ControlDeskBlockEntity.ControlType hitControlType(ControlDeskBlockEntity desk, Direction facing,
                                                                    BlockPos pos, Vec3 click) {
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)
                && hitBounds(installBounds(ControlDeskBlockEntity.ControlType.PEDAL, facing, pos), click)) {
            return ControlDeskBlockEntity.ControlType.PEDAL;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)
                && hitBounds(installBounds(ControlDeskBlockEntity.ControlType.JOYSTICK, facing, pos), click)) {
            return ControlDeskBlockEntity.ControlType.JOYSTICK;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)
                && hitBounds(List.of(joystick2PlaceBox(desk, facing, pos)), click)) {
            return ControlDeskBlockEntity.ControlType.JOYSTICK_2;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)
                && hitBounds(List.of(throttlePlaceBox(desk, facing, pos)), click)) {
            return ControlDeskBlockEntity.ControlType.THROTTLE;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)
                && hitBounds(List.of(throttle2PlaceBox(desk, facing, pos)), click)) {
            return ControlDeskBlockEntity.ControlType.THROTTLE_2;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)
                && hitBounds(List.of(monitor2PlaceBox(desk, facing, pos)), click)) {
            return ControlDeskBlockEntity.ControlType.MONITOR_2;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.DOCK)
                && hitBounds(List.of(dockPlaceBox(facing, pos)), click)) {
            return ControlDeskBlockEntity.ControlType.DOCK;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.BAFFLE)
                && hitBounds(List.of(bafflePlaceBox(facing, pos)), click)) {
            return ControlDeskBlockEntity.ControlType.BAFFLE;
        }
        return null;
    }

    /**
     * joystick_2 放置盒的世界 AABB（北向基准 4×9×4，中心 = 放置中心 placeX/placeZ，底 y7 = 预览盒下沉 1px
     * 示意；随 FACING 旋转，与安装预览一致）。供扳手拆除命中判定（{@link #hitControlType}）与客户端扳手拆除预览共用。
     */
    public static AABB joystick2PlaceBox(ControlDeskBlockEntity desk, Direction facing, BlockPos pos) {
        int half = ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF;
        int cx = desk.getJoystick2PlaceX();
        int cz = desk.getJoystick2PlaceZ();
        Vec3 p0 = modelToWorld(pos, cx - half, ControlDeskBlockEntity.JOYSTICK_2_PLACE_Y_BOTTOM, cz - half, facing);
        Vec3 p1 = modelToWorld(pos, cx + half, ControlDeskBlockEntity.JOYSTICK_2_PLACE_Y_TOP, cz + half, facing);
        return new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
    }

    /**
     * throttle 放置盒的世界 AABB（北向基准 14×6×6，中心 = 放置中心 placeX/placeZ（唯一合法位 (8,12)），底 y7 =
     * 预览盒下沉 1px 示意；随 FACING 旋转，与安装预览一致）。供扳手拆除命中判定（{@link #hitControlType}）与客户端扳手拆除预览共用。
     */
    public static AABB throttlePlaceBox(ControlDeskBlockEntity desk, Direction facing, BlockPos pos) {
        int cx = desk.getThrottlePlaceX();
        int cz = desk.getThrottlePlaceZ();
        Vec3 p0 = modelToWorld(pos, cx - ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_PLACE_Y_BOTTOM, cz - ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z, facing);
        Vec3 p1 = modelToWorld(pos, cx + ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_PLACE_Y_TOP, cz + ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z, facing);
        return new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
    }

    /**
     * throttle_2 放置盒的世界 AABB（北向基准 14×6×6，中心 = 放置中心 placeX/placeZ（唯一合法位 (8,12)），底 y7 =
     * 预览盒下沉 1px 示意；随 FACING 旋转，与安装预览一致）。供扳手拆除命中判定（{@link #hitControlType}）与客户端扳手拆除预览共用。
     */
    public static AABB throttle2PlaceBox(ControlDeskBlockEntity desk, Direction facing, BlockPos pos) {
        int cx = desk.getThrottle2PlaceX();
        int cz = desk.getThrottle2PlaceZ();
        Vec3 p0 = modelToWorld(pos, cx - ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_2_PLACE_Y_BOTTOM, cz - ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z, facing);
        Vec3 p1 = modelToWorld(pos, cx + ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_2_PLACE_Y_TOP, cz + ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z, facing);
        return new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
    }

    /**
     * monitor_2 放置盒的世界 AABB（北向基准 14×6×12，中心 = 放置中心 placeX/placeZ（唯一合法位 (8,12)），底 y7 =
     * 预览盒下沉 1px 示意；随 FACING 旋转，与安装预览一致）。供扳手拆除命中判定（{@link #hitControlType}）与客户端扳手拆除预览共用。
     */
    public static AABB monitor2PlaceBox(ControlDeskBlockEntity desk, Direction facing, BlockPos pos) {
        int cx = desk.getMonitor2PlaceX();
        int cz = desk.getMonitor2PlaceZ();
        Vec3 p0 = modelToWorld(pos, cx - ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.MONITOR_2_PLACE_Y_BOTTOM, cz - ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z, facing);
        Vec3 p1 = modelToWorld(pos, cx + ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.MONITOR_2_PLACE_Y_TOP, cz + ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z, facing);
        return new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
    }

    /**
     * 拓展坞放置盒的世界 AABB（北向基准 0,0,0,16,8,8 = 桌体北侧整块空区，随 FACING 旋转；与安装预览一致）。
     * 供扳手拆除命中判定（{@link #hitControlType}）与客户端扳手拆除预览共用。
     */
    public static AABB dockPlaceBox(Direction facing, BlockPos pos) {
        Vec3 p0 = modelToWorld(pos, 0, 0, 0, facing);
        Vec3 p1 = modelToWorld(pos, 16, 8, 8, facing);
        return new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
    }

    /**
     * 挡板放置盒的世界 AABB（北向基准 0,0,0,16,16,8 = 桌体北侧整块全高区域，随 FACING 旋转；与安装预览一致）。
     * 供扳手拆除命中判定（{@link #hitControlType}）与客户端扳手拆除预览共用。
     */
    public static AABB bafflePlaceBox(Direction facing, BlockPos pos) {
        Vec3 p0 = modelToWorld(pos, 0, 0, 0, facing);
        Vec3 p1 = modelToWorld(pos, 16, 16, 8, facing);
        return new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
    }

    /** 北向基准模型坐标（px）→ 世界坐标：绕方块中心 Y 旋转到 FACING（与渲染 rotateCenteredDegrees 同约定）。 */
    private static Vec3 modelToWorld(BlockPos pos, float x, float y, float z, Direction facing) {
        // 必须用 double 运算：Sable 子次元的 plot 坐标可达 2×10^7，float 在此时 ULP=2，
        // int+float 会把 px/16 的偏移全部舍入掉 → 放置盒塌缩成一条线（sable 兼容问题）
        double bx = x / 16.0;
        double by = y / 16.0;
        double bz = z / 16.0;
        return switch (facing) {
            case NORTH -> new Vec3(pos.getX() + bx, pos.getY() + by, pos.getZ() + bz);
            case SOUTH -> new Vec3(pos.getX() + (1.0 - bx), pos.getY() + by, pos.getZ() + (1.0 - bz));
            case WEST  -> new Vec3(pos.getX() + bz, pos.getY() + by, pos.getZ() + (1.0 - bx));
            case EAST  -> new Vec3(pos.getX() + (1.0 - bz), pos.getY() + by, pos.getZ() + bx);
            default    -> new Vec3(pos.getX() + bx, pos.getY() + by, pos.getZ() + bz);
        };
    }

    /**
     * 点击位置是否命中安装位（闭区间 + 边界容差）。
     * 不能用 {@link AABB#contains}：它是半开区间（z &lt; maxZ），而准星从北侧命中桌体表面 z=8
     * 恰为安装位框（z0..8）的 maxZ，会导致永不命中。客户端预览变色与服务端拆除判定共用本方法。
     */
    public static boolean hitBounds(List<AABB> bounds, Vec3 click) {
        double eps = 0.001;
        for (AABB aabb : bounds) {
            if (click.x >= aabb.minX - eps && click.x <= aabb.maxX + eps
                    && click.y >= aabb.minY - eps && click.y <= aabb.maxY + eps
                    && click.z >= aabb.minZ - eps && click.z <= aabb.maxZ + eps) {
                return true;
            }
        }
        return false;
    }

    /** 方块被破坏时已安装控件随掉落（对齐 MonitorBlock.getDrops 的做法）。 */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof ControlDeskBlockEntity desk) {
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)) {
                drops.add(new ItemStack(MyModItems.CONTROL_PEDAL.get()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)) {
                drops.add(new ItemStack(MyModItems.CONTROL_JOYSTICK.get()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)) {
                drops.add(new ItemStack(MyModItems.CONTROL_MONITOR_2.get()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)) {
                drops.add(new ItemStack(MyModItems.CONTROL_THROTTLE.get()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)) {
                drops.add(new ItemStack(MyModItems.CONTROL_JOYSTICK_2.get()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)) {
                drops.add(new ItemStack(MyModItems.CONTROL_THROTTLE_2.get()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.DOCK)) {
                drops.add(new ItemStack(MyModBlocks.my_control_desk.get().asItem()));
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.BAFFLE)) {
                drops.add(new ItemStack(AllBlocks.BRASS_CASING.get().asItem()));
            }
        }
        return drops;
    }

    /** 控件类型对应的物品。 */
    public static Item controlItem(ControlDeskBlockEntity.ControlType type) {
        return switch (type) {
            case PEDAL -> MyModItems.CONTROL_PEDAL.get();
            case JOYSTICK -> MyModItems.CONTROL_JOYSTICK.get();
            case MONITOR_2 -> MyModItems.CONTROL_MONITOR_2.get();
            case THROTTLE -> MyModItems.CONTROL_THROTTLE.get();
            case JOYSTICK_2 -> MyModItems.CONTROL_JOYSTICK_2.get();
            case THROTTLE_2 -> MyModItems.CONTROL_THROTTLE_2.get();
            // 拓展坞 = 另一台控制台方块物品（手持控制台右键已放置的控制台 → 安装为 slab 形态）
            case DOCK -> MyModBlocks.my_control_desk.get().asItem();
            // 挡板 = create:brass_casing（手持右键已放置的控制台 → 安装为 3/4 楼梯形态）
            case BAFFLE -> AllBlocks.BRASS_CASING.get().asItem();
        };
    }

    private static ControlDeskBlockEntity.ControlType controlTypeOf(ItemStack stack) {
        if (stack.is(MyModItems.CONTROL_PEDAL.get())) return ControlDeskBlockEntity.ControlType.PEDAL;
        if (stack.is(MyModItems.CONTROL_JOYSTICK.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK;
        if (stack.is(MyModItems.CONTROL_MONITOR_2.get())) return ControlDeskBlockEntity.ControlType.MONITOR_2;
        if (stack.is(MyModItems.CONTROL_THROTTLE.get())) return ControlDeskBlockEntity.ControlType.THROTTLE;
        if (stack.is(MyModItems.CONTROL_JOYSTICK_2.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK_2;
        if (stack.is(MyModItems.CONTROL_THROTTLE_2.get())) return ControlDeskBlockEntity.ControlType.THROTTLE_2;
        // 拓展坞 = 手持控制台方块物品右键已放置的控制台 → 安装为 slab 形态（不放置新方块）
        if (stack.is(MyModBlocks.my_control_desk.get().asItem())) return ControlDeskBlockEntity.ControlType.DOCK;
        // 挡板 = 手持 create:brass_casing 右键已放置的控制台 → 安装为 3/4 楼梯形态（不放置 casing 方块）
        if (stack.is(AllBlocks.BRASS_CASING.get().asItem())) return ControlDeskBlockEntity.ControlType.BAFFLE;
        return null;
    }

    /**
     * joystick_2 预览盒/放置共用的吸附中心：命中点 → 北向基准模型坐标（px，0..16，与渲染旋转互逆）→
     * 吸附到 1px 网格整数 px，作为 4×4 盒子中心。返回 {cx, cz}。客户端预览（{@code ControlDeskPlacementOverlay}
     * / {@code ControlDeskGhostPreviewRenderer}）与服务端放置共用。
     */
    public static int[] snappedBoxCenter(BlockPos pos, Direction facing, Vec3 click) {
        float bx = (float) (click.x - pos.getX());
        float bz = (float) (click.z - pos.getZ());
        float mx, mz;
        switch (facing) {
            case SOUTH -> { mx = (1f - bx) * 16f; mz = (1f - bz) * 16f; }
            case WEST  -> { mx = (1f - bz) * 16f; mz = bx * 16f; }
            case EAST  -> { mx = bz * 16f; mz = (1f - bx) * 16f; }
            default    -> { mx = bx * 16f; mz = bz * 16f; }
        }
        return new int[]{Math.round(mx), Math.round(mz)};
    }

    /**
     * throttle / throttle_2 / monitor_2 专用的「吸附 + 钳制」放置中心：命中点 → 北向模型坐标 →
     * 吸附整数 px → <b>钳制到「占地矩形（半宽 halfX×halfZ）完全位于桌顶网格内」的合法中心范围</b>
     * （cx ∈ [1+halfX, 15-halfX]，cz ∈ [zMin+halfZ, 15-halfZ]，zMin = docked ? 1 : 9）。
     * 预览盒只能在可放置区域内移动（准星移出网格时盒子停在边界上，不显示越界红框）；
     * 与服务端放置共用，钳制后必满足 {@link #placementInGrid}。
     */
    public static int[] snappedBoxCenterClamped(BlockPos pos, Direction facing, Vec3 click,
                                                boolean docked, int halfX, int halfZ) {
        int[] c = snappedBoxCenter(pos, facing, click);
        int zMin = docked ? 1 : 9;
        return new int[]{
                Math.max(1 + halfX, Math.min(15 - halfX, c[0])),
                Math.max(zMin + halfZ, Math.min(15 - halfZ, c[1]))
        };
    }

    /**
     * 放置中心 (cx,cz) 的占地矩形（半宽 halfX×halfZ）是否<b>完全</b>位于桌顶网格内
     * （普通 6×14：x1..15 / z9..15；docked 14×14：x1..15 / z1..15）。
     * 客户端预览（{@code ControlDeskPlacementOverlay} 变红 / ghost 隐藏）与服务端放置（{@link #useItemOn} 拒绝）共用，
     * 防止「占位只差一格在网格外也能放」。docked 由 blockstate 读 {@link #DOCKED} 传入。
     */
    public static boolean placementInGrid(boolean docked, int cx, int cz, int halfX, int halfZ) {
        int zMin = docked ? 1 : 9;
        return cx - halfX >= 1 && cx + halfX <= 15
                && cz - halfZ >= zMin && cz + halfZ <= 15;
    }

    /**
     * joystick_2（4×4 占地，半宽 2）专用：占位是否完全位于桌顶网格内，委托 {@link #placementInGrid}。
     */
    public static boolean joystick2PlacementInGrid(boolean docked, int cx, int cz) {
        return placementInGrid(docked, cx, cz,
                ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF, ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF);
    }

    /**
     * 桌体中心 → 玩家的最近水平方向（90° 间隔，北/南/西/东）：joystick_2 安装旋转
     * （{@link ControlDeskBlockEntity#rotationToFace}）与客户端 ghost 预览共用同一实现，防预览与实装不一致。
     * 玩家为 null 或恰在桌体中心（getNearest 返回非水平方向）时返回 null。
     * <p>Sable 物理体兼容：pos 是子次元 plot 坐标（可达 2×10⁷），而玩家在世界空间 ——
     * 必须先经 {@link SableCompat#toLocalPosition} 把玩家位置投影回 plot 空间再算方向，
     * 否则坐标差被巨大的 plot 偏移淹没（恒返回 NORTH），「面向玩家」安装旋转失效。
     */
    @Nullable
    public static Direction directionFromDeskTo(@Nullable Player player, BlockPos pos) {
        if (player == null) return null;
        double px = player.getX();
        double pz = player.getZ();
        SubLevel sub = SableCompat.getContainingSubLevel(player.level(), pos);
        if (sub != null) {
            // 世界 → plot（partialTick 1.0 = 逻辑姿态：服务端即 logicalPose，客户端 renderPose(1.0) 亦为逻辑姿态）
            Vec3 local = SableCompat.toLocalPosition(sub, 1.0f, new Vec3(px, player.getY(), pz));
            px = local.x;
            pz = local.z;
        }
        Direction dir = Direction.getNearest(
                px - (pos.getX() + 0.5), 0, pz - (pos.getZ() + 0.5));
        return dir.getAxis().isHorizontal() ? dir : null;
    }

    /**
     * 控件安装位的世界 AABB 列表（随 FACING 旋转；PEDAL 为一对左右两个框）。
     * 供安装/拆除预览框与拆除判定（onSneakWrenched 按点击位置命中）使用；
     * 如需调整安装位置，改上面的北向基准 shape 即可。
     * （monitor_2 / throttle / joystick_2 的原后缘插槽已移除，改桌顶 6×14 棋盘网格自由放置 ——
     * throttle / joystick_2 走各自放置盒（{@link #throttlePlaceBox} / {@link #joystick2PlaceBox}），
     * monitor_2 无放置位框，故本方法对三者均返回空列表）
     */
    public static List<AABB> installBounds(ControlDeskBlockEntity.ControlType type, Direction facing, BlockPos pos) {
        List<AABB> result = new ArrayList<>();
        switch (type) {
            case PEDAL -> {
                result.add(PEDAL_LEFT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
                result.add(PEDAL_RIGHT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
            }
            case JOYSTICK -> result.add(JOYSTICK_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
            case MONITOR_2, THROTTLE, JOYSTICK_2, THROTTLE_2 -> { /* 无安装位框（插槽已移除） */ }
            case DOCK -> result.add(dockPlaceBox(facing, pos));
            case BAFFLE -> result.add(bafflePlaceBox(facing, pos));
        }
        return result;
    }
}
