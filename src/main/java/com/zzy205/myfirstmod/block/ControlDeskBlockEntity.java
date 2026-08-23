package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 控制台方块实体 — 仅承载 Flywheel Visual（踏板/操纵杆 PartialModel 渲染），暂无业务状态。
 */
public class ControlDeskBlockEntity extends BlockEntity {

    public ControlDeskBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.control_desk_entity.get(), pos, state);
    }
}
