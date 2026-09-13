package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 发动机核心方块实体：Create 动力源骨架（发电逻辑待后续模块接入）。
 * <p>
 * 当前为占位实现：{@link #getGeneratedSpeed()} 返回 {@link #GENERATED_SPEED}（= 0，不实际发电）。
 * 贯通传动杆随 Create 动力网络正常连通（被动节点：外部输入可经两端穿过）。
 * 后续接入流体燃烧室 / 蒸汽动力室等模块后，在 tick 中按燃烧/蒸汽状态调用
 * {@code updateGeneratedRotation()} 即可切换为真正的动力源。
 * 参考来源：Create {@code GeneratingKineticBlockEntity}；simulated {@code PortableEngineBlockEntity}。
 */
public class EngineCoreBlockEntity extends GeneratingKineticBlockEntity {

    /** 发电转速（RPM）。占位 = 0（不发电）；后续接入燃烧/蒸汽模块后改为实际值 */
    public static final float GENERATED_SPEED = 0;

    public EngineCoreBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.engine_core_entity.get(), pos, state);
    }

    @Override
    public float getGeneratedSpeed() {
        return GENERATED_SPEED;
    }
}
