package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 流体端口（fluid_port）方块实体：仅负责"开盖时长"追踪。
 * <p>
 * 移植自参考 mod CreateFluidLogistics 的 {@code FluidHatchBlockEntity}
 * （参考来源：{@code references/CreateFluidLogistics-master/.../content/fluids/fluidHatch/FluidHatchBlockEntity.java}），
 * 去掉了 {@code FilteringBehaviour}（流体过滤器暂未实现）。
 * <p>
 * 开盖关闭由 {@link FluidPortBlock} 的方块调度 tick 驱动（无需 BE ticker）：
 * 每次右键传输调用 {@link #extendOpen} 延长开盖截止时刻，关闭 tick 触发时若仍应保持开盖则重新调度。
 * 状态为瞬态（开盖仅持续 OPEN_TICKS tick），不持久化 NBT。
 */
public class FluidPortBlockEntity extends BlockEntity {
    private long openUntilGameTime;
    private long scheduledCloseGameTime;

    public FluidPortBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.fluid_port_entity.get(), pos, state);
    }

    void extendOpen(ServerLevel level, int ticks) {
        openUntilGameTime = Math.max(openUntilGameTime, level.getGameTime() + ticks);
    }

    boolean shouldRemainOpen(ServerLevel level) {
        return openUntilGameTime > level.getGameTime();
    }

    boolean hasScheduledCloseTick(ServerLevel level) {
        return scheduledCloseGameTime > level.getGameTime();
    }

    int getRemainingOpenTicks(ServerLevel level) {
        long remaining = openUntilGameTime - level.getGameTime();
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, remaining));
    }

    void markCloseTickScheduled(ServerLevel level, int delay) {
        scheduledCloseGameTime = level.getGameTime() + delay;
    }

    void clearOpenPulse() {
        openUntilGameTime = 0;
        scheduledCloseGameTime = 0;
    }
}
