package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
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
import net.minecraft.world.level.block.Mirror;
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
import org.jspecify.annotations.NonNull;

import java.util.EnumMap;
import java.util.Map;

public class MySensorBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<MySensorBlock> CODEC = MySensorBlock.simpleCodec(MySensorBlock::new);
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

    public MySensorBlock(BlockBehaviour.Properties properties) {
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
    public @NonNull BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

//    @Override
//    public @NonNull BlockState mirror(BlockState state, Mirror mirror) {
//        return state.rotate(mirror.getRotation(state.getValue(FACING)));
//    }

    @Override
    public @NonNull RenderShape getRenderShape(@NonNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean canSurvive(@NonNull BlockState state, @NonNull LevelReader level, @NonNull BlockPos pos) {
        Direction supportDirection = switch (state.getValue(FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.getValue(FACING).getOpposite();
        };
        BlockPos supportPos = pos.relative(supportDirection);
        return !level.getBlockState(supportPos).isAir();
    }

    @Override
    public void neighborChanged(@NonNull BlockState state, @NonNull Level level, @NonNull BlockPos pos, @NonNull Block block, @NonNull BlockPos fromPos, boolean isMoving) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    // ── 红石输出 ──

    @Override
    protected int getSignal(@NonNull BlockState state, @NonNull BlockGetter level, @NonNull BlockPos pos, @NonNull Direction side) {
        if (level.getBlockEntity(pos) instanceof MySensorBlockEntity be)
            return be.getRedstoneOutput();
        return 0;
    }

    @Override
    protected boolean isSignalSource(@NonNull BlockState state) {
        return state.getValue(POWERED);
    }

    /**
     * 更新传感器的红石输出信号并通知相邻方块。
     * 由 {@link MySensorBlockEntity#setRedstoneOutput} 调用。
     */
    public static void updateRedstoneOutput(Level level, BlockPos pos, int signal) {
        // 防止在区块卸载/世界保存期间产生 block update 死锁
        if (level.isClientSide || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.is(MyModBlocks.my_sensor.get())) return; // 方块已被替换则跳过
        boolean shouldPower = signal > 0;
        if (state.hasProperty(POWERED) && state.getValue(POWERED) != shouldPower) {
            level.setBlock(pos, state.setValue(POWERED, shouldPower), Block.UPDATE_ALL);
        }
        level.updateNeighborsAt(pos, state.getBlock());
    }

    @Override
    protected @NonNull VoxelShape getShape(@NonNull BlockState state, @NonNull BlockGetter worldIn, @NonNull BlockPos pos, @NonNull CollisionContext context) {
        AttachFace face = state.getValue(FACE);
        return SHAPES.get(face).get(state.getValue(FACING));
    }

    @Override
    protected @NonNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(@NonNull BlockPos pos, @NonNull BlockState state) {
        return new MySensorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NonNull Level level, @NonNull BlockState state, @NonNull BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof MySensorBlockEntity sensorBE) {
                MySensorBlockEntity.serverTick(lvl, pos, st, sensorBE);
            }
        };
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
            Component.translatable("gui.ccnavigationtable.sensor_nbt");

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
            if (be instanceof MySensorBlockEntity sensorBE) {
                sensorChannel = sensorBE.getScrolledValue();
                var regChannels = com.zzy205.myfirstmod.compat.cc.SensorRegistry.getOccupiedChannels();
                occupiedChannels = regChannels.stream().mapToInt(Integer::intValue).toArray();
            } else {
                sensorChannel = 0;
                occupiedChannels = new int[0];
            }

            // 打开 NBT 查看 GUI，通过 extraData 传递位置、初始 NBT 快照和频道信息
            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inv, p) ->
                                    new com.zzy205.myfirstmod.screen.MySensorMenu(containerId, pos, attachedNBT, inv),
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
     * 如果方块实体在 Sable 物理子次元中（航空学等 mod 的物理组装），
     * 将 NBT 中的 x/y/z 坐标直接替换为真实世界坐标。
     * <p>
     * 使用反射避免 Sable 未加载时引发 {@link NoClassDefFoundError}。
     */
    @SuppressWarnings("CallToPrintStackTrace")
    static void tryAddRealWorldPos(Level level, BlockEntity be, CompoundTag nbt) {
        try {
            Object helper = Class.forName("dev.ryanhcode.sable.Sable")
                    .getField("HELPER").get(null);

            // Sable.HELPER.getContaining(BlockEntity) -> SubLevel | null
            Object subLevel = helper.getClass()
                    .getMethod("getContaining", BlockEntity.class)
                    .invoke(helper, be);

            if (subLevel != null) {
                // Sable.HELPER.projectOutOfSubLevel(Level, Position) -> Vec3
                Vec3 realPos = (Vec3) helper.getClass()
                        .getMethod("projectOutOfSubLevel", Level.class, Position.class)
                        .invoke(helper, level, be.getBlockPos().getCenter());

                // 直接替换 NBT 中的 x/y/z 为真实世界坐标
                nbt.putDouble("x", realPos.x);
                nbt.putDouble("y", realPos.y);
                nbt.putDouble("z", realPos.z);
            }
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            // Sable 未加载，无需修正坐标
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
