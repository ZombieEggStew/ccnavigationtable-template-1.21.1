package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.MyModPartialModels;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 控件安装<b>半透明模型预览</b>（参考 aeroworks {@code SocketPlacementClient#onRenderLevelStage}）：
 * 手持控件物品 + 准星指向 controlDesk 且该位置<b>可安装</b>（未装该控件类型）时，
 * 在安装位渲染该控件的半透明模型 —— {@code RenderType.translucentMovingBlock()} +
 * {@code SuperByteBuffer.color(255,255,255,110)} + 固定光照 {@code 0xF000F0}。
 * 与 {@link ControlDeskPlacementOverlay} 的 AABB 线框预览<b>共存</b>（线框 + 半透明模型，对齐 aeroworks 的
 * socket 线框 + ghost 模型方案）；已安装位置仍只显示红色线框（由 overlay 负责），不叠加半透明模型。
 * <p>
 * 变换与 {@link ControlDeskRenderer 安装渲染}同一朝向约定：模型按与底座相同的方块空间（北向）建模，
 * PoseStack 平移到方块原点后绕方块中心 Y 旋转到 FACING（{@code rotateCenteredDegrees}）。
 */
public class ControlDeskGhostPreviewRenderer {

    /** 半透明 alpha（aeroworks GHOST_ALPHA = 160） */
    private static final int GHOST_ALPHA = 160;
    /** 固定光照（block + sky 满亮），半透明预览不受世界光照影响 */
    private static final int GHOST_LIGHT = 0xF000F0;

    private ControlDeskGhostPreviewRenderer() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ControlDeskGhostPreviewRenderer::onRenderLevelStage);
    }

    @SubscribeEvent
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        ItemStack held = mc.player.getMainHandItem();
        ControlDeskBlockEntity.ControlType type = ControlDeskPlacementOverlay.controlTypeOf(held);
        if (type == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return;
        if (desk.isInstalled(type)) return; // 已装该控件：不显示半透明模型（红色线框由 overlay 负责）

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType renderType = RenderType.translucentMovingBlock();

        ms.pushPose();
        ms.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        for (PartialModel model : partsOf(type)) {
            SuperByteBuffer buffer = CachedBuffers.partial(model, state);
            // 与 BER/Flywheel 同一朝向约定：绕方块中心 Y 旋转到 FACING（rotateCenteredDegrees = 绕 buffer 中心）
            buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
            buffer.color(255, 255, 255, GHOST_ALPHA);
            buffer.light(GHOST_LIGHT);
            buffer.renderInto(ms, buffers.getBuffer(renderType));
        }
        ms.popPose();
        buffers.endBatch(renderType);
    }

    /** 控件类型 → 安装后渲染的全部部件（底座 → 本体，与 {@link ControlDeskRenderer} 安装渲染一致）。 */
    private static PartialModel[] partsOf(ControlDeskBlockEntity.ControlType type) {
        return switch (type) {
            case PEDAL -> new PartialModel[]{
                    MyModPartialModels.CONTROL_DESK_PEDAL_BASE,
                    MyModPartialModels.CONTROL_DESK_PEDAL,
                    MyModPartialModels.CONTROL_DESK_PEDAL_RIGHT};
            case JOYSTICK -> new PartialModel[]{
                    MyModPartialModels.CONTROL_DESK_JOYSTICK_BASE,
                    MyModPartialModels.CONTROL_DESK_JOYSTICK};
            case MONITOR_2 -> new PartialModel[]{MyModPartialModels.CONTROL_DESK_MONITOR_2};
            case THROTTLE -> new PartialModel[]{
                    MyModPartialModels.CONTROL_DESK_THROTTLE_BASE,
                    MyModPartialModels.CONTROL_DESK_THROTTLE_HANDLE,
                    MyModPartialModels.CONTROL_DESK_THROTTLE_INDICATOR};
            case JOYSTICK_2 -> new PartialModel[]{
                    MyModPartialModels.CONTROL_DESK_JOYSTICK_2_BASE,
                    MyModPartialModels.CONTROL_DESK_JOYSTICK_2_HANDLE};
        };
    }
}
