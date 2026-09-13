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
 * 蒸汽动力室 Flywheel 渲染：一个 {@link OrientedInstance}（活塞），模式照抄
 * {@code FluidCombustionChamberVisual}。
 * <p>
 * 活塞模型（{@code piston.json}）沿 +Y 建模（y0..13，伸出口沿 3px），
 * 实例使用<b>默认 pivot (0.5,0.5,0.5)</b>（= 方块中心）+ position = 方块坐标 +
 * rotation = blockstate FACING 旋转 → 变换链等价于 {@code T(pos)·T(0.5)·R(facing)·T(−0.5)}，
 * 与 blockstate 对静态腔体模型的旋转完全一致，活塞始终对齐腔体开口。
 * <p>
 * 旋转四元数同 {@code FluidCombustionChamberVisual.pistonOrientation} / {@code AicBlockEntity.getBaseQuaternion()}
 * （原版 BlockModelRotation：{@code rotateYXZ(−y·rad, −x·rad, 0)} = Ry(−θy)·Rx(−θx)）。
 */
public class SteamPowerChamberVisual extends AbstractBlockEntityVisual<SteamPowerChamberBlockEntity>
        implements SimpleDynamicVisual {

    private final OrientedInstance piston;

    public SteamPowerChamberVisual(VisualizationContext context, SteamPowerChamberBlockEntity blockEntity,
                                   float partialTick) {
        super(context, blockEntity, partialTick);

        piston = instancerProvider()
                .instancer(InstanceTypes.ORIENTED, Models.partial(MyModPartialModels.STEAM_POWER_CHAMBER_PISTON))
                .createInstance();
        // 默认 pivot 0.5 → 旋转绕方块中心（与 blockstate 静态模型旋转一致）
        piston.position(getVisualPosition()).setChanged();
    }

    @Override
    public void beginFrame(Context context) {
        Direction facing = blockEntity.getBlockState().getValue(SteamPowerChamberBlock.FACING);

        // 活塞滑动（沿 FACING 方向；当前 offset = 0 无动画）
        float offset = blockEntity.getPistonOffset();
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

    /** blockstate FACING 旋转四元数（绕方块中心；与 blockstate JSON 变体旋转一致，同 FluidCombustionChamberVisual.pistonOrientation） */
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
