package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 顶盘（{@code aero_bearing_plate}）的<b>输出半传动杆</b>渲染（原版 BER 路径）。
 * <p>
 * 轴承本体只画背面（输入侧）的 {@code SHAFT_HALF}；顶盘在 sub-level 内位于结构正面，
 * 这里画 FACING 一侧的半轴，与背面半轴合成一根<b>贯穿传动杆</b>，
 * 转速来自应力网络（顶盘已与轴承同网络，输入转速 = 输出转速），故绕 FACING 轴以
 * {@code 转速 × 时间} 旋转。
 * <p>
 * 注意两点：
 * <ul>
 *   <li><b>不套用父类的 Flywheel 早退</b>：顶盘的 BE 位于 sub-level plot 内，Sable 用原版
 *       {@code BlockEntityRenderDispatcher} 分发 sub-level 的 BE 渲染；若沿用
 *       {@code KineticBlockEntityRenderer#renderSafe} 的
 *       {@code VisualizationManager.supportsVisualization(...)} 早退，装了 Flywheel 时这根轴会直接消失。
 *       本 mod 未为顶盘注册 Flywheel Visual（与 swivel 一致：swivel 也只有 BER、没有 Visual），
 *       因此这里始终走 BER 是唯一渲染来源，不会重复绘制。</li>
 *   <li>半轴朝 FACING 侧（结构内部方向），与输入半轴（{@link MyBearingRenderer} 的
 *       {@code facing.getOpposite()}）方向相反、同轴。</li>
 * </ul>
 * 参考来源：{@code references/Simulated-Project-main/.../swivel_bearing/link_block/SwivelBearingPlateBlockRenderer.java}
 * （swivel 顶盘在 FACING 面画 {@code SHAFT_SIXTEENTH}，本项目沿用轴承同一套 {@code SHAFT_HALF} 使贯穿轴截面一致）。
 */
public class MyBearingPlateRenderer extends KineticBlockEntityRenderer<MyBearingPlateBlockEntity> {

    public MyBearingPlateRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(MyBearingPlateBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(MyBearingPlateBlock.FACING);
        BlockPos pos = be.getBlockPos();
        float speed = be.getSpeed();
        float angleDeg = (AnimationTickHolder.getRenderTime(level) * speed * 3f / 10f) % 360;
        angleDeg += getRotationOffsetForPosition(be, pos, facing.getAxis());
        float angle = angleDeg / 180f * (float) Math.PI;

        SuperByteBuffer shaftBuffer = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, facing);
        kineticRotationTransform(shaftBuffer, be, facing.getAxis(), angle, light);
        shaftBuffer.renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }
}
