package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.item.MyModItems;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
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

    /** 北向基准形状（对应模型元素 from/to）：仅桌体一块；选择框/碰撞箱均使用，安装控件不改变形状 */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Block.box(0, 0, 8, 16, 8, 16),
            Direction.NORTH
    );

    // ── 控件安装位（北向基准 0..16 模型空间，随 FACING 旋转；供安装/拆除预览框与拆除判定使用） ──
    // 北侧空区 z0..8 分成左/中/右：左踏板 x11..16、操纵杆 x5..11、右踏板 x0..5（操作者面朝南，左=东=+X）
    private static final VoxelShape PEDAL_LEFT_SHAPE = Block.box(12, 1, 1, 16, 7, 8);
    private static final VoxelShape PEDAL_RIGHT_SHAPE = Block.box(0, 1, 1, 4, 7, 8);
    private static final VoxelShape JOYSTICK_SHAPE = Block.box(5, 0, 0, 11, 8, 8);
    private static final VoxelShaper PEDAL_LEFT_SHAPER = VoxelShaper.forHorizontal(PEDAL_LEFT_SHAPE, Direction.NORTH);
    private static final VoxelShaper PEDAL_RIGHT_SHAPER = VoxelShaper.forHorizontal(PEDAL_RIGHT_SHAPE, Direction.NORTH);
    private static final VoxelShaper JOYSTICK_SHAPER = VoxelShaper.forHorizontal(JOYSTICK_SHAPE, Direction.NORTH);
    // monitor_2 / throttle / joystick_2 共用插槽：桌体后缘上方整宽一条（y 8..14、z 8..16，北向基准），三模块互斥安装
    private static final VoxelShape BACK_SLOT_SHAPE = Block.box(0, 8, 8, 16, 14, 16);
    private static final VoxelShaper BACK_SLOT_SHAPER = VoxelShaper.forHorizontal(BACK_SLOT_SHAPE, Direction.NORTH);

    public ControlDeskBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE.get(state.getValue(FACING));
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
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ControlDeskBlockEntity desk)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!desk.install(type)) {
                // 已安装：不消耗物品，提示玩家
                if (player != null) {
                    player.displayClientMessage(
                            Component.translatable("gui.ccpe.control_desk.already_installed"), true);
                }
                return ItemInteractionResult.SUCCESS;
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
                    || desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2);
            if (!anyInstalled) {
                // 光桌：没有模块可拆，走默认拆方块
                return IWrenchable.super.onSneakWrenched(state, context);
            }
            // 对准模块：按点击位置命中安装位，只拆对应的那个
            Direction facing = state.getValue(FACING);
            ControlDeskBlockEntity.ControlType hit = hitControlType(desk, facing, pos, context.getClickLocation());
            if (hit != null && desk.remove(hit)) {
                Block.popResource(level, pos, new ItemStack(controlItem(hit)));
                IWrenchable.playRemoveSound(level, pos);
            }
            // 无论是否命中安装位都消费交互，避免误拆方块
            return InteractionResult.SUCCESS;
        }
        return IWrenchable.super.onSneakWrenched(state, context);
    }

    /** 点击位置命中的已安装控件类型（PEDAL 左右两框任一命中即算）；未命中返回 null。 */
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
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)
                && hitBounds(installBounds(ControlDeskBlockEntity.ControlType.MONITOR_2, facing, pos), click)) {
            return ControlDeskBlockEntity.ControlType.MONITOR_2;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)
                && hitBounds(installBounds(ControlDeskBlockEntity.ControlType.THROTTLE, facing, pos), click)) {
            return ControlDeskBlockEntity.ControlType.THROTTLE;
        }
        if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)
                && hitBounds(installBounds(ControlDeskBlockEntity.ControlType.JOYSTICK_2, facing, pos), click)) {
            return ControlDeskBlockEntity.ControlType.JOYSTICK_2;
        }
        return null;
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
        };
    }

    private static ControlDeskBlockEntity.ControlType controlTypeOf(ItemStack stack) {
        if (stack.is(MyModItems.CONTROL_PEDAL.get())) return ControlDeskBlockEntity.ControlType.PEDAL;
        if (stack.is(MyModItems.CONTROL_JOYSTICK.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK;
        if (stack.is(MyModItems.CONTROL_MONITOR_2.get())) return ControlDeskBlockEntity.ControlType.MONITOR_2;
        if (stack.is(MyModItems.CONTROL_THROTTLE.get())) return ControlDeskBlockEntity.ControlType.THROTTLE;
        if (stack.is(MyModItems.CONTROL_JOYSTICK_2.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK_2;
        return null;
    }

    /**
     * 控件安装位的世界 AABB 列表（随 FACING 旋转；PEDAL 为一对左右两个框）。
     * 供安装/拆除预览框与拆除判定（onSneakWrenched 按点击位置命中）使用；
     * 如需调整安装位置，改上面的北向基准 shape 即可。
     * MONITOR_2 / THROTTLE / JOYSTICK_2 共用 {@link #BACK_SLOT_SHAPE}（桌体后缘上方，互斥安装）。
     */
    public static List<AABB> installBounds(ControlDeskBlockEntity.ControlType type, Direction facing, BlockPos pos) {
        List<AABB> result = new ArrayList<>();
        switch (type) {
            case PEDAL -> {
                result.add(PEDAL_LEFT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
                result.add(PEDAL_RIGHT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
            }
            case JOYSTICK -> result.add(JOYSTICK_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
            case MONITOR_2 -> result.add(BACK_SLOT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
            case THROTTLE -> result.add(BACK_SLOT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
            case JOYSTICK_2 -> result.add(BACK_SLOT_SHAPER.get(facing).move(pos.getX(), pos.getY(), pos.getZ()).bounds());
        }
        return result;
    }
}
