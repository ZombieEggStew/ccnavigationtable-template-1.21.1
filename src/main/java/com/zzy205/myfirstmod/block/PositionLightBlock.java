package com.zzy205.myfirstmod.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
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
 * 航行灯（红/绿/白 position light）。
 * <p>
 * 参考实现：CreateDeco 的 {@code CageLampBlock}
 * （references/CreateDeco-1.21-neo/src/main/java/com/github/talrey/createdeco/blocks/CageLampBlock.java）。
 * 行为：6 向贴附、水浸；亮灭（{@code LIT}）只由<b>玩家右键</b>与 Lua
 * （{@code ccpe.sensor_system.setLights}，FMC 门控）控制，<b>不响应红石</b>——
 * 原「红石信号 XOR INVERTED 反相」逻辑与 {@code INVERTED} 属性已移除
 * （LIT 成为直接可写状态，右键/ Lua 切换）；切换时播放粒子与音效。
 * 差异：仅用原版 API（{@link SimpleWaterloggedBlock} 替代 Create 的 ProperWaterloggedBlock）；
 * 实现 {@link IWrenchable} 默认行为：普通右键扳手旋转朝向（FACING，6 向贴附，
 * 旋转后 {@code canSurvive} 校验不通过则不转），蹲下 + 右键扳手快速拆除（掉落进背包）；
 * 亮灭仍只由空手右键 / Lua 切换（扳手右键不再落入切换逻辑，见 WrenchItem 分发）。
 * 模型为自建 position_light（底座 + 灯体），整体高 5px；亮灭状态只换灯体贴图，几何不变。
 */
public class PositionLightBlock extends DirectionalBlock implements SimpleWaterloggedBlock, IWrenchable {

	protected static final VoxelShape AABB_UP = Block.box(5, 0, 5, 11, 5, 11);
	protected static final VoxelShape AABB_DOWN = Block.box(5, 11, 5, 11, 16, 11);
	protected static final VoxelShape AABB_EAST = Block.box(0, 5, 5, 5, 11, 11);
	protected static final VoxelShape AABB_WEST = Block.box(11, 5, 5, 16, 11, 11);
	protected static final VoxelShape AABB_SOUTH = Block.box(5, 5, 0, 11, 11, 5);
	protected static final VoxelShape AABB_NORTH = Block.box(5, 5, 11, 11, 11, 16);

	/** 切换开关时的粒子（颜色由构造器传入，用法对齐 CreateDeco 的 DustParticleOptions）。 */
	public final DustParticleOptions particle;

	public PositionLightBlock(BlockBehaviour.Properties props, Vector3f color) {
		super(props);
		this.particle = new DustParticleOptions(color, 0.3F);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.UP)
			.setValue(BlockStateProperties.LIT, false)
			.setValue(BlockStateProperties.WATERLOGGED, false));
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState()
			.setValue(FACING, ctx.getClickedFace())
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

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level.isClientSide) {
			makeParticle(state, level, pos);
			return InteractionResult.SUCCESS;
		}
		// 右键直接切换亮灭（不响应红石；亮灭仅由右键 / Lua setLights 控制）
		BlockState next = state.cycle(BlockStateProperties.LIT);
		level.setBlock(pos, next, 3);
		float pitch = next.getValue(BlockStateProperties.LIT) ? 0.7F : 0.5F;
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
		builder.add(FACING, BlockStateProperties.LIT, BlockStateProperties.WATERLOGGED);
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
