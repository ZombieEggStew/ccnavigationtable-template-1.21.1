package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 自动打包机的 BER：逻辑照抄 Create 的 {@code PackagerRenderer}（门 hatch + 托盘 tray + 托盘上的包裹）。
 * <p>
 * 唯一改动：托盘模型。<b>不能</b>直接用 {@code PackagerRenderer.getTrayModel(state)} —— 它靠
 * {@code AllBlocks.PACKAGER.has(state)} 判断，我们的方块不是 create:packager，会拿到
 * {@code PACKAGER_TRAY_DEFRAG}（另一种机器的托盘）。这里固定用 {@code PACKAGER_TRAY_REGULAR}，
 * 保证外观与 create:packager 完全一致。
 * <p>
 * 参考来源：api/create-1.21.1-6.0.10-280-sources/com/simibubi/create/content/logistics/packager/PackagerRenderer.java
 */
public class AutoPackagerRenderer extends SmartBlockEntityRenderer<AutoPackagerBlockEntity> {

    public AutoPackagerRenderer(Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(AutoPackagerBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

        ItemStack renderedBox = be.getRenderedBox();
        float trayOffset = be.getTrayOffset(partialTicks);
        BlockState blockState = be.getBlockState();
        Direction facing = blockState.getValue(PackagerBlock.FACING)
            .getOpposite();

        if (!VisualizationManager.supportsVisualization(be.getLevel())) {
            var hatchModel = PackagerRenderer.getHatchModel(be);

            SuperByteBuffer sbb = CachedBuffers.partial(hatchModel, blockState);
            sbb.translate(Vec3.atLowerCornerOf(facing.getNormal())
                    .scale(.49999f))
                .rotateYCenteredDegrees(AngleHelper.horizontalAngle(facing))
                .rotateXCenteredDegrees(AngleHelper.verticalAngle(facing))
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            sbb = CachedBuffers.partial(AllPartialModels.PACKAGER_TRAY_REGULAR, blockState);
            sbb.translate(Vec3.atLowerCornerOf(facing.getNormal())
                    .scale(trayOffset))
                .rotateYCenteredDegrees(facing.toYRot())
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
        }

        if (!renderedBox.isEmpty()) {
            ms.pushPose();
            var msr = TransformStack.of(ms);
            msr.translate(Vec3.atLowerCornerOf(facing.getNormal())
                    .scale(trayOffset))
                .translate(.5f, .5f, .5f)
                .rotateYDegrees(facing.toYRot())
                .translate(0, 2 / 16f, 0)
                .scale(1.49f, 1.49f, 1.49f);
            Minecraft.getInstance()
                .getItemRenderer()
                .renderStatic(null, renderedBox, ItemDisplayContext.FIXED, false, ms, buffer, be.getLevel(), light,
                    overlay, 0);
            ms.popPose();
        }
    }
}
