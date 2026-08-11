package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.ModuleKnobRotatePayload;
import com.zzy205.myfirstmod.network.ModulePressPayload;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 在 Monitor 屏幕表面渲染棋盘网格 + 模块边框 + 放置预览。
 * 使用 Catnip Outliner 渲染 —— 效果与 Create 一致。
 */
public class MonitorGridOverlay {

    /** 客户端按下状态追踪 */
    private static int pressingModuleId = -1;
    private static BlockPos pressingPos = null;
    /** 钮子开关防连发：记录上次触发的 moduleId，松开右键后清除 */
    private static int toggleFiredId = -1;

    /** 旋钮拖拽状态 */
    private static boolean knobDragging = false;
    private static BlockPos knobDragPos = null;
    private static Direction knobDragFacing = null;
    private static int knobDragModuleId = -1;
    private static float knobAccumAngle = 0f;
    /** 旋钮中心在屏幕局部坐标（1/16 格单位），由 drag start 计算 */
    private static float knobCenterX = 0f;
    private static float knobCenterY = 0f;
    /** 上一帧的 raw atan2 角度（弧度），用于跨象限解缠绕 */
    private static float knobPrevRawAngle = 0f;
    /** 解缠绕后的累计角度增量（弧度），可超过 2π */
    private static float knobUnwrappedDelta = 0f;
    private static int knobSendCooldown = 0;
    private static final int KNOB_SEND_INTERVAL = 2;   // 每 2 tick 发送一次

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onClientTick);
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

        // 鼠标命中位置 → 网格坐标（射线与屏幕平面求交，+0.025 内凹）
        int[] gp = MonitorBlock.rayToGrid(pos, facing,
                player.getEyePosition((float) event.getPartialTick().getGameTimeDeltaTicks()),
                player.getViewVector((float) event.getPartialTick().getGameTimeDeltaTicks()));
        MonitorModule hoveredModule = null;
        if (gp != null) {
            hoveredModule = grid.getModule(grid.getCell(gp[0], gp[1]));
        }

        boolean showGrid = heldType != null;
        boolean showPreview = heldType != null || hoveredModule != null;

        // ── 按钮按下/释放检测 ──
        boolean useDown = mc.options.keyUse.isDown();
        boolean isToggle = hoveredModule != null && hoveredModule.type() == ModuleType.TOGGLE_SWITCH;

        if (hoveredModule != null && useDown && heldType == null) {
            if (isToggle) {
                // 钮子开关：右键触发切换，松开右键后才允许再次触发
                if (toggleFiredId != hoveredModule.id()) {
                    PacketDistributor.sendToServer(new ModulePressPayload(pos, hoveredModule.id(), true));
                    toggleFiredId = hoveredModule.id();
                }
            } else if (pressingModuleId < 0) {
                // 按钮：按住即按下
                PacketDistributor.sendToServer(new ModulePressPayload(pos, hoveredModule.id(), true));
                pressingModuleId = hoveredModule.id();
                pressingPos = pos;
            }
        }

        // 按钮释放
        if (pressingModuleId >= 0) {
            boolean sameModule = hoveredModule != null && hoveredModule.id() == pressingModuleId
                    && pressingPos != null && pos.equals(pressingPos);
            if (!useDown || !sameModule) {
                PacketDistributor.sendToServer(new ModulePressPayload(
                        pressingPos != null ? pressingPos : pos, pressingModuleId, false));
                pressingModuleId = -1;
                pressingPos = null;
            }
        }

        // 钮子开关松开右键→清除防连发
        if (!useDown) toggleFiredId = -1;

        // ── 旋钮拖拽 ──
        if (hoveredModule != null && hoveredModule.type() == ModuleType.KNOB
                && useDown && heldType == null) {
            if (!knobDragging) {
                knobDragging = true;
                knobDragPos = pos;
                knobDragFacing = facing;
                knobDragModuleId = hoveredModule.id();
                // 旋钮中心（屏幕局部坐标，1/16 格单位）
                knobCenterX = MonitorBlock.SCREEN_X_MIN + hoveredModule.gridX() + hoveredModule.getWidth() / 2f;
                knobCenterY = MonitorBlock.SCREEN_Y_MIN + hoveredModule.gridY() + hoveredModule.getHeight() / 2f;
                var be = level.getBlockEntity(pos);
                if (be instanceof MonitorBlockEntity monitorBE) {
                    knobAccumAngle = monitorBE.getGridState().getKnobAngle(hoveredModule.id());
                }
                // 按下瞬间准心角度（弧度），作为解缠绕起点
                knobPrevRawAngle = computeCrosshairAngle(player, pos, facing);
                knobUnwrappedDelta = 0f;
            }
        } else if (knobDragging && !useDown) {
            knobDragging = false;
            knobDragPos = null;
            knobDragModuleId = -1;
        }

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
        float lw = (float) (1 / 128f * Config.MONITOR_GRID_LINE_WIDTH.get());

        for (int i = 0; i <= 14; i++) {
            float x = x0 + i / 16f;
            Vec3 from = world(pos, x, y0, z, f);
            Vec3 to = world(pos, x, y1, z, f);
            o.showLine("grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= 12; i++) {
            float y = y0 + i / 16f;
            Vec3 from = world(pos, x0, y, z, f);
            Vec3 to = world(pos, x1, y, z, f);
            o.showLine("grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
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
        float lw = (float) (1 / 128f * Config.MONITOR_OUTLINE_LINE_WIDTH.get());

        Vec3 p00 = world(pos, x0, y0, z, f);
        Vec3 p10 = world(pos, x1, y0, z, f);
        Vec3 p11 = world(pos, x1, y1, z, f);
        Vec3 p01 = world(pos, x0, y1, z, f);

        o.showLine(slot + "_top",    p00, p10).colored(color).lineWidth(lw);
        o.showLine(slot + "_right",  p10, p11).colored(color).lineWidth(lw);
        o.showLine(slot + "_bottom", p11, p01).colored(color).lineWidth(lw);
        o.showLine(slot + "_left",   p01, p00).colored(color).lineWidth(lw);
    }

    // ── 旋钮拖拽：准心绕旋钮中心旋转 → 旋钮跟随 ──

    /** 计算玩家准心在屏幕平面上相对旋钮中心的角度（弧度） */
    private static float computeCrosshairAngle(Player player,
                                                BlockPos pos, Direction facing) {
        // 与 MonitorBlock.rayToGrid 相同的射线-平面求交
        float c = MonitorBlock.ROT_ORIGIN / 16f;
        float planeZ = MonitorBlock.SCREEN_Z / 16f + 0.025f;
        Vec3 eyePos = player.getEyePosition(1f);
        Vec3 lookVec = player.getViewVector(1f);

        Vec3 normal, point;
        switch (facing) {
            case NORTH:
                normal = new Vec3(0, 0, 1);
                point = new Vec3(pos.getX() + c, pos.getY() + c, pos.getZ() + planeZ);
                break;
            case SOUTH:
                normal = new Vec3(0, 0, -1);
                point = new Vec3(pos.getX() + c, pos.getY() + c, pos.getZ() + (1 - planeZ));
                break;
            case EAST:
                normal = new Vec3(-1, 0, 0);
                point = new Vec3(pos.getX() + (1 - planeZ), pos.getY() + c, pos.getZ() + c);
                break;
            case WEST:
                normal = new Vec3(1, 0, 0);
                point = new Vec3(pos.getX() + planeZ, pos.getY() + c, pos.getZ() + c);
                break;
            default: return 0f;
        }

        double denom = lookVec.dot(normal);
        if (Math.abs(denom) < 1e-6) return 0f;
        double t = point.subtract(eyePos).dot(normal) / denom;
        if (t < 0) return 0f;

        Vec3 hit = eyePos.add(lookVec.scale(t));

        // 转为屏幕局部坐标（1/16 格单位，与 worldHitToGrid 一致）
        double lx = hit.x - pos.getX();
        double ly = hit.y - pos.getY();
        double lz = hit.z - pos.getZ();
        double rx;
        switch (facing) {
            case NORTH: rx = lx;     break;
            case SOUTH: rx = 2*c - lx; break;
            case EAST:  rx = lz;     break;
            case WEST:  rx = 2*c - lz; break;
            default: return 0f;
        }
        float sx = (float)(rx * 16.0);
        float sy = (float)(ly * 16.0);

        return (float) Math.atan2(sy - knobCenterY, sx - knobCenterX);
    }

    public static void onClientTick(ClientTickEvent.Pre event) {
        if (!knobDragging) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 当前 raw 角度 → 解缠绕（允许跨象限连续转动）
        float rawAngle = computeCrosshairAngle(mc.player, knobDragPos, knobDragFacing);
        float diff = rawAngle - knobPrevRawAngle;
        // 规范化 diff 到 [-π, π]
        if (diff > Math.PI) diff -= (float)(2 * Math.PI);
        else if (diff < -Math.PI) diff += (float)(2 * Math.PI);
        knobUnwrappedDelta += diff;
        knobPrevRawAngle = rawAngle;

        float newAngle = knobAccumAngle + (float) Math.toDegrees(knobUnwrappedDelta);

        // 周期性发送旋转角度到服务端
        knobSendCooldown--;
        if (knobSendCooldown <= 0) {
            knobSendCooldown = KNOB_SEND_INTERVAL;
            PacketDistributor.sendToServer(
                    new ModuleKnobRotatePayload(knobDragPos, knobDragModuleId, newAngle));
        }
    }

}
