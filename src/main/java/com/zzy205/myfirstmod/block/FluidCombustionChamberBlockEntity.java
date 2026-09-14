package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 流体燃烧室方块实体：轻量 BE（供 Flywheel Visual / BER 读取活塞状态）。
 * <p>
 * 活塞动画（客户端计算，无服务端状态）：父引擎（FACING 反方向贴的核心）运行时，
 * 活塞伸出量在 ±{@link #PISTON_STROKE}（±2/16 块，默认模型活塞在中间位）间正弦往复；
 * <b>角度随引擎输出转速推进</b>（照 Create SteamEngine：每 tick 推进 speed×3/10 度，1 圈 = 1 往复，
 * 动画速度与转速挂钩）；引擎停止时活塞回中间位（offset = 0）。
 * <p>
 * 相位规则（方案 B：对置 + 轴向交替）：
 * <ul>
 *   <li><b>对置</b>：贴附方向为负方向的面（-X/-Y/-Z）比正方向面多 π → 引擎核心对置面的两个活塞差半周期；</li>
 *   <li><b>轴向交替</b>：父核心沿引擎轴（AXIS）的坐标 mod 2 为奇数时再多 π → 相邻核心的燃烧室差半周期（一排活塞成波浪）。</li>
 * </ul>
 * 参考来源：{@code FluidPortBlockEntity}（普通 BlockEntity，无 Create SmartBlockEntity 依赖）；
 * Create {@code SteamEngineBlockEntity#getTargetAngle}（活塞角度随转速推进）。
 */
public class FluidCombustionChamberBlockEntity extends BlockEntity {

    /** 活塞行程半幅（块）：默认模型活塞在中间位，往复 ±2/16 */
    public static final float PISTON_STROKE = 2f / 16f;

    /** 客户端：活塞相位上一 tick 值（相位回绕检测用，触发每周期一次的"噗嗤"音效） */
    @OnlyIn(Dist.CLIENT)
    protected float prevPistonPhase = -1f;

    public FluidCombustionChamberBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.fluid_combustion_chamber_entity.get(), pos, state);
    }

    /**
     * 活塞伸出量（块单位，沿活塞轴 = FACING 方向）：引擎运行时在 ±{@link #PISTON_STROKE} 间正弦往复，
     * 停止时为 0（模型中间位）。角度随引擎输出转速推进（每 tick speed×3/10 度，1 圈 = 1 往复），
     * 动画速度与转速挂钩；客户端按游戏时间 + partialTick 计算，无服务端状态。
     */
    public float getPistonOffset(float partialTick) {
        Direction facing = getBlockState().getValue(FluidCombustionChamberBlock.FACING);
        BlockPos parent = worldPosition.relative(facing.getOpposite());
        EngineCoreBlockEntity controller = engineController(parent);
        if (controller == null || !controller.isRunning())
            return 0;
        float angle = (float) ((level.getGameTime() + partialTick) * controller.getSpeed() * 3f / 10)
                % 360 / 180f * (float) Math.PI;
        float phase = angle + directionPhase(facing) + axisParityPhase(parent);
        return (float) (Math.sin(phase) * PISTON_STROKE);
    }

    /** 父引擎（FACING 反方向贴的核心）整条引擎 controller；未贴核心返回 null */
    protected EngineCoreBlockEntity engineController(BlockPos parent) {
        if (level.getBlockEntity(parent) instanceof EngineCoreBlockEntity core)
            return core.getControllerBE();
        return null;
    }

    /**
     * 客户端 tick：活塞相位回绕（每周期一次、固定位置）→ 把本室坐标入队到引擎的"噗嗤"音效池
     * （由引擎 controller 每 tick 统一播放，照 Create SoundPool 去重）。相位与活塞动画同源
     * （角度随转速推进 + 对置/轴向交替偏移），不同相位的燃烧室在不同角度触发 → 波浪式"噗嗤噗嗤"。
     * 音量配置 = {@link Config#ENGINE_FLUID_PUFF_VOLUME}；设为 0 时直接跳过（省性能）。
     */
    @OnlyIn(Dist.CLIENT)
    public void tickClient() {
        if (Config.ENGINE_FLUID_PUFF_VOLUME.get() <= 0)
            return;
        Direction facing = getBlockState().getValue(FluidCombustionChamberBlock.FACING);
        BlockPos parent = worldPosition.relative(facing.getOpposite());
        EngineCoreBlockEntity controller = engineController(parent);
        if (controller == null || !controller.isRunning())
            return;
        float angle = (level.getGameTime() * controller.getSpeed() * 3f / 10) % 360 / 180f * (float) Math.PI;
        float phase = (angle + directionPhase(facing) + axisParityPhase(parent)) % (float) (Math.PI * 2);
        if (prevPistonPhase > phase)
            controller.getFluidPistonSoundPool().queueAt(worldPosition);
        prevPistonPhase = phase;
    }

    /** 对置相位偏移：负方向贴附面返回 π，正方向返回 0（对置面 = 方向取反 → 相差 π） */
    private static float directionPhase(Direction facing) {
        return facing.getStepX() + facing.getStepY() + facing.getStepZ() < 0 ? (float) Math.PI : 0;
    }

    /** 轴向交替相位偏移：父核心沿引擎轴（AXIS）的坐标 mod 2 为奇数返回 π → 相邻核心的燃烧室差半周期 */
    private float axisParityPhase(BlockPos parent) {
        BlockState state = level.getBlockState(parent);
        if (!state.is(MyModBlocks.engine_core.get()))
            return 0;
        Direction.Axis axis = state.getValue(EngineCoreBlock.AXIS);
        int coord = switch (axis) {
            case X -> parent.getX();
            case Y -> parent.getY();
            case Z -> parent.getZ();
        };
        return (coord & 1) == 1 ? (float) Math.PI : 0;
    }
}
