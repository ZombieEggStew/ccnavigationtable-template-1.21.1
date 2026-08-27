package com.zzy205.myfirstmod.client;

import com.simibubi.create.AllItems;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.network.ModuleConfigPayload;
import com.zzy205.myfirstmod.network.ModuleKnobRotatePayload;
import com.zzy205.myfirstmod.network.ModulePressPayload;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import com.zzy205.myfirstmod.network.PlaceScreenPayload;
import com.zzy205.myfirstmod.network.RemoveModulePayload;
import com.zzy205.myfirstmod.network.RemoveScreenPayload;
import com.zzy205.myfirstmod.screen.MonitorModuleScreen;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * monitor_2 表面小 Monitor 的客户端交互（对齐 {@link MonitorGridOverlay}）：
 * <ul>
 *   <li>手持 Monitor 模块物品（toggle_switch / knob / button / screen）→ 屏幕面显示 10×8 棋盘网格</li>
 *   <li>右键放置模块 / 屏幕两点放置（payload 复用 Monitor 的包，pos = controlDesk 方块）</li>
 *   <li>按钮按压 / 钮子切换 / 旋钮拖拽（命中走 {@link Monitor2HitDetector} 独立检测）</li>
 *   <li>扳手蹲下右键拆除模块 / 屏幕</li>
 *   <li>右键模块 / 屏幕打开配置菜单（{@link MonitorModuleScreen}）</li>
 * </ul>
 * 每帧重新 show，离开/换物品后自动消失（Outliner 语义）。交互状态按 BlockPos 隔离。
 */
public class Monitor2GridOverlay {

    private static final float GRID_LINE_OFFSET = 0.0f;   // 网格线画在屏幕面本身（用户定稿 0px）
    private static Component hoveredTooltip;

    /** DEBUG：9 宫格部件锚点可视化（四角/四边/中心各一色十字线）。排查 9 宫格错位用，定位后置 false 或删除。 */
    private static final boolean DEBUG_SCREEN_PATCH = false;

    /** DEBUG：monitor_2 命中检测调试（命中点十字 + 屏幕面/网格边界框 + 命中数值日志）。进游戏收集信息用，定位后置 false 或删除。 */
    private static final boolean DEBUG_HIT = false;

    /** DEBUG_HIT 的日志节流计数器（每 N tick 打一次，防刷屏）。 */
    private static int debugHitTick = 0;

    /** 单个 monitor_2 的客户端交互状态（按 BlockPos 隔离）。 */
    static class InteractionState {
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
        /** 卡位模式下上一次吸附到的档位角度（度），用于只在跨档时发声 */
        float knobLastDetent = 0f;
        /** 拖拽中旋钮把手的视觉角度（度，含卡位前半程的微扭动），仅用于模型渲染 */
        float knobVisualAngle = 0f;
        /** 本次拖拽的卡位步长（0 = 自由模式，此时把手跟随服务端角度） */
        int knobDetentStep = 0;

        // ── 屏幕两点放置 ──
        boolean screenPlacing = false;
        int screenAnchorX = -1;
        int screenAnchorY = -1;
        boolean screenLastUseDown = false;  // 防连发
        /** Shift+右键模块防连发 */
        boolean shiftUseLastDown = false;
    }

    /** 所有活跃 monitor_2 的交互状态，key 为 controlDesk 方块坐标 */
    private static final Map<BlockPos, InteractionState> interactions = new HashMap<>();

    /** 当前准心悬浮的旋钮（全局唯一），仅用于悬停时显示角度 */
    private static BlockPos hoveredKnobPos = null;
    private static int hoveredKnobModuleId = -1;

    /** 获取正在拖动的旋钮角度；未拖动或模块不匹配时返回 null。 */
    public static Float getActiveKnobAngle(BlockPos pos, int moduleId) {
        InteractionState state = interactions.get(pos);
        if (state == null || !state.knobDragging || state.knobDragModuleId != moduleId) return null;
        return state.knobDisplayAngle;
    }

    /** 获取正在拖动的旋钮把手视觉角度（含限位后的越界缓冲）；未拖动时返回 null。 */
    public static Float getActiveKnobVisualAngle(BlockPos pos, int moduleId) {
        InteractionState state = interactions.get(pos);
        if (state == null || !state.knobDragging || state.knobDragModuleId != moduleId) return null;
        return state.knobVisualAngle;
    }

    /** 获取当前准心悬浮的旋钮模块 ID；未悬浮旋钮时返回 -1。 */
    public static int getHoveredKnobModuleId(BlockPos pos) {
        return (hoveredKnobPos != null && hoveredKnobPos.equals(pos)) ? hoveredKnobModuleId : -1;
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(Monitor2GridOverlay::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(Monitor2GridOverlay::onRenderGui);
        NeoForge.EVENT_BUS.addListener(Monitor2GridOverlay::onClientTick);
    }

    /** 是否为扳手：Create 扳手，或加入了原版 {@code minecraft:tools/wrenches} tag 的其它扳手。 */
    private static boolean isWrench(ItemStack stack) {
        return stack.is(AllItems.WRENCH.get()) || stack.is(Tags.Items.TOOLS_WRENCH);
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        hoveredTooltip = null;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        Level level = player.level();
        float partialTick = (float) event.getPartialTick().getGameTimeDeltaTicks();

        // ── 独立命中检测：瞄准 monitor_2 屏幕面 ──
        Monitor2HitDetector.Monitor2Hit hit = Monitor2HitDetector.find(level, player, partialTick);
        if (hit == null) {
            if (DEBUG_HIT && (++debugHitTick & 19) == 0) {
                CCPeripheralExtender.LOGGER.info("[Monitor2Hit] 未命中 monitor_2 屏幕（准星 {}）",
                        mc.hitResult != null ? mc.hitResult.getType() : "无 hitResult");
            }
            hoveredKnobPos = null;
            hoveredKnobModuleId = -1;
            return;
        }

        BlockPos pos = hit.pos();
        Direction facing = hit.facing();
        float screenX = hit.screenX();
        float screenY = hit.screenY();
        int[] gp = hit.grid();

        // DEBUG：命中数值日志（节流每 20 tick 一次）
        if (DEBUG_HIT && (++debugHitTick & 19) == 0) {
            CCPeripheralExtender.LOGGER.info("[Monitor2Hit] 命中 pos={} facing={} dist={} screenX={} screenY={} grid={}",
                    pos.toShortString(), facing, String.format("%.2f", hit.distance()),
                    String.format("%.2f", screenX), String.format("%.2f", screenY),
                    gp == null ? "null" : "[" + gp[0] + "," + gp[1] + "]");
        }

        ControlDeskBlockEntity desk = level.getBlockEntity(pos) instanceof ControlDeskBlockEntity d ? d : null;
        GridState grid = desk != null ? desk.getMonitor2Grid() : new GridState(
                ControlDeskBlockEntity.MONITOR_2_GRID_WIDTH, ControlDeskBlockEntity.MONITOR_2_GRID_HEIGHT);

        ItemStack held = player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);
        boolean holdingScreen = held.is(MyModItems.MODULE_SCREEN.get());
        boolean holdingWrench = isWrench(held);

        var interact = interactions.computeIfAbsent(pos, k -> new InteractionState());

        // ── 屏幕放置：切换物品则取消 ──
        if (interact.screenPlacing && !holdingScreen) {
            interact.screenPlacing = false;
        }

        String keyPrefix = "control-desk/monitor2/" + pos.toShortString();

        Outliner outliner = Outliner.getInstance();

        // DEBUG：命中点可视化（黄色十字 = 命中点；白色框 = 屏幕面 x2..14/y1..11；青色框 = 网格区 x3..13/y2..10）
        if (DEBUG_HIT) {
            drawHitDebug(outliner, pos, facing, screenX, screenY, gp, keyPrefix);
        }

        // DEBUG：绘制 9 宫格各部件锚点十字线，收集错位信息
        if (DEBUG_SCREEN_PATCH && desk != null) {
            drawScreenPatchDebug(outliner, pos, facing, grid, keyPrefix);
        }

        int moduleColor = (Config.MONITOR_OUTLINE_A.get() << 24)
                | (Config.MONITOR_OUTLINE_R.get() << 16)
                | (Config.MONITOR_OUTLINE_G.get() << 8)
                | Config.MONITOR_OUTLINE_B.get();

        MonitorModule hoveredModule = null;
        if (gp != null) {
            hoveredModule = grid.getModule(grid.getCell(gp[0], gp[1]));
        }
        GridState.ScreenRegion screenAt = gp != null ? grid.getScreenAt(gp[0], gp[1]) : null;

        // ── 记录当前准心悬浮的旋钮模块 ──
        if (hoveredModule != null && hoveredModule.type() == ModuleType.KNOB) {
            hoveredKnobPos = pos;
            hoveredKnobModuleId = hoveredModule.id();
        } else {
            hoveredKnobPos = null;
            hoveredKnobModuleId = -1;
        }

        if (hoveredModule != null) {
            var config = grid.getModuleConfig(hoveredModule.id());
            String text = config.getString("text");
            if (!text.isBlank()) {
                Component tooltip = Component.literal(text);
                if (hoveredModule.type() == ModuleType.TOGGLE_SWITCH) {
                    int stateColor = grid.isPressed(hoveredModule.id()) ? 0x55FF55 : 0xFF5555;
                    tooltip = Component.literal("▶ ")
                        .withStyle(style -> style.withColor(stateColor))
                        .append(tooltip);
                }
                hoveredTooltip = tooltip;
            }
        } else if (screenAt != null && !screenAt.tooltipText().isBlank()) {
            hoveredTooltip = Component.literal(screenAt.tooltipText());
        }

        boolean showGrid = heldType != null || holdingScreen;
        boolean onScreenCell = gp != null && grid.getCell(gp[0], gp[1]) == GridState.SCREEN_CELL_MARKER;
        boolean showPreview = heldType != null || hoveredModule != null || holdingScreen || interact.screenPlacing || onScreenCell;

        boolean useDown = mc.options.keyUse.isDown();
        boolean shiftHeld = player.isShiftKeyDown();
        boolean shiftUseEdge = useDown && shiftHeld && !interact.shiftUseLastDown;
        interact.shiftUseLastDown = useDown && shiftHeld;
        boolean useEdge = useDown && !interact.screenLastUseDown;
        interact.screenLastUseDown = useDown;

        // ── 扳手蹲下右键拆除模块/屏幕 ──
        if (holdingWrench && shiftUseEdge && gp != null) {
            int cellId = grid.getCell(gp[0], gp[1]);
            if (cellId >= 0) {
                PacketDistributor.sendToServer(new RemoveModulePayload(pos, cellId));
                return;
            }
            if (cellId == GridState.SCREEN_CELL_MARKER) {
                PacketDistributor.sendToServer(new RemoveScreenPayload(pos, gp[0], gp[1]));
                return;
            }
        }

        // ── 右键模块 / 屏幕 → 打开配置 GUI ──
        if (hoveredModule != null && (shiftUseEdge || holdingWrench && useEdge)
            && heldType == null && !holdingScreen) {
            String text = grid.getModuleConfig(hoveredModule.id()).getString("text");
            mc.setScreen(new MonitorModuleScreen(pos, grid, hoveredModule.type().name, hoveredModule.id(), text));
            return;
        }

        if (screenAt != null && (shiftUseEdge || holdingWrench && useEdge)
            && heldType == null && !holdingScreen) {
            mc.setScreen(new MonitorModuleScreen(pos, grid, GridState.SCREEN_NAME, screenAt.id(), screenAt.tooltipText()));
            return;
        }

        // ── 屏幕两点放置交互（边沿触发，防连发）──
        if (holdingScreen && gp != null && useEdge) {
            if (!interact.screenPlacing) {
                interact.screenPlacing = true;
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

        // ── 模块放置：手持模块物品 + 点击空格子 ──
        if (heldType != null && !holdingScreen && !holdingWrench && !interact.screenPlacing
                && useEdge && gp != null) {
            if (grid.canPlace(gp[0], gp[1], heldType.width, heldType.height)) {
                PacketDistributor.sendToServer(new PlaceModulePayload(pos, gp[0], gp[1], heldType.name));
                return;
            }
        }

        // ── 按钮按下/释放检测 ──
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
            beginKnobDrag(interact, pos, facing, screenX, screenY, hoveredModule, grid);
        } else if (interact.knobDragging && !useDown) {
            interact.knobDragging = false;
            interact.knobDragModuleId = -1;
            interact.knobDragFacing = null;
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

    // ── monitor_2 屏幕面网格 / 模块框（变换与渲染一致，见 ControlDeskPlacementOverlay.monitor2World）──

    /** 屏幕局部坐标 [sx, sy]（px）→ 世界（北向基准 → 放置平移 → case 22.5° 旋转 → FACING → 方块偏移）。 */
    private static Vec3 world(BlockPos pos, float sx, float sy, float z, Direction facing) {
        // monitor_2 网格自由放置后放置中心跟随 BE（勿用固定常量）；BE 缺失时回退唯一合法位 (8,12)
        ControlDeskBlockEntity desk = Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(pos) instanceof ControlDeskBlockEntity d ? d : null;
        int px = desk != null ? desk.getMonitor2PlaceX() : ControlDeskBlockEntity.MONITOR_2_PLACE_X;
        int pz = desk != null ? desk.getMonitor2PlaceZ() : ControlDeskBlockEntity.MONITOR_2_PLACE_Z;
        return ControlDeskPlacementOverlay.monitor2World(pos, sx, sy, z, facing, px, pz);
    }

    private static void drawGridLines(Outliner o, BlockPos pos, Direction facing, String keyPrefix) {
        float z = ControlDeskBlockEntity.MONITOR_2_SCREEN_Z + GRID_LINE_OFFSET;
        float x0 = ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1;
        float x1 = ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MAX - 1;
        float y0 = ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1;
        float y1 = ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MAX - 1;
        float lw = (float) (1 / 256f * Config.MONITOR_GRID_LINE_WIDTH.get());

        for (int i = 0; i <= ControlDeskBlockEntity.MONITOR_2_GRID_WIDTH; i++) {
            float x = x0 + i;
            Vec3 from = world(pos, x, y0, z, facing);
            Vec3 to = world(pos, x, y1, z, facing);
            o.showLine(keyPrefix + "/grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= ControlDeskBlockEntity.MONITOR_2_GRID_HEIGHT; i++) {
            float y = y0 + i;
            Vec3 from = world(pos, x0, y, z, facing);
            Vec3 to = world(pos, x1, y, z, facing);
            o.showLine(keyPrefix + "/grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    /**
     * DEBUG：把 9 宫格各部件锚点画成彩色十字线（与 {@link ControlDeskRenderer#renderMonitor2Screens}
     * 相同的坐标公式），用于排查 9 宫格错位。
     * 颜色：左上=红 右上=绿 左下=蓝 右下=黄 左/右/上/下边=青 中心=品红。
     */
    private static void drawScreenPatchDebug(Outliner o, BlockPos pos, Direction facing, GridState grid, String keyPrefix) {
        for (var scr : grid.getScreenRegions()) {
            float scrX = ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1 + scr.minX();
            float scrY = ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1 + scr.minY();
            float scrW = scr.width();
            float scrH = scr.height();
            float scrZ = ControlDeskBlockEntity.MONITOR_2_SCREEN_Z - ControlDeskBlockEntity.MONITOR_2_MODULE_PROTRUDE_PX;
            float border = 1f; // 1px 边框（borderSize*16）

            // 四个角（锚点 = 角模型中心，即格子中心）
            marker(o, pos, facing, scrX, scrY, scrZ, 0xFF0000, keyPrefix + "/dbg_tl");
            marker(o, pos, facing, scrX + scrW - border, scrY, scrZ, 0x00FF00, keyPrefix + "/dbg_tr");
            marker(o, pos, facing, scrX, scrY + scrH - border, scrZ, 0x0000FF, keyPrefix + "/dbg_bl");
            marker(o, pos, facing, scrX + scrW - border, scrY + scrH - border, scrZ, 0xFFFF00, keyPrefix + "/dbg_br");

            // 四边
            int tilesH = Math.max(0, scr.width() - 2);
            int tilesV = Math.max(0, scr.height() - 2);
            for (int i = 0; i < tilesV; i++) {
                float y = scrY + 1f + i * 1f;
                marker(o, pos, facing, scrX, y, scrZ, 0x00FFFF, keyPrefix + "/dbg_l" + i);
                marker(o, pos, facing, scrX + scrW - 1f, y, scrZ, 0x00FFFF, keyPrefix + "/dbg_r" + i);
            }
            for (int i = 0; i < tilesH; i++) {
                float x = scrX + 1f + i * 1f;
                marker(o, pos, facing, x, scrY, scrZ, 0x00FFFF, keyPrefix + "/dbg_t" + i);
                marker(o, pos, facing, x, scrY + scrH - 1f, scrZ, 0x00FFFF, keyPrefix + "/dbg_b" + i);
            }

            // 中心面板（锚点 = 内区左下角）
            marker(o, pos, facing, scrX + 1f, scrY + 1f, scrZ, 0xFF00FF, keyPrefix + "/dbg_center");
        }
    }

    /** DEBUG：在 (sx, sy, sz)（模型空间 px）画一个小十字线。 */
    private static void marker(Outliner o, BlockPos pos, Direction facing,
                               float sx, float sy, float sz, int color, String key) {
        float r = 0.5f; // 十字半长（px）
        Vec3 c = world(pos, sx, sy, sz, facing);
        Vec3 dx = world(pos, sx + r, sy, sz, facing);
        Vec3 dy = world(pos, sx, sy + r, sz, facing);
        Vec3 dxn = world(pos, sx - r, sy, sz, facing);
        Vec3 dyn = world(pos, sx, sy - r, sz, facing);
        o.showLine(key + "_x", dxn, dx).colored(color).lineWidth(1 / 32f);
        o.showLine(key + "_y", dyn, dy).colored(color).lineWidth(1 / 32f);
    }

    /**
     * DEBUG：monitor_2 命中检测可视化。
     * <ul>
     *   <li>黄色十字 = Monitor2HitDetector 返回的命中点 (screenX, screenY)，画在屏幕面 z=SCREEN_Z</li>
     *   <li>白色框 = 屏幕面边界（x2..14 / y1..11）</li>
     *   <li>青色框 = 网格区域边界（内缩 1px：x3..13 / y2..10）</li>
     *   <li>红色小框 = localToGrid 得到的格 (gp)</li>
     * </ul>
     * 用来核对：准星所指位置 ↔ 命中点 ↔ 网格坐标是否一致；若命中点偏出白框或黄十字与准星不符，即为变换/单位问题。
     */
    private static void drawHitDebug(Outliner o, BlockPos pos, Direction facing,
                                     float screenX, float screenY, int[] gp, String keyPrefix) {
        float z = ControlDeskBlockEntity.MONITOR_2_SCREEN_Z + GRID_LINE_OFFSET;
        // 命中点十字（黄）
        marker(o, pos, facing, screenX, screenY, z, 0xFFFF00, keyPrefix + "/dbg_hit");
        // 屏幕面边界（白，细线）
        drawRectLines(o, pos, facing,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN, ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MAX, ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MAX,
                z, 0xFFFFFF, keyPrefix + "/dbg_screen", 1 / 128f);
        // 网格区域边界（青，细线）
        drawRectLines(o, pos, facing,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1, ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1,
                ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MAX - 1, ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MAX - 1,
                z, 0x00FFFF, keyPrefix + "/dbg_grid", 1 / 128f);
        // gp 格（红，粗线）
        if (gp != null) {
            drawRectLines(o, pos, facing,
                    ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1 + gp[0],
                    ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1 + gp[1],
                    ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1 + gp[0] + 1,
                    ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1 + gp[1] + 1,
                    z, 0xFF0000, keyPrefix + "/dbg_gp", 1 / 32f);
        }
    }

    /** DEBUG：在屏幕面 (x0,y0)-(x1,y1) 画矩形四边（模型空间 px）。 */
    private static void drawRectLines(Outliner o, BlockPos pos, Direction facing,
                                      float x0, float y0, float x1, float y1,
                                      float z, int color, String key, float lw) {
        Vec3 p00 = world(pos, x0, y0, z, facing);
        Vec3 p10 = world(pos, x1, y0, z, facing);
        Vec3 p11 = world(pos, x1, y1, z, facing);
        Vec3 p01 = world(pos, x0, y1, z, facing);
        o.showLine(key + "_t", p00, p10).colored(color).lineWidth(lw);
        o.showLine(key + "_r", p10, p11).colored(color).lineWidth(lw);
        o.showLine(key + "_b", p11, p01).colored(color).lineWidth(lw);
        o.showLine(key + "_l", p01, p00).colored(color).lineWidth(lw);
    }

    private static void drawModuleOutline(Outliner o, BlockPos pos,
                                          int gx, int gy, int w, int h, String slot, int color, Direction facing) {
        float x0 = ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1 + gx;
        float y0 = ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1 + gy;
        float x1 = x0 + w;
        float y1 = y0 + h;
        float z = ControlDeskBlockEntity.MONITOR_2_SCREEN_Z + GRID_LINE_OFFSET;
        float lw = (float) (1 / 128f * Config.MONITOR_OUTLINE_LINE_WIDTH.get());

        Vec3 p00 = world(pos, x0, y0, z, facing);
        Vec3 p10 = world(pos, x1, y0, z, facing);
        Vec3 p11 = world(pos, x1, y1, z, facing);
        Vec3 p01 = world(pos, x0, y1, z, facing);

        o.showLine(slot + "_top",    p00, p10).colored(color).lineWidth(lw);
        o.showLine(slot + "_right",  p10, p11).colored(color).lineWidth(lw);
        o.showLine(slot + "_bottom", p11, p01).colored(color).lineWidth(lw);
        o.showLine(slot + "_left",   p01, p00).colored(color).lineWidth(lw);
    }

    // ── 旋钮拖拽（monitor_2：屏幕局部坐标直接由 Monitor2HitDetector 给出，无 yaw/pitch/offset）──

    private static void beginKnobDrag(InteractionState state, BlockPos pos, Direction facing,
                                      float screenX, float screenY, MonitorModule module, GridState grid) {
        state.knobDragging = true;
        state.knobDragFacing = facing;
        state.knobDragModuleId = module.id();
        state.knobCenterX = ControlDeskBlockEntity.MONITOR_2_SCREEN_X_MIN + 1
                + module.gridX() + module.getWidth() / 2f;
        state.knobCenterY = ControlDeskBlockEntity.MONITOR_2_SCREEN_Y_MIN + 1
                + module.gridY() + module.getHeight() / 2f;

        state.knobAccumAngle = grid.getKnobAngle(module.id());
        state.knobDetentStep = grid.getDetentStep(module.id());
        boolean physicalLimit = grid.getModuleConfig(module.id()).getBoolean("physical_limit");
        state.knobPrevRawAngle = (float) Math.atan2(screenY - state.knobCenterY, screenX - state.knobCenterX);
        state.knobUnwrappedDelta = 0f;
        state.knobLastSoundAngle = state.knobAccumAngle;
        state.knobDisplayAngle = physicalLimit
                ? state.knobAccumAngle : normalizeDisplayAngle(state.knobAccumAngle);
        state.knobLastDetent = state.knobDetentStep > 0
                ? GridState.snapToDetent(state.knobAccumAngle, state.knobDetentStep)
                : state.knobDisplayAngle;
        state.knobVisualAngle = state.knobDisplayAngle;
        state.knobSendCooldown = 0;
    }

    public static void onClientTick(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        var it = interactions.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            BlockPos pos = entry.getKey();
            var state = entry.getValue();

            if (!state.knobDragging) {
                if (state.pressingModuleId < 0 && state.toggleFiredId < 0 && !state.screenPlacing) {
                    it.remove();
                }
                continue;
            }

            tickKnobDrag(mc, pos, state);
        }
    }

    private static void tickKnobDrag(Minecraft mc, BlockPos pos, InteractionState state) {
        if (mc.level == null || mc.player == null) return;

        var hit = Monitor2HitDetector.find(mc.level, mc.player, 1.0f);
        if (hit == null || !hit.pos().equals(pos)) {
            state.knobDragging = false;
            state.knobDragModuleId = -1;
            state.knobDragFacing = null;
            return;
        }

        ControlDeskBlockEntity desk = mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity d ? d : null;
        GridState grid = desk != null ? desk.getMonitor2Grid()
                : new GridState(ControlDeskBlockEntity.MONITOR_2_GRID_WIDTH, ControlDeskBlockEntity.MONITOR_2_GRID_HEIGHT);

        float rawAngle = (float) Math.atan2(hit.screenY() - state.knobCenterY, hit.screenX() - state.knobCenterX);
        float diff = rawAngle - state.knobPrevRawAngle;
        if (diff > Math.PI) diff -= (float) (2 * Math.PI);
        else if (diff < -Math.PI) diff += (float) (2 * Math.PI);
        state.knobUnwrappedDelta += diff;
        state.knobPrevRawAngle = rawAngle;

        float newAngle = state.knobAccumAngle + (float) Math.toDegrees(state.knobUnwrappedDelta);
        int detentStep = grid.getDetentStep(state.knobDragModuleId);
        boolean physicalLimit = grid.getModuleConfig(state.knobDragModuleId).getBoolean("physical_limit");

        float sendAngle;
        float visualAngle;
        if (detentStep > 0) {
            float snapped = GridState.snapToDetent(newAngle, detentStep);
            if (physicalLimit) snapped = grid.clampKnobAngle(state.knobDragModuleId, snapped);
            state.knobDisplayAngle = physicalLimit ? snapped : normalizeDisplayAngle(snapped);
            if (snapped != state.knobLastDetent) {
                float soundAngle = normalizeDisplayAngle(snapped);
                float soundPitch = 0.5f + (soundAngle / 360f) * 1.5f;
                mc.player.playSound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.1f, soundPitch);
                state.knobLastDetent = snapped;
            }
            float off = newAngle - snapped;
            if (!physicalLimit) {
                if (off > 180f) off -= 360f;
                else if (off < -180f) off += 360f;
            }
            visualAngle = snapped + off / 3f;
            state.knobVisualAngle = physicalLimit ? visualAngle : normalizeDisplayAngle(visualAngle);
            sendAngle = snapped;
        } else {
            sendAngle = physicalLimit ? grid.clampKnobAngle(state.knobDragModuleId, newAngle) : newAngle;
            state.knobDisplayAngle = physicalLimit ? sendAngle : normalizeDisplayAngle(sendAngle);
            float soundDiff = sendAngle - state.knobLastSoundAngle;
            int soundSteps = (int) (soundDiff / 12f);
            if (soundSteps != 0) {
                float cycleAngle = sendAngle % 360f;
                if (cycleAngle < 0) cycleAngle += 360f;
                float soundPitch = 0.5f + (cycleAngle / 360f) * 1.5f;
                mc.player.playSound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.1f, soundPitch);
                state.knobLastSoundAngle = sendAngle - (soundDiff - soundSteps * 12f);
            }
            float overshoot = newAngle - sendAngle;
            visualAngle = sendAngle + overshoot / 3f;
            state.knobVisualAngle = physicalLimit ? visualAngle : normalizeDisplayAngle(visualAngle);
        }

        sendAngle = grid.clampKnobAngle(state.knobDragModuleId, sendAngle);
        state.knobDisplayAngle = grid.clampKnobAngle(state.knobDragModuleId, state.knobDisplayAngle);

        state.knobSendCooldown--;
        if (state.knobSendCooldown <= 0) {
            state.knobSendCooldown = 2;
            PacketDistributor.sendToServer(new ModuleKnobRotatePayload(pos, state.knobDragModuleId, sendAngle));
        }
    }

    private static float normalizeDisplayAngle(float angle) {
        float normalized = angle % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }
}
