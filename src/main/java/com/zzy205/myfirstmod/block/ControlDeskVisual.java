package com.zzy205.myfirstmod.block;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * 控制台 Flywheel Visual：按 BE 已安装控件状态叠加渲染控件（底座 + 本体）。
 * 安装状态变化时动态创建/删除实例；实例存在期间每帧重置变换并刷新 facing 旋转
 * （必须 setIdentityTransform，translate 为累加语义，否则模型每帧漂移）。
 * 模型按与底座相同的方块空间（北向）建模，渲染时平移到方块位置 + 绕方块中心 Y 旋转到 FACING。
 */
public class ControlDeskVisual extends AbstractBlockEntityVisual<ControlDeskBlockEntity>
        implements SimpleDynamicVisual {

    private TransformedInstance pedal;
    private TransformedInstance pedalRight;
    private TransformedInstance pedalBase;
    private TransformedInstance joystick;
    private TransformedInstance joystickBase;

    public ControlDeskVisual(VisualizationContext ctx, ControlDeskBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);
        this.transformAll();
    }

    @Override
    public void beginFrame(Context context) {
        this.transformAll();
    }

    private void transformAll() {
        final ControlDeskBlockEntity be = this.blockEntity;
        final BlockState state = be.getBlockState();
        final Direction facing = state.getValue(ControlDeskBlock.FACING);

        boolean pedalWanted = be.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL);
        boolean joystickWanted = be.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK);

        this.pedalBase = syncInstance(this.pedalBase, pedalWanted, MyModPartialModels.CONTROL_DESK_PEDAL_BASE, facing);
        this.pedal = syncInstance(this.pedal, pedalWanted, MyModPartialModels.CONTROL_DESK_PEDAL, facing);
        this.pedalRight = syncInstance(this.pedalRight, pedalWanted, MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT, facing);
        this.joystickBase = syncInstance(this.joystickBase, joystickWanted, MyModPartialModels.CONTROL_DESK_JOYSTICK_BASE, facing);
        this.joystick = syncInstance(this.joystick, joystickWanted, MyModPartialModels.CONTROL_DESK_JOYSTICK, facing);
    }

    /** 按安装状态创建/删除实例；存在的实例每帧重置变换 + 平移到位 + 旋转到 facing 并标记更新。 */
    private TransformedInstance syncInstance(TransformedInstance instance, boolean wanted,
                                             PartialModel model, Direction facing) {
        if (wanted && instance == null) {
            instance = this.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
                    .createInstance();
        } else if (!wanted && instance != null) {
            instance.delete();
            return null;
        }
        if (instance != null) {
            instance.setIdentityTransform();
            instance.translate(this.getVisualPosition());
            instance.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
            instance.setChanged();
        }
        return instance;
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        if (this.pedal != null) consumer.accept(this.pedal);
        if (this.pedalRight != null) consumer.accept(this.pedalRight);
        if (this.pedalBase != null) consumer.accept(this.pedalBase);
        if (this.joystick != null) consumer.accept(this.joystick);
        if (this.joystickBase != null) consumer.accept(this.joystickBase);
    }

    @Override
    public void updateLight(float v) {
        if (this.pedal != null) this.relight(this.pedal);
        if (this.pedalRight != null) this.relight(this.pedalRight);
        if (this.pedalBase != null) this.relight(this.pedalBase);
        if (this.joystick != null) this.relight(this.joystick);
        if (this.joystickBase != null) this.relight(this.joystickBase);
    }

    @Override
    protected void _delete() {
        if (this.pedal != null) this.pedal.delete();
        if (this.pedalRight != null) this.pedalRight.delete();
        if (this.pedalBase != null) this.pedalBase.delete();
        if (this.joystick != null) this.joystick.delete();
        if (this.joystickBase != null) this.joystickBase.delete();
    }
}
