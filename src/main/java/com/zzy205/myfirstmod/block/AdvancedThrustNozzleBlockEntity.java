package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矢量喷管（{@code ccpe:advanced_thrust_nozzle}）方块实体。
 * <p>
 * 阶段一（当前）：只是把方块实体类型换成自己的，其它一切（{@code sable$physicsTick} 推力、
 * 气压、排气/进气/尾迹粒子、遮挡射线、染料同步、goggle tooltip）全部继承
 * {@link ThrustNozzleBlockEntity}；喷口可转动的姿态暂时由
 * {@link AdvancedThrustNozzleRenderer} 里的一段纯客户端测试姿态驱动（还没有控制逻辑、也没有同步）。
 * <p>
 * 阶段二（待做）：把倾斜状态搬到这里 —— 目标方向（服务端权威）+ 锥角钳位 + 逐 tick 逼近 +
 * {@code previous} 帧插值，NBT 与 {@code getUpdateTag}/{@code handleUpdateTag} 各存 3 个 float，
 * 再让推力 / 粒子都改用倾斜后的轴（届时在 {@link ThrustNozzleBlockEntity} 加一个
 * "取喷嘴轴向"的钩子即可，一处改全链路生效）。
 */
public class AdvancedThrustNozzleBlockEntity extends ThrustNozzleBlockEntity {

    public AdvancedThrustNozzleBlockEntity(final BlockPos pos, final BlockState state) {
        super(MyModBlockEntities.advanced_thrust_nozzle_entity.get(), pos, state);
    }
}
