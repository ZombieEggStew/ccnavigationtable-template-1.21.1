package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蒸汽动力室方块实体：轻量 BE（供 Flywheel Visual / BER 读取活塞状态）。
 * <p>
 * <b>服务端（由引擎 controller 驱动，本 BE 不 tick）</b>：{@link #burnTicks} = 剩余燃烧时长
 * （burnTick 制，1 tick 烧 1 burnTick，等价原版熔炉速率）。来源：
 * <ul>
 *   <li>固体燃料：controller 从本室 6 邻居容器抽取 1 个熔炉燃料物品 → +物品 burnTime；</li>
 *   <li>流体燃料：controller 从源罐 drain（1mb = burn_ticks_per_bucket/1000 burnTick，如熔岩 20 tick/mb）。</li>
 * </ul>
 * 水不可用时 controller 暂停消耗（burnTicks 冻结，不烧燃料）。burnTicks 持久化到 NBT。
 * <p>
 * <b>客户端（活塞动画）</b>：父引擎（FACING 反方向贴的核心）运行时，活塞伸出量在
 * ±{@link #PISTON_STROKE}（±2/16 块，默认模型活塞在中间位）间正弦往复；<b>角度随引擎输出转速推进</b>
 * （照 Create SteamEngine：每 tick 推进 speed×3/10 度，1 圈 = 1 往复，动画速度与转速挂钩）；
 * 停止时回中间位。
 * 相位规则同流体燃烧室（方案 B）：对置面差半周期 + 沿引擎轴向相邻核心交替。
 * <p>
 * 参考来源：{@code FluidPortBlockEntity}（普通 BlockEntity，无 Create SmartBlockEntity 依赖）。
 */
public class SteamPowerChamberBlockEntity extends BlockEntity {

    /** 活塞行程半幅（块）：默认模型活塞在中间位，往复 ±2/16 */
    public static final float PISTON_STROKE = 2f / 16f;

    /** 剩余燃烧时长（burnTick 制，float 支持效率小数递减；服务端，仅 controller 读写；持久化） */
    public float burnTicks = 0f;

    /** 流体燃料消耗累加器（mb 小数，服务端，仅 controller 读写；不持久化——重启丢失不足 1mb 无影响） */
    public float fluidFuelDebt = 0f;

    public SteamPowerChamberBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.steam_power_chamber_entity.get(), pos, state);
    }

    // ================= NBT（服务端状态持久化） =================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("BurnTicks", burnTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        burnTicks = tag.getFloat("BurnTicks");
    }

    // ================= 活塞动画（客户端） =================

    /**
     * 活塞伸出量（块单位，沿活塞轴 = FACING 方向）：引擎运行时在 ±{@link #PISTON_STROKE} 间正弦往复，
     * 停止时为 0（模型中间位）。角度随引擎输出转速推进（每 tick speed×3/10 度，1 圈 = 1 往复），
     * 动画速度与转速挂钩；客户端按游戏时间 + partialTick 计算，无服务端状态。
     */
    public float getPistonOffset(float partialTick) {
        Direction facing = getBlockState().getValue(SteamPowerChamberBlock.FACING);
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
