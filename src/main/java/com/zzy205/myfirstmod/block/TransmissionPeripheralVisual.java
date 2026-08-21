package com.zzy205.myfirstmod.block;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock.AXIS;

/**
 * Flywheel 渲染：传动杆（shaft）。
 * <p>
 * 变速器模式：角度 = 转速 × 时间（等效原 RotatingInstance 的转速旋转）。
 * 舵机模式：输出端 shaft 按服务器同步的权威角度渲染（定位语义，无随机 offset），输入端保持转速旋转。
 * 参照 AeroWorks StepperServoVisual 的 OrientedInstance 手法。
 */
public class TransmissionPeripheralVisual extends KineticBlockEntityVisual<TransmissionPeripheralBlockEntity>
        implements SimpleDynamicVisual {

    private final List<OrientedInstance> shaftInstances = new ArrayList<>();
    private final List<Direction> shaftDirections = new ArrayList<>();
    private final Quaternionf rotation = new Quaternionf();

    public TransmissionPeripheralVisual(VisualizationContext ctx, TransmissionPeripheralBlockEntity be, float partialTick) {
        super(ctx, be, partialTick);

        Direction.Axis axis = be.getBlockState().getValue(AXIS);

        for (Direction direction : Iterate.directionsInAxis(axis)) {
            OrientedInstance instance = instancerProvider()
                    .instancer(InstanceTypes.ORIENTED, Models.partial(AllPartialModels.SHAFT_HALF))
                    .createInstance();
            instance.position((Vec3i) getVisualPosition())
                    .setChanged();
            shaftInstances.add(instance);
            shaftDirections.add(direction);
        }
    }

    @Override
    protected void _delete() {
        shaftInstances.forEach(OrientedInstance::delete);
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
        float time = AnimationTickHolder.getRenderTime(blockEntity.getLevel());
        float partialTick = context.partialTick();
        Direction.Axis axis = blockEntity.getBlockState().getValue(AXIS);
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(
                blockEntity, blockEntity.getBlockPos(), axis);

        for (int i = 0; i < shaftInstances.size(); i++) {
            Direction direction = shaftDirections.get(i);
            float angleDeg;
            if (blockEntity.isServoMode() && blockEntity.isServoOutputFace(direction)) {
                angleDeg = blockEntity.getServoDisplayAngle(partialTick);
            } else {
                float speed = getDirectionalSpeed(direction);
                angleDeg = (time * speed * 3f / 10f) % 360 + offset;
            }
            applyRotation(shaftInstances.get(i), direction, angleDeg / 180f * (float) Math.PI);
        }
    }

    private void applyRotation(OrientedInstance instance, Direction direction, float angleRad) {
        rotation.set(orientationFor(direction)).rotateZ(angleRad);
        instance.rotation(rotation).setChanged();
    }

    /** SHAFT_HALF 默认朝 +Z（SOUTH），旋转到指定面（同 KineticBlockEntityRenderer partialFacing 语义） */
    private static Quaternionf orientationFor(Direction facing) {
        float halfPi = 1.5707964f;
        return switch (facing) {
            case UP -> new Quaternionf().rotateX(-halfPi);
            case DOWN -> new Quaternionf().rotateX(halfPi);
            case NORTH -> new Quaternionf().rotateY((float) Math.PI);
            case SOUTH -> new Quaternionf();
            case EAST -> new Quaternionf().rotateY(halfPi);
            case WEST -> new Quaternionf().rotateY(-halfPi);
        };
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
