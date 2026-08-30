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
 * 万向环/罗盘盘/偏航标记三个 {@link OrientedInstance}，每帧 identityRotation 后重建父子旋转。
 * 层级（外→内）：test 绕 Y 偏航指北 → gimbal 绕 Z 滚转 → compass 绕 X 俯仰
 * （四元数 Y / Y·Z / Y·Z·X，各自独立实例），与 BER 共享同一套朝向约定。
 */
public class InsVisual extends AbstractBlockEntityVisual<InsBlockEntity>
        implements SimpleDynamicVisual {

    /** 转动部件（万向环/罗盘盘/偏航标记）整体下移量（块单位）：模型与旋转中心同步下移 3.5px */
    private static final float PIVOT_DROP = 3.5f / 16f;

    private final OrientedInstance gimbal;
    private final OrientedInstance compass;
    private final OrientedInstance yaw;

    private final List<ColoredLitInstance> allInstances = new ArrayList<>();

    public InsVisual(final VisualizationContext ctx, final InsBlockEntity blockEntity, final float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.gimbal = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.INS_GIMBAL))
                .createInstance().position(this.getVisualPosition())
                .translatePosition(0.5f, 0.5f - PIVOT_DROP, 0.5f)
                .translatePivot(-0.5f, -0.5f, -0.5f);

        this.compass = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.INS_COMPASS))
                .createInstance().position(this.getVisualPosition())
                .translatePosition(0.5f, 0.5f - PIVOT_DROP, 0.5f)
                .translatePivot(-0.5f, -0.5f, -0.5f);

        this.yaw = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.INS_YAW))
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

        // 层级（外→内）：test(Y 偏航) → gimbal(Z 滚转) → compass(X 俯仰)，每个部件独立四元数
        // test（最外层）：只绕 Y
        final Quaternionf testQ = this.blockEntity.getBaseQuaternion();
        this.blockEntity.applyCompassQuaternion(testQ, partialTicks);
        this.yaw.rotation(testQ);
        this.yaw.setChanged();

        // gimbal（中间层）：Y·Z
        final Quaternionf gimbalQ = this.blockEntity.getBaseQuaternion();
        this.blockEntity.applyCompassQuaternion(gimbalQ, partialTicks);
        this.blockEntity.applyPrimaryQuaternion(gimbalQ, partialTicks);
        this.gimbal.rotation(gimbalQ);
        this.gimbal.setChanged();

        // compass（最里层）：Y·Z·X
        final Quaternionf compassQ = this.blockEntity.getBaseQuaternion();
        this.blockEntity.applyCompassQuaternion(compassQ, partialTicks);
        this.blockEntity.applyPrimaryQuaternion(compassQ, partialTicks);
        this.blockEntity.applySecondaryQuaternion(compassQ, partialTicks);
        this.compass.rotation(compassQ);
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
