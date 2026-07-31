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

    // 鈹€鈹€ 绾㈢煶杈撳嚭 鈹€鈹€

    @Override
    protected int getSignal(@NonNull BlockState state, @NonNull BlockGetter level, @NonNull BlockPos pos, @NonNull Direction side) {
        if (level.getBlockEntity(pos) instanceof PeripheralExtenderBlockEntity be)
            return be.getRedstoneOutput();
        return 0;
    }

    @Override
    protected boolean isSignalSource(@NonNull BlockState state) {
        return state.getValue(POWERED);
    }

    /**
     * 鏇存柊浼犳劅鍣ㄧ殑绾㈢煶杈撳嚭淇″彿骞堕€氱煡鐩搁偦鏂瑰潡锟?
     * 锟?{@link PeripheralExtenderBlockEntity#setRedstoneOutput} 璋冪敤锟?
     */
    public static void updateRedstoneOutput(Level level, BlockPos pos, int signal) {
        // 闃叉鍦ㄥ尯鍧楀嵏锟?涓栫晫淇濆瓨鏈熼棿浜х敓 block update 姝婚攣
        if (level.isClientSide || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.is(MyModBlocks.micro_peripheral_extender.get())) return; // 鏂瑰潡宸茶鏇挎崲鍒欒烦锟?
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
        return new PeripheralExtenderBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NonNull Level level, @NonNull BlockState state, @NonNull BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                PeripheralExtenderBlockEntity.serverTick(lvl, pos, st, sensorBE);
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

    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    //  鍙抽敭 GUI锛氭樉绀洪檮鐫€鏂瑰潡锟?NBT
    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    private static final Component SENSOR_GUI_TITLE =
            Component.translatable("gui.ccnavigationtable.sensor_nbt");

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // 璇诲彇闄勭潃鏂瑰潡锟?NBT
            CompoundTag attachedNBT = getAttachedBlockNBT(level, state, pos);

            // 鑾峰彇浼犳劅锟?BE 鐨勯閬撲俊鎭紙蹇呴』 final 鎵嶈兘锟?lambda 涓娇鐢級
            BlockEntity be = level.getBlockEntity(pos);
            final int sensorChannel;
            final int[] occupiedChannels;
            final int loadMode;
            final boolean onPhysicsBody;
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                sensorChannel = sensorBE.getScrolledValue();
                var regChannels = com.zzy205.myfirstmod.compat.cc.SensorRegistry.getOccupiedChannels();
                occupiedChannels = regChannels.stream().mapToInt(Integer::intValue).toArray();
                loadMode = sensorBE.getLoadMode();
                onPhysicsBody = sensorBE.isOnPhysicsBody();
            } else {
                sensorChannel = 0;
                occupiedChannels = new int[0];
                loadMode = 0;
                onPhysicsBody = false;
            }

            // 鎵撳紑 NBT 鏌ョ湅 GUI锛岄€氳繃 extraData 浼犻€掍綅缃€佸垵锟?NBT 蹇収鍜岄閬撲俊锟?
            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inv, p) ->
                                    new com.zzy205.myfirstmod.screen.PeripheralExtenderMenu(containerId, pos, attachedNBT, inv),
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
     * 璁＄畻浼犳劅鍣ㄦ墍闄勭潃鐨勬柟鍧楀潗锟?
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
     * 璇诲彇闄勭潃鏂瑰潡/鏈哄櫒鐨勫畬锟?NBT 鏁版嵁锟?
     * 濡傛灉鏂瑰潡锟?Sable 鐗╃悊瀛愭鍏冧腑锛堝鑸┖瀛︾粍瑁呭悗鐨勭墿浣擄級锟?
     * 浼氶澶栧啓鍏ョ湡瀹炰笘鐣屽潗锟?{@code RealWorldPos}锟?
     */
    public static CompoundTag getAttachedBlockNBT(Level level, BlockState state, BlockPos sensorPos) {
        BlockPos attachedPos = getAttachedPos(state, sensorPos);
        BlockEntity attachedBE = level.getBlockEntity(attachedPos);

        if (attachedBE != null) {
            CompoundTag nbt = attachedBE.saveWithFullMetadata(level.registryAccess());

            // 灏濊瘯閫氳繃 Sable API 鑾峰彇鐗╃悊缁勮鍚庣殑鐪熷疄涓栫晫鍧愭爣
            tryAddRealWorldPos(level, attachedBE, nbt);

            return nbt;
        }

        // 濡傛灉娌℃湁 BlockEntity锛岃繑鍥為檮鐫€鏂瑰潡鐨勭姸鎬佷俊锟?
        CompoundTag fallback = new CompoundTag();
        BlockState attachedState = level.getBlockState(attachedPos);
        fallback.putString("block", attachedState.getBlock().getDescriptionId());
        fallback.putString("note", "This block has no NBT data (no BlockEntity)");
        return fallback;
    }

    /**
     * 濡傛灉鏂瑰潡瀹炰綋锟?Sable 鐗╃悊瀛愭鍏冧腑锛堣埅绌哄锟?mod 鐨勭墿鐞嗙粍瑁咃級锟?
     * 锟?NBT 涓殑 x/y/z 鍧愭爣鐩存帴鏇挎崲涓虹湡瀹炰笘鐣屽潗鏍囷拷?
     * <p>
     * 浣跨敤鍙嶅皠閬垮厤 Sable 鏈姞杞芥椂寮曞彂 {@link NoClassDefFoundError}锟?
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

                // 鐩存帴鏇挎崲 NBT 涓殑 x/y/z 涓虹湡瀹炰笘鐣屽潗锟?
                nbt.putDouble("x", realPos.x);
                nbt.putDouble("y", realPos.y);
                nbt.putDouble("z", realPos.z);
            }
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            // Sable 鏈姞杞斤紝鏃犻渶淇鍧愭爣
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
