package com.zzy205.myfirstmod.block;

import com.mojang.math.Axis;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

import java.util.function.Consumer;

/**
 * Flywheel 渲染：顶部转盘（复用 Create 的 {@code create:block/bearing/top} partial），
 * 绕 FACING 轴按 {@link ServoBearingBlockEntity#getInterpolatedAngle}（服务端角度 + 帧间插值）旋转。
 * <p>
 * 参照 {@code BearingVisual} 的 OrientedInstance 手法；插值角度为<b>插值式</b>（修复版），
 * 因此传 {@code ctx.partialTick()} 而不是原版的 {@code partialTick() - 1}（原版是外推式）。
 * <p>
 * 参考来源：{@code references/Create-mc1.21.1-dev/.../bearing/BearingVisual.java}。
 */
public class ServoBearingVisual extends KineticBlockEntityVisual<ServoBearingBlockEntity>
        implements SimpleDynamicVisual {

    private final OrientedInstance topInstance;
    private final Axis rotationAxis;
    private final Quaternionf blockOrientation;

    public ServoBearingVisual(final VisualizationContext ctx, final ServoBearingBlockEntity be, final float partialTick) {
        super(ctx, be, partialTick);

        final Direction facing = be.getBlockState().getValue(ServoBearingBlock.FACING);
        rotationAxis = Axis.of(Direction.get(Direction.AxisDirection.POSITIVE, facing.getAxis()).step());
        blockOrientation = getBlockStateOrientation(facing);

        topInstance = instancerProvider()
                .instancer(InstanceTypes.ORIENTED, Models.partial(MyModPartialModels.SERVO_BEARING_TOP))
                .createInstance();
        topInstance.position(getVisualPosition())
                .rotation(blockOrientation)
                .setChanged();
    }

    @Override
    public void beginFrame(final DynamicVisual.Context ctx) {
        final float interpolatedAngle = blockEntity.getInterpolatedAngle(ctx.partialTick());
        final Quaternionf rot = rotationAxis.rotationDegrees(interpolatedAngle);
        rot.mul(blockOrientation);
        topInstance.rotation(rot)
                .setChanged();
    }

    @Override
    public void updateLight(final float partialTick) {
        relight(topInstance);
    }

    @Override
    protected void _delete() {
        topInstance.delete();
    }

    @Override
    public void collectCrumblingInstances(final Consumer<Instance> consumer) {
        consumer.accept(topInstance);
    }

    /** 顶部转盘随 FACING 定向（同 {@code BearingVisual.getBlockStateOrientation}） */
    static Quaternionf getBlockStateOrientation(final Direction facing) {
        final Quaternionf orientation;
        if (facing.getAxis().isHorizontal()) {
            orientation = Axis.YP.rotationDegrees(AngleHelper.horizontalAngle(facing.getOpposite()));
        } else {
            orientation = new Quaternionf();
        }
        orientation.mul(Axis.XP.rotationDegrees(-90 - AngleHelper.verticalAngle(facing)));
        return orientation;
    }
}
