package com.zzy205.myfirstmod.block;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * 航空集成计算机（AIC）Flywheel 渲染：一个 {@link OrientedInstance}（可旋转罗盘）。
 * <p>
 * 变换链（与 BER 一致）：罗盘先平移到局部位置 {@link AicBlock#COMPASS_POS}，
 * 再绕该点旋转姿态 Q，最后整体应用 blockstate facing 旋转（绕方块中心）。
 * 等价于 {@code T(corner + c + R·(P−c))·R(R·Q)}（c = 方块中心），
 * 即实例 position = 方块角 + facing 后的罗盘位置、rotation = facingRot·Q。
 */
public class AicVisual extends AbstractBlockEntityVisual<AicBlockEntity>
        implements SimpleDynamicVisual {

    /** 方块中心（blockstate 旋转绕此点） */
    private static final Vector3f CENTER = new Vector3f(0.5f, 0.5f, 0.5f);

    private final OrientedInstance compass;

    public AicVisual(final VisualizationContext ctx, final AicBlockEntity blockEntity, final float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.compass = this.instancerProvider().instancer(InstanceTypes.ORIENTED,
                        Models.partial(MyModPartialModels.AIC_COMPASS))
                .createInstance()
                .position(this.getVisualPosition())
                .translatePivot(-0.5f, -0.5f, -0.5f); // pivot → (0,0,0)，旋转中心 = position
    }

    @Override
    public void beginFrame(final Context context) {
        final Quaternionf base = this.blockEntity.getBaseQuaternion(); // blockstate facing（绕方块中心）

        // 罗盘位置经 facing 旋转：P' = c + R·(P − c)
        final Vector3f p = new Vector3f(AicBlock.COMPASS_POS).sub(CENTER).rotate(base).add(CENTER);
        final BlockPos pos = this.getVisualPosition();
        this.compass.position(pos.getX() + p.x, pos.getY() + p.y, pos.getZ() + p.z);

        // 姿态：base·Y·Z·X（偏航→滚转→俯仰，与 INS 同序）
        final Quaternionf q = new Quaternionf(base);
        this.blockEntity.applyCompassQuaternion(q, context.partialTick());
        this.blockEntity.applyPrimaryQuaternion(q, context.partialTick());
        this.blockEntity.applySecondaryQuaternion(q, context.partialTick());
        this.compass.rotation(q);
        this.compass.setChanged();
    }

    @Override
    public void collectCrumblingInstances(final Consumer<Instance> consumer) {
        consumer.accept(this.compass);
    }

    @Override
    public void updateLight(final float v) {
        this.relight(this.compass);
    }

    @Override
    protected void _delete() {
        this.compass.delete();
    }
}
