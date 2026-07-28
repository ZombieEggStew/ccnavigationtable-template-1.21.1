package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;


public class MySensorBlockEntity extends BlockEntity {
    public MySensorBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.my_sensor_entity.get(), pos, state);
    }
}
