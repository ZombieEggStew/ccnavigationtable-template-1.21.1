package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.client.MonitorGridOverlay;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Monitor Flywheel 渲染：外壳（bearing + case）与全部模块（底座 + 额外部件）实例化渲染；
 * 背景面板、按钮灯带、屏幕 9 宫格与文字仍留在 BER（Flywheel 无法表达）。
 * <p>
 * 变换与 {@link com.zzy205.myfirstmod.client.MonitorTransform} 完全一致：
 * facing（方块中心 Y）→ offset（沿 facing 前后）→ yaw（颈部 Y）→ pitch（铰链 X，仅 case/模块）；
 * 模块再叠加屏幕表面定位（px,py,pz）+ 初始旋转 + 部件动画。枢轴常量单一来源 {@link MonitorBlock}。
 * <p>
 * 模块按压/旋钮动画状态由本 Visual 持有（{@link #anims}），并经 {@link #ACTIVE_ANIMS} 按 BlockPos
 * 发布给 BER（按钮标签/灯带需要动画深度），与项目「客户端状态按 BlockPos 隔离」的约定一致。
 * BER（{@link MonitorRenderer}）在 Flywheel 可用时跳过外壳与模块模型绘制，避免双渲染。
 */
public class MonitorVisual extends AbstractBlockEntityVisual<MonitorBlockEntity>
        implements SimpleDynamicVisual {

    /** 底座按压深度（块单位），与 MonitorRenderer.PRESS_DEPTH 一致（当前注册的模块类型均不启用） */
    private static final float PRESS_DEPTH = 0.6f;

    /** 供 MonitorRenderer（BER）读取的模块动画值，外层 key=BlockPos，内层 key=moduleId */
    private static final Map<BlockPos, Map<Integer, Float>> ACTIVE_ANIMS = new HashMap<>();

    /** BER 读取模块动画值（按钮标签/灯带深度用）；无 Visual 时返回 null。 */
    public static Float getModuleAnim(BlockPos pos, int moduleId) {
        Map<Integer, Float> anims = ACTIVE_ANIMS.get(pos);
        return anims == null ? null : anims.get(moduleId);
    }

    /** 单模块的两个实例（底座 + 额外部件：按钮头/钮子拉杆/旋钮把手）。 */
    private static final class ModuleVisual {
        final ModuleType type;
        final TransformedInstance base;
        final TransformedInstance extra;

        ModuleVisual(ModuleType type, TransformedInstance base, TransformedInstance extra) {
            this.type = type;
            this.base = base;
            this.extra = extra;
        }

        void delete() {
            base.delete();
            if (extra != null) extra.delete();
        }
    }

    private final TransformedInstance bearing;
    private final TransformedInstance shell;
    /** moduleId → 模块实例 */
    private final Map<Integer, ModuleVisual> modules = new HashMap<>();
    /** 本 BE 的按压/旋钮动画值（单一实现 {@link ModuleRenderBehavior#stepAnim}） */
    private final Map<Integer, Float> anims = new HashMap<>();

    public MonitorVisual(VisualizationContext ctx, MonitorBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.bearing = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(MyModPartialModels.MONITOR_BEARING))
                .createInstance();
        this.shell = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(MyModPartialModels.MONITOR_CASE))
                .createInstance();

        this.transformAll();
    }

    @Override
    public void beginFrame(Context context) {
        this.transformAll();
    }

    private void transformAll() {
        final BlockState state = this.blockEntity.getBlockState();
        final Direction facing = state.getValue(MonitorBlock.FACING);
        final float yaw = this.blockEntity.getYawAngle();
        final float pitch = this.blockEntity.getPitchAngle();
        final int offset = this.blockEntity.getOffset();

        this.bearing.setIdentityTransform();
        this.shell.setIdentityTransform();
        // bearing 不随 pitch，case 叠加 pitch
        this.applyShell(this.bearing, facing, yaw, offset, 0f);
        this.applyShell(this.shell, facing, yaw, offset, pitch);
        this.bearing.setChanged();
        this.shell.setChanged();

        final GridState grid = this.blockEntity.getGridState();
        final BlockPos pos = this.blockEntity.getBlockPos();

        // 同步模块实例：删除已移除的模块
        this.modules.keySet().removeIf(id -> {
            if (!grid.getAllModules().containsKey(id)) {
                this.modules.get(id).delete();
                return true;
            }
            return false;
        });

        // 发布本帧动画值（供 BER 按钮标签/灯带深度读取）
        ACTIVE_ANIMS.put(pos, this.anims);

        for (MonitorModule mod : grid.getAllModules().values()) {
            ModuleVisual mv = this.modules.get(mod.id());
            if (mv == null) {
                mv = this.createModuleVisual(mod.type());
                this.modules.put(mod.id(), mv);
                this.relight(mv);
            }

            var bhv = ModuleRenderBehavior.of(mod.type());
            boolean isKnob = mod.type() == ModuleType.KNOB;

            float px = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + mod.gridX()) / 16f + bhv.offsetX();
            float py = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + mod.gridY()) / 16f + bhv.offsetY();
            float pz = MonitorBlock.SCREEN_Z / 16f + bhv.offsetZ();

            float target;
            if (isKnob) {
                Float visual = MonitorGridOverlay.getActiveKnobVisualAngle(pos, mod.id());
                target = visual != null ? visual : grid.getKnobAngle(mod.id());
            } else {
                target = grid.isPressed(mod.id()) ? 1f : 0f;
            }
            float next = ModuleRenderBehavior.stepAnim(this.anims, mod.id(), isKnob, target,
                    bhv.animPressSpeed(), bhv.animReleaseSpeed());

            // 底座
            mv.base.setIdentityTransform();
            this.applyShell(mv.base, facing, yaw, offset, pitch);
            mv.base.translate(px, py, pz);
            if (bhv.usePressDepth()) mv.base.translate(0f, 0f, PRESS_DEPTH * next / 16f);
            this.applyInitialRotation(mv.base, mod.type());
            mv.base.setChanged();

            // 额外部件：底座变换 + 部件动画（与 ModuleRenderBehavior.renderExtra 一致）
            mv.extra.setIdentityTransform();
            this.applyShell(mv.extra, facing, yaw, offset, pitch);
            mv.extra.translate(px, py, pz);
            if (bhv.usePressDepth()) mv.extra.translate(0f, 0f, PRESS_DEPTH * next / 16f);
            this.applyInitialRotation(mv.extra, mod.type());
            switch (mod.type()) {
                case BUTTON_1X1 -> mv.extra.translate(0f, 0f,
                        ModuleRenderBehavior.ButtonBehavior.PRESS_DEPTH * next / 16f);
                case TOGGLE_SWITCH -> {
                    mv.extra.translate(1 / 32f, 0f, 1 / 32f);
                    mv.extra.rotateX((float) Math.toRadians(-30 + next * 60));
                }
                case KNOB -> mv.extra.rotateY((float) Math.toRadians(-next));
            }
            mv.extra.setChanged();
        }
    }

    /** 平移到位 + facing → offset → yaw → pitch（与 MonitorTransform 的 PoseStack 顺序一致，后调为内层先作用于顶点）。 */
    private void applyShell(TransformedInstance instance, Direction facing, float yaw, int offset, float pitch) {
        instance.translate(this.getVisualPosition());
        instance.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
        if (offset != 0) {
            instance.translate(0f, 0f, -offset / 16f);
        }
        if (yaw != 0f) {
            instance.rotateAround(new Quaternionf().rotateY((float) Math.toRadians(yaw)),
                    MonitorBlock.NECK_X / 16f, 0f, MonitorBlock.NECK_Z / 16f);
        }
        if (pitch != 0f) {
            instance.rotateAround(new Quaternionf().rotateX((float) Math.toRadians(pitch)),
                    0f, MonitorBlock.HINGE_Y / 16f, MonitorBlock.HINGE_Z / 16f);
        }
    }

    /** 模块初始旋转（与 ModuleRenderBehavior.applyInitialRotation 一致）。 */
    private static void applyInitialRotation(TransformedInstance instance, ModuleType type) {
        if (type == ModuleType.TOGGLE_SWITCH || type == ModuleType.KNOB) {
            instance.rotateX((float) Math.toRadians(-90));
        }
    }

    private ModuleVisual createModuleVisual(ModuleType type) {
        PartialModel base = switch (type) {
            case BUTTON_1X1 -> MyModPartialModels.MODULE_BUTTON_BASE;
            case TOGGLE_SWITCH -> MyModPartialModels.MODULE_TOGGLE_BASE;
            case KNOB -> MyModPartialModels.MODULE_KNOB_BASE;
        };
        PartialModel extra = switch (type) {
            case BUTTON_1X1 -> MyModPartialModels.MODULE_BUTTON_HEAD;
            case TOGGLE_SWITCH -> MyModPartialModels.MODULE_TOGGLE_LEVER;
            case KNOB -> MyModPartialModels.MODULE_KNOB_HANDLE;
        };
        TransformedInstance baseInst = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(base)).createInstance();
        TransformedInstance extraInst = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(extra)).createInstance();
        return new ModuleVisual(type, baseInst, extraInst);
    }

    private void relight(ModuleVisual mv) {
        this.relight(mv.base);
        if (mv.extra != null) this.relight(mv.extra);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(this.bearing);
        consumer.accept(this.shell);
        for (ModuleVisual mv : this.modules.values()) {
            consumer.accept(mv.base);
            if (mv.extra != null) consumer.accept(mv.extra);
        }
    }

    @Override
    public void updateLight(float v) {
        this.relight(this.bearing);
        this.relight(this.shell);
        for (ModuleVisual mv : this.modules.values()) this.relight(mv);
    }

    @Override
    protected void _delete() {
        this.bearing.delete();
        this.shell.delete();
        for (ModuleVisual mv : this.modules.values()) mv.delete();
        ACTIVE_ANIMS.remove(this.blockEntity.getBlockPos());
    }
}
