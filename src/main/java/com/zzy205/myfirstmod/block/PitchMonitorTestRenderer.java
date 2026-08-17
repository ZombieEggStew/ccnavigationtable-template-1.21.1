package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.client.PitchMonitorTransform;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Renders the test monitor case as a separately baked model around its hinge axis,
 * plus a simple checkerboard grid on the screen face. The cell under the player's
 * crosshair (ray vs. the sloped/flat front face) is highlighted, to verify that the
 * pitched/yawed surface detects the line of sight correctly.
 */
public class PitchMonitorTestRenderer implements BlockEntityRenderer<PitchMonitorTestBlockEntity> {

    private static final RandomSource RANDOM = RandomSource.create(42L);

    // Grid geometry, matching the test Monitor screen (case front face box(1,3,4,15,15,9)).
    private static final float SCREEN_X_MIN = 1f;
    private static final float SCREEN_X_MAX = 15f;
    private static final float SCREEN_Y_MIN = 3f;
    private static final float SCREEN_Y_MAX = 15f;
    private static final float SCREEN_Z = 4f;
    private static final float GRID_INSET = 1f;
    private static final int GRID_W = 12;
    private static final int GRID_H = 10;

    // Small forward offsets (model pixels) to avoid z-fighting with the case front face.
    private static final float CELL_Z_OFFSET = 0.01f;
    private static final float HOVER_Z_OFFSET = 0.02f;

    /** Translucent, unlit, no-cull color quads for the checkerboard + highlight. */
    private static final RenderType GRID_QUAD = RenderType.create(
            CCPeripheraExtender.MOD_ID + ":pitch_monitor_grid",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false)
    );

    public PitchMonitorTestRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(PitchMonitorTestBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        Direction facing = blockEntity.getBlockState().getValue(PitchMonitorTestBlock.FACING);

        poseStack.pushPose();
        // facing → offset → yaw（外层到内层）
        PitchMonitorTransform.applyFacing(poseStack, facing);
        PitchMonitorTransform.applyOffset(poseStack, blockEntity.getOffset());
        PitchMonitorTransform.applyYaw(poseStack, blockEntity.getYawAngle());

        // bearing：只受 yaw，不随 pitch
        BakedModel bearingModel = MonitorPreloadedModels.getPitchTestBearing();
        if (bearingModel != null) {
            renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), bearingModel, light, overlay);
        }

        // case + 棋盘：受 yaw + pitch
        PitchMonitorTransform.applyPitch(poseStack, blockEntity.getPitchAngle());

        BakedModel caseModel = MonitorPreloadedModels.getPitchTestCase();
        if (caseModel != null) {
            renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), caseModel, light, overlay);
        }

        // Grid is drawn inside the same (facing/yaw/pitch) pose so it follows the screen face.
        int[] hovered = computeHoveredCell(blockEntity, partialTick);
        renderGrid(poseStack, buffer, hovered);

        poseStack.popPose();
    }

    /**
     * Casts the crosshair ray against the (pitched/yawed or flat) screen front face.
     * Inverse-transforms the ray back into model space (facing逆 → yaw逆 → pitch逆),
     * then maps the hit point to grid coordinates.
     *
     * @return [gx, gy] or null when the crosshair is not over the grid.
     */
    private int[] computeHoveredCell(PitchMonitorTestBlockEntity blockEntity, float partialTick) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null) return null;

        Vec3 origin = player.getEyePosition(partialTick);
        Vec3 dir = player.getViewVector(partialTick);

        // Sable 子次元兼容：blockEntity.getBlockPos() 是子次元局部（plot）坐标，
        // 因此把世界空间的视线射线投影回 plot 空间，与 Sable 的 clip mixin 同口径。
        var subLevel = SableCompat.getContainingSubLevel(blockEntity);
        if (subLevel != null) {
            origin = SableCompat.toLocalPosition(subLevel, partialTick, origin);
            dir = SableCompat.toLocalDirection(subLevel, partialTick, dir);
        }

        Vec3 block = Vec3.atLowerCornerOf(blockEntity.getBlockPos());
        double[] o = { origin.x - block.x, origin.y - block.y, origin.z - block.z };
        double[] d = { dir.x, dir.y, dir.z };

        Direction facing = blockEntity.getBlockState().getValue(PitchMonitorTestBlock.FACING);
        PitchMonitorTransform.inverseToModel(o, d, facing, blockEntity.getYawAngle(),
                blockEntity.getPitchAngle(), blockEntity.getOffset());

        double planeZ = SCREEN_Z / 16.0;
        if (Math.abs(d[2]) < 1e-6) return null;
        double t = (planeZ - o[2]) / d[2];
        if (t < 0) return null;

        double hx = o[0] + t * d[0];   // x 不受 pitch 旋转影响
        double hy = o[1] + t * d[1];

        double px = hx * 16.0;
        double py = hy * 16.0;
        if (px < SCREEN_X_MIN + GRID_INSET || px > SCREEN_X_MAX - GRID_INSET) return null;
        if (py < SCREEN_Y_MIN + GRID_INSET || py > SCREEN_Y_MAX - GRID_INSET) return null;

        int gx = (int) Math.floor(px - SCREEN_X_MIN - GRID_INSET);
        int gy = (int) Math.floor(py - SCREEN_Y_MIN - GRID_INSET);
        if (gx < 0 || gx >= GRID_W || gy < 0 || gy >= GRID_H) return null;
        return new int[]{gx, gy};
    }

    /** Draws the always-on checkerboard and the hovered-cell highlight on the screen face. */
    private static void renderGrid(PoseStack poseStack, MultiBufferSource buffer, int[] hovered) {
        VertexConsumer vc = buffer.getBuffer(GRID_QUAD);
        PoseStack.Pose pose = poseStack.last();
        float cellSize = 1f / 16f;
        float cellZ = (SCREEN_Z - CELL_Z_OFFSET) / 16f;

        for (int gx = 0; gx < GRID_W; gx++) {
            for (int gy = 0; gy < GRID_H; gy++) {
                float x0 = (SCREEN_X_MIN + GRID_INSET + gx) / 16f;
                float y0 = (SCREEN_Y_MIN + GRID_INSET + gy) / 16f;
                float x1 = x0 + cellSize;
                float y1 = y0 + cellSize;
                if (((gx + gy) & 1) == 0) {
                    quad(vc, pose, x0, y0, x1, y1, cellZ, 1f, 1f, 1f, 0.4f);
                } else {
                    quad(vc, pose, x0, y0, x1, y1, cellZ, 0f, 0f, 0f, 0.4f);
                }
            }
        }

        if (hovered != null) {
            float x0 = (SCREEN_X_MIN + GRID_INSET + hovered[0]) / 16f;
            float y0 = (SCREEN_Y_MIN + GRID_INSET + hovered[1]) / 16f;
            float z = (SCREEN_Z - HOVER_Z_OFFSET) / 16f;
            quad(vc, pose, x0, y0, x0 + cellSize, y0 + cellSize, z, 0.25f, 1f, 0.35f, 0.85f);
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x0, float y0, float x1, float y1, float z,
                             float r, float g, float b, float a) {
        vc.addVertex(pose, x0, y0, z).setColor(r, g, b, a);
        vc.addVertex(pose, x0, y1, z).setColor(r, g, b, a);
        vc.addVertex(pose, x1, y1, z).setColor(r, g, b, a);
        vc.addVertex(pose, x1, y0, z).setColor(r, g, b, a);
    }

    private static void renderModel(PoseStack poseStack, VertexConsumer consumer, BakedModel model,
                                    int light, int overlay) {
        var pose = poseStack.last();
        for (Direction direction : Direction.values()) {
            for (var quad : model.getQuads(null, direction, RANDOM, ModelData.EMPTY, null)) {
                consumer.putBulkData(pose, quad, 1, 1, 1, 1, light, overlay);
            }
        }
        for (var quad : model.getQuads(null, null, RANDOM, ModelData.EMPTY, null)) {
            consumer.putBulkData(pose, quad, 1, 1, 1, 1, light, overlay);
        }
    }
}
