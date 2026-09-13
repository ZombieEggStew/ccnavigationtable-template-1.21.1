package com.zzy205.myfirstmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蒸汽动力室方块实体：轻量 BE（供 Flywheel Visual / BER 读取活塞状态），模式照抄
 * {@code FluidCombustionChamberBlockEntity}。
 * <p>
 * 当前无动画：{@link #getPistonOffset()} 恒为 0，活塞按模型默认位置（y0..13，伸出开口 3px）静态渲染。
 * 后续接入动画时在客户端 tick 中模拟活塞往复并返回伸出量（块单位，沿 FACING 方向）。
 */
public class SteamPowerChamberBlockEntity extends BlockEntity {

    public SteamPowerChamberBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.steam_power_chamber_entity.get(), pos, state);
    }

    /** 活塞伸出量（块单位，沿活塞轴 = FACING 方向）。当前恒 0（未做动画） */
    public float getPistonOffset() {
        return 0;
    }
}
