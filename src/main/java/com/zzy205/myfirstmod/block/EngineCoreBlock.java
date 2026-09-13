package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 发动机核心（aero_engine / engine_core）：Create 动力源方块骨架。
 * <p>
 * 轴向设计（方案 3，同 Create 传动轴 / 齿轮箱 / 本项目 transmission_peripheral）：
 * <ul>
 *   <li>使用 {@link RotatedPillarKineticBlock} 的 {@code AXIS}（X/Y/Z）三向状态，blockstate 恰好 3 个 state；
 *       blockstate 模型映射见 {@code assets/ccpe/blockstates/engine_core.json}（模型南北对称，x/z 共用，y 用 x 旋转）。</li>
 *   <li>{@code hasShaftTowards} 沿 AXIS 前后两面 → <b>贯通传动杆</b>（模型中心预留 6×6 轴孔，匹配 Create SHAFT_HALF 截面）。</li>
 *   <li>扳手默认处理来自 {@code IRotate}/{@code IWrenchable}：右键在轴之间切换（X↔Z，Y 轴不转）、潜行右键拆除，无需覆写。</li>
 *   <li>放置默认继承 {@link RotatedPillarKineticBlock#getStateForPlacement}：优先对齐相邻传动轴，其次按点击面/视线方向定轴。</li>
 * </ul>
 * 发电逻辑由 {@link EngineCoreBlockEntity} 提供（当前占位，不实际发电；后续接入燃烧室/蒸汽室等模块）。
 * 参考来源：Create {@code AbstractEncasedShaftBlock} / {@code GearboxBlock}；simulated {@code PortableEngineBlock}。
 */
public class EngineCoreBlock extends RotatedPillarKineticBlock implements IBE<EngineCoreBlockEntity> {

    public EngineCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<EngineCoreBlockEntity> getBlockEntityClass() {
        return EngineCoreBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EngineCoreBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.engine_core_entity.get();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EngineCoreBlockEntity(pos, state);
    }

    /** 贯通传动杆：沿 AXIS 的前后两面都能接轴 */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }
}
