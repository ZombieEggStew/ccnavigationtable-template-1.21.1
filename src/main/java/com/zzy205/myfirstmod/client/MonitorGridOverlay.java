package com.zzy205.myfirstmod.client;

import com.simibubi.create.AllItems;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MonitorBlock;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.ModulePressPayload;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import com.zzy205.myfirstmod.network.PlaceScreenPayload;
import com.zzy205.myfirstmod.network.RemoveModulePayload;
import com.zzy205.myfirstmod.network.RemoveScreenPayload;
import com.zzy205.myfirstmod.screen.MonitorMenuScreen;
import com.zzy205.myfirstmod.screen.MonitorModuleScreen;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
 * 在 Monitor 屏幕表面渲染棋盘网格 + 模块边框 + 放置预览。
 * 使用 Catnip Outliner 渲染 —— 效果与 Create 一致。
 * 
 * 架构：每个 Monitor（BlockPos）持有独立的 {@link InteractionState}，
 * 多 Monitor 共存时互不干扰。
 */
public class MonitorGridOverlay {

    private static final float GRID_LINE_OFFSET = 0.06f;
    private static Component hoveredTooltip;

    /**
     * 单个 Monitor 的客户端交互状态。
     * 所有状态都按 BlockPos 隔离，消除多 Monitor 之间的静态变量共享问题。
     */
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
        Direction screenAnchorFacing = null;
        int screenAnchorX = -1;
        int screenAnchorY = -1;
        boolean screenLastUseDown = false;  // 防连发
        /** Shift+右键模块防连发 */
        boolean shiftUseLastDown = false;
    }

    /** 所有活跃 Monitor 的交互状态，key 为 Monitor 方块坐标 */
    private static final Map<BlockPos, InteractionState> interactions = new HashMap<>();

    /** 当前准心悬浮的旋钮（全局唯一：准心同时只能对准一个方块），仅用于悬停时显示角度 */
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
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onRenderGui);
        NeoForge.EVENT_BUS.addListener(MonitorGridOverlay::onClientTick);
        CCPeripheralExtender.LOGGER.info("MonitorGridOverlay registered with Catnip Outliner");
    }

    /** 是否为扳手：Create 扳手，或加入了原版 {@code minecraft:tools/wrenches} tag 的其它扳手（与 Create 官方判断一致）。 */
    private static boolean isWrench(ItemStack stack) {
        return stack.is(AllItems.WRENCH.get()) || stack.is(Tags.Items.TOOLS_WRENCH);
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        // Sable 自带 LineOutlineMixin（compatibility.create.render_fixes）：Catnip 绘制
        // LineOutline 时，会把子次元内的端点用 renderPose()（与方块同一插值姿态、同一
        // partialTick）变换到世界空间 —— 绘制时刻变换，移动物理体上零滞后。
        // 因此本 mod 只传 plot 局部坐标给 Outliner（见 world()），不手动变换到世界空间，
        // 也不依赖早期渲染阶段更新几何；交互时序保持原有 AFTER_BLOCK_ENTITIES。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        hoveredTooltip = null;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        Level level = player.level();
        float partialTick = (float) event.getPartialTick().getGameTimeDeltaTicks();

        // ── 独立动态命中检测：不依赖原版 mc.hitResult，屏幕旋出方块范围后仍可交互 ──
        MonitorHitDetector.MonitorHit hit = MonitorHitDetector.find(level, player, partialTick);

        // ── 底座命中：屏幕未命中时，退回原版 pick 判断准心是否在 Monitor 底座（碰撞体 y 0..2/16）上 ──
        BlockPos basePos = null;
        MonitorBlockEntity baseBE = null;
        if (hit == null && mc.hitResult instanceof BlockHitResult bhr) {
            BlockPos p = bhr.getBlockPos();
            if (level.getBlockState(p).getBlock() instanceof MonitorBlock) {
                double localY = bhr.getLocation().y - p.getY();
                if (localY >= -0.01 && localY <= 2.0 / 16.0 + 0.01) {
                    basePos = p;
                    baseBE = level.getBlockEntity(p) instanceof MonitorBlockEntity m ? m : null;
                }
            }
        }

        // ── 释放所有非当前 Monitor 的活跃按钮按下 ──
        releaseStalePressesExcept(player, hit != null ? hit.pos() : basePos);

        // ── 底座交互：蹲下+右键，或扳手+右键，打开 Monitor 配置菜单 ──
        if (hit == null && basePos != null) {
            var baseInteract = interactions.computeIfAbsent(basePos, k -> new InteractionState());
            boolean useDown = mc.options.keyUse.isDown();
            boolean shiftHeld = player.isShiftKeyDown();
            boolean shiftUseEdge = useDown && shiftHeld && !baseInteract.shiftUseLastDown;
            baseInteract.shiftUseLastDown = useDown && shiftHeld;
            boolean useEdge = useDown && !baseInteract.screenLastUseDown;
            baseInteract.screenLastUseDown = useDown;

            ItemStack held = player.getMainHandItem();
            ModuleType heldType = ModuleType.fromItem(held);
            boolean holdingScreen = held.is(MyModItems.MODULE_SCREEN.get());
            boolean holdingWrench = isWrench(held);

            // 扳手蹲下右键底座 → 拆卸整台 Monitor（由服务端 onSneakWrenched 处理），不再打开菜单；
            // 扳手普通右键 / 空手蹲下右键 → 仍打开 Monitor 菜单。
            if ((shiftUseEdge && !holdingWrench || holdingWrench && useEdge && !shiftHeld)
                    && heldType == null && !holdingScreen) {
                openMonitorMenu(mc, basePos, baseBE);
            }

            hoveredKnobPos = null;
            hoveredKnobModuleId = -1;
            return;
        }

        if (hit == null) {
            hoveredKnobPos = null;
            hoveredKnobModuleId = -1;
            return;
        }

        BlockPos pos = hit.pos();
        Direction facing = hit.facing();
        float monitorYaw = hit.yaw();
        float monitorPitch = hit.pitch();
        int monitorOffset = hit.offset();

        GridState grid = null;
        MonitorBlockEntity monitorBE = level.getBlockEntity(pos) instanceof MonitorBlockEntity m ? m : null;
        if (monitorBE != null) grid = monitorBE.getGridState();
        if (grid == null) grid = new GridState();

        ItemStack held = player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);
        boolean holdingScreen = held.is(MyModItems.MODULE_SCREEN.get());
        boolean holdingWrench = isWrench(held);

        // ── 获取此 Monitor 的独立交互状态 ──
        var interact = interactions.computeIfAbsent(pos, k -> new InteractionState());

        // ── 屏幕放置：切换物品则取消 ──
        if (interact.screenPlacing && !holdingScreen) {
            interact.screenPlacing = false;
        }

        // 用 BlockPos 前缀区分不同 Monitor 的 Outliner 条目
        String keyPrefix = pos.toShortString();

        Outliner outliner = Outliner.getInstance();
        int moduleColor = (Config.MONITOR_OUTLINE_A.get() << 24)
                | (Config.MONITOR_OUTLINE_R.get() << 16)
                | (Config.MONITOR_OUTLINE_G.get() << 8)
                | Config.MONITOR_OUTLINE_B.get();

        // ── 动态射线命中：网格坐标已由检测器算好；旋钮拖拽仍需 plot 空间射线 ──
        Vec3[] ray = crosshairRay(level, pos, player, partialTick);
        int[] gp = hit.grid();
        MonitorModule hoveredModule = null;
        if (gp != null) {
            hoveredModule = grid.getModule(grid.getCell(gp[0], gp[1]));
        }
        GridState.ScreenRegion screenAt = gp != null ? grid.getScreenAt(gp[0], gp[1]) : null;

        // ── 记录当前准心悬浮的旋钮模块，供渲染器在“仅悬停”时显示角度 ──
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

        // ── 右键模块 / 屏幕 → 打开配置 GUI（普通扳手右键或空手蹲下右键）──
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

        // ── Shift+右键 Monitor 空白处 → 打开 Monitor 自身菜单（频道/背景 + 俯仰/偏航/偏移）──
        if (shiftUseEdge && heldType == null && !holdingScreen && !holdingWrench) {
            openMonitorMenu(mc, pos, monitorBE);
            return;
        }

        // ── 屏幕两点放置交互（边沿触发，防连发）──
        if (holdingScreen && gp != null && useEdge) {
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

        // ── 模块放置：手持模块物品 + 点击空格子（动态射线命中）──
        if (heldType != null && !holdingScreen && !holdingWrench && !interact.screenPlacing
                && useEdge && gp != null) {
            if (grid.canPlace(gp[0], gp[1], heldType.width, heldType.height)) {
                PacketDistributor.sendToServer(new PlaceModulePayload(pos, gp[0], gp[1], heldType.name));
                return;
            }
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
            KnobInteractionHandler.begin(interact, pos, facing, monitorYaw, monitorPitch, monitorOffset,
                    ray, hoveredModule, monitorBE);
        } else if (interact.knobDragging && !useDown) {
            KnobInteractionHandler.end(interact);
        }
        } // !holdingScreen && !screenPlacing

        // 1. 网格线（手持模块或屏幕物品时）
        if (showGrid) {
            drawGridLines(outliner, pos, facing, monitorYaw, monitorPitch, monitorOffset, keyPrefix);
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
            drawModuleOutline(outliner, pos, minX, minY, w, h, keyPrefix + "/screen_preview", color, facing,
                    monitorYaw, monitorPitch, monitorOffset);
        }

        // 2. 放置预览 / 对准高亮
        if (showPreview && !interact.screenPlacing && !holdingScreen) {
            if (heldType != null && gp != null) {
                boolean ok = grid.canPlace(gp[0], gp[1], heldType.width, heldType.height);
                int color = ok ? 0x4CDA64 : 0xFF5E5E;
                drawModuleOutline(outliner, pos, gp[0], gp[1],
                        heldType.width, heldType.height, keyPrefix + "/preview", color, facing,
                        monitorYaw, monitorPitch, monitorOffset);
            } else if (hoveredModule != null) {
                drawModuleOutline(outliner, pos, hoveredModule.gridX(), hoveredModule.gridY(),
                        hoveredModule.getWidth(), hoveredModule.getHeight(),
                        keyPrefix + "/hover", moduleColor, facing,
                        monitorYaw, monitorPitch, monitorOffset);
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
                            scr.width(), scr.height(), keyPrefix + "/screen_hover", screenColor, facing,
                            monitorYaw, monitorPitch, monitorOffset);
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

    /** 打开 Monitor 配置菜单（频道/背景 + 俯仰/偏航/偏移）。 */
    private static void openMonitorMenu(Minecraft mc, BlockPos pos, MonitorBlockEntity monitorBE) {
        int channel = 0;
        int[] occupied = new int[0];
        String background = MonitorBackground.DEFAULT;
        int pitch = 0, yaw = 0, offset = 0;
        if (monitorBE != null) {
            channel = monitorBE.getChannel();
            occupied = monitorBE.getOccupiedChannels();
            background = monitorBE.getBackground();
            pitch = Math.round(monitorBE.getPitchAngle());
            yaw = Math.round(monitorBE.getYawAngle());
            offset = monitorBE.getOffset();
        }
        mc.setScreen(new MonitorMenuScreen(pos, channel, occupied, background, pitch, yaw, offset));
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

    /** 获取准心视线射线，投影回 plot 空间（Sable 子次元兼容）。返回 [origin, dir]。 */
    private static Vec3[] crosshairRay(Level level, BlockPos pos, Player player, float partialTick) {
        Vec3 origin = player.getEyePosition(partialTick);
        Vec3 dir = player.getViewVector(partialTick);
        SubLevel subLevel = SableCompat.getContainingSubLevel(level, pos);
        if (subLevel != null) {
            origin = SableCompat.toLocalPosition(subLevel, partialTick, origin);
            dir = SableCompat.toLocalDirection(subLevel, partialTick, dir);
        }
        return new Vec3[]{origin, dir};
    }

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

    /**
     * Monitor 屏幕上的点 → plot（子次元局部）坐标。
     * 注意：故意返回 plot 局部坐标，不做世界空间变换 —— Sable 的 LineOutlineMixin
     * 会在 Catnip 绘制线条时用 renderPose()（与方块同一插值姿态、同一 partialTick）
     * 把子次元内端点变换到世界空间；绘制时刻变换保证移动物理体上零滞后。
     * 不在子次元时 plot 坐标即世界坐标，行为不变。
     */
    private static Vec3 world(BlockPos pos, float x, float y, float z, Direction f,
                              float yaw, float pitch, int offset) {
        // 模型空间 → 块局部（pitch → yaw → offset），再 facing + 方块偏移
        double[] p = { x, y, z };
        MonitorBlock.transformPointToLocal(p, yaw, pitch, offset);
        Vec3 r = rot((float) p[0], (float) p[1], (float) p[2], f);
        return new Vec3(pos.getX() + r.x, pos.getY() + r.y, pos.getZ() + r.z);
    }

    // ── 网格线 ──

    private static void drawGridLines(Outliner o, BlockPos pos, Direction f,
                                      float yaw, float pitch, int offset, String keyPrefix) {
        float z = MonitorBlock.SCREEN_Z / 16f + GRID_LINE_OFFSET;
        float x0 = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET) / 16f;
        float x1 = (MonitorBlock.SCREEN_X_MAX - MonitorBlock.GRID_INSET) / 16f;
        float y0 = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET) / 16f;
        float y1 = (MonitorBlock.SCREEN_Y_MAX - MonitorBlock.GRID_INSET) / 16f;
        float lw = (float) (1 / 256f * Config.MONITOR_GRID_LINE_WIDTH.get());

        for (int i = 0; i <= GridState.GRID_WIDTH; i++) {
            float x = x0 + i / 16f;
            Vec3 from = world(pos, x, y0, z, f, yaw, pitch, offset);
            Vec3 to = world(pos, x, y1, z, f, yaw, pitch, offset);
            o.showLine(keyPrefix + "/grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= GridState.GRID_HEIGHT; i++) {
            float y = y0 + i / 16f;
            Vec3 from = world(pos, x0, y, z, f, yaw, pitch, offset);
            Vec3 to = world(pos, x1, y, z, f, yaw, pitch, offset);
            o.showLine(keyPrefix + "/grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    // ── 模块/预览矩形边框 ──

    private static void drawModuleOutline(Outliner o, BlockPos pos,
                                           int gx, int gy, int w, int h, String slot, int color, Direction f,
                                           float yaw, float pitch, int offset) {
        float x0 = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + gx) / 16f;
        float y0 = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + gy) / 16f;
        float x1 = x0 + w / 16f;
        float y1 = y0 + h / 16f;
        float z = MonitorBlock.SCREEN_Z / 16f + GRID_LINE_OFFSET;
        float lw = (float) (1 / 128f * Config.MONITOR_OUTLINE_LINE_WIDTH.get());

        Vec3 p00 = world(pos, x0, y0, z, f, yaw, pitch, offset);
        Vec3 p10 = world(pos, x1, y0, z, f, yaw, pitch, offset);
        Vec3 p11 = world(pos, x1, y1, z, f, yaw, pitch, offset);
        Vec3 p01 = world(pos, x0, y1, z, f, yaw, pitch, offset);

        o.showLine(slot + "_top",    p00, p10).colored(color).lineWidth(lw);
        o.showLine(slot + "_right",  p10, p11).colored(color).lineWidth(lw);
        o.showLine(slot + "_bottom", p11, p01).colored(color).lineWidth(lw);
        o.showLine(slot + "_left",   p01, p00).colored(color).lineWidth(lw);
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

            KnobInteractionHandler.tick(mc, pos, state);
        }
    }

}
