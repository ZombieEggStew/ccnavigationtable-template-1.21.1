package com.zzy205.myfirstmod.client;

import com.simibubi.create.AllItems;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MonitorSlabBlock;
import com.zzy205.myfirstmod.block.MonitorSlabBlockEntity;
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
import com.zzy205.myfirstmod.screen.MonitorSlabConfigScreen;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
 * monitor_slab 表面 Monitor 的客户端交互（对齐 {@link Monitor2GridOverlay}）：
 * <ul>
 *   <li>手持 Monitor 模块物品（toggle_switch / knob / button / screen）→ 面板显示 14×14 棋盘网格
 *       （地板 = 顶面水平网格；贴墙 = 朝向 FACING 的竖直面板网格；天花板 = 底面水平网格）</li>
 *   <li>右键放置模块 / 屏幕两点放置（payload 复用 Monitor 的包，pos = slab 方块）</li>
 *   <li>按钮按压 / 钮子切换 / 旋钮拖拽（命中走 {@link MonitorSlabHitDetector} 独立检测）</li>
 *   <li>扳手蹲下右键拆除模块 / 屏幕</li>
 *   <li>右键模块 / 屏幕打开配置菜单（{@link MonitorModuleScreen}）</li>
 *   <li>扳手普通右键（不蹲下）或 空手蹲下右键，命中 slab <b>任意位置</b>（含侧面）→ 打开板式监视器配置菜单
 *       {@link MonitorSlabConfigScreen}（频道滚轮条，全局频道系统；扳手右键不再旋转 FACING）；
 *       右键落在表面内容（模块/屏幕）上时优先打开对应模块配置菜单</li>
 * </ul>
 * 每帧重新 show，离开/换物品后自动消失（Outliner 语义）。交互状态按 BlockPos 隔离。
 * <p>
 * 几何：面板按 blockstate 的 FACE/FACING 取 {@link MonitorSlabBlockEntity#panelFrame} 单一来源
 * （地板顶面 / 贴墙竖直边界 / 天花板底面），网格 14×14（起点 (1,1)px，格=1px）。
 * 无 yaw/pitch/tilt，网格线 / 模块框全部轴对齐绘制（块单位，double 运算兼容 Sable plot 坐标）。
 */
public class MonitorSlabGridOverlay {

    private static final float GRID_LINE_OFFSET = 0.0f;   // 网格线画在顶面本身（对齐 monitor_2 用户定稿 0px）
    private static Component hoveredTooltip;

    /** DEBUG：命中检测调试（命中点十字 + 网格区域边界框）。进游戏收集信息用，定位后置 false 或删除。 */
    private static final boolean DEBUG_HIT = false;

    /** DEBUG_HIT 的日志节流计数器（每 N tick 打一次，防刷屏）。 */
    private static int debugHitTick = 0;

    /** 菜单打开右键边沿检测（防连发，参考 ControlDeskPlacementOverlay） */
    private static boolean lastUseDown;

    /** 单个 slab 的客户端交互状态（按 BlockPos 隔离）。 */
    static class InteractionState {
        /** 按钮按下追踪：当前被按住的模块 ID，-1 表示无 */
        int pressingModuleId = -1;
        /** 钮子开关防连发：上次触发的 moduleId，松开右键后清除 */
        int toggleFiredId = -1;

        // ── 旋钮拖拽 ──
        boolean knobDragging = false;
        int knobDragModuleId = -1;
        float knobAccumAngle = 0f;
        /** 旋钮中心在面板局部坐标（模型空间 px） */
        float knobCenterX = 0f;
        float knobCenterZ = 0f;
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

    /** 所有活跃 slab 的交互状态，key 为 slab 方块坐标 */
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
        NeoForge.EVENT_BUS.addListener(MonitorSlabGridOverlay::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(MonitorSlabGridOverlay::onRenderGui);
        NeoForge.EVENT_BUS.addListener(MonitorSlabGridOverlay::onClientTick);
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

        // ── 独立命中检测：瞄准 slab 面板（地板顶面 / 贴墙竖直边界 / 天花板底面）──
        MonitorSlabHitDetector.SlabHit hit = MonitorSlabHitDetector.find(level, player, partialTick);
        if (hit == null) {
            if (DEBUG_HIT && (++debugHitTick & 19) == 0) {
                CCPeripheralExtender.LOGGER.info("[SlabHit] 未命中 slab 面板（准星 {}）",
                        mc.hitResult != null ? mc.hitResult.getType() : "无 hitResult");
            }
            hoveredKnobPos = null;
            hoveredKnobModuleId = -1;
            return;
        }

        BlockPos pos = hit.pos();
        float px = hit.px();
        float pz = hit.pz();
        int[] gp = hit.grid();

        // 面板几何按 blockstate 的 FACE/FACING 取（地板水平面 / 贴墙竖直边界），命中/渲染共用单一来源
        BlockState state = level.getBlockState(pos);

        // DEBUG：命中数值日志（节流每 20 tick 一次）
        if (DEBUG_HIT && (++debugHitTick & 19) == 0) {
            CCPeripheralExtender.LOGGER.info("[SlabHit] 命中 pos={} dist={} px={} pz={} grid={}",
                    pos.toShortString(), String.format("%.2f", hit.distance()),
                    String.format("%.2f", px), String.format("%.2f", pz),
                    gp == null ? "null" : "[" + gp[0] + "," + gp[1] + "]");
        }

        MonitorSlabBlockEntity slab = level.getBlockEntity(pos) instanceof MonitorSlabBlockEntity s ? s : null;
        GridState grid = slab != null ? slab.getGridState() : new GridState(
                MonitorSlabBlockEntity.GRID_WIDTH, MonitorSlabBlockEntity.GRID_HEIGHT);

        ItemStack held = player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);
        boolean holdingScreen = held.is(MyModItems.MODULE_SCREEN.get());
        boolean holdingWrench = isWrench(held);

        var interact = interactions.computeIfAbsent(pos, k -> new InteractionState());

        // ── 屏幕放置：切换物品则取消 ──
        if (interact.screenPlacing && !holdingScreen) {
            interact.screenPlacing = false;
        }

        String keyPrefix = "monitor-slab/" + pos.toShortString();

        Outliner outliner = Outliner.getInstance();

        // DEBUG：命中点可视化（黄色十字 = 命中点；白色框 = 面板；青色框 = 网格区域）
        if (DEBUG_HIT) {
            drawHitDebug(outliner, pos, state, px, pz, gp, keyPrefix);
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

        // ── 打开板式监视器配置菜单（频道，抄 ControlDeskConfigScreen）：扳手普通右键（不蹲下）或 空手蹲下右键，命中顶面任意位置 ──
        // 悬停模块/屏幕时上方已优先打开 MonitorModuleScreen（模块配置），不冲突；
        // 扳手右键不再旋转 FACING（服务端 MonitorSlabBlock.onWrenched 一律消费），光板也能打开
        if (heldType == null && !holdingScreen
                && ((holdingWrench && useEdge && !shiftHeld)
                || (held.isEmpty() && shiftUseEdge))) {
            mc.setScreen(new MonitorSlabConfigScreen(pos));
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
            beginKnobDrag(interact, pos, px, pz, hoveredModule, grid);
        } else if (interact.knobDragging && !useDown) {
            interact.knobDragging = false;
            interact.knobDragModuleId = -1;
        }
        } // !holdingScreen && !screenPlacing

        // 1. 网格线（手持模块或屏幕物品时）
        if (showGrid) {
            drawGridLines(outliner, pos, state, keyPrefix);
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
            drawModuleOutline(outliner, pos, state, minX, minY, w, h, keyPrefix + "/screen_preview", color);
        }

        // 2. 放置预览 / 对准高亮
        if (showPreview && !interact.screenPlacing && !holdingScreen) {
            if (heldType != null && gp != null) {
                boolean ok = grid.canPlace(gp[0], gp[1], heldType.width, heldType.height);
                int color = ok ? 0x4CDA64 : 0xFF5E5E;
                drawModuleOutline(outliner, pos, state, gp[0], gp[1],
                        heldType.width, heldType.height, keyPrefix + "/preview", color);
            } else if (hoveredModule != null) {
                drawModuleOutline(outliner, pos, state, hoveredModule.gridX(), hoveredModule.gridY(),
                        hoveredModule.getWidth(), hoveredModule.getHeight(),
                        keyPrefix + "/hover", moduleColor);
            } else if (onScreenCell) {
                var scr = grid.getScreenAt(gp[0], gp[1]);
                if (scr != null) {
                    int alpha = Math.max(0x20, Config.MONITOR_OUTLINE_A.get() / 2);
                    int screenColor = (alpha << 24)
                            | (Config.MONITOR_OUTLINE_R.get() << 16)
                            | (Config.MONITOR_OUTLINE_G.get() << 8)
                            | Config.MONITOR_OUTLINE_B.get();
                    drawModuleOutline(outliner, pos, state, scr.minX(), scr.minY(),
                            scr.width(), scr.height(), keyPrefix + "/screen_hover", screenColor);
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

    // ── slab 面板网格 / 模块框（按 blockstate 的 FACE/FACING 面板坐标系，块单位，double 运算兼容 Sable plot 坐标）──

    /**
     * 面板局部坐标 [px, pz, py]（px 沿 grid x、pz 沿 grid y，面板内模型空间 px；py = 面板高度 px，
     * 地板 = 世界 Y、贴墙 = 面板法线方向）→ 世界（块单位）。
     * 地板形态退化为 pos + (px/16, py/16, pz/16)（与旧实现一致）；贴墙形态画在朝向 FACING 的竖直面板上。
     */
    private static Vec3 world(BlockPos pos, BlockState state, float px, float pz, float py) {
        MonitorSlabBlockEntity.PanelFrame frame = MonitorSlabBlockEntity.panelFrame(state);
        if (frame == null) { // 不应发生（panelFrame 对三种 FACE 都返回帧）；回退旧地板映射
            return new Vec3(pos.getX() + px / 16.0, pos.getY() + py / 16.0, pos.getZ() + pz / 16.0);
        }
        double n = (py - MonitorSlabBlockEntity.PANEL_Y_PX) / 16.0;
        Vec3 rel = MonitorSlabBlockEntity.panelLocalToWorld(frame, px / 16.0, pz / 16.0, n);
        return new Vec3(pos.getX() + rel.x, pos.getY() + rel.y, pos.getZ() + rel.z);
    }

    private static void drawGridLines(Outliner o, BlockPos pos, BlockState state, String keyPrefix) {
        float y = MonitorSlabBlockEntity.PANEL_Y_PX + GRID_LINE_OFFSET;
        float x0 = MonitorSlabBlockEntity.GRID_ORIGIN_X_PX;
        float z0 = MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX;
        float x1 = x0 + MonitorSlabBlockEntity.GRID_WIDTH;
        float z1 = z0 + MonitorSlabBlockEntity.GRID_HEIGHT;
        float lw = (float) (1 / 256f * Config.MONITOR_GRID_LINE_WIDTH.get());

        for (int i = 0; i <= MonitorSlabBlockEntity.GRID_WIDTH; i++) {
            float x = x0 + i;
            Vec3 from = world(pos, state, x, z0, y);
            Vec3 to = world(pos, state, x, z1, y);
            o.showLine(keyPrefix + "/grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= MonitorSlabBlockEntity.GRID_HEIGHT; i++) {
            float z = z0 + i;
            Vec3 from = world(pos, state, x0, z, y);
            Vec3 to = world(pos, state, x1, z, y);
            o.showLine(keyPrefix + "/grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    /**
     * DEBUG：slab 命中检测可视化。
     * <ul>
     *   <li>黄色十字 = 命中点 (px, pz)，画在顶面</li>
     *   <li>白色框 = 面板边界（x0..16 / z0..16）</li>
     *   <li>青色框 = 网格区域边界（内缩 1px）</li>
     *   <li>红色小框 = localToGrid 得到的格 (gp)</li>
     * </ul>
     */
    private static void drawHitDebug(Outliner o, BlockPos pos, BlockState state, float px, float pz, int[] gp, String keyPrefix) {
        float y = MonitorSlabBlockEntity.PANEL_Y_PX + GRID_LINE_OFFSET;
        // 命中点十字（黄）
        marker(o, pos, state, px, pz, y, 0xFFFF00, keyPrefix + "/dbg_hit");
        // 面板边界（白，细线）
        drawRectLines(o, pos, state, 0f, 0f, 16f, 16f, y, 0xFFFFFF, keyPrefix + "/dbg_panel", 1 / 128f);
        // 网格区域边界（青，细线）
        drawRectLines(o, pos, state,
                MonitorSlabBlockEntity.GRID_ORIGIN_X_PX, MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX,
                MonitorSlabBlockEntity.GRID_ORIGIN_X_PX + MonitorSlabBlockEntity.GRID_WIDTH,
                MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX + MonitorSlabBlockEntity.GRID_HEIGHT,
                y, 0x00FFFF, keyPrefix + "/dbg_grid", 1 / 128f);
        // gp 格（红，粗线）
        if (gp != null) {
            drawRectLines(o, pos, state,
                    MonitorSlabBlockEntity.GRID_ORIGIN_X_PX + gp[0],
                    MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX + gp[1],
                    MonitorSlabBlockEntity.GRID_ORIGIN_X_PX + gp[0] + 1,
                    MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX + gp[1] + 1,
                    y, 0xFF0000, keyPrefix + "/dbg_gp", 1 / 32f);
        }
    }

    /** DEBUG：在 (px, pz, py)（面板局部 px）画一个小十字线。 */
    private static void marker(Outliner o, BlockPos pos, BlockState state, float px, float pz, float py, int color, String key) {
        float r = 0.5f; // 十字半长（px）
        Vec3 c = world(pos, state, px, pz, py);
        Vec3 dx = world(pos, state, px + r, pz, py);
        Vec3 dy = world(pos, state, px, pz + r, py);
        Vec3 dxn = world(pos, state, px - r, pz, py);
        Vec3 dyn = world(pos, state, px, pz - r, py);
        o.showLine(key + "_x", dxn, dx).colored(color).lineWidth(1 / 32f);
        o.showLine(key + "_y", dyn, dy).colored(color).lineWidth(1 / 32f);
    }

    /** DEBUG：在面板 (x0,z0)-(x1,z1) 画矩形四边（面板局部 px）。 */
    private static void drawRectLines(Outliner o, BlockPos pos, BlockState state,
                                      float x0, float z0, float x1, float z1,
                                      float y, int color, String key, float lw) {
        Vec3 p00 = world(pos, state, x0, z0, y);
        Vec3 p10 = world(pos, state, x1, z0, y);
        Vec3 p11 = world(pos, state, x1, z1, y);
        Vec3 p01 = world(pos, state, x0, z1, y);
        o.showLine(key + "_t", p00, p10).colored(color).lineWidth(lw);
        o.showLine(key + "_r", p10, p11).colored(color).lineWidth(lw);
        o.showLine(key + "_b", p11, p01).colored(color).lineWidth(lw);
        o.showLine(key + "_l", p01, p00).colored(color).lineWidth(lw);
    }

    /** 模块/屏幕占位框：网格格 (gx, gy) 起 w×h 格，画在面板上。 */
    private static void drawModuleOutline(Outliner o, BlockPos pos, BlockState state,
                                          int gx, int gy, int w, int h, String slot, int color) {
        float x0 = MonitorSlabBlockEntity.GRID_ORIGIN_X_PX + gx;
        float z0 = MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX + gy;
        float x1 = x0 + w;
        float z1 = z0 + h;
        float y = MonitorSlabBlockEntity.PANEL_Y_PX + GRID_LINE_OFFSET;
        float lw = (float) (1 / 128f * Config.MONITOR_OUTLINE_LINE_WIDTH.get());

        Vec3 p00 = world(pos, state, x0, z0, y);
        Vec3 p10 = world(pos, state, x1, z0, y);
        Vec3 p11 = world(pos, state, x1, z1, y);
        Vec3 p01 = world(pos, state, x0, z1, y);

        o.showLine(slot + "_top",    p00, p10).colored(color).lineWidth(lw);
        o.showLine(slot + "_right",  p10, p11).colored(color).lineWidth(lw);
        o.showLine(slot + "_bottom", p11, p01).colored(color).lineWidth(lw);
        o.showLine(slot + "_left",   p01, p00).colored(color).lineWidth(lw);
    }

    // ── 旋钮拖拽（slab：面板局部坐标直接由 MonitorSlabHitDetector 给出，无 yaw/pitch/tilt）──

    private static void beginKnobDrag(InteractionState state, BlockPos pos,
                                      float px, float pz, MonitorModule module, GridState grid) {
        state.knobDragging = true;
        state.knobDragModuleId = module.id();
        state.knobCenterX = MonitorSlabBlockEntity.GRID_ORIGIN_X_PX
                + module.gridX() + module.getWidth() / 2f;
        state.knobCenterZ = MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX
                + module.gridY() + module.getHeight() / 2f;

        state.knobAccumAngle = grid.getKnobAngle(module.id());
        state.knobDetentStep = grid.getDetentStep(module.id());
        boolean physicalLimit = grid.getModuleConfig(module.id()).getBoolean("physical_limit");
        state.knobPrevRawAngle = (float) Math.atan2(pz - state.knobCenterZ, px - state.knobCenterX);
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

        // ── 配置菜单打开（扳手右键 slab 任意位置 / 空手蹲下右键，抄 ControlDeskPlacementOverlay）──
        // 先判定右键的是不是表面内容（模块/屏幕）：面板内容命中由 onRenderLevel 打开对应配置菜单
        // （MonitorModuleScreen，保持已验证逻辑）；这里只处理非内容命中（侧面 / 面板空格 / 面板外）→
        // 打开板式监视器配置菜单 MonitorSlabConfigScreen（扳手右键不再旋转 FACING，服务端 onWrenched 一律消费）
        if (mc.screen == null && mc.level != null) {
            ItemStack held = mc.player.getMainHandItem();
            boolean useDown = mc.options.keyUse.isDown();
            boolean useEdge = useDown && !lastUseDown;
            lastUseDown = useDown;
            if (useEdge) {
                boolean wrench = isWrench(held);
                boolean emptySneak = held.isEmpty() && mc.player.isShiftKeyDown();
                boolean openMenu = (wrench && !mc.player.isShiftKeyDown()) || emptySneak;
                if (openMenu && mc.hitResult instanceof BlockHitResult hit
                        && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof MonitorSlabBlock) {
                    BlockPos pos = hit.getBlockPos();
                    var panelHit = MonitorSlabHitDetector.find(mc.level, mc.player, 1.0f);
                    boolean onPanelContent = panelHit != null && panelHit.pos().equals(pos) && panelHit.grid() != null;
                    if (!onPanelContent) {
                        mc.setScreen(new MonitorSlabConfigScreen(pos));
                    }
                }
            }
        }

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

        var hit = MonitorSlabHitDetector.find(mc.level, mc.player, 1.0f);
        if (hit == null || !hit.pos().equals(pos)) {
            state.knobDragging = false;
            state.knobDragModuleId = -1;
            return;
        }

        MonitorSlabBlockEntity slab = mc.level.getBlockEntity(pos) instanceof MonitorSlabBlockEntity s ? s : null;
        GridState grid = slab != null ? slab.getGridState() : new GridState(
                MonitorSlabBlockEntity.GRID_WIDTH, MonitorSlabBlockEntity.GRID_HEIGHT);

        float rawAngle = (float) Math.atan2(hit.pz() - state.knobCenterZ, hit.px() - state.knobCenterX);
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
