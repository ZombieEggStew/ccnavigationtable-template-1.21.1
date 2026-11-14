package com.zzy205.myfirstmod.block;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.joml.Quaternionf;

import java.util.function.Consumer;

/**
 * Flywheel 渲染：轴承背面（FACING 对面）的<b>半个传动杆</b>（Create {@code SHAFT_HALF}）。
 * <p>
 * 参照 {@code TransmissionPeripheralVisual} 的 OrientedInstance 手法：
 * 每帧按当前 FACING 动态定向（扳手旋转后无需重建 visual），绕<b>轴正方向</b>以
 * {@code 转速 × 时间} 旋转（同 Create BER {@code kineticRotationTransform} 的转向约定，
 * 因此上半轴朝轴的负方向时必须把角度取反，见 {@code beginFrame}）。
 * <p>
 * 参考来源：{@code src/main/java/com/zzy205/myfirstmod/block/TransmissionPeripheralVisual.java}。
 */
public class MyBearingVisual extends KineticBlockEntityVisual<MyBearingBlockEntity>
        implements SimpleDynamicVisual {

    private final OrientedInstance shaftInstance;
    private final Quaternionf rotation = new Quaternionf();

    public MyBearingVisual(VisualizationContext ctx, MyBearingBlockEntity be, float partialTick) {
        super(ctx, be, partialTick);

        shaftInstance = instancerProvider()
                .instancer(InstanceTypes.ORIENTED, Models.partial(AllPartialModels.SHAFT_HALF))
                .createInstance();
        shaftInstance.position((Vec3i) getVisualPosition())
                .setChanged();
    }

    @Override
    protected void _delete() {
        shaftInstance.delete();
    }

    @Override
    public void updateLight(float partialTick) {
        relight(shaftInstance);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(shaftInstance);
    }

    @Override
    public void beginFrame(Context context) {
        float time = AnimationTickHolder.getRenderTime(blockEntity.getLevel());
        Direction facing = blockEntity.getBlockState().getValue(MyBearingBlock.FACING);
        Direction.Axis axis = facing.getAxis();
        Direction shaftFace = facing.getOpposite();
        float speed = blockEntity.getSpeed();
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(
                blockEntity, blockEntity.getBlockPos(), axis);
        float angleDeg = (time * speed * 3f / 10f) % 360 + offset;
        float angle = angleDeg / 180f * (float) Math.PI;

        // 转向约定：Create 的 BER（kineticRotationTransform）恒绕「轴正方向」旋转；
        // 这里 orientationFor 把半轴的局部 +Z 转到 shaftFace（多数情况下是轴的负方向），
        // 局部 rotateZ 于是等于绕负方向转，视觉上方向相反 —— 按轴向符号取反才对得上。
        float spin = shaftFace.getAxisDirection() == Direction.AxisDirection.POSITIVE ? angle : -angle;
        rotation.set(orientationFor(shaftFace)).rotateZ(spin);
        shaftInstance.rotation(rotation).setChanged();
    }

    /** SHAFT_HALF 默认朝 +Z（SOUTH），旋转到指定面（同 TransmissionPeripheralVisual.orientationFor） */
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
}
