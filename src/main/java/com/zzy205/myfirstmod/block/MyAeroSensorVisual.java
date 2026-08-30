package com.zzy205.myfirstmod.block;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.ColoredLitInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 惯性导航系统 Flywheel 渲染，照抄 {@code simulated:gimbal_sensor} 的 GimbalSensorVisual：
 * 万向环/罗盘盘两个 {@link OrientedInstance}，每帧 identityRotation 后重建父子旋转
 * （base → 绕 Z → 绕 X），与 BER 共享同一套朝向约定。
 */
public class MyAeroSensorVisual extends AbstractBlockEntityVisual<MyAeroSensorBlockEntity>
        implements SimpleDynamicVisual {

    /** 转动部件（万向环/罗盘盘）整体下移量（块单位）：模型与旋转中心同步下移 5px */
    private static final float PIVOT_DROP = 4.5f / 16f;

    private final OrientedInstance gimbal;
    private final OrientedInstance compass;

    private final List<ColoredLitInstance> allInstances = new ArrayList<>();

    public MyAeroSensorVisual(final VisualizationContext ctx, final MyAeroSensorBlockEntity blockEntity, final float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.gimbal = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.MY_AERO_SENSOR_GIMBAL))
                .createInstance().position(this.getVisualPosition())
                .translatePosition(0.5f, 0.5f - PIVOT_DROP, 0.5f)
                .translatePivot(-0.5f, -0.5f, -0.5f);

        this.compass = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.MY_AERO_SENSOR_COMPASS))
                .createInstance().position(this.getVisualPosition())
                .translatePosition(0.5f, 0.5f - PIVOT_DROP, 0.5f)
                .translatePivot(-0.5f, -0.5f, -0.5f);

        this.allInstances.add(this.gimbal);
        this.allInstances.add(this.compass);
    }

    @Override
    public void beginFrame(final Context context) {
        this.handleRotations(context.partialTick());
    }

    private void handleRotations(final float partialTicks) {
        this.gimbal.identityRotation();
        this.compass.identityRotation();

        final Quaternionf base = this.blockEntity.getBaseQuaternion();

        this.blockEntity.applyPrimaryQuaternion(base, partialTicks);
        this.gimbal.rotation(base);
        this.gimbal.setChanged();

        this.blockEntity.applySecondaryQuaternion(base, partialTicks);
        this.compass.rotation(base);
        this.compass.setChanged();
    }

    @Override
    public void collectCrumblingInstances(final Consumer<Instance> consumer) {
        for (final ColoredLitInstance inst : this.allInstances) {
            consumer.accept(inst);
        }
    }

    @Override
    public void updateLight(final float v) {
        for (final ColoredLitInstance inst : this.allInstances) {
            this.relight(inst);
        }
    }

    @Override
    protected void _delete() {
        for (final ColoredLitInstance inst : this.allInstances) {
            inst.delete();
        }
    }
}
