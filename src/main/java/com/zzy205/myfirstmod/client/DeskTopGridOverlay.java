package com.zzy205.myfirstmod.client;

import com.simibubi.create.AllItems;
import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.GridTarget;
import com.zzy205.myfirstmod.network.PlaceModulePayload;
import com.zzy205.myfirstmod.network.RemoveModulePayload;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 控制台<b>桌顶小模块</b>（monitor 模块：button / toggle_switch / knob）放置与拆除交互（最小可用版）：
 * <ul>
 *   <li>手持模块物品 + 准星指向控制台 → 桌顶显示 6×14 棋盘网格 + 放置预览框（绿=可放 / 红=被占/与大模块重叠）</li>
 *   <li>右键空格子放置（{@link PlaceModulePayload} + {@link GridTarget#DESK_TOP}，服务端落库并消耗物品）</li>
 *   <li>扳手蹲下右键命中已放的小模块 → 拆除（{@link RemoveModulePayload}，物品返还背包/掉落）</li>
 * </ul>
 * 桌顶网格 = 北向基准 14×6 格（x1..15 / z9..15，1px/格），格 (gx, gy) ↔ px (1+gx, 9+gy)；
 * 与桌顶大模块（joystick_2 / throttle / throttle_2 / monitor_2）占地互斥（{@code ControlDeskBlockEntity#deskTopOverlapsBigModule}）。
 * monitor_2 屏幕面命中时由 {@link Monitor2GridOverlay} 接管（模块物品优先放屏幕）。
 */
public class DeskTopGridOverlay {

    private static final int COLOR_VALID = 0x4CDA64;
    private static final int COLOR_INVALID = 0xFF5E5E;

    /** 右键边沿检测（防连发） */
    private static boolean lastUseDown;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(DeskTopGridOverlay::onClientTick);
    }

    @SubscribeEvent
    private static void onClientTick(ClientTickEvent.Pre event) {
        // 功能临时关闭（总开关见 ControlDeskBlockEntity.DESK_TOP_MODULES_ENABLED），重新启用改为 true 即可
        if (!ControlDeskBlockEntity.DESK_TOP_MODULES_ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        ItemStack held = mc.player.getMainHandItem();
        ModuleType heldType = ModuleType.fromItem(held);
        boolean wrench = isWrench(held);
        if (heldType == null && !wrench) return;

        if (!(mc.hitResult instanceof BlockHitResult hit)) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return;

        // monitor_2 屏幕面命中时由 Monitor2GridOverlay 接管（模块物品优先放屏幕）
        var screenHit = Monitor2HitDetector.find(mc.level, mc.player, 1.0f);
        if (screenHit != null && screenHit.pos().equals(pos)) return;

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        GridState grid = desk.getDeskTopGrid();
        int[] cell = ControlDeskBlock.deskTopCellAt(pos, facing, hit.getLocation());

        boolean useDown = mc.options.keyUse.isDown();
        boolean useEdge = useDown && !lastUseDown;
        lastUseDown = useDown;
        boolean shiftUseEdge = useEdge && mc.player.isShiftKeyDown();

        String keyPrefix = "control-desk/desktop/" + pos.toShortString();

        // ── 拆除：扳手蹲下右键命中桌顶小模块 → payload（服务端落库 + 返还物品）──
        if (wrench && shiftUseEdge && cell != null) {
            int moduleId = grid.getCell(cell[0], cell[1]);
            if (moduleId >= 0) {
                PacketDistributor.sendToServer(new RemoveModulePayload(pos, moduleId, GridTarget.DESK_TOP));
                return;
            }
        }

        // ── 放置：手持模块物品 + 右键空格子 → payload（服务端落库 + 消耗物品）──
        if (heldType != null && !wrench && useEdge && cell != null) {
            if (canPlace(desk, grid, cell[0], cell[1], heldType)) {
                PacketDistributor.sendToServer(
                        new PlaceModulePayload(pos, cell[0], cell[1], heldType.name, GridTarget.DESK_TOP));
                return;
            }
        }

        // ── 预览绘制 ──
        Outliner outliner = Outliner.getInstance();
        if (heldType != null) {
            drawGrid(outliner, pos, facing, keyPrefix);
            if (cell != null) {
                boolean ok = canPlace(desk, grid, cell[0], cell[1], heldType);
                drawCellBox(outliner, pos, facing, cell[0], cell[1],
                        heldType.width, heldType.height, ok ? COLOR_VALID : COLOR_INVALID, keyPrefix + "/preview");
            }
        } else if (wrench && cell != null) {
            int moduleId = grid.getCell(cell[0], cell[1]);
            if (moduleId >= 0) {
                var mod = grid.getModule(moduleId);
                if (mod != null) {
                    drawCellBox(outliner, pos, facing, mod.gridX(), mod.gridY(),
                            mod.getWidth(), mod.getHeight(), COLOR_VALID, keyPrefix + "/hover");
                }
            }
        }
    }

    /** 候选格能否放置（网格占用 + 与桌顶大模块占地互斥，与服务端一致）。 */
    private static boolean canPlace(ControlDeskBlockEntity desk, GridState grid, int gx, int gy, ModuleType type) {
        return grid.canPlace(gx, gy, type.width, type.height)
                && !desk.deskTopOverlapsBigModule(1 + gx, 9 + gy, type.width, type.height);
    }

    /** 桌顶 6×14 棋盘网格线（1px/格，x1..15 / z9..15，y=桌顶面防 z-fight；与 showTopGrid 同款）。 */
    private static void drawGrid(Outliner outliner, BlockPos pos, Direction facing, String keyPrefix) {
        float lw = 1 / 128f;
        for (int i = 0; i <= ControlDeskBlockEntity.DESK_TOP_GRID_WIDTH; i++) {
            Vec3 from = ControlDeskPlacementOverlay.gridWorld(pos, 1 + i, 7.1f, 9f, facing);
            Vec3 to = ControlDeskPlacementOverlay.gridWorld(pos, 1 + i, 7.1f, 15f, facing);
            outliner.showLine(keyPrefix + "/v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= ControlDeskBlockEntity.DESK_TOP_GRID_HEIGHT; i++) {
            Vec3 from = ControlDeskPlacementOverlay.gridWorld(pos, 1f, 7.1f, 9 + i, facing);
            Vec3 to = ControlDeskPlacementOverlay.gridWorld(pos, 15f, 7.1f, 9 + i, facing);
            outliner.showLine(keyPrefix + "/h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    /** 桌顶模块占地框（格 gx,gy 起 w×h，画在桌顶面）。 */
    private static void drawCellBox(Outliner outliner, BlockPos pos, Direction facing,
                                    int gx, int gy, int w, int h, int color, String key) {
        float x0 = 1 + gx;
        float z0 = 9 + gy;
        float x1 = 1 + gx + w;
        float z1 = 9 + gy + h;
        float y = 7.1f;
        Vec3 p00 = ControlDeskPlacementOverlay.gridWorld(pos, x0, y, z0, facing);
        Vec3 p10 = ControlDeskPlacementOverlay.gridWorld(pos, x1, y, z0, facing);
        Vec3 p11 = ControlDeskPlacementOverlay.gridWorld(pos, x1, y, z1, facing);
        Vec3 p01 = ControlDeskPlacementOverlay.gridWorld(pos, x0, y, z1, facing);
        outliner.showLine(key + "_t", p00, p10).colored(color).lineWidth(1 / 32f);
        outliner.showLine(key + "_r", p10, p11).colored(color).lineWidth(1 / 32f);
        outliner.showLine(key + "_b", p11, p01).colored(color).lineWidth(1 / 32f);
        outliner.showLine(key + "_l", p01, p00).colored(color).lineWidth(1 / 32f);
    }

    /** 是否为扳手：Create 扳手，或加入了原版 {@code minecraft:tools/wrenches} tag 的其它扳手。 */
    private static boolean isWrench(ItemStack stack) {
        return stack.is(AllItems.WRENCH.get()) || stack.is(Tags.Items.TOOLS_WRENCH);
    }
}
