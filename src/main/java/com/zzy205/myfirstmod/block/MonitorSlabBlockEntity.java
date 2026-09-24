package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import com.zzy205.myfirstmod.client.MonitorSlabClientRegistry;
import com.zzy205.myfirstmod.compat.cc.GlobalChannelRegistry;
import com.zzy205.myfirstmod.compat.cc.MonitorPeripheral;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.ScreenText;
import com.zzy205.myfirstmod.network.SyncGridPayload;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 板式监视器（monitor_slab）方块实体 — 持有表面 Monitor 棋盘网格状态。
 * <p>
 * 实现 {@link MonitorGridHost}（对齐 {@code ControlDeskBlockEntity} 的 monitor_2 段），因此 8 个
 * Monitor payload（{@code MonitorPacketHandlers.findHost} 按 {@code instanceof MonitorGridHost} 分发）、
 * Lua 模块 handle、{@code MonitorModuleScreen} 配置菜单全部自动可用，无需复制。
 * <p>
 * 本阶段仅支持<b>地板（FLOOR）放置</b>：面板 = slab 顶面（世界 y = pos.y + 0.5），命中/渲染都按水平面处理。
 * 网格 14×14（顶面 16×16 px，四周各内缩 1px → 网格起点 (1,1)px，格 = 1px）。
 */
public class MonitorSlabBlockEntity extends BlockEntity implements MonitorGridHost, PartialSafeNBT {

    // ═══════════════ 面板几何（北向基准模型空间 px，单一来源） ═══════════════
    // 命中检测（MonitorSlabHitDetector）、渲染（MonitorSlabRenderer）、交互（MonitorSlabGridOverlay）
    // 全部共用这组常量，保证「渲染与检测严格互逆」。

    /** 面板（顶面）在模型空间的 y（slab y0..8 → 顶面 y8，世界 = pos.y + 8/16）。 */
    public static final float PANEL_Y_PX = 8f;
    /** 面板网格（格）：14×14（顶面 16×16，四周各内缩 1px，用户定稿）。 */
    public static final int GRID_WIDTH = 14;
    public static final int GRID_HEIGHT = 14;
    /** 网格相对面板四周的内缩（px）。 */
    public static final float GRID_INSET_PX = 1f;
    /** 网格起点（格 (0,0) 的最小角，模型空间 px）：面板 0..16 内缩 1px → (1,1)。 */
    public static final float GRID_ORIGIN_X_PX = GRID_INSET_PX;
    public static final float GRID_ORIGIN_Z_PX = GRID_INSET_PX;
    /** 模块背面相对面板的凸出量（px）：模块本地背面 z=1px → 锚点 = 面板 − 1px 使背面贴面板、整体向外凸 1px。 */
    public static final float MODULE_PROTRUDE_PX = 1f;
    /** 模块凸出后上表面 y（px）：面板 y8 + 凸出 1px → 9px（模块/文字基准面，防 z-fight 用）。 */
    public static final float MODULE_SURFACE_Y_PX = PANEL_Y_PX + MODULE_PROTRUDE_PX;

    /**
     * 面板几何（世界朝向，块单位，相对方块 pos）：面板平面点 {@code panelOrigin}、面板平面内两轴
     * {@code uDir}（grid x 方向）/ {@code vDir}（grid y 方向）、面板法线 {@code nDir}（朝外）、
     * 从「水平摊平局部帧（右=X/前=Y/上=Z）」到「面板局部帧」的旋转（{@code faceYawDeg} + 绕 X −90°，仅非地板形态使用）。
     * <p>
     * 命中检测（{@code MonitorSlabHitDetector}）、网格绘制（{@code MonitorSlabGridOverlay}）、
     * 渲染（{@code MonitorSlabRenderer}）、扳手拆除判定（{@code MonitorSlabBlock}）全部共用，单一来源。
     */
    public record PanelFrame(Vec3 panelOrigin, Vec3 uDir, Vec3 vDir, Vec3 nDir, float faceYawDeg) {}

    /**
     * 按 blockstate 的 FACE/FACING 返回面板几何（单一来源，渲染与检测严格互逆）。
     * <ul>
     *   <li>地板（FLOOR）：面板 = 顶面 y8/16，grid x → +X / grid y → +Z / 法线 +Y；</li>
     *   <li>贴墙（WALL）：面板 = 朝向 FACING 的 8px 边界（贴墙放置时面对的那一面），
     *       grid x → 面板「水平向右」（north +X / south −X / east +Z / west −Z）、grid y → +Y（朝上）、法线 = FACING；</li>
     *   <li>天花板（CEILING）：本阶段不支持，返回 null。</li>
     * </ul>
     */
    @Nullable
    public static PanelFrame panelFrame(BlockState state) {
        AttachFace face = state.getValue(MonitorSlabBlock.FACE);
        float y = PANEL_Y_PX / 16f;
        return switch (face) {
            case FLOOR -> new PanelFrame(new Vec3(0, y, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0), 0f);
            case CEILING -> null; // 本阶段仅地板 + 贴墙
            case WALL -> switch (state.getValue(MonitorSlabBlock.FACING)) {
                case NORTH -> new PanelFrame(new Vec3(0, 0, y), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, -1), 0f);
                case SOUTH -> new PanelFrame(new Vec3(0, 0, y), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), 180f);
                case EAST -> new PanelFrame(new Vec3(y, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(1, 0, 0), -90f);
                default -> new PanelFrame(new Vec3(y, 0, 0), new Vec3(0, 0, -1), new Vec3(0, 1, 0), new Vec3(-1, 0, 0), 90f); // WEST
            };
        };
    }

    /** 面板局部坐标（面板平面内，块单位）[u, v, n] → 世界（相对方块 pos 的偏移）。 */
    public static Vec3 panelLocalToWorld(PanelFrame frame, double u, double v, double n) {
        return frame.panelOrigin()
                .add(frame.uDir().scale(u))
                .add(frame.vDir().scale(v))
                .add(frame.nDir().scale(n));
    }

    // ═══════════════ 状态 ═══════════════

    /** 表面棋盘网格状态（14×14，懒加载）。 */
    private GridState gridState;

    /** 全局频道号（-1 表示尚未注册，加载时自动分配；与显示器/传感器共享全局频道命名空间，对齐 {@link MonitorBlockEntity}）。 */
    private int channel = -1;
    /** 所有已被占用的全局频道号快照（服务端设置，客户端经 updateTag 同步，配置菜单用它跳过已占用频道）。 */
    private int[] occupiedChannels = new int[0];

    /** CC:T 外设实例（懒加载），避免直接在 BE 上实现 IPeripheral 导致 getType() 冲突（对齐 {@link MonitorBlockEntity}）。 */
    @Nullable
    private IPeripheral peripheral;

    public MonitorSlabBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.monitor_slab_entity.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && this.level.isClientSide) {
            // 客户端独立命中检测（MonitorSlabHitDetector）依赖此注册表枚举候选 slab
            MonitorSlabClientRegistry.add(this.getBlockPos());
        }
        if (this.level != null && !this.level.isClientSide) {
            // 全局频道注册（照 MonitorBlockEntity）：-1 自动分配最小空闲频道，冲突顺延
            int assigned = GlobalChannelRegistry.register(this.channel, this);
            if (assigned != this.channel) {
                this.channel = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && this.level.isClientSide) {
            MonitorSlabClientRegistry.remove(this.getBlockPos());
        }
        if (this.level != null && !this.level.isClientSide) {
            GlobalChannelRegistry.unregister(this.channel, this);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (this.level != null && this.level.isClientSide) {
            MonitorSlabClientRegistry.remove(this.getBlockPos());
        }
        super.onChunkUnloaded();
    }

    // ═══════════════ 全局频道（信号系统，对齐 MonitorBlockEntity） ═══════════════

    /** 全局频道号。 */
    public int getChannel() {
        return channel;
    }

    /** 获取已占用全局频道号数组（客户端配置菜单用它跳过已占用频道）。 */
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    /** 更新全局频道号（服务端调用）：重新注册（冲突顺延）并同步客户端。 */
    public void setChannel(int newChannel) {
        if (level == null || level.isClientSide) return;
        // -1 表示客户端尚未同步到真实频道，直接忽略，避免误触发自动重分配
        if (newChannel < 0) return;
        if (newChannel == this.channel) return;
        int assigned = GlobalChannelRegistry.register(newChannel, this);
        this.channel = assigned;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** 从全局注册表同步 occupiedChannels 快照到本 BE，并通知客户端（占用集合变化时由 GlobalChannelRegistry 广播调用）。 */
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        this.occupiedChannels = GlobalChannelRegistry.occupiedChannelsArray();
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    // ═══════════════ CC:T 外设（照抄 MonitorBlockEntity，直接复用 MonitorPeripheral） ═══════════════

    /**
     * 获取 CC:T 外设实例（懒加载）。直接复用 {@link MonitorPeripheral}（宿主参数化为
     * {@link MonitorGridHost}，type = "ccpe:monitor_slab"）：模块/屏幕查询 handle 与
     * 音效方法完全同款，作用在本 slab 的 14×14 网格上（对齐 monitor_2 复用方式）。
     * 经 {@code pe.getPeripheral(ch)} / {@code peripheral.wrap} 获取。
     */
    public IPeripheral getPeripheral() {
        if (peripheral == null) {
            peripheral = new MonitorPeripheral(this, "ccpe:monitor_slab");
        }
        return peripheral;
    }

    // ═══════════════ MonitorGridHost 实现（照抄 monitor_2 段） ═══════════════

    /** 获取（懒加载）表面网格状态。 */
    public GridState getGridState() {
        if (gridState == null) {
            gridState = new GridState(GRID_WIDTH, GRID_HEIGHT);
        }
        return gridState;
    }

    /** 网格是否为空（无模块也无屏幕；供渲染快速跳过）。 */
    public boolean hasContent() {
        return gridState != null && (!gridState.isEmpty() || gridState.hasScreen());
    }

    /** 尝试放置表面模块（服务端调用），成功返回 moduleId，失败返回 -1。 */
    @Override
    public int tryPlaceModule(int x, int y, ModuleType type) {
        int id = getGridState().tryPlace(x, y, type);
        if (id >= 0) {
            slabChanged();
        }
        return id;
    }

    /** 移除表面模块（服务端调用），成功返回被移除的模块类型名，失败返回 null。 */
    @Override
    public String tryRemoveModule(int moduleId) {
        var mod = getGridState().tryRemove(moduleId);
        if (mod != null) {
            slabChanged();
            return mod.type().name;
        }
        return null;
    }

    /** 玩家点击按钮按下（服务端调用）：始终记录玩家点击；锁定时不改变按下状态。 */
    @Override
    public void pressModuleByPlayer(int id) {
        GridState grid = getGridState();
        grid.recordPlayerClick(id);
        if (grid.isPlayerLocked(id)) return;
        grid.press(id);
        slabChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
        }
    }

    /** 玩家释放按钮（服务端调用）：锁定时不改变状态。 */
    @Override
    public void releaseModuleByPlayer(int id) {
        if (getGridState().isPlayerLocked(id)) return;
        releaseModule(id);
    }

    /** 应用模块/屏幕的 ID 与配置（服务端调用）。 */
    @Override
    public void applyModuleConfig(String name, int oldId, int newId, CompoundTag config) {
        GridState grid = getGridState();
        boolean changed;
        if (GridState.SCREEN_NAME.equals(name)) {
            changed = grid.updateScreen(oldId, newId, config.getString("text"));
        } else {
            changed = grid.trySetId(oldId, newId);
            if (changed) {
                grid.setModuleConfig(newId, config);
                if (ModuleType.KNOB == ModuleType.byName(name)) {
                    grid.setKnobAngle(newId, grid.getKnobAngle(newId));
                    grid.snapKnobToDetent(newId);
                }
            }
        }
        if (changed) {
            slabChanged();
        }
    }

    /** 新增一个表面屏幕（服务端调用），自动分配最小空闲 ID；失败返回 -1。 */
    @Override
    public int addScreen(int x1, int y1, int x2, int y2) {
        int id = getGridState().addScreen(x1, y1, x2, y2);
        if (id >= 0) {
            slabChanged();
        }
        return id;
    }

    /** 移除表面指定格子的屏幕（服务端调用）。 */
    @Override
    public boolean removeScreenAt(int gx, int gy) {
        if (getGridState().removeScreenAt(gx, gy)) {
            slabChanged();
            return true;
        }
        return false;
    }

    /** 同步网格状态到所有追踪此区块的客户端（对齐 MonitorBlockEntity.syncGridToClients）。 */
    private void syncGridToClients() {
        if (level instanceof ServerLevel serverLevel) {
            var payload = new SyncGridPayload(worldPosition, getGridState().save(level.registryAccess()));
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition), payload);
        }
    }

    /** 网格变更：本地标记 + 服务端推送 BE 更新与 grid 数据。 */
    private void slabChanged() {
        this.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            syncGridToClients();
        }
    }

    @Override
    public void pressModule(int id) {
        getGridState().press(id);
        slabChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
        }
    }

    @Override
    public void releaseModule(int id) {
        getGridState().release(id);
        slabChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_OFF,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
        }
    }

    @Override
    public void toggleModule(int id) {
        GridState grid = getGridState();
        grid.toggle(id);
        slabChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, 0.3f, grid.isPressed(id) ? 1.2f : 1.1f);
        }
    }

    @Override
    public void setToggleState(int id, boolean state) {
        if (getGridState().getModule(id) == null) return;
        if (getGridState().isPressed(id) == state) return;
        if (state) getGridState().press(id); else getGridState().release(id);
        slabChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, 0.3f, state ? 1.2f : 1.1f);
        }
    }

    @Override
    public void rotateKnob(int id, float angle) {
        GridState grid = getGridState();
        int step = grid.getDetentStep(id);
        if (step > 0) angle = GridState.snapToDetent(angle, step);
        grid.setKnobAngle(id, angle);
        slabChanged();
    }

    @Override
    public void setTooltip(int id, String text) {
        GridState grid = getGridState();
        if (grid.getModule(id) != null) {
            CompoundTag config = grid.getModuleConfig(id).copy();
            config.putString("text", text);
            grid.setModuleConfig(id, config);
        } else if (grid.getScreenById(id) != null) {
            grid.updateScreen(id, id, text);
        } else {
            return;
        }
        slabChanged();
    }

    @Override
    public void setButtonPlayerControl(int id, boolean enabled) {
        getGridState().setPlayerLocked(id, !enabled);
        slabChanged();
    }

    @Override
    public void setButtonLight(int id, float brightness) {
        getGridState().setLightBrightness(id, brightness);
        getGridState().setLightCodeControlled(id, true);
        slabChanged();
    }

    @Override
    public void setButtonLightControl(int id, boolean codeControlled) {
        getGridState().setLightCodeControlled(id, codeControlled);
        slabChanged();
    }

    @Override
    public void setButtonLabelText(int id, String text) {
        if (getGridState().getModule(id) == null) return;
        getGridState().setButtonLabelText(id, text);
        slabChanged();
    }

    @Override
    public void setButtonLabelPosition(int id, double x, double y) {
        if (getGridState().getModule(id) == null) return;
        getGridState().setButtonLabelPosition(id, x, y);
        slabChanged();
    }

    @Override
    public void setButtonLabelScale(int id, double scale) {
        if (getGridState().getModule(id) == null) return;
        getGridState().setButtonLabelScale(id, scale);
        slabChanged();
    }

    @Override
    public void setButtonLabelColor(int id, int color) {
        if (getGridState().getModule(id) == null) return;
        getGridState().setButtonLabelColor(id, color);
        slabChanged();
    }

    @Override
    public void setButtonLabelDropShadow(int id, boolean dropShadow) {
        if (getGridState().getModule(id) == null) return;
        getGridState().setButtonLabelDropShadow(id, dropShadow);
        slabChanged();
    }

    // ── 屏幕（格子模型） ──

    private boolean canMutateScreen(int id) {
        if (level == null || level.isClientSide) return false;
        return getGridState().getScreenById(id) != null;
    }

    private double screenInnerWidthPx(GridState.ScreenRegion scr) {
        return scr.width() - 2 * ScreenText.DRAWABLE_INSET * 16;
    }

    private double screenInnerHeightPx(GridState.ScreenRegion scr) {
        return scr.height() - 2 * ScreenText.DRAWABLE_INSET * 16;
    }

    @Override
    public void screenSetGrid(int id, int cols, int rows) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).setGrid(cols, rows);
        slabChanged();
    }

    @Override
    public int[] getScreenGrid(int id) {
        ScreenText t = getGridState().getScreenText(id);
        if (t == null) return null;
        return new int[] { t.getCols(), t.getRows() };
    }

    @Override
    public void screenSetTextScale(int id, double scale, @Nullable Double lineSpacing) {
        if (!canMutateScreen(id)) return;
        GridState.ScreenRegion scr = getGridState().getScreenById(id);
        ScreenText t = getGridState().getOrCreateScreenText(id);
        t.setTextScale(scale, lineSpacing != null ? lineSpacing : ScreenText.LINE_SPACING,
                screenInnerWidthPx(scr), screenInnerHeightPx(scr));
        slabChanged();
    }

    @Override
    public void screenWrite(int id, String text) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).write(text);
        slabChanged();
    }

    @Override
    public void screenClear(int id) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).clear();
        slabChanged();
    }

    @Override
    public void screenSetCursor(int id, int col, int row) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).setCursorPos(col, row);
        slabChanged();
    }

    @Override
    public void screenSetTextColour(int id, int colour) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).setTextColour(colour);
        slabChanged();
    }

    @Override
    public void screenSetZIndex(int id, double z) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).setZIndex(z);
        slabChanged();
    }

    @Override
    public void screenSetOverflowMode(int id, String mode) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).setOverflowMode(ScreenText.OverflowMode.byName(mode));
        slabChanged();
    }

    @Override
    public void screenSetVisible(int id, boolean visible) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).setVisible(visible);
        slabChanged();
    }

    @Override
    public void screenFill(int id, int col, int row, int w, int h, int colour) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).fill(col, row, w, h, colour);
        slabChanged();
    }

    @Override
    public void screenWriteField(int id, int col, int row, int width, String text, String align, @Nullable Integer colour) {
        if (!canMutateScreen(id)) return;
        ScreenText t = getGridState().getOrCreateScreenText(id);
        t.writeField(col, row, width, text, ScreenText.Align.byName(align),
                colour != null ? colour : t.getTextColour());
        slabChanged();
    }

    @Override
    public void screenFillField(int id, int col, int row, int width, int count, int colour, String align) {
        if (!canMutateScreen(id)) return;
        ScreenText t = getGridState().getOrCreateScreenText(id);
        t.fillField(col, row, width, count, colour, ScreenText.Align.byName(align));
        slabChanged();
    }

    @Override
    public void screenDraw(int id, List<int[]> cells,
                           List<ScreenText.Rect> rects, List<ScreenText.Line> lines, List<ScreenText.Circle> circles) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).replaceAll(cells, rects, lines, circles);
        slabChanged();
    }

    @Override
    public void screenReplaceCells(int id, List<int[]> cells) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).replaceCells(cells);
        slabChanged();
    }

    @Override
    public void screenReplaceShapes(int id, List<ScreenText.Rect> rects,
                                    List<ScreenText.Line> lines, List<ScreenText.Circle> circles) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).replaceShapes(rects, lines, circles);
        slabChanged();
    }

    @Override
    public void screenDrawRect(int id, double x, double y, double w, double h,
                               int colour, boolean solid, double lineWidth, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        ScreenText t = getGridState().getOrCreateScreenText(id);
        t.addRect(x, y, w, h, colour, solid, lineWidth, z != null ? z : t.getZIndex());
        slabChanged();
    }

    @Override
    public void screenClearRects(int id) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).clearRects();
        slabChanged();
    }

    @Override
    public void screenDrawLine(int id, double x1, double y1, double x2, double y2,
                               int colour, double lineWidth, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        ScreenText t = getGridState().getOrCreateScreenText(id);
        t.addLine(x1, y1, x2, y2, colour, lineWidth, z != null ? z : t.getZIndex());
        slabChanged();
    }

    @Override
    public void screenDrawCircle(int id, double cx, double cy, double radius, int colour,
                                 boolean solid, double lineWidth, int segments, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        ScreenText t = getGridState().getOrCreateScreenText(id);
        t.addCircle(cx, cy, radius, colour, solid, lineWidth, segments, z != null ? z : t.getZIndex());
        slabChanged();
    }

    @Override
    public void screenClearShapes(int id) {
        if (!canMutateScreen(id)) return;
        getGridState().getOrCreateScreenText(id).clearShapes();
        slabChanged();
    }

    // ═══════════════ NBT（四路径：saveAdditional / loadAdditional / writeSafe / getUpdateTag） ═══════════════

    private static final String TAG_GRID_STATE = "GridState";
    private static final String TAG_CHANNEL = "Channel";
    private static final String TAG_OCCUPIED_CHANNELS = "OccupiedChannels";

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_CHANNEL, channel);
        tag.putIntArray(TAG_OCCUPIED_CHANNELS, occupiedChannels);
        if (gridState != null) {
            tag.put(TAG_GRID_STATE, gridState.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_CHANNEL)) channel = tag.getInt(TAG_CHANNEL);
        if (tag.contains(TAG_OCCUPIED_CHANNELS)) occupiedChannels = tag.getIntArray(TAG_OCCUPIED_CHANNELS);
        if (tag.contains(TAG_GRID_STATE)) {
            getGridState().load(registries, tag.getCompound(TAG_GRID_STATE));
        }
    }

    /** Create 原理图 / 装置搬运时的「安全 NBT」（Schematicannon 打印保留表面模块与频道号）。 */
    @Override
    public void writeSafe(CompoundTag compound, HolderLookup.Provider registries) {
        compound.putInt(TAG_CHANNEL, channel);
        if (gridState != null) {
            compound.put(TAG_GRID_STATE, gridState.save(registries));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt(TAG_CHANNEL, channel);
        tag.putIntArray(TAG_OCCUPIED_CHANNELS, occupiedChannels);
        if (gridState != null) {
            tag.put(TAG_GRID_STATE, gridState.save(registries));
        }
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（默认返回 null 会导致快照不同步）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            loadAdditional(tag, registries);
        }
    }
}
