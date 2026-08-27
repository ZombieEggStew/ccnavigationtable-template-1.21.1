package com.zzy205.myfirstmod.client;

import com.simibubi.create.AllItems;
import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.MyModBlocks;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.screen.ControlDeskConfigScreen;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;

import java.util.List;

/**
 * controlDesk 交互预览与菜单打开：
 * <ul>
 *   <li>手持踏板/操纵杆 → 准星指向 controlDesk 时在安装位显示预览框（绿=可装 / 红=已装）</li>
 *   <li>手持控制台方块物品 → 准星指向 controlDesk 时显示拓展坞安装预览框
 *       （北向基准 0,0,0,16,8,8 = 桌体北侧整块空区，随 FACING 旋转；仅预览框，无 ghost 实物）；
 *       右键安装后控制台转为 slab 形态（DOCKED，见 {@link ControlDeskBlock}）</li>
 *   <li>手持扳手 → 准星指向 controlDesk 时显示已安装控件的安装位（默认绿）；视角命中安装位变红，蹲下右键拆对应模块</li>
 *   <li>扳手普通右键（不蹲下）或 空手蹲下右键，准星指向 controlDesk（任意位置）→ 打开控制台配置菜单 {@link ControlDeskConfigScreen}（右键边沿防连发）；扳手蹲下右键 → 不拦截，交给服务端 {@code onSneakWrenched} 拆除模块</li>
 * </ul>
 * 每 tick 重新 show，离开/换物品后自动消失（Outliner 语义）。
 */
public class ControlDeskPlacementOverlay {

    private static final int COLOR_VALID = 0x4CDA64;
    private static final int COLOR_INVALID = 0xFF5E5E;

    /** 右键边沿检测（防连发，参考 MonitorGridOverlay） */
    private static boolean lastUseDown;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ControlDeskPlacementOverlay::onClientTick);
    }

    @SubscribeEvent
    private static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        ItemStack held = mc.player.getMainHandItem();

        // ── 需要准星命中控制台方块的分支：菜单打开 / 控件安装预览 / 扳手拆除预览 ──
        // （monitor_2 表面网格走下方独立命中检测，不依赖 hitResult）
        if (mc.hitResult instanceof BlockHitResult hit) {
            // ── 打开控制台配置菜单：扳手普通右键（不蹲下）或 空手蹲下右键，准星指向控制台任意位置 ──
            // 扳手蹲下右键 = 拆除（服务端 onSneakWrenched），这里不拦截，让右键事件正常传到服务端
            boolean useDown = mc.options.keyUse.isDown();
            boolean useEdge = useDown && !lastUseDown;
            lastUseDown = useDown;
            if (useEdge) {
                boolean wrench = isWrench(held);
                boolean emptySneak = held.isEmpty() && mc.player.isShiftKeyDown();
                boolean openMenu = (wrench && !mc.player.isShiftKeyDown()) || emptySneak;
                if (openMenu && isControlDesk(mc, hit)) {
                    mc.setScreen(new ControlDeskConfigScreen(hit.getBlockPos()));
                    return;
                }
            }

            ControlDeskBlockEntity.ControlType type = controlTypeOf(held);
            if (type != null) {
                showInstallPreview(mc, hit, type);
                // monitor_2 / throttle / joystick_2 的原后缘插槽已移除（installBounds 为空 → 无 AABB 安装预览框）；
                // 手持三者时改显桌顶 6×14 棋盘网格（1px 格、内缩 1px；纯显示）
                if (type == ControlDeskBlockEntity.ControlType.THROTTLE
                        || type == ControlDeskBlockEntity.ControlType.JOYSTICK_2
                        || type == ControlDeskBlockEntity.ControlType.MONITOR_2
                        || type == ControlDeskBlockEntity.ControlType.THROTTLE_2) {
                    showTopGrid(mc, hit);
                }
                // joystick_2：额外显示 3D 放置预览盒（4×9×4，底在桌顶面，跟随准星吸附到 1px 网格）
                if (type == ControlDeskBlockEntity.ControlType.JOYSTICK_2) {
                    showJoystick2Box(mc, hit);
                }
                // throttle：额外显示 3D 放置预览盒（14×6×6，唯一合法位 (8,12) 全占桌顶网格）
                if (type == ControlDeskBlockEntity.ControlType.THROTTLE) {
                    showThrottleBox(mc, hit);
                }
                // throttle_2：额外显示 3D 放置预览盒（14×6×6，唯一合法位 (8,12) 全占桌顶网格）
                if (type == ControlDeskBlockEntity.ControlType.THROTTLE_2) {
                    showThrottle2Box(mc, hit);
                }
                // monitor_2：额外显示 3D 放置预览盒（14×6×12，唯一合法位 (8,12) 全占桌顶网格）
                if (type == ControlDeskBlockEntity.ControlType.MONITOR_2) {
                    showMonitor2Box(mc, hit);
                }
                return;
            }
            // 手持控制台方块物品：显示拓展坞安装预览框（右键安装后转为 slab 形态；无 ghost 实物）
            if (isDock(held)) {
                showDockBox(mc, hit);
                return;
            }
            if (isWrench(held)) {
                showRemovePreview(mc, hit);
            }
        }
    }

    /** 准星指向的方块是否为控制台。 */
    private static boolean isControlDesk(Minecraft mc, BlockHitResult hit) {
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof ControlDeskBlock;
    }

    /** 手持控件物品：在安装位显示预览框（绿=可装 / 红=已装）。 */
    private static void showInstallPreview(Minecraft mc, BlockHitResult hit, ControlDeskBlockEntity.ControlType type) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;

        // 装拓展坞后禁装 PEDAL / JOYSTICK（北侧空区被桌面覆盖）：不显示安装预览
        if (state.getValue(ControlDeskBlock.DOCKED)
                && (type == ControlDeskBlockEntity.ControlType.PEDAL
                || type == ControlDeskBlockEntity.ControlType.JOYSTICK)) {
            return;
        }

        BlockEntity be = mc.level.getBlockEntity(pos);
        boolean installed = be instanceof ControlDeskBlockEntity desk && desk.isInstalled(type);

        showBounds(pos, type, state.getValue(ControlDeskBlock.FACING),
                installed ? COLOR_INVALID : COLOR_VALID, "install");
    }

    /** 手持扳手：已安装控件显示绿色预览框；准星视角命中安装位（点击位置在框内）的模块变红，蹲下右键即可拆除。 */
    private static void showRemovePreview(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;

        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof ControlDeskBlockEntity desk)) return;

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        Vec3 click = hit.getLocation();
        for (ControlDeskBlockEntity.ControlType type : ControlDeskBlockEntity.ControlType.values()) {
            if (!desk.isInstalled(type)) continue;
            if (type == ControlDeskBlockEntity.ControlType.JOYSTICK_2) {
                // 摇杆2：显示放置位 4×9×4 盒子（与安装预览/服务端拆除判定共用 ControlDeskBlock.joystick2PlaceBox）
                AABB box = ControlDeskBlock.joystick2PlaceBox(desk, facing, pos);
                boolean hovered = ControlDeskBlock.hitBounds(List.of(box), click);
                Outliner.getInstance().showAABB("control-desk/remove/" + pos.toShortString() + "/JOYSTICK_2", box)
                        .colored(hovered ? COLOR_INVALID : COLOR_VALID)
                        .lineWidth(1 / 64f);
                continue;
            }
            if (type == ControlDeskBlockEntity.ControlType.THROTTLE) {
                // 油门：显示放置位 14×6×6 盒子（与安装预览/服务端拆除判定共用 ControlDeskBlock.throttlePlaceBox）
                AABB box = ControlDeskBlock.throttlePlaceBox(desk, facing, pos);
                boolean hovered = ControlDeskBlock.hitBounds(List.of(box), click);
                Outliner.getInstance().showAABB("control-desk/remove/" + pos.toShortString() + "/THROTTLE", box)
                        .colored(hovered ? COLOR_INVALID : COLOR_VALID)
                        .lineWidth(1 / 64f);
                continue;
            }
            if (type == ControlDeskBlockEntity.ControlType.THROTTLE_2) {
                // 油门2：显示放置位 14×6×6 盒子（与安装预览/服务端拆除判定共用 ControlDeskBlock.throttle2PlaceBox）
                AABB box = ControlDeskBlock.throttle2PlaceBox(desk, facing, pos);
                boolean hovered = ControlDeskBlock.hitBounds(List.of(box), click);
                Outliner.getInstance().showAABB("control-desk/remove/" + pos.toShortString() + "/THROTTLE_2", box)
                        .colored(hovered ? COLOR_INVALID : COLOR_VALID)
                        .lineWidth(1 / 64f);
                continue;
            }
            if (type == ControlDeskBlockEntity.ControlType.MONITOR_2) {
                // 监视器2：显示放置位 14×6×12 盒子（与安装预览/服务端拆除判定共用 ControlDeskBlock.monitor2PlaceBox）
                AABB box = ControlDeskBlock.monitor2PlaceBox(desk, facing, pos);
                boolean hovered = ControlDeskBlock.hitBounds(List.of(box), click);
                Outliner.getInstance().showAABB("control-desk/remove/" + pos.toShortString() + "/MONITOR_2", box)
                        .colored(hovered ? COLOR_INVALID : COLOR_VALID)
                        .lineWidth(1 / 64f);
                continue;
            }
            List<AABB> bounds = ControlDeskBlock.installBounds(type, facing, pos);
            // 命中判断与服务端拆除判定共用 ControlDeskBlock.hitBounds（闭区间+容差）
            boolean hovered = ControlDeskBlock.hitBounds(bounds, click);
            showBounds(pos, type, facing, hovered ? COLOR_INVALID : COLOR_VALID, "remove");
        }
    }

    private static void showBounds(BlockPos pos, ControlDeskBlockEntity.ControlType type,
                                   Direction facing, int color, String mode) {
        List<AABB> bounds = ControlDeskBlock.installBounds(type, facing, pos);
        Outliner outliner = Outliner.getInstance();
        String keyPrefix = "control-desk/" + mode + "/" + pos.toShortString();
        for (int i = 0; i < bounds.size(); i++) {
            outliner.showAABB(keyPrefix + "/" + type + "/" + i, bounds.get(i))
                    .colored(color)
                    .lineWidth(1 / 16f);
        }
    }

    /**
     * 桌顶棋盘网格（1px 格、四周内缩 1px；北向基准 x1..15 / y8，随 FACING 旋转）。
     * 手持 throttle / joystick_2 时显示（对齐 monitor 的白色 1px 网格线）；纯显示，放置逻辑待做。
     * 普通形态 6×14 格（z9..15）；装拓展坞（DOCKED）后桌面扩展为整块，网格变 14×14 格（z1..15）。
     */
    private static void showTopGrid(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        boolean docked = state.getValue(ControlDeskBlock.DOCKED);
        int zMin = docked ? 1 : 9;
        Outliner outliner = Outliner.getInstance();
        String prefix = "control-desk/grid/" + pos.toShortString();
        float lw = 1 / 128f;
        // 15 条竖线（x=1..15，跨 z1..15 / z9..15）+ 横线（普通 7 条 z=9..15 / docked 15 条 z=1..15），
        // y=8（桌顶面，gridWorld 内抬高防 z-fight）
        for (int i = 0; i <= 14; i++) {
            Vec3 from = gridWorld(pos, i + 1f, 7.1f, zMin, facing);
            Vec3 to = gridWorld(pos, i + 1f, 7.1f, 15f, facing);
            outliner.showLine(prefix + "/v" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
        for (int i = 0; i <= 15 - zMin; i++) {
            Vec3 from = gridWorld(pos, 1f, 7.1f, zMin + i, facing);
            Vec3 to = gridWorld(pos, 15f, 7.1f, zMin + i, facing);
            outliner.showLine(prefix + "/h" + i, from, to).colored(0xFFFFFF).lineWidth(lw);
        }
    }

    /** 北向基准模型坐标（px）→ 世界坐标：绕方块中心 Y 旋转到 FACING（与底座模型 rotateCenteredDegrees 同约定）；y 略抬离桌面防 z-fight。 */
    private static Vec3 gridWorld(BlockPos pos, float x, float y, float z, Direction facing) {
        float bx = x / 16f;
        float by = y / 16f + 0.06f; // 略高于桌顶面（0.06 块 ≈ 1px，对齐 monitor GRID_LINE_OFFSET）
        float bz = z / 16f;
        return switch (facing) {
            case NORTH -> new Vec3(pos.getX() + bx, pos.getY() + by, pos.getZ() + bz);
            case SOUTH -> new Vec3(pos.getX() + (1f - bx), pos.getY() + by, pos.getZ() + (1f - bz));
            case WEST  -> new Vec3(pos.getX() + bz, pos.getY() + by, pos.getZ() + (1f - bx));
            case EAST  -> new Vec3(pos.getX() + (1f - bz), pos.getY() + by, pos.getZ() + bx);
            default    -> new Vec3(pos.getX() + bx, pos.getY() + by, pos.getZ() + bz);
        };
    }

    /**
     * 手持 joystick_2：3D 放置预览盒（占地 4×4、高 9，底在桌顶面下方 1px = y7..16）。
     * 参考 monitor 的 2D 模块预览（跟随准星、吸附网格）：这里用 {@link Outliner#showAABB} 画 3D 盒子（12 棱边）。
     * 盒子中心 = 命中点吸附后<b>钳制</b>到「4×4 占位完全位于桌顶网格内」的合法范围
     * （与服务端放置共用 {@link ControlDeskBlock#snappedBoxCenterClamped}），预览只能在可放置区域内移动；
     * 与已装模块占用重叠时盒子变红。
     */
    private static void showJoystick2Box(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        Direction facing = state.getValue(ControlDeskBlock.FACING);
        boolean docked = state.getValue(ControlDeskBlock.DOCKED);

        int[] c = ControlDeskBlock.snappedBoxCenterClamped(pos, facing, hit.getLocation(), docked,
                ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF, ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF);
        int cx = c[0];
        int cz = c[1];
        boolean blocked = isJoystick2PlacementBlocked(mc, pos, cx, cz);
        int half = ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF;
        Vec3 p0 = gridWorld(pos, cx - half, ControlDeskBlockEntity.JOYSTICK_2_PLACE_Y_BOTTOM, cz - half, facing);
        Vec3 p1 = gridWorld(pos, cx + half, ControlDeskBlockEntity.JOYSTICK_2_PLACE_Y_TOP, cz + half, facing);
        AABB box = new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
        Outliner.getInstance().showAABB("control-desk/box/" + pos.toShortString(), box)
                .colored(blocked ? COLOR_INVALID : COLOR_VALID)
                .lineWidth(1 / 64f);
    }

    /**
     * 手持 throttle：3D 放置预览盒（占地 14×6、高 6，底在桌顶面下方 1px = y7..13）。
     * 盒子中心 = 命中点吸附后<b>钳制</b>到「14×6 占位完全位于桌顶网格内」的合法范围
     * （与服务端放置共用 {@link ControlDeskBlock#snappedBoxCenterClamped}），预览只能在可放置区域内移动；
     * 与已装模块占用重叠时变红。
     */
    private static void showThrottleBox(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        Direction facing = state.getValue(ControlDeskBlock.FACING);
        boolean docked = state.getValue(ControlDeskBlock.DOCKED);

        int[] c = ControlDeskBlock.snappedBoxCenterClamped(pos, facing, hit.getLocation(), docked,
                ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X, ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z);
        int cx = c[0];
        int cz = c[1];
        boolean blocked = isThrottlePlacementBlocked(mc, pos, cx, cz);
        Vec3 p0 = gridWorld(pos, cx - ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_PLACE_Y_BOTTOM, cz - ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z, facing);
        Vec3 p1 = gridWorld(pos, cx + ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_PLACE_Y_TOP, cz + ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z, facing);
        AABB box = new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
        Outliner.getInstance().showAABB("control-desk/box-throttle/" + pos.toShortString(), box)
                .colored(blocked ? COLOR_INVALID : COLOR_VALID)
                .lineWidth(1 / 64f);
    }

    /**
     * 手持 throttle_2：3D 放置预览盒（占地 14×6、高 6，底在桌顶面下方 1px = y7..13）。
     * 盒子中心 = 命中点吸附后<b>钳制</b>到「14×6 占位完全位于桌顶网格内」的合法范围
     * （与服务端放置共用 {@link ControlDeskBlock#snappedBoxCenterClamped}），预览只能在可放置区域内移动；
     * 与已装模块占用重叠时变红。
     */
    private static void showThrottle2Box(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        Direction facing = state.getValue(ControlDeskBlock.FACING);
        boolean docked = state.getValue(ControlDeskBlock.DOCKED);

        int[] c = ControlDeskBlock.snappedBoxCenterClamped(pos, facing, hit.getLocation(), docked,
                ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X, ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z);
        int cx = c[0];
        int cz = c[1];
        boolean blocked = isThrottle2PlacementBlocked(mc, pos, cx, cz);
        Vec3 p0 = gridWorld(pos, cx - ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_2_PLACE_Y_BOTTOM, cz - ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z, facing);
        Vec3 p1 = gridWorld(pos, cx + ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.THROTTLE_2_PLACE_Y_TOP, cz + ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z, facing);
        AABB box = new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
        Outliner.getInstance().showAABB("control-desk/box-throttle2/" + pos.toShortString(), box)
                .colored(blocked ? COLOR_INVALID : COLOR_VALID)
                .lineWidth(1 / 64f);
    }

    /**
     * 手持 monitor_2：3D 放置预览盒（占地 14×6、高 12，底在桌顶面下方 1px = y7..19）。
     * 盒子中心 = 命中点吸附后<b>钳制</b>到「14×6 占位完全位于桌顶网格内」的合法范围
     * （与服务端放置共用 {@link ControlDeskBlock#snappedBoxCenterClamped}），预览只能在可放置区域内移动；
     * 与已装模块占用重叠时变红。
     */
    private static void showMonitor2Box(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        Direction facing = state.getValue(ControlDeskBlock.FACING);
        boolean docked = state.getValue(ControlDeskBlock.DOCKED);

        int[] c = ControlDeskBlock.snappedBoxCenterClamped(pos, facing, hit.getLocation(), docked,
                ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X, ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z);
        int cx = c[0];
        int cz = c[1];
        boolean blocked = isMonitor2PlacementBlocked(mc, pos, cx, cz);
        Vec3 p0 = gridWorld(pos, cx - ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.MONITOR_2_PLACE_Y_BOTTOM, cz - ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z, facing);
        Vec3 p1 = gridWorld(pos, cx + ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X,
                ControlDeskBlockEntity.MONITOR_2_PLACE_Y_TOP, cz + ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z, facing);
        AABB box = new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
        Outliner.getInstance().showAABB("control-desk/box-monitor2/" + pos.toShortString(), box)
                .colored(blocked ? COLOR_INVALID : COLOR_VALID)
                .lineWidth(1 / 64f);
    }

    /**
     * 手持控制台方块物品：显示拓展坞安装预览框（北向基准 0,0,0,16,8,8 = 桌体北侧整块空区，随 FACING 旋转）。
     * 仅显示预览框 —— 无半透明 ghost 实物；已装拓展坞变红（不可重复安装）。
     */
    private static void showDockBox(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;
        Direction facing = state.getValue(ControlDeskBlock.FACING);
        boolean installed = state.getValue(ControlDeskBlock.DOCKED);

        Vec3 p0 = gridWorld(pos, 0, 0, 0, facing);
        Vec3 p1 = gridWorld(pos, 16, 8, 8, facing);
        AABB box = new AABB(
                Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.min(p0.z, p1.z),
                Math.max(p0.x, p1.x), Math.max(p0.y, p1.y), Math.max(p0.z, p1.z));
        Outliner.getInstance().showAABB("control-desk/box-dock/" + pos.toShortString(), box)
                .colored(installed ? COLOR_INVALID : COLOR_VALID)
                .lineWidth(1 / 16f);
    }

    /**
     * monitor_2 屏幕面点（北向基准模型空间 px）→ 世界坐标。
     * 变换链与渲染一致：case 22.5° x 旋转（Blockbench 元素 rotation，绕 origin [14,4,3]）→ px/16 →
     * 放置平移 shift = ((placeX-modelCenter)/16, (MODEL_PLACE_Y-modelBottomY)/16, (placeZ-modelCenter)/16)
     * → 桌体 FACING 旋转（绕方块中心 Y，gridWorld 同约定）→ +方块坐标。
     * 网格线画在屏幕面本身（无内凹偏移，用户定稿 0px）。
     * <p>供 {@link Monitor2GridOverlay} 复用（monitor_2 表面网格/模块框绘制）。
     * {@code placeX/placeZ} 为 monitor_2 实际放置中心（BE 存储，网格自由放置后跟随吸附位置，勿用固定常量）。
     */
    static Vec3 monitor2World(BlockPos pos, float x, float y, float z, Direction facing, int placeX, int placeZ) {
        // 网格线画在屏幕面本身（无内凹偏移，用户定稿 0px）
        z += 0.0f;

        // case 22.5° x 轴旋转（绕 origin，Blockbench 元素 rotation；方向符号待进游戏验证，反了翻转 TILT_DEG 符号）
        double rad = Math.toRadians(ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_DEG);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double oy = ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Y;
        double oz = ControlDeskBlockEntity.MONITOR_2_SCREEN_TILT_ORIGIN_Z;
        double dy = y - oy, dz = z - oz;
        double ry = oy + dy * cos - dz * sin;
        double rz = oz + dy * sin + dz * cos;

        // px → 块 + 放置平移
        double bx = x / 16.0 + (placeX - ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER) / 16.0;
        double by = ry / 16.0 + (ControlDeskBlockEntity.MODEL_PLACE_Y - ControlDeskBlockEntity.MONITOR_2_MODEL_BOTTOM_Y) / 16.0;
        double bz = rz / 16.0 + (placeZ - ControlDeskBlockEntity.MONITOR_2_MODEL_CENTER) / 16.0;

        // 桌体 FACING 旋转（绕方块中心 Y）+ 方块偏移
        return switch (facing) {
            case NORTH -> new Vec3(pos.getX() + bx, pos.getY() + by, pos.getZ() + bz);
            case SOUTH -> new Vec3(pos.getX() + (1 - bx), pos.getY() + by, pos.getZ() + (1 - bz));
            case WEST -> new Vec3(pos.getX() + bz, pos.getY() + by, pos.getZ() + (1 - bx));
            case EAST -> new Vec3(pos.getX() + (1 - bz), pos.getY() + by, pos.getZ() + bx);
            default -> new Vec3(pos.getX() + bx, pos.getY() + by, pos.getZ() + bz);
        };
    }

    /**
     * joystick_2 候选放置（中心 cx,cz）是否被阻挡：同类型已装 /
     * 与已装模块占地矩形重叠（{@link ControlDeskBlockEntity#blocksPlacement}，含 throttle / monitor_2 的 14×6）。
     */
    private static boolean isJoystick2PlacementBlocked(Minecraft mc, BlockPos pos, int cx, int cz) {
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return false;
        return desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK_2)
                || desk.blocksPlacement(cx, cz,
                        ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF, ControlDeskBlockEntity.JOYSTICK_2_FOOTPRINT_HALF);
    }

    /**
     * throttle 候选放置（唯一合法位 (8,12)）是否被阻挡：同类型已装 /
     * 与已装模块占地矩形重叠（{@link ControlDeskBlockEntity#blocksPlacement}，含 joystick_2 的 4×4 与 monitor_2 的 14×6）。
     */
    private static boolean isThrottlePlacementBlocked(Minecraft mc, BlockPos pos, int cx, int cz) {
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return false;
        return desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE)
                || desk.blocksPlacement(cx, cz,
                        ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_X, ControlDeskBlockEntity.THROTTLE_FOOTPRINT_HALF_Z);
    }

    /**
     * throttle_2 候选放置（唯一合法位 (8,12)）是否被阻挡：同类型已装 /
     * 与已装模块占地矩形重叠（{@link ControlDeskBlockEntity#blocksPlacement}，含 joystick_2 的 4×4 与 throttle / monitor_2 的 14×6）。
     */
    private static boolean isThrottle2PlacementBlocked(Minecraft mc, BlockPos pos, int cx, int cz) {
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return false;
        return desk.isInstalled(ControlDeskBlockEntity.ControlType.THROTTLE_2)
                || desk.blocksPlacement(cx, cz,
                        ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_X, ControlDeskBlockEntity.THROTTLE_2_FOOTPRINT_HALF_Z);
    }

    /**
     * monitor_2 候选放置（唯一合法位 (8,12)）是否被阻挡：同类型已装 /
     * 与已装模块占地矩形重叠（{@link ControlDeskBlockEntity#blocksPlacement}，含 joystick_2 的 4×4 与 throttle 的 14×6）。
     */
    private static boolean isMonitor2PlacementBlocked(Minecraft mc, BlockPos pos, int cx, int cz) {
        if (!(mc.level.getBlockEntity(pos) instanceof ControlDeskBlockEntity desk)) return false;
        return desk.isInstalled(ControlDeskBlockEntity.ControlType.MONITOR_2)
                || desk.blocksPlacement(cx, cz,
                        ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_X, ControlDeskBlockEntity.MONITOR_2_FOOTPRINT_HALF_Z);
    }

    /** 手持物品 → 控件类型（供本类预览判定与 {@link ControlDeskGhostPreviewRenderer} 半透明模型预览复用）。 */
    static ControlDeskBlockEntity.ControlType controlTypeOf(ItemStack stack) {
        if (stack.is(MyModItems.CONTROL_PEDAL.get())) return ControlDeskBlockEntity.ControlType.PEDAL;
        if (stack.is(MyModItems.CONTROL_JOYSTICK.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK;
        if (stack.is(MyModItems.CONTROL_MONITOR_2.get())) return ControlDeskBlockEntity.ControlType.MONITOR_2;
        if (stack.is(MyModItems.CONTROL_THROTTLE.get())) return ControlDeskBlockEntity.ControlType.THROTTLE;
        if (stack.is(MyModItems.CONTROL_JOYSTICK_2.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK_2;
        if (stack.is(MyModItems.CONTROL_THROTTLE_2.get())) return ControlDeskBlockEntity.ControlType.THROTTLE_2;
        return null;
    }

    /** 手持控制台方块物品（拓展坞 = 手持控制台右键另一台已放置的控制台 → 安装为 slab 形态）。 */
    private static boolean isDock(ItemStack stack) {
        return stack.is(MyModBlocks.my_control_desk.get().asItem());
    }

    private static boolean isWrench(ItemStack stack) {
        return stack.is(AllItems.WRENCH.get()) || stack.is(Tags.Items.TOOLS_WRENCH);
    }
}
