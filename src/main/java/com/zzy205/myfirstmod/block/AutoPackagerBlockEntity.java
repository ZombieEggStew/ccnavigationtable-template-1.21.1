package com.zzy205.myfirstmod.block;

import java.util.List;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 自动打包机方块实体：继承 {@link PackagerBlockEntity}（抽取/打包/动画/告示牌地址/库存联动全部照用），
 * 只把"红石上升沿触发"换成"按间隔自动触发"。
 * <p>
 * 触发逻辑见 {@link #tick()}：服务端每 tick 递减 {@link #intervalTicks}，归零后调父类的
 * {@code updateSignAddress(); attemptToSend(null);}（与父类 lazyTick 的红石路径完全同一条打包链）。
 * 上一包还占着托盘（{@code animationTicks != 0} 或 {@code heldBox} 非空）时不触发、计数保持 0，
 * 等托盘空出来立刻补打 —— 因此最小实际间隔受 20t 打包动画节流。
 * <p>
 * 间隔通过 {@link AutoPackagerScrollValueBehaviour}（长按右键数值面板，单位为秒，量程 1s~60s）配置，
 * 数值本身由父类行为系统写进 NBT（"ScrollValue"，单位 tick），倒计时进度单独存 "IntervalTicks"。
 * <p>
 * 参考来源：api/create-1.21.1-6.0.10-280-sources 的
 * com/simibubi/create/content/logistics/packager/PackagerBlockEntity.java
 * 与 com/simibubi/create/content/redstone/diodes/BrassDiodeBlockEntity.java（脉冲定时器数值面板）。
 */
public class AutoPackagerBlockEntity extends PackagerBlockEntity {

    /** 默认间隔 20t = 1 秒。 */
    public static final int DEFAULT_INTERVAL = 20;
    /** 量程 1 秒 ~ 60 秒（内部按 tick 存：1 秒 = 20t）。 */
    public static final int MIN_INTERVAL = 1 * 20;
    public static final int MAX_INTERVAL = 60 * 20;

    public AutoPackagerScrollValueBehaviour intervalBehaviour;

    /** 距下一包的剩余 tick 数；0 表示"本 tick 就该打，但托盘被占用时要保持 0 继续等"。 */
    private int intervalTicks;

    /** BlockEntityType.Builder.of 用的两参构造（类型从注册表取，同项目其他 BE 的写法）。 */
    public AutoPackagerBlockEntity(BlockPos pos, BlockState state) {
        this(MyModBlockEntities.auto_packager_entity.get(), pos, state);
    }

    public AutoPackagerBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        // 注意：父类构造器里就会调用 addBehaviours()，字段初始化器在 super() 之后才执行，
        // 所以 intervalTicks 只能在构造体内赋值（不要改成字段初始化器）。
        intervalTicks = DEFAULT_INTERVAL;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 目标容器（InvManipulationBehaviour）/ 库存版本追踪 / 成就 / CC 行为，全部交给父类
        super.addBehaviours(behaviours);

        // 数值框显示在东西南北四个侧面的正中（排除顶面与底面）：不论 FACING 朝哪（尤其是朝上装青蛙港时），
        // 玩家都能从任意一个水平面按住右键打开数值面板。Sided 槽位由 Create 的全局机制驱动：
        // 悬停/右键时取"玩家看的那一面"（ValueBox 的 highlightFace / ValueSettingsInputHandler 的 fromSide）。
        intervalBehaviour = new AutoPackagerScrollValueBehaviour(
            Component.translatable("gui.ccpe.auto_packager.interval"), this,
            new CenteredSideValueBoxTransform((state, side) -> side.getAxis().isHorizontal()));
        intervalBehaviour.between(MIN_INTERVAL, MAX_INTERVAL)
            .withFormatter(AutoPackagerScrollValueBehaviour::format)
            .withCallback(this::onIntervalChanged);
        intervalBehaviour.setValue(DEFAULT_INTERVAL);
        behaviours.add(intervalBehaviour);
    }

    private void onIntervalChanged(int newInterval) {
        // 调小间隔时立刻收敛，调大时等当前这轮打完再按新节奏
        intervalTicks = Math.min(intervalTicks, newInterval);
        setChanged();
    }

    /** 库存链接（LINKED）不影响自动打包：本方块始终自己按间隔打包（按需求"忽略 stock_link"）。 */
    @Override
    public boolean redstoneModeActive() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide)
            return;

        if (intervalTicks > 0) {
            intervalTicks--;
            return;
        }

        // 托盘还被上一包占着（动画中 / 没被青蛙港或玩家取走）：保持倒计时为 0，下 tick 再试
        if (animationTicks != 0 || !heldBox.isEmpty())
            return;

        updateSignAddress();
        attemptToSend(null);
        intervalTicks = intervalBehaviour == null ? DEFAULT_INTERVAL : intervalBehaviour.getValue();
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        intervalTicks = Math.max(0, compound.getInt("IntervalTicks"));
        if (intervalTicks <= 0)
            intervalTicks = DEFAULT_INTERVAL;
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putInt("IntervalTicks", intervalTicks);
    }
}
