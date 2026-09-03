package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.ryanhcode.offroad.content.components.TireLike;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2d;

/**
 * 从动轮悬架原版 BER 渲染（无 Flywheel Visual，与 offroad WheelMount 一致）。
 * <p>
 * 参考来源：{@code references/Simulated-Project-main/offroad/.../wheel_mount/WheelMountRenderer.java}。
 * 模型<b>直接复用 offroad 资产</b>（零拷贝）：tele/弹簧/mount 部件 = 跨 namespace 引用
 * {@code offroad:block/wheel_mount/...} 的 partial model；静态底盘由 blockstate 模型渲染
 * （{@code blockstates/trailing_wheel.json} → {@code offroad:block/wheel_mount/block}）。
 * <p>
 * 与 offroad 渲染器的差异（见 {@code memo/wheel-axle-design.md}）：
 * <ul>
 * <li>无 SHAFT_HALF（无传动轴）；</li>
 * <li>无 FilteringRenderer（无悬挂强度滚轮 UI）；</li>
 * <li>无红石转向 yaw 旋转与 diode（首版无转向）；</li>
 * <li>轮子自转 = 从动滚动角 {@link TrailingWheelBlockEntity#getLerpedAngle}。</li>
 * </ul>
 */
public class TrailingWheelRenderer extends SafeBlockEntityRenderer<TrailingWheelBlockEntity> {

    // ── offroad wheel_mount 动态部件（跨 namespace 直接引用，运行时 bundled 必带 offroad） ──
    private static final PartialModel TELE_OUTER = PartialModel.of(ResourceLocation.fromNamespaceAndPath("offroad", "block/wheel_mount/tele_outer"));
    private static final PartialModel TELE_INNER = PartialModel.of(ResourceLocation.fromNamespaceAndPath("offroad", "block/wheel_mount/tele_inner"));
    private static final PartialModel TELE_MOUNT = PartialModel.of(ResourceLocation.fromNamespaceAndPath("offroad", "block/wheel_mount/mount"));
    private static final PartialModel SPRING_UPPER = PartialModel.of(ResourceLocation.fromNamespaceAndPath("offroad", "block/wheel_mount/spring_upper"));
    private static final PartialModel SPRING_MIDDLE = PartialModel.of(ResourceLocation.fromNamespaceAndPath("offroad", "block/wheel_mount/spring_middle"));
    private static final PartialModel SPRING_LOWER = PartialModel.of(ResourceLocation.fromNamespaceAndPath("offroad", "block/wheel_mount/spring_lower"));

    public TrailingWheelRenderer(final BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(final TrailingWheelBlockEntity be, final float partialTicks, final PoseStack ms,
                              final MultiBufferSource buffer, final int light, final int overlay) {
        final VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        final Direction direction = be.getBlockState()
                .getValue(BlockStateProperties.HORIZONTAL_FACING)
                .getOpposite();
        final BlockState blockState = be.getBlockState();

        final SuperByteBuffer teleOuter = CachedBuffers.partial(TELE_OUTER, blockState);
        final SuperByteBuffer teleInner = CachedBuffers.partial(TELE_INNER, blockState);
        final SuperByteBuffer teleMount = CachedBuffers.partial(TELE_MOUNT, blockState);
        final SuperByteBuffer springTop = CachedBuffers.partial(SPRING_UPPER, blockState);
        final SuperByteBuffer springBottom = CachedBuffers.partial(SPRING_LOWER, blockState);
        final SuperByteBuffer springMiddle = CachedBuffers.partial(SPRING_MIDDLE, blockState);

        // 几何常量与 offroad WheelMountRenderer 一致（模型复用 offroad，常量不可改）
        final double wheelPivotOffsetHor = 10.0 / 16.0;
        final double springWheelPivotOffsetHor = 12.0 / 16.0;
        final double springWheelPivotOffsetVer = -2.0 / 16.0;

        final double horizontalWheelPosition = 22.0 / 16.0;
        final double verticalWheelPosition = -be.getLerpedExtension(partialTicks);

        final double teleMountHor = 0.0 / 16.0;
        final double teleMountVer = -6.0 / 16.0;

        final double springMountHor = 7.0 / 16.0;
        final double springMountVer = 7.0 / 16.0;

        final double teleAngle = Math.atan2(verticalWheelPosition - teleMountVer, horizontalWheelPosition - wheelPivotOffsetHor - teleMountHor);
        final double teleDistance = new Vector2d(verticalWheelPosition - teleMountVer, horizontalWheelPosition - wheelPivotOffsetHor - teleMountHor).length();

        final double springAngle = Math.atan2(verticalWheelPosition - springWheelPivotOffsetVer - springMountVer, horizontalWheelPosition - springWheelPivotOffsetHor - springMountHor);
        final double springDistance = new Vector2d(verticalWheelPosition - springWheelPivotOffsetVer - springMountVer, horizontalWheelPosition - springWheelPivotOffsetHor - springMountHor).length();

        ms.pushPose();
        TransformStack.of(ms)
                .center()
                .rotateYDegrees(AngleHelper.horizontalAngle(direction))
                .rotateXDegrees(AngleHelper.verticalAngle(direction))
                .uncenter();

        // telescope（伸缩臂）
        ms.pushPose();
        ms.translate(0.0, -6.0 / 16.0, 0.0);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.XP.rotation((float) teleAngle));
        ms.translate(-0.5, -0.5, -0.5);
        teleOuter.light(light).renderInto(ms, vb);
        ms.translate(0.0, 0.0, -(teleDistance - 1.0));
        teleInner.light(light).renderInto(ms, vb);
        ms.popPose();

        // 轮组（mount + 轮胎）；无转向 → 不绕 pivot 做 yaw 旋转
        ms.pushPose();
        ms.translate(0.0, verticalWheelPosition, 26.0 / 16.0 - horizontalWheelPosition);
        teleMount.light(light).renderInto(ms, vb);

        ms.translate(0.5, 0.5, 0.5);
        ms.translate(0.0, 0.0, -26.0 / 16.0f);

        final double signMultiplier = -be.getLerpedAngle(partialTicks)
                * (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : -1.0)
                * (direction.getAxis() == Direction.Axis.X ? 1.0 : -1.0);

        ms.mulPose(Axis.ZP.rotation((float) signMultiplier));

        final ItemStack itemStack = be.getHeldItem();
        final TireLike tireLike = itemStack.get(OffroadDataComponents.TIRE);
        if (tireLike != null) {
            final Vec3 rotation = tireLike.rotation();
            ms.mulPose(Axis.XP.rotation((float) Math.toRadians(rotation.x)));
            ms.mulPose(Axis.YP.rotation((float) Math.toRadians(rotation.y)));
            ms.mulPose(Axis.ZP.rotation((float) Math.toRadians(rotation.z)));

            if (tireLike.model().isPresent()) {
                final ResourceLocation model = tireLike.model().get();
                ms.translate(tireLike.offset().x, tireLike.offset().y, tireLike.offset().z);
                final SuperByteBuffer wheel = CachedBuffers.partial(PartialModel.of(model), be.getBlockState());
                wheel.light(light)
                        .translate(-0.5f, 0.0f, -0.5f)
                        .renderInto(ms, vb);
            } else {
                ms.translate(tireLike.offset().x, tireLike.offset().y, tireLike.offset().z);
                Minecraft.getInstance().getItemRenderer()
                        .renderStatic(
                                itemStack,
                                ItemDisplayContext.NONE,
                                light,
                                overlay,
                                ms,
                                buffer,
                                be.getLevel(),
                                0
                        );
            }
        }

        ms.popPose();

        // 弹簧
        ms.pushPose();
        ms.translate(0.5, 0.5 + springMountVer, 0.5 - springMountHor);
        ms.mulPose(Axis.XP.rotation((float) springAngle + (float) Math.PI / 2.0f));
        ms.translate(-0.5, -0.5 - springMountVer, -0.5 + springMountHor);

        final float springExtension = (float) springDistance;
        final float springSpan = springExtension - 4.0f / 16.0f;

        springTop.light(light).renderInto(ms, vb);
        springMiddle.light(light)
                .translate(0.0f, 13.0f / 16.0f, 0.0f)
                .scale(1.0f, springSpan / (14.0f / 16.0f), 1.0f)
                .translateBack(0.0f, 13.0f / 16.0f, 0.0f)
                .renderInto(ms, vb);
        springBottom.light(light)
                .translate(0.0, -(springSpan + -14.0 / 16.0), 0.0)
                .renderInto(ms, vb);
        ms.popPose();

        ms.popPose();
    }

    @Override
    public int getViewDistance() {
        return 512;
    }

    /** 大轮胎（radius 可达 2 块）会伸出安装块很远，膨胀渲染盒防视锥剔除 */
    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull TrailingWheelBlockEntity blockEntity) {
        AABB aabb = new AABB(blockEntity.getBlockPos());
        final ItemStack heldItem = blockEntity.getHeldItem();
        final TireLike tireLike = heldItem != null ? heldItem.get(OffroadDataComponents.TIRE) : null;
        if (tireLike != null) {
            aabb = aabb.inflate(tireLike.radius() + 1);
        }
        return aabb;
    }
}
