package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/**
 * 在 Monitor 屏幕表面渲染棋盘网格 + 模块边框 + 放置预览。
 * 参照 Control-Panels：AFTER_BLOCK_ENTITIES + RenderType.lines() + LevelRenderer.renderShape()。
 */
public class MonitorGridOverlay {

    private static boolean LOGGED = false;
    /** 调试：每秒输出一次日志，避免刷屏 */
    private static long lastDebugTick = 0;
    private static final long DEBUG_INTERVAL_MS = 1000;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderLevel);
        CCPeripheraExtender.LOGGER.info("MonitorGridOverlay registered on NeoForge.EVENT_BUS");
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr)) return;

        BlockPos pos = bhr.getBlockPos();
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof MonitorBlock)) return;

        if (!LOGGED) {
            CCPeripheraExtender.LOGGER.info("MonitorGridOverlay FIRED! pos={}", pos);
            LOGGED = true;
        }

        Direction facing = state.getValue(MonitorBlock.FACING);
        GridState grid = null;
        if (level.getBlockEntity(pos) instanceof MonitorBlockEntity be) grid = be.getGridState();
        if (grid == null) grid = new GridState();

        ItemStack held = player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);

        // ═══ 调试日志 ═══
        long now = System.currentTimeMillis();
        if (now - lastDebugTick > DEBUG_INTERVAL_MS) {
            lastDebugTick = now;
            CCPeripheraExtender.LOGGER.info("[MonitorDebug] held={} type={} facing={} hitPos=({},{},{})",
                    held.isEmpty() ? "EMPTY" : held.getItem(),
                    heldType,
                    facing,
                    String.format("%.3f", bhr.getLocation().x),
                    String.format("%.3f", bhr.getLocation().y),
                    String.format("%.3f", bhr.getLocation().z));
        }

        var poseStack = event.getPoseStack();
        var cam = event.getCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        Matrix4f mat = poseStack.last().pose();

        MultiBufferSource.BufferSource bufSource = mc.renderBuffers().bufferSource();
        VertexConsumer vc = bufSource.getBuffer(RenderType.lines());

        // 1. 网格线
        drawGridLines(vc, mat, facing);

        // 2. 已放置模块边框
        if (!grid.isEmpty()) {
            drawModuleOutlines(poseStack, vc, mat, grid, facing);
        }

        // 3. 放置预览
        if (heldType != null) {
            int[] gp = MonitorBlock.worldHitToGrid(pos, facing,
                    bhr.getLocation().x, bhr.getLocation().y, bhr.getLocation().z);

            // ═══ 调试日志 ═══
            if (now - lastDebugTick <= DEBUG_INTERVAL_MS + 100) { // 在上面的日志同一帧
                CCPeripheraExtender.LOGGER.info("[MonitorDebug] worldHitToGrid={} type={}x{}",
                        gp == null ? "NULL" : String.format("[%d,%d]", gp[0], gp[1]),
                        heldType.width, heldType.height);
            }

            if (gp != null) {
                boolean ok = grid.canPlace(gp[0], gp[1], heldType.width, heldType.height);
                CCPeripheraExtender.LOGGER.info("[MonitorDebug] canPlace={} drawing preview at ({},{}) {}x{}",
                        ok, gp[0], gp[1], heldType.width, heldType.height);

                drawPreview(poseStack, vc, mat, gp[0], gp[1],
                        heldType.width, heldType.height, ok, facing);
            } else if (now - lastDebugTick <= DEBUG_INTERVAL_MS + 100) {
                CCPeripheraExtender.LOGGER.info("[MonitorDebug] worldHitToGrid returned NULL — check screen constants or hit location");
            }
        }

        poseStack.popPose();
    }

    // ── 旋转（局部→方块空间）──

    private static void rot(float[] out, float x, float y, float z, Direction f) {
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        switch (f) {
            case NORTH: out[0]=x; out[1]=y; out[2]=z; break;
            case SOUTH: out[0]=2*c-x; out[1]=y; out[2]=2*c-z; break;
            case EAST:  out[0]=2*c-z; out[1]=y; out[2]=x; break;
            case WEST:  out[0]=z; out[1]=y; out[2]=2*c-x; break;
            default:    out[0]=x; out[1]=y; out[2]=z;
        }
    }

    // ── 网格线（手动线段）──

    private static void drawGridLines(VertexConsumer vc, Matrix4f mat, Direction f) {
        // float z = MonitorBlock.SCREEN_Z / 16f + 0.05f;
        float z = MonitorBlock.SCREEN_Z / 16f;
        float x0 = MonitorBlock.SCREEN_X_MIN / 16f;
        float x1 = MonitorBlock.SCREEN_X_MAX / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f;
        float y1 = MonitorBlock.SCREEN_Y_MAX / 16f;
        float[] a = new float[3], b = new float[3];
        // 竖线
        for (int i = 0; i <= 14; i++) {
            float x = x0 + i / 16f;
            rot(a, x, y0, z, f);
            rot(b, x, y1, z, f);
            vc.addVertex(mat, a[0], a[1], a[2]).setColor(1.0f, 1.0f, 1.0f, 0.3f).setNormal(0, 0, 1);
            vc.addVertex(mat, b[0], b[1], b[2]).setColor(1.0f, 1.0f, 1.0f, 0.3f).setNormal(0, 0, 1);
        }
        // 横线
        for (int i = 0; i <= 12; i++) {
            float y = y0 + i / 16f;
            rot(a, x0, y, z, f);
            rot(b, x1, y, z, f);
            vc.addVertex(mat, a[0], a[1], a[2]).setColor(1.0f, 1.0f, 1.0f, 0.3f).setNormal(0, 0, 1);
            vc.addVertex(mat, b[0], b[1], b[2]).setColor(1.0f, 1.0f, 1.0f, 0.3f).setNormal(0, 0, 1);
        }
    }

    // ── 已放置模块边框（与网格线同方式：rot + 手动线段）──

    private static void drawModuleOutlines(PoseStack ps, VertexConsumer vc, Matrix4f mat,
                                            GridState grid, Direction f) {
        for (var mod : grid.getAllModules().values()) {
            int col = getColor(mod.id());
            float r = ((col>>16)&0xFF)/255f, g = ((col>>8)&0xFF)/255f, b = (col&0xFF)/255f;
            float x0 = MonitorBlock.SCREEN_X_MIN / 16f + mod.gridX() / 16f;
            float y0 = MonitorBlock.SCREEN_Y_MIN / 16f + mod.gridY() / 16f;
            float x1 = x0 + mod.getWidth() / 16f;
            float y1 = y0 + mod.getHeight() / 16f;
            drawRectOutline(vc, mat, x0, y0, x1, y1, MonitorBlock.SCREEN_Z / 16f, r, g, b, 0.5f, f);
        }
    }

    // ── 放置预览（与网格线同方式）──

    private static void drawPreview(PoseStack ps, VertexConsumer vc, Matrix4f mat,
                                     int gx, int gy, int w, int h, boolean ok, Direction f) {
        float x0 = MonitorBlock.SCREEN_X_MIN / 16f + gx / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f + gy / 16f;
        float x1 = x0 + w / 16f;
        float y1 = y0 + h / 16f;
        // 在网格面上方略微偏移，确保可见
        float z = MonitorBlock.SCREEN_Z / 16f;
        float r = ok ? 0f : 1f, green = ok ? 1f : 0f;
        drawRectOutline(vc, mat, x0, y0, x1, y1, z, r, green, 0f, 0.9f, f);
    }

    /** 绘制矩形四边（与网格线相同的 rot 方式）。 */
    private static void drawRectOutline(VertexConsumer vc, Matrix4f mat,
                                         float x0, float y0, float x1, float y1, float z,
                                         float r, float g, float b, float a, Direction f) {
        float[] p0 = new float[3], p1 = new float[3], p2 = new float[3], p3 = new float[3];
        rot(p0, x0, y0, z, f);
        rot(p1, x1, y0, z, f);
        rot(p2, x1, y1, z, f);
        rot(p3, x0, y1, z, f);
        vc.addVertex(mat, p0[0], p0[1], p0[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p1[0], p1[1], p1[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p1[0], p1[1], p1[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p2[0], p2[1], p2[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p2[0], p2[1], p2[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p3[0], p3[1], p3[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p3[0], p3[1], p3[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(mat, p0[0], p0[1], p0[2]).setColor(r, g, b, a).setNormal(0, 0, 1);
    }

    private static int getColor(int id) {
        int[] pal = {0x5A8F3C, 0x3C7ABF, 0xBF8F3C, 0x8F3CBF, 0xBF3C5A, 0x3CBFBF, 0xBFBF3C, 0xBF6F3C};
        return pal[id % pal.length];
    }
}
