package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 控制台方块实体 — 目前仅承载 Flywheel Visual；控件安装/状态数据待接入。
 */
public class ControlDeskBlockEntity extends BlockEntity {

    public ControlDeskBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.control_desk_entity.get(), pos, state);
    }
}
