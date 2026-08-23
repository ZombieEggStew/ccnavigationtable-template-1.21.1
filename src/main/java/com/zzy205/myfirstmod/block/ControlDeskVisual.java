package com.zzy205.myfirstmod.block;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * 控制台 Flywheel 渲染：踏板与操纵杆 PartialModel 叠加在底座（blockstate 静态模型）之上。
 * <p>
 * 参照 simulated 的 ThrottleLeverVisual：模型按与底座相同的方块空间建模，
 * 渲染时平移到方块位置，再按 FACING 绕 Y 旋转（与 blockstate 对底座模型的 y 旋转一致）。
 * 暂无动画，仅静态叠加。
 */
public class ControlDeskVisual extends AbstractBlockEntityVisual<ControlDeskBlockEntity>
        implements SimpleDynamicVisual {

    private final TransformedInstance pedal;
    private final TransformedInstance joystick;

    public ControlDeskVisual(VisualizationContext ctx, ControlDeskBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.pedal = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(MyModPartialModels.CONTROL_DESK_PEDAL))
                .createInstance();
        this.joystick = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(MyModPartialModels.CONTROL_DESK_JOYSTICK))
                .createInstance();

        this.transformAll();
    }

    @Override
    public void beginFrame(Context context) {
        this.transformAll();
    }

    private void transformAll() {
        this.pedal.setIdentityTransform();
        this.joystick.setIdentityTransform();

        final BlockState state = this.blockEntity.getBlockState();
        final Direction facing = state.getValue(ControlDeskBlock.FACING);

        this.initialTransform(this.pedal, facing);
        this.initialTransform(this.joystick, facing);

        this.pedal.setChanged();
        this.joystick.setChanged();
    }

    /** 平移到位 + 绕方块中心 Y 旋转到 facing（与项目 Monitor 的 facing 约定一致：-facing.getOpposite().toYRot()） */
    private void initialTransform(TransformedInstance instance, Direction facing) {
        instance.translate(this.getVisualPosition());
        instance.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(this.pedal);
        consumer.accept(this.joystick);
    }

    @Override
    public void updateLight(float v) {
        this.relight(this.pedal);
        this.relight(this.joystick);
    }

    @Override
    protected void _delete() {
        this.pedal.delete();
        this.joystick.delete();
    }
}
