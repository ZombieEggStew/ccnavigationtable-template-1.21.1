package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.ModuleKnobRotatePayload;
import com.zzy205.myfirstmod.network.ModulePressPayload;
import com.zzy205.myfirstmod.network.PlaceScreenPayload;
import com.zzy205.myfirstmod.network.RemoveScreenPayload;
import com.zzy205.myfirstmod.screen.MonitorMenuScreen;
import com.zzy205.myfirstmod.screen.MonitorModuleScreen;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * 在 Monitor 屏幕表面渲染棋盘网格 + 模块边框 + 放置预览。
 * 使用 Catnip Outliner 渲染 —— 效果与 Create 一致。
 * 
 * 架构：每个 Monitor（BlockPos）持有独立的 {@link InteractionState}，
 * 多 Monitor 共存时互不干扰。
 */
public class MonitorGridOverlay {

    private static final int KNOB_SEND_INTERVAL = 2;
    private static final float KNOB_SOUND_STEP = 12f; // 每旋转多少度播放一次音效
    private static final float GRID_LINE_OFFSET = 0.06f;
    private static Component hoveredTooltip;

    /**
     * 单个 Monitor 的客户端交互状态。
     * 所有状态都按 BlockPos 隔离，消除多 Monitor 之间的静态变量共享问题。
     */
    private static class InteractionState {
        /** 按钮按下追踪：当前被按住的模块 ID，-1 表示无 */
        int pressingModuleId = -1;
        /** 钮子开关防连发：上次触发的 moduleId，松开右键后清除 */
        int toggleFiredId = -1;

        // ── 旋钮拖拽 ──
        boolean knobDragging = false;
        Direction knobDragFacing = null;
        int knobDragModuleId = -1;
        float knobAccumAngle = 0f;
        /** 旋钮中心在屏幕局部坐标（1/16 格单位） */
        float knobCenterX = 0f;
        float knobCenterY = 0f;
        /** 上一帧 raw atan2 角度（弧度），跨象限解缠绕 */
        float knobPrevRawAngle = 0f;
        /** 解缠绕后累计角度增量（弧度） */
        float knobUnwrappedDelta = 0f;
        int knobSendCooldown = 0;
        /** 上次播放音效时的角度（度） */
        float knobLastSoundAngle = 0f;
        /** 当前拖动中的绝对角度（度），仅客户端显示使用 */
        float knobDisplayAngle = 0f;

        // ── 屏幕两点放置 ──
        boolean screenPlacing = false;
        Direction screenAnchorFacing = null;
        int screenAnchorX = -1;
        int screenAnchorY = -1;
        boolean screenLastUseDown = false;  // 防连发
        /** Shift+右键模块防连发 */
        boolean shiftUseLastDown = false;
    }

    /** 所有活跃 Monitor 的交互状态，key 为 Monitor 方块坐标 */
    private static final Map<BlockPos, InteractionState> interactions = new HashMap<>();

    /** 获取正在拖动的旋钮角度；未拖动或模块不匹配时返回 null。 */
    public static Float getActiveKnobAngle(BlockPos pos, int moduleId) {
        InteractionState state = interactions.get(pos);
        if (state == null || !state.knobDragging || state.knobDragModuleId != moduleId) return null;
        return state.knobDisplayAngle;
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderGui);
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onClientTick);
        CCPeripheraExtender.LOGGER.info("MonitorGridOverlay registered with Catnip Outliner");
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        hoveredTooltip = null;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        HitResult hit = mc.hitResult;
        BlockPos pos = null;
        boolean isMonitor = false;
        if (hit instanceof BlockHitResult bhr) {
            pos = bhr.getBlockPos();
            Level level = player.level();
            BlockState state = level.getBlockState(pos);
            isMonitor = state.getBlock() instanceof MonitorBlock;
        }

        // ── 释放所有非当前 Monitor 的活跃按钮按下 ──
        releaseStalePressesExcept(player, isMonitor ? pos : null);

        if (!isMonitor || pos == null) return;

        BlockHitResult bhr = (BlockHitResult) hit;
        Level level = player.level();
        BlockState state = level.getBlockState(pos);

        Direction facing = state.getValue(MonitorBlock.FACING);
        GridState grid = null;
        if (level.getBlockEntity(pos) instanceof MonitorBlockEntity be) grid = be.getGridState();
        if (grid == null) grid = new GridState();

        ItemStack held = player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);
        boolean holdingScreen = held.getItem().toString().equals("ccpe:module_screen");
        boolean holdingWrench = held.getItem().toString().contains("create") && held.getItem().toString().contains("wrench");

        // ── 获取此 Monitor 的独立交互状态 ──
        var interact = interactions.computeIfAbsent(pos, k -> new InteractionState());

        // ── 屏幕放置：切换物品或看向其他方块则取消 ──
        if (interact.screenPlacing && (!holdingScreen || !pos.equals(pos))) {
            interact.screenPlacing = false;
        }

        // 用 BlockPos 前缀区分不同 Monitor 的 Outliner 条目
        String keyPrefix = pos.toShortString();

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
        GridState.ScreenRegion screenAt = gp != null ? grid.getScreenAt(gp[0], gp[1]) : null;

        if (hoveredModule != null) {
            var config = grid.getModuleConfig(hoveredModule.id());
            String text = config.getString("text");
            if (!text.isBlank()) {
                hoveredTooltip = Component.literal(text);
            }
        } else if (screenAt != null && !screenAt.text().isBlank()) {
            hoveredTooltip = Component.literal(screenAt.text());
        }

        boolean showGrid = heldType != null || holdingScreen;
        boolean onScreenCell = gp != null && grid.getCell(gp[0], gp[1]) == GridState.SCREEN_CELL_MARKER;
        boolean showPreview = heldType != null || hoveredModule != null || holdingScreen || interact.screenPlacing || onScreenCell;

        boolean useDown = mc.options.keyUse.isDown();

        // ── Shift+右键模块 / 屏幕 → 打开配置 GUI（边沿触发）──
        boolean shiftHeld = player.isShiftKeyDown();
        boolean shiftUseEdge = useDown && shiftHeld && !interact.shiftUseLastDown;
        interact.shiftUseLastDown = useDown && shiftHeld;

        if (hoveredModule != null && shiftUseEdge && heldType == null && !holdingScreen) {
            String text = grid.getModuleConfig(hoveredModule.id()).getString("text");
            mc.setScreen(new MonitorModuleScreen(pos, grid, hoveredModule.type().name, hoveredModule.id(), text));
            return;
        }

        if (screenAt != null && shiftUseEdge && heldType == null && !holdingScreen) {
            mc.setScreen(new MonitorModuleScreen(pos, grid, "screen", screenAt.id(), screenAt.text()));
            return;
        }

        // ── Shift+右键 Monitor 空白处 → 打开 Monitor 自身菜单（滚轮选择频道/背景）──
        if (shiftUseEdge && heldType == null && !holdingScreen && !holdingWrench) {
            int channel = 0;
            int[] occupied = new int[0];
            String background = MonitorBackground.DEFAULT;
            if (level.getBlockEntity(pos) instanceof MonitorBlockEntity monitorBE) {
                channel = monitorBE.getChannel();
                occupied = monitorBE.getOccupiedChannels();
                background = monitorBE.getBackground();
            }
            mc.setScreen(new MonitorMenuScreen(pos, channel, occupied, background));
            return;
        }

        // ── 屏幕两点放置交互（边沿触发，防连发）──
        boolean screenClickEdge = useDown && !interact.screenLastUseDown;
        interact.screenLastUseDown = useDown;

        if (holdingScreen && gp != null && screenClickEdge) {
            if (!interact.screenPlacing) {
                interact.screenPlacing = true;
                interact.screenAnchorFacing = facing;
                interact.screenAnchorX = gp[0];
                interact.screenAnchorY = gp[1];
            } else {
                int minX = Math.min(interact.screenAnchorX, gp[0]);
                int maxX = Math.max(interact.screenAnchorX, gp[0]);
                int minY = Math.min(interact.screenAnchorY, gp[1]);
                int maxY = Math.max(interact.screenAnchorY, gp[1]);
                if (maxX - minX >= GridState.SCREEN_MIN_SIZE - 1
                        && maxY - minY >= GridState.SCREEN_MIN_SIZE - 1) {
                    PacketDistributor.sendToServer(
                            new PlaceScreenPayload(pos, interact.screenAnchorX, interact.screenAnchorY, gp[0], gp[1]));
                }
                interact.screenPlacing = false;
            }
        }

        // 扳手拆卸屏幕（边沿触发）
        if (holdingWrench && gp != null && screenClickEdge
                && grid.getCell(gp[0], gp[1]) == GridState.SCREEN_CELL_MARKER) {
            PacketDistributor.sendToServer(new RemoveScreenPayload(pos, gp[0], gp[1]));
        }

        // ── 按钮按下/释放检测 ──
        // 屏幕放置模式 / 手持扳手时不触发按钮/钮子/旋钮交互（扳手直接拆卸模块）
        if (!holdingScreen && !holdingWrench && !interact.screenPlacing) {
        boolean isToggle = hoveredModule != null && hoveredModule.type() == ModuleType.TOGGLE_SWITCH;
        boolean isKnob = hoveredModule != null && hoveredModule.type() == ModuleType.KNOB;

        if (hoveredModule != null && useDown && heldType == null && !interact.knobDragging) {
            if (isToggle) {
                if (interact.toggleFiredId != hoveredModule.id()) {
                    PacketDistributor.sendToServer(new ModulePressPayload(pos, hoveredModule.id(), true));
                    interact.toggleFiredId = hoveredModule.id();
                }
            } else if (!isKnob && interact.pressingModuleId < 0) {
                PacketDistributor.sendToServer(new ModulePressPayload(pos, hoveredModule.id(), true));
                interact.pressingModuleId = hoveredModule.id();
            }
        }

        // 按钮释放
        if (interact.pressingModuleId >= 0) {
            boolean sameModule = hoveredModule != null && hoveredModule.id() == interact.pressingModuleId;
            if (!useDown || !sameModule) {
                PacketDistributor.sendToServer(new ModulePressPayload(pos, interact.pressingModuleId, false));
                interact.pressingModuleId = -1;
            }
        }

        // 钮子开关松开右键→清除防连发
        if (!useDown) interact.toggleFiredId = -1;

        // ── 旋钮拖拽 ──
        if (hoveredModule != null && hoveredModule.type() == ModuleType.KNOB
                && useDown && heldType == null && !interact.knobDragging) {
            interact.knobDragging = true;
            interact.knobDragFacing = facing;
            interact.knobDragModuleId = hoveredModule.id();
            interact.knobCenterX = MonitorBlock.SCREEN_X_MIN + hoveredModule.gridX() + hoveredModule.getWidth() / 2f;
            interact.knobCenterY = MonitorBlock.SCREEN_Y_MIN + hoveredModule.gridY() + hoveredModule.getHeight() / 2f;
            var be = level.getBlockEntity(pos);
            if (be instanceof MonitorBlockEntity monitorBE) {
                interact.knobAccumAngle = monitorBE.getGridState().getKnobAngle(hoveredModule.id());
            }
            interact.knobPrevRawAngle = computeCrosshairAngle(player, pos, facing, interact.knobCenterX, interact.knobCenterY);
            interact.knobUnwrappedDelta = 0f;
            interact.knobLastSoundAngle = interact.knobAccumAngle;
            interact.knobDisplayAngle = normalizeDisplayAngle(interact.knobAccumAngle);
        } else if (interact.knobDragging && !useDown) {
            interact.knobDragging = false;
            interact.knobDragModuleId = -1;
        }
        } // !holdingScreen && !screenPlacing

        // 1. 网格线（手持模块或屏幕物品时）
        if (showGrid) {
            drawGridLines(outliner, pos, facing, keyPrefix);
        }

        // 1.5 屏幕放置预览
        if (interact.screenPlacing && gp != null) {
            int minX = Math.min(interact.screenAnchorX, gp[0]);
            int maxX = Math.max(interact.screenAnchorX, gp[0]);
            int minY = Math.min(interact.screenAnchorY, gp[1]);
            int maxY = Math.max(interact.screenAnchorY, gp[1]);
            int w = maxX - minX + 1;
            int h = maxY - minY + 1;
            boolean bigEnough = w >= GridState.SCREEN_MIN_SIZE && h >= GridState.SCREEN_MIN_SIZE;
            boolean canPlace = grid.canPlaceScreen(minX, minY, maxX, maxY);
            int color = (bigEnough && canPlace) ? 0x4CDA64 : 0xFF5E5E;
            drawModuleOutline(outliner, pos, minX, minY, w, h, keyPrefix + "/screen_preview", color, facing);
        }

        // 2. 放置预览 / 对准高亮
        if (showPreview && !interact.screenPlacing && !holdingScreen) {
            if (heldType != null && gp != null) {
                boolean ok = grid.canPlace(gp[0], gp[1], heldType.width, heldType.height);
                int color = ok ? 0x4CDA64 : 0xFF5E5E;
                drawModuleOutline(outliner, pos, gp[0], gp[1],
                        heldType.width, heldType.height, keyPrefix + "/preview", color, facing);
            } else if (hoveredModule != null) {
                drawModuleOutline(outliner, pos, hoveredModule.gridX(), hoveredModule.gridY(),
                        hoveredModule.getWidth(), hoveredModule.getHeight(),
                        keyPrefix + "/hover", moduleColor, facing);
            } else if (onScreenCell) {
                // 悬停在屏幕上 → 高亮整个屏幕区域
                var scr = grid.getScreenAt(gp[0], gp[1]);
                if (scr != null) {
                    int alpha = Math.max(0x20, Config.MONITOR_OUTLINE_A.get() / 2);
                    int screenColor = (alpha << 24)
                            | (Config.MONITOR_OUTLINE_R.get() << 16)
                            | (Config.MONITOR_OUTLINE_G.get() << 8)
                            | Config.MONITOR_OUTLINE_B.get();
                    drawModuleOutline(outliner, pos, scr.minX(), scr.minY(),
                            scr.width(), scr.height(), keyPrefix + "/screen_hover", screenColor, facing);
                }
            }
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (hoveredTooltip == null || mc.screen != null || mc.options.hideGui) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int x = graphics.guiWidth() / 2 + 10;
        int y = graphics.guiHeight() / 2 + 12;
        graphics.renderTooltip(mc.font, hoveredTooltip, x, y);
    }

    /** 释放所有非 exceptPos 的 Monitor 上仍处于按下状态的按钮，清理过期条目 */
    private static void releaseStalePressesExcept(Player player, BlockPos exceptPos) {
        var it = interactions.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var state = entry.getValue();
            BlockPos pos = entry.getKey();

            // 跳过当前正在看的 Monitor（由 onRenderLevel 正常处理）
            if (pos.equals(exceptPos)) continue;

            if (state.pressingModuleId >= 0) {
                PacketDistributor.sendToServer(
                        new ModulePressPayload(pos, state.pressingModuleId, false));
                state.pressingModuleId = -1;
            }
            state.toggleFiredId = -1;

            // 无活跃交互时清理条目
            if (!state.knobDragging && state.pressingModuleId < 0 && !state.screenPlacing) {
                it.remove();
            }
        }
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

    private static void drawGridLines(Outliner o, BlockPos pos, Direction f, String keyPrefix) {
        float z = MonitorBlock.SCREEN_Z / 16f + GRID_LINE_OFFSET;
        float x0 = MonitorBlock.SCREEN_X_MIN / 16f;
        float x1 = MonitorBlock.SCREEN_X_MAX / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f;
        float y1 = MonitorBlock.SCREEN_Y_MAX / 16f;
        float lw = (float) (1 / 256f * Config.MONITOR_GRID_LINE_WIDTH.get());

        for (int i = 0; i <= 14; i++) {
            float x = x0 + i / 16f;
            Vec3 from = world(pos, x, y0, z, f);
            Vec3 to = world(pos, x, y1, z, f);
            o.showLine(keyPrefix + "/grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= 12; i++) {
            float y = y0 + i / 16f;
            Vec3 from = world(pos, x0, y, z, f);
            Vec3 to = world(pos, x1, y, z, f);
            o.showLine(keyPrefix + "/grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    // ── 模块/预览矩形边框 ──

    private static void drawModuleOutline(Outliner o, BlockPos pos,
                                           int gx, int gy, int w, int h, String slot, int color, Direction f) {
        float x0 = MonitorBlock.SCREEN_X_MIN / 16f + gx / 16f;
        float y0 = MonitorBlock.SCREEN_Y_MIN / 16f + gy / 16f;
        float x1 = x0 + w / 16f;
        float y1 = y0 + h / 16f;
        float z = MonitorBlock.SCREEN_Z / 16f + GRID_LINE_OFFSET;
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
                                                BlockPos pos, Direction facing,
                                                float knobCx, float knobCy) {
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

        return (float) Math.atan2(sy - knobCy, sx - knobCx);
    }

    public static void onClientTick(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 遍历所有活跃 Monitor 的旋钮拖拽状态
        var it = interactions.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            BlockPos pos = entry.getKey();
            var state = entry.getValue();

            if (!state.knobDragging) {
                // 无任何活跃交互时清理条目（toggle 防连发也算活跃）
                if (state.pressingModuleId < 0 && state.toggleFiredId < 0 && !state.screenPlacing) {
                    it.remove();
                }
                continue;
            }

            // 准心不在任何方块上，或看向的方块与拖拽起始 Monitor 不同 → 取消拖拽
            if (!(mc.hitResult instanceof BlockHitResult bhr) || !bhr.getBlockPos().equals(pos)) {
                state.knobDragging = false;
                state.knobDragModuleId = -1;
                continue;
            }

            // 当前 raw 角度 → 解缠绕
            float rawAngle = computeCrosshairAngle(mc.player, pos, state.knobDragFacing,
                    state.knobCenterX, state.knobCenterY);
            float diff = rawAngle - state.knobPrevRawAngle;
            if (diff > Math.PI) diff -= (float)(2 * Math.PI);
            else if (diff < -Math.PI) diff += (float)(2 * Math.PI);
            state.knobUnwrappedDelta += diff;
            state.knobPrevRawAngle = rawAngle;

            float newAngle = state.knobAccumAngle + (float) Math.toDegrees(state.knobUnwrappedDelta);
            state.knobDisplayAngle = normalizeDisplayAngle(newAngle);

            // ── 谢泼德音阶音效 ──
            float soundDiff = newAngle - state.knobLastSoundAngle;
            int soundSteps = (int) (soundDiff / KNOB_SOUND_STEP);
            if (soundSteps != 0) {
                float cycleAngle = newAngle % 360f;
                if (cycleAngle < 0) cycleAngle += 360f;
                float pitch = 0.5f + (cycleAngle / 360f) * 1.5f;
                mc.player.playSound(SoundEvents.LEVER_CLICK, 0.1f, pitch);
                state.knobLastSoundAngle = newAngle - (soundDiff - soundSteps * KNOB_SOUND_STEP);
            }

            // 周期性发送旋转角度到服务端
            state.knobSendCooldown--;
            if (state.knobSendCooldown <= 0) {
                state.knobSendCooldown = KNOB_SEND_INTERVAL;
                PacketDistributor.sendToServer(
                        new ModuleKnobRotatePayload(pos, state.knobDragModuleId, newAngle));
            }
        }
    }

    private static float normalizeDisplayAngle(float angle) {
        float normalized = angle % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

}
