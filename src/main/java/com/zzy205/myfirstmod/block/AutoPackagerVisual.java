package com.zzy205.myfirstmod.block;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerRenderer;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 自动打包机的 Flywheel 视觉：门 hatch 与托盘 tray 的实例化渲染（方块本体由 blockstate 静态模型渲染）。
 * 逻辑照抄 Create 的 {@code PackagerVisual}，唯一改动是托盘固定用 {@code PACKAGER_TRAY_REGULAR}
 * （原因见 {@link AutoPackagerRenderer} 的类注释）。
 * <p>
 * 参考来源：api/create-1.21.1-6.0.10-280-sources/com/simibubi/create/content/logistics/packager/PackagerVisual.java
 */
public class AutoPackagerVisual extends AbstractBlockEntityVisual<AutoPackagerBlockEntity> implements SimpleDynamicVisual {

    public final TransformedInstance hatch;
    public final TransformedInstance tray;

    public float lastTrayOffset = Float.NaN;
    public PartialModel lastHatchPartial;

    public AutoPackagerVisual(VisualizationContext ctx, AutoPackagerBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        lastHatchPartial = PackagerRenderer.getHatchModel(blockEntity);
        hatch = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(lastHatchPartial))
            .createInstance();

        tray = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(AllPartialModels.PACKAGER_TRAY_REGULAR))
            .createInstance();

        Direction facing = blockState.getValue(PackagerBlock.FACING)
            .getOpposite();

        var lowerCorner = Vec3.atLowerCornerOf(facing.getNormal());
        hatch.setIdentityTransform()
            .translate(getVisualPosition())
            .translate(lowerCorner
                .scale(.49999f))
            .rotateYCenteredDegrees(AngleHelper.horizontalAngle(facing))
            .rotateXCenteredDegrees(AngleHelper.verticalAngle(facing))
            .setChanged();

        animate(partialTick);
    }

    @Override
    public void beginFrame(Context ctx) {
        animate(ctx.partialTick());
    }

    public void animate(float partialTick) {
        var hatchPartial = PackagerRenderer.getHatchModel(blockEntity);

        if (hatchPartial != this.lastHatchPartial) {
            instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(hatchPartial))
                .stealInstance(hatch);

            this.lastHatchPartial = hatchPartial;
        }

        float trayOffset = blockEntity.getTrayOffset(partialTick);

        if (trayOffset != lastTrayOffset) {
            Direction facing = blockState.getValue(PackagerBlock.FACING)
                .getOpposite();

            var lowerCorner = Vec3.atLowerCornerOf(facing.getNormal());

            tray.setIdentityTransform()
                .translate(getVisualPosition())
                .translate(lowerCorner.scale(trayOffset))
                .rotateYCenteredDegrees(facing.toYRot())
                .setChanged();

            lastTrayOffset = trayOffset;
        }
    }

    @Override
    public void updateLight(float partialTick) {
        relight(hatch, tray);
    }

    @Override
    protected void _delete() {
        hatch.delete();
        tray.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {

    }
}
