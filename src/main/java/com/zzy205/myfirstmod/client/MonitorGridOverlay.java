package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 在 Monitor 屏幕表面渲染棋盘网格 + 模块边框 + 放置预览。
 * 使用 Catnip Outliner 渲染 —— 效果与 Create 一致。
 */
public class MonitorGridOverlay {

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderLevel);
        CCPeripheraExtender.LOGGER.info("MonitorGridOverlay registered with Catnip Outliner");
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

        Direction facing = state.getValue(MonitorBlock.FACING);
        GridState grid = null;
        if (level.getBlockEntity(pos) instanceof MonitorBlockEntity be) grid = be.getGridState();
        if (grid == null) grid = new GridState();

        ItemStack held = player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);

        Outliner outliner = Outliner.getInstance();
        int moduleColor = (Config.MONITOR_OUTLINE_A.get() << 24)
                | (Config.MONITOR_OUTLINE_R.get() << 16)
                | (Config.MONITOR_OUTLINE_G.get() << 8)
                | Config.MONITOR_OUTLINE_B.get();

        // 鼠标命中位置 → 网格坐标 → 是否对准已安装模块
        int[] gp = MonitorBlock.worldHitToGrid(pos, facing,
                bhr.getLocation().x, bhr.getLocation().y, bhr.getLocation().z);
        MonitorModule hoveredModule = null;
        if (gp != null) {
            hoveredModule = grid.getModule(grid.getCell(gp[0], gp[1]));
        }

        boolean showGrid = heldType != null;
        boolean showPreview = heldType != null || hoveredModule != null;

        // 1. 网格线（仅手持模块时）
        if (showGrid) {
            drawGridLines(outliner, pos, facing);
        }

        // 2. 放置预览 / 对准高亮
        if (showPreview) {
            if (heldType != null && gp != null) {
                // 手持模块：绿色/红色放置预览
                boolean ok = grid.canPlace(gp[0], gp[1], heldType.width, heldType.height);
                int color = ok ? 0x4CDA64 : 0xFF5E5E;
                drawModuleOutline(outliner, pos, gp[0], gp[1],
                        heldType.width, heldType.height, "preview", color, facing);
            } else if (hoveredModule != null) {
                // 空手对准已安装模块：高亮边框
                drawModuleOutline(outliner, pos, hoveredModule.gridX(), hoveredModule.gridY(),
                        hoveredModule.getWidth(), hoveredModule.getHeight(),
                        "hover", moduleColor, facing);
            }
        }
    }

    /** 颜色提亮（限制不超过 0xFF） */
    private static int brighten(int color, float factor) {
        int r = Math.min(0xFF, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(0xFF, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(0xFF, (int) ((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    // ── 坐标旋转：NORTH 局部 → 世界局部 ──

    private static Vec3 rot(float x, float y, float z, Direction f) {
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        return switch (f) {
            case NORTH -> new Vec3(x, y, z);
            case SOUTH -> new Vec3(2 * c - x, y, 2 * c - z);
            case EAST  -> new Vec3(2 * c - z, y, x);
            case WEST  -> new Vec3(z, y, 2 * c - x);
            default    -> new Vec3(x, y, z);
        };
    }

    private static Vec3 world(BlockPos pos, float x, float y, float z, Direction f) {
        Vec3 r = rot(x, y, z, f);
        return new Vec3(pos.getX() + r.x, pos.getY() + r.y, pos.getZ() + r.z);
    }

    // ── 网格线 ──

    private static void drawGridLines(Outliner o, BlockPos pos, Direction f) {
        float z = MonitorBlock.SCREEN_Z / 16f + 0.05f;
        float x0 = MonitorBlock.SCREEN_X_MIN / 16f;
        float x1 = MonitorBlock.SCREEN_X_MAX / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f;
        float y1 = MonitorBlock.SCREEN_Y_MAX / 16f;

        // 竖线
        for (int i = 0; i <= 14; i++) {
            float x = x0 + i / 16f;
            Vec3 from = world(pos, x, y0, z, f);
            Vec3 to = world(pos, x, y1, z, f);
            o.showLine("grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(1 / 128f);
        }
        // 横线
        for (int i = 0; i <= 12; i++) {
            float y = y0 + i / 16f;
            Vec3 from = world(pos, x0, y, z, f);
            Vec3 to = world(pos, x1, y, z, f);
            o.showLine("grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(1 / 128f);
        }
    }

    // ── 模块/预览矩形边框 ──

    private static void drawModuleOutline(Outliner o, BlockPos pos,
                                           int gx, int gy, int w, int h, String slot, int color, Direction f) {
        float x0 = MonitorBlock.SCREEN_X_MIN / 16f + gx / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f + gy / 16f;
        float x1 = x0 + w / 16f;
        float y1 = y0 + h / 16f;
        float z = MonitorBlock.SCREEN_Z / 16f + 0.05f;

        // 四边
        Vec3 p00 = world(pos, x0, y0, z, f);
        Vec3 p10 = world(pos, x1, y0, z, f);
        Vec3 p11 = world(pos, x1, y1, z, f);
        Vec3 p01 = world(pos, x0, y1, z, f);

        o.showLine(slot + "_top",    p00, p10).colored(color).lineWidth(1 / 64f);
        o.showLine(slot + "_right",  p10, p11).colored(color).lineWidth(1 / 64f);
        o.showLine(slot + "_bottom", p11, p01).colored(color).lineWidth(1 / 64f);
        o.showLine(slot + "_left",   p01, p00).colored(color).lineWidth(1 / 64f);
    }

}
