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
import net.minecraft.util.Mth;
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
        // joystick_2 / throttle / monitor_2 已接入棋盘自由放置：实物预览跟随各自 3D 预览盒（见下方盒位平移）
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return;
        if (desk.isInstalled(type)) return; // 已装该控件：不显示半透明模型（红色线框由 overlay 负责）

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        // 预览盒中心与放置常量：joystick_2 跟随准星吸附到 1px 网格；throttle / monitor_2 为唯一合法位 (8,12)（14×6 全占网格）；
        // PEDAL / JOYSTICK 无放置盒（固定安装位）→ box 保持 null（不平移、不绕盒心旋转）
        int[] box = null;
        float modelCenter = 0f;
        int halfX = 0, halfZ = 0;
        if (type == ControlDeskBlockEntity.ControlType.JOYSTICK_2) {
            box = ControlDeskBlock.snappedBoxCenter(pos, facing, hit.getLocation());
            modelCenter = ControlDeskBlockEntity.JOYSTICK_2_MODEL_CENTER;
            halfX = halfZ = ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF;
        } else if (type == ControlDeskBlockEntity.ControlType.THROTTLE) {
            box = new int[]{ControlDeskBlockEntity.THROTTLE_PLACE_X, ControlDeskBlockEntity.THROTTLE_PLACE_Z};
            modelCenter = ControlDeskBlockEntity.THROTTLE_MODEL_CENTER;
            halfX = ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X;
            halfZ = ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z;
        } else if (type == ControlDeskBlockEntity.ControlType.THROTTLE_2) {
            box = new int[]{ControlDeskBlockEntity.THROTTLE_2_PLACE_X, ControlDeskBlockEntity.THROTTLE_2_PLACE_Z};
            modelCenter = ControlDeskBlockEntity.THROTTLE_2_MODEL_CENTER;
            halfX = ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X;
            halfZ = ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z;
        } else if (type == ControlDeskBlockEntity.ControlType.MONITOR_2) {
            box = new int[]{ControlDeskBlockEntity.MONITOR_2_PLACE_X, ControlDeskBlockEntity.MONITOR_2_PLACE_Z};
            modelCenter = ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER;
            halfX = ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X;
            halfZ = ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z;
        }
        // 候选位置被占地矩形占用重叠 → 不显示实物（盒子在 overlay 中变红）
        if (box != null && desk.blocksPlacement(box[0], box[1], halfX, halfZ)) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType renderType = RenderType.translucentMovingBlock();

        ms.pushPose();
        ms.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        // 实物预览：模型平移到盒位（模型默认中心 x/z=8、底座底 y=0 → 放置位底 y=7，见 ControlDeskBlockEntity 常量）；
        // 安装朝向旋转绕盒子中心进行（模型已到盒位，绕盒心转才不甩开）。
        // 安装朝向旋转预览：与实装同公式（桌体 FACING + 桌→玩家水平方向；joystick_2 90° 间隔、throttle 只能 0°/180°、
        // monitor_2 不面向玩家 → 0）
        Direction toPlayer = ControlDeskBlock.directionFromDeskTo(mc.player, pos);
        int previewRot = switch (type) {
            // joystick_2 在「-Z 面向玩家」基础上加固定 +90° 偏移（模型默认朝向差 90°，见 rotationToFace2）
            case JOYSTICK_2 -> ControlDeskBlockEntity.rotationToFace2(facing, toPlayer);
            // throttle / throttle_2 只能 0°/180°（照抄 throttle）
            case THROTTLE, THROTTLE_2 -> ControlDeskBlockEntity.rotationToFace180(facing, toPlayer);
            default -> 0;
        };
        float boxX = box != null ? box[0] / 16f : 0f;
        float boxZ = box != null ? box[1] / 16f : 0f;
        float shiftX = box != null ? (box[0] - modelCenter) / 16f : 0f;
        // 模型坐桌面（y8，不下沉；仅预览盒下沉 1px），MODEL_BOTTOM_Y 三个模块均 0
        float shiftY = box != null
                ? (ControlDeskBlockEntity.MODEL_PLACE_Y - ControlDeskBlockEntity.JOYSTICK_2_MODEL_BOTTOM_Y) / 16f : 0f;
        float shiftZ = box != null ? (box[1] - modelCenter) / 16f : 0f;
        for (PartialModel model : partsOf(type)) {
            SuperByteBuffer buffer = CachedBuffers.partial(model, state);
            // 与 BER/Flywheel 同一朝向约定：绕方块中心 Y 旋转到 FACING（rotateCenteredDegrees = 绕 buffer 中心）
            buffer.rotateCenteredDegrees(-facing.getOpposite().toYRot(), Direction.UP);
            if (previewRot != 0) {
                // 绕盒子中心做安装朝向旋转（Y 旋转，枢轴 y 值不影响结果）
                buffer.translate(boxX, 0.5f, boxZ);
                buffer.rotate(Mth.DEG_TO_RAD * previewRot, Direction.UP);
                buffer.translate(-boxX, -0.5f, -boxZ);
            }
            if (box != null) {
                // 平移到盒位（最后调用 = 最内层变换，先于 facing/安装旋转作用于模型空间）
                buffer.translate(shiftX, shiftY, shiftZ);
            }
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
            case THROTTLE_2 -> new PartialModel[]{
                    MyModPartialModels.CONTROL_DESK_THROTTLE_2_BASE,
                    MyModPartialModels.CONTROL_DESK_THROTTLE_2_HANDLE};
            case JOYSTICK_2 -> new PartialModel[]{
                    MyModPartialModels.CONTROL_DESK_JOYSTICK_2_BASE,
                    MyModPartialModels.CONTROL_DESK_JOYSTICK_2_HANDLE};
        };
    }
}
