package com.zzy205.myfirstmod.block;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

import java.util.function.Consumer;

/**
 * 流体燃烧室 Flywheel 渲染：一个 {@link OrientedInstance}（活塞）。
 * <p>
 * 活塞模型（{@code piston.json}）沿 +Y 建模（y0..13，伸出口沿 3px），
 * 实例使用<b>默认 pivot (0.5,0.5,0.5)</b>（= 方块中心）+ position = 方块坐标 +
 * rotation = blockstate FACING 旋转 → 变换链等价于 {@code T(pos)·T(0.5)·R(facing)·T(−0.5)}，
 * 与 blockstate 对静态腔体模型的旋转完全一致，活塞始终对齐腔体开口。
 * <p>
 * 旋转四元数同 {@code AicBlockEntity.getBaseQuaternion()}（原版 BlockModelRotation：
 * {@code rotateYXZ(−y·rad, −x·rad, 0)} = Ry(−θy)·Rx(−θx)，up 单位 / down x180 / 水平 x90+y旋转）。
 * <p>
 * 活塞滑动动画（未来）：活塞沿模型 +Y 平移 t → 世界位置 {@code pos + FACING·t}；
 * 当前 {@code getPistonOffset() = 0}，位置恒为方块坐标。
 * 参考来源：{@code AicVisual}（6 向 facing 四元数 + OrientedInstance 手法）。
 */
public class FluidCombustionChamberVisual extends AbstractBlockEntityVisual<FluidCombustionChamberBlockEntity>
        implements SimpleDynamicVisual {

    private final OrientedInstance piston;

    public FluidCombustionChamberVisual(VisualizationContext context, FluidCombustionChamberBlockEntity blockEntity,
                                        float partialTick) {
        super(context, blockEntity, partialTick);

        piston = instancerProvider()
                .instancer(InstanceTypes.ORIENTED, Models.partial(MyModPartialModels.FLUID_COMBUSTION_CHAMBER_PISTON))
                .createInstance();
        // 默认 pivot 0.5 → 旋转绕方块中心（与 blockstate 静态模型旋转一致）
        piston.position(getVisualPosition()).setChanged();
    }

    @Override
    public void beginFrame(Context ctx) {
        Direction facing = blockEntity.getBlockState().getValue(FluidCombustionChamberBlock.FACING);

        // 活塞滑动（沿 FACING 方向；引擎运行时 ±2/16 正弦往复，见 FluidCombustionChamberBlockEntity#getPistonOffset）
        float offset = blockEntity.getPistonOffset(ctx.partialTick());
        if (offset == 0) {
            piston.position(getVisualPosition());
        } else {
            var pos = getVisualPosition();
            piston.position(pos.getX() + facing.getStepX() * offset,
                    pos.getY() + facing.getStepY() * offset,
                    pos.getZ() + facing.getStepZ() * offset);
        }

        piston.rotation(pistonOrientation(facing)).setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        relight(piston);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(piston);
    }

    @Override
    protected void _delete() {
        piston.delete();
    }

    /** blockstate FACING 旋转四元数（绕方块中心；与 blockstate JSON 变体旋转一致，同 AicBlockEntity.getBaseQuaternion） */
    public static Quaternionf pistonOrientation(Direction facing) {
        float y = switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        float x = switch (facing) {
            case DOWN -> 180;
            case NORTH, SOUTH, EAST, WEST -> 90;
            default -> 0;
        };
        return new Quaternionf().rotateYXZ(-(float) Math.toRadians(y), -(float) Math.toRadians(x), 0);
    }
}
