package com.zzy205.myfirstmod.block;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock.AXIS;

/**
 * Flywheel 渲染：传动杆（shaft）。
 * 参照 Create Propulsion 的 RedstoneTransmissionVisual。
 */
public class TransmissionPeripheralVisual extends KineticBlockEntityVisual<TransmissionPeripheralBlockEntity>
        implements SimpleDynamicVisual {

    private final List<RotatingInstance> shaftInstances = new ArrayList<>();

    public TransmissionPeripheralVisual(VisualizationContext ctx, TransmissionPeripheralBlockEntity be, float partialTick) {
        super(ctx, be, partialTick);

        Direction.Axis axis = be.getBlockState().getValue(AXIS);

        for (Direction direction : Iterate.directionsInAxis(axis)) {
            RotatingInstance instance = instancerProvider()
                    .instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                    .createInstance();

            instance.setup(be)
                    .setPosition(getVisualPosition())
                    .setRotationAxis(Direction.Axis.Z)
                    .rotateToFace(Direction.SOUTH, direction)
                    .setChanged();

            shaftInstances.add(instance);
        }
    }

    @Override
    protected void _delete() {
        shaftInstances.forEach(RotatingInstance::delete);
    }

    @Override
    public void updateLight(float partialTick) {
        shaftInstances.forEach(this::relight);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        shaftInstances.forEach(consumer);
    }

    @Override
    public void beginFrame(Context context) {
        Direction.Axis axis = blockEntity.getBlockState().getValue(AXIS);
        int idx = 0;

        for (Direction direction : Iterate.directionsInAxis(axis)) {
            if (idx >= shaftInstances.size()) break;
            float speed = getDirectionalSpeed(direction);
            shaftInstances.get(idx).setup(blockEntity, speed).setChanged();
            idx++;
        }
    }

    /**
     * 输入端返回全速，输出端返回变速后的转速。
     * 参照 RedstoneTransmissionRenderer.getDirectionalSpeed。
     */
    private float getDirectionalSpeed(Direction direction) {
        if (!blockEntity.hasSource() || blockEntity.getSourceFacing() == direction) {
            return blockEntity.getSpeed();
        }
        return blockEntity.getSpeed() * blockEntity.getRotationSpeedModifier(direction);
    }
}
