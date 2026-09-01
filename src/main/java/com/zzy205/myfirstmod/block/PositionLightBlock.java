package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * 航行灯（红色 position light）。
 * <p>
 * 参考实现：CreateDeco 的 {@code CageLampBlock}
 * （references/CreateDeco-1.21-neo/src/main/java/com/github/talrey/createdeco/blocks/CageLampBlock.java）。
 * 行为对齐：6 向贴附、红石点亮（{@code INVERTED} 反相开关）、右键切换反相、切换时播放粒子与音效。
 * 差异：仅用原版 API（{@link SimpleWaterloggedBlock} 替代 Create 的 ProperWaterloggedBlock，不实现 IWrenchable）；
 * 模型为自建 position_light（底座 + 灯体），整体高 5px；亮灭状态只换灯体贴图，几何不变。
 */
public class PositionLightBlock extends DirectionalBlock implements SimpleWaterloggedBlock {

	protected static final VoxelShape AABB_UP = Block.box(5, 0, 5, 11, 5, 11);
	protected static final VoxelShape AABB_DOWN = Block.box(5, 11, 5, 11, 16, 11);
	protected static final VoxelShape AABB_EAST = Block.box(0, 5, 5, 5, 11, 11);
	protected static final VoxelShape AABB_WEST = Block.box(11, 5, 5, 16, 11, 11);
	protected static final VoxelShape AABB_SOUTH = Block.box(5, 5, 0, 11, 11, 5);
	protected static final VoxelShape AABB_NORTH = Block.box(5, 5, 11, 11, 11, 16);

	/** 切换开关时的红色粒子（用法对齐 CreateDeco 的 DustParticleOptions）。 */
	public final DustParticleOptions particle = new DustParticleOptions(new Vector3f(1.0F, 0.0F, 0.0F), 0.3F);

	public PositionLightBlock(BlockBehaviour.Properties props) {
		super(props);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.UP)
			.setValue(BlockStateProperties.LIT, false)
			.setValue(BlockStateProperties.INVERTED, false)
			.setValue(BlockStateProperties.WATERLOGGED, false));
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState()
			.setValue(FACING, ctx.getClickedFace())
			.setValue(BlockStateProperties.LIT,
				ctx.getLevel().hasSignal(ctx.getClickedPos(), ctx.getClickedFace()))
			.setValue(BlockStateProperties.WATERLOGGED,
				ctx.getLevel().getFluidState(ctx.getClickedPos()).getType() == Fluids.WATER);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx) {
		return switch (state.getValue(FACING)) {
			case UP -> AABB_UP;
			case DOWN -> AABB_DOWN;
			case EAST -> AABB_EAST;
			case WEST -> AABB_WEST;
			case SOUTH -> AABB_SOUTH;
			case NORTH -> AABB_NORTH;
		};
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	public BlockState updateShape(BlockState state, Direction from, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		if (state.getValue(BlockStateProperties.WATERLOGGED)) {
			level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
		}
		return !this.canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
			: super.updateShape(state, from, neighbor, level, pos, neighborPos);
	}

	@Override
	public FluidState getFluidState(BlockState state) {
		return state.getValue(BlockStateProperties.WATERLOGGED) ? Fluids.WATER.getSource(false)
			: Fluids.EMPTY.defaultFluidState();
	}

	/** 亮灭 = 附着面红石信号 XOR 反相开关（对齐 CreateDeco {@code shouldBeLit}）。 */
	public static boolean shouldBeLit(BlockState state, Level level, BlockPos pos) {
		Direction attach = state.getValue(FACING).getOpposite();
		return state.getValue(BlockStateProperties.INVERTED) ^ level.hasSignal(pos.relative(attach), attach);
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moved) {
		// 任意邻居变化都重算亮灭（比 CreateDeco 只在附着块变化时重算更稳健：附着块被红石线远程供电也能更新）
		boolean lit = shouldBeLit(state, level, pos);
		if (state.getValue(BlockStateProperties.LIT) != lit) {
			level.setBlock(pos, state.setValue(BlockStateProperties.LIT, lit), 3);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		BlockState next = state.cycle(BlockStateProperties.INVERTED);
		if (level.isClientSide) {
			makeParticle(state, level, pos);
			return InteractionResult.SUCCESS;
		}
		next = next.setValue(BlockStateProperties.LIT, shouldBeLit(next, level, pos));
		level.setBlock(pos, next, 3);
		float pitch = next.getValue(BlockStateProperties.INVERTED) ? 0.6F : 0.5F;
		level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.3F, pitch);
		return InteractionResult.CONSUME;
	}

	private void makeParticle(BlockState state, LevelAccessor level, BlockPos pos) {
		Direction direction = state.getValue(FACING);
		float x = pos.getX() + 0.5F + 0.1F * direction.getStepX();
		float y = pos.getY() + 0.5F + 0.1F * direction.getStepY();
		float z = pos.getZ() + 0.5F + 0.1F * direction.getStepZ();
		level.addParticle(particle, x, y, z, 0.0D, 0.0D, 0.0D);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		// 不调用 super：DirectionalBlock 的 createBlockStateDefinition 也会加 FACING，重复添加会抛异常。
		// 与参考 CageLampBlock 一致，自行把全部属性一次加齐。
		builder.add(FACING, BlockStateProperties.LIT, BlockStateProperties.INVERTED, BlockStateProperties.WATERLOGGED);
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		// 宽松贴附判定：支撑方块非空气即可（对齐 PitotTube / PeripheralExtender 等贴附式方块）。
		// 不能用 Block.canSupportCenter：它要求支撑面是完整 16×16 全脸，
		// 台阶/楼梯/Create 风帆等非全脸方块会放不上去。
		BlockPos supportPos = pos.relative(state.getValue(FACING).getOpposite());
		return !level.getBlockState(supportPos).isAir();
	}

	@Override
	protected MapCodec<? extends DirectionalBlock> codec() {
		return null; // 对齐参考 CageLampBlock；本方块不走 datapack 方块注册
	}
}
