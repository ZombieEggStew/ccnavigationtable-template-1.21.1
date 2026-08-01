package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.zzy205.myfirstmod.compat.cc.PeripheralExtenderRegistry;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import com.zzy205.myfirstmod.screen.PeripheralExtenderMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

public class PeripheralExtenderBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<PeripheralExtenderBlock> CODEC = PeripheralExtenderBlock.simpleCodec(PeripheralExtenderBlock::new);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    private static final Map<AttachFace, Map<Direction, VoxelShape>> SHAPES;

    static {
        Map<AttachFace, Map<Direction, VoxelShape>> shapes = new EnumMap<>(AttachFace.class);
        shapes.put(AttachFace.FLOOR, buildHorizontalShapes(Block.box(5, 0, 4, 11, 2, 12)));
        shapes.put(AttachFace.CEILING, buildHorizontalShapes(Block.box(5, 14, 4, 11, 16, 12)));
        shapes.put(AttachFace.WALL, buildHorizontalShapes(Block.box(5, 4, 14, 11, 12, 16)));
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

    public PeripheralExtenderBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING, POWERED);
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
    public boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        Direction supportDirection = switch (state.getValue(FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.getValue(FACING).getOpposite();
        };
        BlockPos supportPos = pos.relative(supportDirection);
        return !level.getBlockState(supportPos).isAir();
    }

    @Override
    public void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Block block, @NotNull BlockPos fromPos, boolean isMoving) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    // ── 红石输出 ──

    @Override
    protected int getSignal(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull Direction side) {
        if (level.getBlockEntity(pos) instanceof PeripheralExtenderBlockEntity be)
            return be.getRedstoneOutput();
        return 0;
    }

    @Override
    protected boolean isSignalSource(@NotNull BlockState state) {
        return state.getValue(POWERED);
    }

    /**
     * 更新传感器的红石输出信号并通知相邻方块。
     * 由 {@link PeripheralExtenderBlockEntity#setRedstoneOutput} 调用。
     */
    public static void updateRedstoneOutput(Level level, BlockPos pos, int signal) {
        // 防止在区块卸载/世界保存期间产生 block update 死锁
        if (level.isClientSide || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.is(MyModBlocks.micro_peripheral_extender.get())) return; // 方块已被替换则跳过
        boolean shouldPower = signal > 0;
        if (state.hasProperty(POWERED) && state.getValue(POWERED) != shouldPower) {
            level.setBlock(pos, state.setValue(POWERED, shouldPower), Block.UPDATE_ALL);
        }
        level.updateNeighborsAt(pos, state.getBlock());
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter worldIn, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        AttachFace face = state.getValue(FACE);
        return SHAPES.get(face).get(state.getValue(FACING));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new PeripheralExtenderBlockEntity(pos, state);
    }

    /**
     * serverTick 的方法引用，每个方块类型只分配一次，避免每个方块实例创建新 lambda。
     */
    private static final BlockEntityTicker<PeripheralExtenderBlockEntity> SERVER_TICKER =
            PeripheralExtenderBlockEntity::serverTick;

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        // 通过类型检查后安全转型，避免每 tick 执行 instanceof
        if (type == MyModBlockEntities.micro_peripheral_extender_entity.get()) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<T> ticker = (BlockEntityTicker<T>) (BlockEntityTicker<?>) SERVER_TICKER;
            return ticker;
        }
        return null;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!level.isClientSide) {
            ItemStack stack = new ItemStack(this);
            level.destroyBlock(pos, false, player);
            if (player == null || !player.getInventory().add(stack)) {
                Block.popResource(level, pos, stack);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ────────────────────────────────────────
    //  右键 GUI：显示附着方块的 NBT
    // ────────────────────────────────────────

    private static final Component SENSOR_GUI_TITLE =
            Component.translatable("block.ccpe.micro_peripheral_extender");

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // 读取附着方块的 NBT
            CompoundTag attachedNBT = getAttachedBlockNBT(level, state, pos);

            // 获取传感器 BE 的频道信息（必须 final 才能在 lambda 中使用）
            BlockEntity be = level.getBlockEntity(pos);
            final int sensorChannel;
            final int[] occupiedChannels;
            final int loadMode;
            final boolean onPhysicsBody;
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                sensorChannel = sensorBE.getScrolledValue();
                var regChannels = PeripheralExtenderRegistry.getOccupiedChannels();
                occupiedChannels = regChannels.stream().mapToInt(Integer::intValue).toArray();
                loadMode = sensorBE.getLoadMode();
                onPhysicsBody = sensorBE.isOnPhysicsBody();
            } else {
                sensorChannel = 0;
                occupiedChannels = new int[0];
                loadMode = 0;
                onPhysicsBody = false;
            }

            // 打开 NBT 查看 GUI，通过 extraData 传递位置、初始 NBT 快照和频道信息
            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inv, p) ->
                                    new PeripheralExtenderMenu(containerId, pos, attachedNBT, inv),
                            SENSOR_GUI_TITLE
                    ),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeNbt(attachedNBT);
                        buf.writeVarInt(sensorChannel);
                        buf.writeVarInt(occupiedChannels.length);
                        for (int ch : occupiedChannels) {
                            buf.writeVarInt(ch);
                        }
                        buf.writeVarInt(loadMode);
                        buf.writeBoolean(onPhysicsBody);
                    }
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 计算传感器所附着的方块坐标
     */
    public static BlockPos getAttachedPos(BlockState state, BlockPos sensorPos) {
        Direction supportDir = switch (state.getValue(FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.getValue(FACING).getOpposite();
        };
        return sensorPos.relative(supportDir);
    }

    /**
     * 读取附着方块/机器的完整 NBT 数据。
     * 如果方块在 Sable 物理子次元中（如航空学组装后的物体），
     * 会额外写入真实世界坐标 {@code RealWorldPos}。
     */
    public static CompoundTag getAttachedBlockNBT(Level level, BlockState state, BlockPos sensorPos) {
        BlockPos attachedPos = getAttachedPos(state, sensorPos);
        BlockEntity attachedBE = level.getBlockEntity(attachedPos);

        if (attachedBE != null) {
            CompoundTag nbt = attachedBE.saveWithFullMetadata(level.registryAccess());

            // 尝试通过 Sable API 获取物理组装后的真实世界坐标
            tryAddRealWorldPos(level, attachedBE, nbt);

            return nbt;
        }

        // 如果没有 BlockEntity，返回附着方块的状态信息
        CompoundTag fallback = new CompoundTag();
        BlockState attachedState = level.getBlockState(attachedPos);
        fallback.putString("block", attachedState.getBlock().getDescriptionId());
        fallback.putString("note", "This block has no NBT data (no BlockEntity)");
        return fallback;
    }

    /**
     * 如果方块实体在 Sable 物理子次元中（航空学 mod 的物理组装），
     * 将 NBT 中的 x/y/z 坐标直接替换为真实世界坐标。
     */
    @SuppressWarnings("CallToPrintStackTrace")
    static void tryAddRealWorldPos(Level level, BlockEntity be, CompoundTag nbt) {
        try {
            var subLevel = SableCompat.getContainingSubLevel(be);

            if (subLevel != null) {
                Vec3 realPos = SableCompat.projectOutOfSubLevel(
                        level, be.getBlockPos());

                if (realPos != null) {
                    nbt.putDouble("x", realPos.x);
                    nbt.putDouble("y", realPos.y);
                    nbt.putDouble("z", realPos.z);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
