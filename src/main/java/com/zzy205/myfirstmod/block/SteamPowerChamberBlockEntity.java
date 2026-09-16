package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 蒸汽动力室方块实体：轻量 BE（供 Flywheel Visual / BER 读取活塞状态）。
 * <p>
 * <b>服务端（由引擎 controller 驱动，本 BE 不 tick）</b>：{@link #burnTicks} = 剩余燃烧时长
 * （burnTick 制，1 tick 烧 1 burnTick，等价原版熔炉速率）。来源：
 * <ul>
 *   <li>固体燃料：controller 并行燃烧批量抽取——全室空炉时一次从燃料箱抽 N×K 个（N = 蒸汽室个数，
 *       K = 配置批次倍数，不足切下一箱），每室 + 抽到数量/N × burnTime（同燃同熄，燃烧时长 = 单个燃料时长）；</li>
 *   <li>流体燃料：controller 从源罐批量 drain（批次 mb → 每室 + 批次/N × burnTicksPerBucket/1000 burnTick）。</li>
 * </ul>
 * 水/燃料源断供时 controller 暂停消耗（burnTicks 冻结，储备烧完为止）。burnTicks 持久化到 NBT。
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

    /** 活塞相位推进系数（rad/tick/rpm）：每 tick 角度 += 转速 × 3/10 度，1 圈 = 1 往复（照 Create SteamEngine） */
    private static final float PISTON_RAD_PER_RPM = 3f / 10f * (float) Math.PI / 180f;

    /**
     * 客户端：累积活塞相位（弧度 0~2π）。<b>累积而非 gameTime×speed</b>——转速变化只改变推进速率、
     * 不造成相位跳变（修复 Lua 每 tick 变油门 → 音效/动画高频重复播放，见 memo 踩坑 27）。
     */
    @OnlyIn(Dist.CLIENT)
    protected float pistonAngle = 0f;

    /** 客户端：上一 tick 累积相位（渲染 partialTick 插值 + 相位回绕检测） */
    @OnlyIn(Dist.CLIENT)
    protected float prevPistonAngle = 0f;

    /** 剩余燃烧时长（burnTick 制，float 支持效率小数递减；服务端，仅 controller 读写；持久化） */
    public float burnTicks = 0f;

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

    // ================= 服务端：底座加热状态同步（HEATED） =================

    /**
     * 服务端 tick：父引擎（FACING 反方向贴的核心）整条引擎 controller 运行时 → 本室 HEATED=true
     * （底座切 heated.json 加热外观），否则 HEATED=false（block.json）。仅在状态变化时 setBlockAndUpdate
     * （不逐 tick 刷方块更新）；未贴核心（独立放置）→ 恒 false。
     */
    public void tickServer() {
        BlockState state = getBlockState();
        boolean heated = isParentEngineRunning();
        if (state.getValue(SteamPowerChamberBlock.HEATED) != heated) {
            level.setBlockAndUpdate(worldPosition, state.setValue(SteamPowerChamberBlock.HEATED, heated));
        }
    }

    /** 父引擎（FACING 反方向贴的核心）整条引擎 controller 是否运行；未贴核心返回 false */
    private boolean isParentEngineRunning() {
        Direction facing = getBlockState().getValue(SteamPowerChamberBlock.FACING);
        BlockPos parent = worldPosition.relative(facing.getOpposite());
        EngineCoreBlockEntity controller = engineController(parent);
        return controller != null && controller.isRunning();
    }

    // ================= 活塞动画（客户端） =================

    /**
     * 活塞伸出量（块单位，沿活塞轴 = FACING 方向）：引擎运行时在 ±{@link #PISTON_STROKE} 间正弦往复，
     * 停止时为 0（模型中间位）。相位为累积角度（每 tick 推进 speed×3/10 度，1 圈 = 1 往复，动画速度与转速挂钩），
     * 渲染按 partialTick 在上一/当前累积角间插值；客户端状态，无服务端参与。
     */
    public float getPistonOffset(float partialTick) {
        Direction facing = getBlockState().getValue(SteamPowerChamberBlock.FACING);
        BlockPos parent = worldPosition.relative(facing.getOpposite());
        EngineCoreBlockEntity controller = engineController(parent);
        if (controller == null || !controller.isRunning())
            return 0;
        float angle = Mth.lerp(partialTick, prevPistonAngle, pistonAngle);
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
     * 客户端 tick：推进活塞累积相位；相位回绕（每周期一次）→ 把本室坐标入队到引擎的"噗嗤"音效池
     * （由引擎 controller 每 tick 统一播放，照 Create SoundPool 去重）。相位与活塞动画同源
     * （累积角度随转速推进 + 对置/轴向交替偏移），不同相位的燃烧室在不同角度触发 → 波浪式"噗嗤噗嗤"。
     * 音量配置 = {@link Config#ENGINE_STEAM_PUFF_VOLUME}；设为 0 时只跳过音效（角度推进始终执行，供动画）。
     */
    @OnlyIn(Dist.CLIENT)
    public void tickClient() {
        Direction facing = getBlockState().getValue(SteamPowerChamberBlock.FACING);
        BlockPos parent = worldPosition.relative(facing.getOpposite());
        EngineCoreBlockEntity controller = engineController(parent);
        prevPistonAngle = pistonAngle;
        // 停机（油门 0 / 缺水 / 暖机等）：活塞回中间位（渲染 getPistonOffset 按 !running 返回 0），
        // 累积角归零 → 重启从中间位开始（避免从停机前的任意相位续走造成跳变）
        if (controller == null || !controller.isRunning()) {
            pistonAngle = 0f;
            return;
        }
        // 累积推进相位：转速变化只改推进速率，不产生相位跳变（修复快速变油门 → 相位跳变/回绕误判/动画高频重复）
        float speed = controller.getSpeed();
        pistonAngle = (pistonAngle + speed * PISTON_RAD_PER_RPM) % (float) (Math.PI * 2);
        // 相位回绕（累积角跨过 2π，每圈一次）→ 入队"噗嗤"音效池
        if (prevPistonAngle > pistonAngle && Config.ENGINE_STEAM_PUFF_VOLUME.get() > 0)
            controller.getSteamPistonSoundPool().queueAt(worldPosition);
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
