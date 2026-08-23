package com.zzy205.myfirstmod.block;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;

import java.util.function.Consumer;

/**
 * 控制台 Flywheel Visual。
 * <p>
 * 踏板/操纵杆已从控制台本体移除，改为可安装控件物品（pedal/joystick）；
 * 控件安装系统接入后，在此按已安装状态叠加对应 PartialModel（参考 aeroworks ConsoleVisual）。
 */
public class ControlDeskVisual extends AbstractBlockEntityVisual<ControlDeskBlockEntity>
        implements SimpleDynamicVisual {

    public ControlDeskVisual(VisualizationContext ctx, ControlDeskBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);
    }

    @Override
    public void beginFrame(Context context) {
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
    }

    @Override
    public void updateLight(float v) {
    }

    @Override
    protected void _delete() {
    }
}
