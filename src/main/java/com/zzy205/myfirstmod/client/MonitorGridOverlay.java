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
import com.zzy205.myfirstmod.network.ModuleKnobRotatePayload;
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
import net.minecraft.sounds.SoundEvents;
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

    /** 获取正在拖动的旋钮把手的视觉角度（含卡位前半程微扭动）；非卡位或未拖动时返回 null。 */
    public static Float getActiveKnobVisualAngle(BlockPos pos, int moduleId) {
        InteractionState state = interactions.get(pos);
        if (state == null || !state.knobDragging || state.knobDragModuleId != moduleId
                || state.knobDetentStep <= 0) return null;
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
        SubLevel subLevel = SableCompat.getContainingSubLevel(level, pos);
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
                hoveredTooltip = Component.literal(text);
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
            interact.knobDragging = true;
            interact.knobDragFacing = facing;
            interact.knobDragModuleId = hoveredModule.id();
            interact.knobCenterX = MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + hoveredModule.gridX() + hoveredModule.getWidth() / 2f;
            interact.knobCenterY = MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + hoveredModule.gridY() + hoveredModule.getHeight() / 2f;
            int detentStep = 0;
            if (monitorBE != null) {
                interact.knobAccumAngle = monitorBE.getGridState().getKnobAngle(hoveredModule.id());
                detentStep = monitorBE.getGridState().getDetentStep(hoveredModule.id());
            }
            interact.knobPrevRawAngle = computeCrosshairAngle(pos, facing, monitorYaw, monitorPitch, monitorOffset,
                    ray[0], ray[1], interact.knobCenterX, interact.knobCenterY);
            interact.knobUnwrappedDelta = 0f;
            interact.knobLastSoundAngle = interact.knobAccumAngle;
            interact.knobDisplayAngle = normalizeDisplayAngle(interact.knobAccumAngle);
            // 卡位模式：以当前角度的最近档位作为起始档位，避免拖拽第一帧误触发音效
            interact.knobLastDetent = detentStep > 0
                    ? GridState.snapToDetent(interact.knobAccumAngle, detentStep)
                    : interact.knobDisplayAngle;
            interact.knobDetentStep = detentStep;
            interact.knobVisualAngle = interact.knobDisplayAngle;
        } else if (interact.knobDragging && !useDown) {
            interact.knobDragging = false;
            interact.knobDragModuleId = -1;
        }
        } // !holdingScreen && !screenPlacing

        // 1. 网格线（手持模块或屏幕物品时）
        if (showGrid) {
            drawGridLines(outliner, pos, facing, monitorYaw, monitorPitch, monitorOffset, keyPrefix, subLevel, partialTick);
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
                    monitorYaw, monitorPitch, monitorOffset, subLevel, partialTick);
        }

        // 2. 放置预览 / 对准高亮
        if (showPreview && !interact.screenPlacing && !holdingScreen) {
            if (heldType != null && gp != null) {
                boolean ok = grid.canPlace(gp[0], gp[1], heldType.width, heldType.height);
                int color = ok ? 0x4CDA64 : 0xFF5E5E;
                drawModuleOutline(outliner, pos, gp[0], gp[1],
                        heldType.width, heldType.height, keyPrefix + "/preview", color, facing,
                        monitorYaw, monitorPitch, monitorOffset, subLevel, partialTick);
            } else if (hoveredModule != null) {
                drawModuleOutline(outliner, pos, hoveredModule.gridX(), hoveredModule.gridY(),
                        hoveredModule.getWidth(), hoveredModule.getHeight(),
                        keyPrefix + "/hover", moduleColor, facing,
                        monitorYaw, monitorPitch, monitorOffset, subLevel, partialTick);
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
                            monitorYaw, monitorPitch, monitorOffset, subLevel, partialTick);
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

    private static Vec3 world(BlockPos pos, float x, float y, float z, Direction f,
                              float yaw, float pitch, int offset, SubLevel subLevel, float partialTick) {
        // 模型空间 → 块局部（pitch → yaw → offset），再 facing + 方块偏移
        double[] p = { x, y, z };
        MonitorBlock.transformPointToLocal(p, yaw, pitch, offset);
        Vec3 r = rot((float) p[0], (float) p[1], (float) p[2], f);
        Vec3 local = new Vec3(pos.getX() + r.x, pos.getY() + r.y, pos.getZ() + r.z);
        return SableCompat.toWorldPosition(subLevel, partialTick, local);
    }

    // ── 网格线 ──

    private static void drawGridLines(Outliner o, BlockPos pos, Direction f,
                                      float yaw, float pitch, int offset, String keyPrefix,
                                      SubLevel subLevel, float partialTick) {
        float z = MonitorBlock.SCREEN_Z / 16f + GRID_LINE_OFFSET;
        float x0 = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET) / 16f;
        float x1 = (MonitorBlock.SCREEN_X_MAX - MonitorBlock.GRID_INSET) / 16f;
        float y0 = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET) / 16f;
        float y1 = (MonitorBlock.SCREEN_Y_MAX - MonitorBlock.GRID_INSET) / 16f;
        float lw = (float) (1 / 256f * Config.MONITOR_GRID_LINE_WIDTH.get());

        for (int i = 0; i <= GridState.GRID_WIDTH; i++) {
            float x = x0 + i / 16f;
            Vec3 from = world(pos, x, y0, z, f, yaw, pitch, offset, subLevel, partialTick);
            Vec3 to = world(pos, x, y1, z, f, yaw, pitch, offset, subLevel, partialTick);
            o.showLine(keyPrefix + "/grid_v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= GridState.GRID_HEIGHT; i++) {
            float y = y0 + i / 16f;
            Vec3 from = world(pos, x0, y, z, f, yaw, pitch, offset, subLevel, partialTick);
            Vec3 to = world(pos, x1, y, z, f, yaw, pitch, offset, subLevel, partialTick);
            o.showLine(keyPrefix + "/grid_h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    // ── 模块/预览矩形边框 ──

    private static void drawModuleOutline(Outliner o, BlockPos pos,
                                           int gx, int gy, int w, int h, String slot, int color, Direction f,
                                           float yaw, float pitch, int offset, SubLevel subLevel, float partialTick) {
        float x0 = (MonitorBlock.SCREEN_X_MIN + MonitorBlock.GRID_INSET + gx) / 16f;
        float y0 = (MonitorBlock.SCREEN_Y_MIN + MonitorBlock.GRID_INSET + gy) / 16f;
        float x1 = x0 + w / 16f;
        float y1 = y0 + h / 16f;
        float z = MonitorBlock.SCREEN_Z / 16f + GRID_LINE_OFFSET;
        float lw = (float) (1 / 128f * Config.MONITOR_OUTLINE_LINE_WIDTH.get());

        Vec3 p00 = world(pos, x0, y0, z, f, yaw, pitch, offset, subLevel, partialTick);
        Vec3 p10 = world(pos, x1, y0, z, f, yaw, pitch, offset, subLevel, partialTick);
        Vec3 p11 = world(pos, x1, y1, z, f, yaw, pitch, offset, subLevel, partialTick);
        Vec3 p01 = world(pos, x0, y1, z, f, yaw, pitch, offset, subLevel, partialTick);

        o.showLine(slot + "_top",    p00, p10).colored(color).lineWidth(lw);
        o.showLine(slot + "_right",  p10, p11).colored(color).lineWidth(lw);
        o.showLine(slot + "_bottom", p11, p01).colored(color).lineWidth(lw);
        o.showLine(slot + "_left",   p01, p00).colored(color).lineWidth(lw);
    }

    // ── 旋钮拖拽：准心绕旋钮中心旋转 → 旋钮跟随 ──

    /** 计算准心视线射线在屏幕上的命中点相对旋钮中心的角度（弧度）。origin/dir 为 plot 空间。 */
    private static float computeCrosshairAngle(BlockPos pos, Direction facing, float yaw, float pitch, int offset,
                                                Vec3 origin, Vec3 dir, float knobCx, float knobCy) {
        float[] local = MonitorBlock.rayToScreenLocal(pos, facing, yaw, pitch, offset, origin, dir);
        if (local == null) return 0f;
        return (float) Math.atan2(local[1] - knobCy, local[0] - knobCx);
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

            // 独立动态命中检测：准心不再对准该 Monitor 的屏幕 → 取消拖拽
            var knobHit = mc.level == null ? null : MonitorHitDetector.find(mc.level, mc.player, 1.0f);
            if (knobHit == null || !knobHit.pos().equals(pos)) {
                state.knobDragging = false;
                state.knobDragModuleId = -1;
                continue;
            }

            // 当前 raw 角度 → 解缠绕（用动态射线求交，兼容可动 Monitor）
            MonitorBlockEntity knobMonitorBE = (mc.level != null && mc.level.getBlockEntity(pos) instanceof MonitorBlockEntity m) ? m : null;
            float knobYaw = knobMonitorBE != null ? knobMonitorBE.getYawAngle() : 0f;
            float knobPitch = knobMonitorBE != null ? knobMonitorBE.getPitchAngle() : 0f;
            int knobOffset = knobMonitorBE != null ? knobMonitorBE.getOffset() : 0;
            Vec3[] ray = crosshairRay(mc.level, pos, mc.player, 1.0f);
            float rawAngle = computeCrosshairAngle(pos, state.knobDragFacing, knobYaw, knobPitch, knobOffset,
                    ray[0], ray[1], state.knobCenterX, state.knobCenterY);
            float diff = rawAngle - state.knobPrevRawAngle;
            if (diff > Math.PI) diff -= (float)(2 * Math.PI);
            else if (diff < -Math.PI) diff += (float)(2 * Math.PI);
            state.knobUnwrappedDelta += diff;
            state.knobPrevRawAngle = rawAngle;

            float newAngle = state.knobAccumAngle + (float) Math.toDegrees(state.knobUnwrappedDelta);

            // 读取卡位配置（0 = 自由旋转）
            int detentStep = 0;
            if (mc.level != null && mc.level.getBlockEntity(pos) instanceof MonitorBlockEntity be) {
                detentStep = be.getGridState().getDetentStep(state.knobDragModuleId);
            }

            float sendAngle;
            if (detentStep > 0) {
                // ── 卡位模式：吸附到最近档位，只在跨档时发声 ──
                float norm = normalizeDisplayAngle(newAngle);
                float snapped = GridState.snapToDetent(norm, detentStep);
                state.knobDisplayAngle = snapped;
                if (snapped != state.knobLastDetent) {
                    float pitch = 0.5f + (snapped / 360f) * 1.5f;
                    mc.player.playSound(SoundEvents.LEVER_CLICK, 0.1f, pitch);
                    state.knobLastDetent = snapped;
                }
                // 弹性微扭动：把手滞后于手部 1/3（最大偏离 step/6），松手后由渲染器弹回档位
                float off = norm - snapped;
                if (off > 180f) off -= 360f;
                else if (off < -180f) off += 360f;
                state.knobVisualAngle = normalizeDisplayAngle(snapped + off / 3f);
                sendAngle = snapped;
            } else {
                // ── 自由模式：每 12° 播放一次谢泼德音阶音效 ──
                state.knobDisplayAngle = normalizeDisplayAngle(newAngle);
                float soundDiff = newAngle - state.knobLastSoundAngle;
                int soundSteps = (int) (soundDiff / KNOB_SOUND_STEP);
                if (soundSteps != 0) {
                    float cycleAngle = newAngle % 360f;
                    if (cycleAngle < 0) cycleAngle += 360f;
                    float pitch = 0.5f + (cycleAngle / 360f) * 1.5f;
                    mc.player.playSound(SoundEvents.LEVER_CLICK, 0.1f, pitch);
                    state.knobLastSoundAngle = newAngle - (soundDiff - soundSteps * KNOB_SOUND_STEP);
                }
                sendAngle = newAngle;
            }

            // 周期性发送旋转角度到服务端
            state.knobSendCooldown--;
            if (state.knobSendCooldown <= 0) {
                state.knobSendCooldown = KNOB_SEND_INTERVAL;
                PacketDistributor.sendToServer(
                        new ModuleKnobRotatePayload(pos, state.knobDragModuleId, sendAngle));
            }
        }
    }

    private static float normalizeDisplayAngle(float angle) {
        float normalized = angle % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

}
