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
 * 万向环/罗盘盘/偏航标记三个 {@link OrientedInstance}，每帧 identityRotation 后重建父子旋转
 * （base → 绕 Z → 绕 X → 偏航标记再绕自身 Y 指北），与 BER 共享同一套朝向约定。
 */
public class MyAeroSensorVisual extends AbstractBlockEntityVisual<MyAeroSensorBlockEntity>
        implements SimpleDynamicVisual {

    /** 转动部件（万向环/罗盘盘/偏航标记）整体下移量（块单位）：模型与旋转中心同步下移 3.5px */
    private static final float PIVOT_DROP = 3.5f / 16f;

    private final OrientedInstance gimbal;
    private final OrientedInstance compass;
    private final OrientedInstance yaw;

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

        this.yaw = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.MY_AERO_SENSOR_YAW))
                .createInstance().position(this.getVisualPosition())
                .translatePosition(0.5f, 0.5f - PIVOT_DROP, 0.5f)
                .translatePivot(-0.5f, -0.5f, -0.5f);

        this.allInstances.add(this.gimbal);
        this.allInstances.add(this.compass);
        this.allInstances.add(this.yaw);
    }

    @Override
    public void beginFrame(final Context context) {
        this.handleRotations(context.partialTick());
    }

    private void handleRotations(final float partialTicks) {
        this.gimbal.identityRotation();
        this.compass.identityRotation();
        this.yaw.identityRotation();

        final Quaternionf base = this.blockEntity.getBaseQuaternion();

        this.blockEntity.applyPrimaryQuaternion(base, partialTicks);
        this.gimbal.rotation(base);
        this.gimbal.setChanged();

        this.blockEntity.applySecondaryQuaternion(base, partialTicks);
        this.compass.rotation(base);
        this.compass.setChanged();

        // 偏航标记跟随罗盘盘（滚转/俯仰）后再绕自身 Y 指北
        this.blockEntity.applyCompassQuaternion(base, partialTicks);
        this.yaw.rotation(base);
        this.yaw.setChanged();
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
