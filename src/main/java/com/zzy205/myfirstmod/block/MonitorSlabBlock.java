package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 板式监视器（monitor_slab）：台阶状方块（slab 形状，表面可放置 Monitor 模块）。
 * <p>
 * blockstate 结构参考 {@code create:stock_link}（FACE + 水平 FACING，实现照抄项目内已验证的
 * {@code fmc.json} / FmcBlock 模式，而非 vanilla DirectionalBlock 的 6 向 FACING）：
 * <ul>
 *   <li>地板 / 天花板形态（{@code FACE}=FLOOR/CEILING）：{@code FACING} 随玩家水平朝向，四向可旋转（4×2 态）；</li>
 *   <li>墙面形态（{@code FACE}=WALL）：{@code FACING} = 点击面，每个方向固定一个 state（4 态）。</li>
 * </ul>
 * 共 12 态。放置时 {@code FACE} = 点击面、{@code FACING} = 玩家水平朝向反向（地板/天花板）或点击面（墙面）。
 * <p>
 * <b>可悬空放置</b>：无稳固检测（已删 {@code canSurvive} / {@code neighborChanged} / 支撑方向换算），
 * 支撑方块破坏/不存在时不会掉落（对齐用户要求，区别于 FmcBlock / PitotTubeBlock 等贴附式方块）。
 * <p>
 * 选择框 = 16×8×16 台阶盒（照抄 quick_fill_fuel_vault：地板底半 y0..8 / 天花板顶半 y8..16 / 墙面 8px 厚贴墙）；
 * 音效对齐 quick_fill_fuel_vault（SoundType.COPPER）；扳手 = {@link IWrenchable}：**普通右键永不旋转**
 * （配置菜单由客户端 overlay 打开，对齐 ControlDeskBlock），潜行右键按点击位置拆除（表面内容上拆单个模块/屏幕，
 * 非内容且光板整拆掉包）。
 * <p>
 * 当前为纯放置逻辑（无方块实体）；表面 Monitor 模块的放置/交互接入见后续步骤。
 * <p>
 * 表面模块：BE = {@link MonitorSlabBlockEntity}（{@link MonitorGridHost}），放置/交互/渲染走
 * {@code MonitorSlabGridOverlay} / {@code MonitorSlabHitDetector} / {@code MonitorSlabRenderer}。
 */
public class MonitorSlabBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<MonitorSlabBlock> CODEC = simpleCodec(MonitorSlabBlock::new);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 选择框按 FACE 分组、每组按 FACING 四向旋转：16×8×16 台阶盒（同 quick_fill_fuel_vault） */
    private static final Map<AttachFace, Map<Direction, VoxelShape>> SHAPES;

    static {
        Map<AttachFace, Map<Direction, VoxelShape>> shapes = new EnumMap<>(AttachFace.class);
        // 地板 = 底半 y0..8；天花板 = 顶半 y8..16；墙面 = 8px 厚贴墙（facing=north 基准 z8..16，绕 Y 四向）
        shapes.put(AttachFace.FLOOR, buildHorizontalShapes(Block.box(0, 0, 0, 16, 8, 16)));
        shapes.put(AttachFace.CEILING, buildHorizontalShapes(Block.box(0, 8, 0, 16, 16, 16)));
        shapes.put(AttachFace.WALL, buildHorizontalShapes(Block.box(0, 0, 8, 16, 16, 16)));
        SHAPES = shapes;
    }

    private static Map<Direction, VoxelShape> buildHorizontalShapes(VoxelShape northShape) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, northShape);
        shapes.put(Direction.EAST, rotateShapeY(northShape, 1));
        shapes.put(Direction.SOUTH, rotateShapeY(northShape, 2));
        shapes.put(Direction.WEST, rotateShapeY(northShape, 3));
        return shapes;
    }

    private static VoxelShape rotateShapeY(VoxelShape shape, int quarterTurns) {
        VoxelShape[] result = new VoxelShape[]{Shapes.empty()};
        int turns = ((quarterTurns % 4) + 4) % 4;

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            switch (turns) {
                case 0 -> result[0] = Shapes.or(result[0], Shapes.box(minX, minY, minZ, maxX, maxY, maxZ));
                case 1 -> result[0] = Shapes.or(result[0], Shapes.box(1.0D - maxZ, minY, minX, 1.0D - minZ, maxY, maxX));
                case 2 -> result[0] = Shapes.or(result[0], Shapes.box(1.0D - maxX, minY, 1.0D - maxZ, 1.0D - minX, maxY, 1.0D - minZ));
                case 3 -> result[0] = Shapes.or(result[0], Shapes.box(minZ, minY, 1.0D - maxX, maxZ, maxY, 1.0D - minX));
            }
        });

        return result[0].optimize();
    }

    public MonitorSlabBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
        );
    }

    /**
     * NeoForge 1.21.1 的 {@link DirectionalBlock} 只是壳：只声明 FACING 常量，
     * 不会把属性注册进 stateDefinition（曾因此放置时 {@code setValue(FACING)} 崩溃），
     * 子类必须自己 add。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        AttachFace face = clickedFace == Direction.DOWN ? AttachFace.CEILING
                : clickedFace == Direction.UP ? AttachFace.FLOOR
                : AttachFace.WALL;
        Direction facing = face == AttachFace.WALL ? clickedFace : context.getHorizontalDirection().getOpposite();

        return defaultBlockState()
                .setValue(FACE, face)
                .setValue(FACING, facing);
    }

    @Override
    public @NotNull BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACE)).get(state.getValue(FACING));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorSlabBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (!(blockEntity instanceof MonitorSlabBlockEntity slabBE)) return drops;

        for (var module : slabBE.getGridState().getAllModules().values()) {
            ItemStack stack = MyModItems.monitorModuleStack(module.type());
            if (!stack.isEmpty()) drops.add(stack);
        }
        for (int ignored = 0; ignored < slabBE.getGridState().getScreenRegions().size(); ignored++) {
            drops.add(new ItemStack(MyModItems.MODULE_SCREEN.get()));
        }
        return drops;
    }

    /**
     * 点击是否命中表面内容（模块或屏幕）。命中点换算到网格格后查 grid 占用（模块 ID ≥ 0 或屏幕格标记）。
     * 地板：面板 = 顶面（y8/16）；贴墙：面板 = 朝向 FACING 的竖直边界；天花板：面板 = 底面（y8/16，朝下）。
     * 后两者走 {@link MonitorSlabBlockEntity#panelFrame}（与命中检测/渲染共用单一来源）。
     * 扳手潜行右键拆除的「拆单个模块/屏幕」判定（{@link #onSneakWrenched}）与「整块拆除」判定共用，单一来源。
     */
    private static boolean isSurfaceContentHit(BlockState state, UseOnContext context) {
        if (state.getValue(FACE) != AttachFace.FLOOR) {
            return isPanelSurfaceContentHit(state, context);
        }
        double localX = context.getClickLocation().x - context.getClickedPos().getX();
        double localY = context.getClickLocation().y - context.getClickedPos().getY();
        double localZ = context.getClickLocation().z - context.getClickedPos().getZ();
        // 面板平面（y = 面板高度 8/16，容差）
        if (localY < MonitorSlabBlockEntity.PANEL_Y_PX / 16.0 - 0.01
                || localY > 16.0 / 16.0 + 0.01) {
            return false;
        }
        // 网格区域（四周内缩 1px）
        double ox = MonitorSlabBlockEntity.GRID_ORIGIN_X_PX / 16.0;
        double oz = MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX / 16.0;
        if (localX < ox || localX >= ox + MonitorSlabBlockEntity.GRID_WIDTH / 16.0) return false;
        if (localZ < oz || localZ >= oz + MonitorSlabBlockEntity.GRID_HEIGHT / 16.0) return false;
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MonitorSlabBlockEntity slab)) {
            return false;
        }
        int gx = (int) ((localX - ox) * 16.0);
        int gy = (int) ((localZ - oz) * 16.0);
        int cell = slab.getGridState().getCell(gx, gy);
        return cell >= 0 || cell == GridState.SCREEN_CELL_MARKER;
    }

    /** 贴墙 / 贴天花板形态的表面内容命中：面板局部坐标（grid x / grid y，面板平面内容差内）→ 网格格 → 查占用。 */
    private static boolean isPanelSurfaceContentHit(BlockState state, UseOnContext context) {
        AttachFace face = state.getValue(FACE);
        if (face != AttachFace.WALL && face != AttachFace.CEILING) return false;
        MonitorSlabBlockEntity.PanelFrame frame = MonitorSlabBlockEntity.panelFrame(state);
        if (frame == null) return false;

        Vec3 p = context.getClickLocation()
                .subtract(Vec3.atLowerCornerOf(context.getClickedPos()))
                .subtract(frame.panelOrigin());
        // 面板平面（法线方向容差；面板在局部帧原点 = 面板平面）
        if (Math.abs(p.dot(frame.nDir())) > 0.01) return false;
        double u = p.dot(frame.uDir());
        double v = p.dot(frame.vDir());
        double ox = MonitorSlabBlockEntity.GRID_ORIGIN_X_PX / 16.0;
        double oz = MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX / 16.0;
        if (u < ox || u >= ox + MonitorSlabBlockEntity.GRID_WIDTH / 16.0) return false;
        if (v < oz || v >= oz + MonitorSlabBlockEntity.GRID_HEIGHT / 16.0) return false;
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MonitorSlabBlockEntity slab)) {
            return false;
        }
        int gx = (int) ((u - ox) * 16.0);
        int gy = (int) ((v - oz) * 16.0);
        int cell = slab.getGridState().getCell(gx, gy);
        return cell >= 0 || cell == GridState.SCREEN_CELL_MARKER;
    }

    /**
     * 扳手普通右键（不蹲下）：一律消费右键，不再旋转方块（配置菜单由客户端
     * {@code MonitorSlabGridOverlay} 打开，对齐 {@code ControlDeskBlock.onWrenched}）。
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    /** 扳手潜行右键：拆除并掉落一个带完整 GridState 配置的 monitor_slab 物品（模块不单独掉落，对齐 MonitorBlock）。
     *  <ul>
     *   <li>点击在表面内容（模块/屏幕）上 → 放行，交给 MonitorSlabGridOverlay 拆单个模块/屏幕（对齐 Monitor 的底座语义）；</li>
     *   <li>点击不在表面内容上且已装模块/屏幕 → 禁止整块拆除，提示先拆模块（对齐 ControlDeskBlock 的 desk_remove_blocked）；</li>
     *   <li>光板（无内容）→ 整块拆除（不判定点击面，顶面/侧面/底面均可）。</li>
     * </ul> */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        // 点击在表面内容（模块/屏幕）上 → 不整块拆除，放行给 MonitorSlabGridOverlay 的模块/屏幕拆除 payload 处理
        if (isSurfaceContentHit(state, context)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        // 已装模块/屏幕：禁止整块拆除（服务端提示，对齐 ControlDeskBlock）
        if (level.getBlockEntity(pos) instanceof MonitorSlabBlockEntity slab && slab.hasContent()) {
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("gui.ccpe.monitor_slab.remove_blocked"), true);
            }
            return InteractionResult.SUCCESS;
        }

        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return InteractionResult.SUCCESS;
        }

        if (player != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MonitorSlabBlockEntity slabBE) {
                CompoundTag tag = new CompoundTag();
                slabBE.saveAdditional(tag, level.registryAccess());
                BlockEntity.addEntityType(tag, MyModBlockEntities.monitor_slab_entity.get());
                ItemStack stack = new ItemStack(this);
                stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag.copy()));
                if (player.isCreative()) {
                    if (!player.getInventory().add(stack)) {
                        Block.popResource(level, pos, stack);
                    }
                } else {
                    player.getInventory().placeItemBackInInventory(stack);
                }
            }
        }

        state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, true);
        level.destroyBlock(pos, false);
        IWrenchable.playRemoveSound(level, pos);
        return InteractionResult.SUCCESS;
    }

    /** 手持 Monitor 模块物品时消费右键（客户端），避免原版继续处理模块物品；放置由 MonitorSlabGridOverlay 走 payload。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // 空手 + 蹲下右键：消费右键（配置菜单由客户端 MonitorSlabGridOverlay 打开，对齐 ControlDeskBlock.useItemOn）
        if (stack.isEmpty() && player != null && player.isShiftKeyDown()) {
            return ItemInteractionResult.SUCCESS;
        }

        if (!level.isClientSide) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (ModuleType.fromItem(stack) != null) {
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
