package com.zzy205.myfirstmod.block;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.Consumer;

/**
 * Flywheel 渲染：AXIS 轴两端各一个<b>半传动杆</b>（Create {@code SHAFT_HALF}）合成贯通传动杆。
 * <p>
 * 两个 {@link RotatingInstance} 共用同一旋转轴/转速/相位（{@code setup(blockEntity, axis)}），
 * 与 BER（{@link EngineCoreRenderer}）同公式，视觉上等价于一根整轴旋转；
 * 各自通过 {@code rotateToFace(SOUTH, 端面)} 定向到对应端面。
 * 方块本体（发动机外壳）由 blockstate 静态模型渲染。
 * <p>
 * 参考来源：Create {@code GearboxVisual}（RotatingInstance + rotateToFace 手法）。
 */
public class EngineCoreVisual extends KineticBlockEntityVisual<EngineCoreBlockEntity> {

    private final RotatingInstance[] shafts = new RotatingInstance[2];

    public EngineCoreVisual(VisualizationContext context, EngineCoreBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);

        Direction.Axis axis = blockState.getValue(BlockStateProperties.AXIS);
        var instancer = instancerProvider().instancer(AllInstanceTypes.ROTATING,
                Models.partial(AllPartialModels.SHAFT_HALF));

        int i = 0;
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != axis) continue;
            RotatingInstance instance = instancer.createInstance();
            instance.setup(blockEntity, axis)
                    .setPosition(getVisualPosition())
                    .rotateToFace(Direction.SOUTH, direction)
                    .setChanged();
            shafts[i++] = instance;
        }
    }

    @Override
    public void update(float pt) {
        for (RotatingInstance shaft : shafts)
            shaft.setup(blockEntity)
                    .setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        relight(shafts);
    }

    @Override
    protected void _delete() {
        for (RotatingInstance shaft : shafts)
            shaft.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        for (RotatingInstance shaft : shafts)
            consumer.accept(shaft);
    }
}
