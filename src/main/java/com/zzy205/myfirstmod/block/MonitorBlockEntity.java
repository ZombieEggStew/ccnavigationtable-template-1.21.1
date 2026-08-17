package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.compat.cc.GlobalChannelRegistry;
import com.zzy205.myfirstmod.compat.cc.MonitorPeripheral;
import com.zzy205.myfirstmod.compat.cc.MonitorRegistry;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.monitor.ScreenText;
import com.zzy205.myfirstmod.network.SyncGridPayload;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;
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
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 显示器 BlockEntity — 持有棋盘网格状态。
 */
public class MonitorBlockEntity extends BlockEntity {

    /** 显示器屏幕文字 */
    private String screenText = "";
    /** 12×10 棋盘网格 */
    private final GridState gridState = new GridState();
    /** 全局频道号（-1 表示尚未注册，注册时自动分配） */
    private int channel = -1;
    /** 背景选项（默认"蓝色棋盘"） */
    private String background = MonitorBackground.DEFAULT;
    /** 所有已被占用的频道号快照（服务端设置，客户端通过 updateTag 同步） */
    private int[] occupiedChannels = new int[0];
    /** CC:T 外设实例（懒加载），避免直接在 BE 上实现 IPeripheral 导致 getType() 冲突 */
    @Nullable
    private IPeripheral peripheral;

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.monitor_entity.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            int assigned = MonitorRegistry.register(this.channel, this);
            if (assigned != this.channel) {
                this.channel = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            MonitorRegistry.unregister(this.channel, this);
        }
        super.setRemoved();
    }

    public String getScreenText() { return screenText; }
    public void setScreenText(String text) { this.screenText = text; setChanged(); }

    public GridState getGridState() { return gridState; }

    /** 全局频道号。 */
    public int getChannel() { return channel; }

    /** 当前背景选项。 */
    public String getBackground() { return background; }

    /** 设置背景（服务端调用）：校验并同步客户端。 */
    public void setBackground(String value) {
        if (level == null || level.isClientSide) return;
        String normalized = MonitorBackground.isValid(value) ? value : MonitorBackground.DEFAULT;
        if (normalized.equals(this.background)) return;
        this.background = normalized;
        setChanged();
        blockChanged();
    }

    /** 获取 CC:T 外设实例（懒加载）。 */
    public IPeripheral getPeripheral() {
        if (peripheral == null) {
            peripheral = new MonitorPeripheral(this);
        }
        return peripheral;
    }

    /** 更新全局频道号（服务端调用）：重新注册并同步客户端。 */
    public void setChannel(int newChannel) {
        if (level == null || level.isClientSide) return;
        // -1 表示客户端尚未同步到真实频道，直接忽略，避免误触发自动重分配
        if (newChannel < 0) return;
        if (newChannel == this.channel) return;
        int assigned = MonitorRegistry.register(newChannel, this);
        this.channel = assigned;
        setChanged();
        blockChanged();
    }

    /** 获取已占用频道号数组（客户端菜单用它跳过已占用频道）。 */
    public int[] getOccupiedChannels() { return occupiedChannels; }

    /** 从全局注册表同步 occupiedChannels 快照到本 BE，并通知客户端。 */
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        var channels = GlobalChannelRegistry.occupiedChannels();
        int[] arr = new int[channels.size()];
        int i = 0;
        for (int ch : channels) arr[i++] = ch;
        this.occupiedChannels = arr;
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    /** 尝试放置模块（服务端调用），成功返回 moduleId，失败返回 -1。 */
    public int tryPlaceModule(int x, int y, ModuleType type) {
        int id = gridState.tryPlace(x, y, type);
        if (id >= 0) {
            setChanged();
            if (level != null && !level.isClientSide) {
                syncGridToClients();
                blockChanged();
            }
        }
        return id;
    }

    /** 移除模块，成功返回被移除的模块类型名，失败返回 null。 */
    public String tryRemoveModule(int moduleId) {
        var mod = gridState.tryRemove(moduleId);
        if (mod != null) {
            setChanged();
            if (level != null && !level.isClientSide) {
                syncGridToClients();
                blockChanged();
            }
            return mod.type().name;
        }
        return null;
    }

    /** 将网格状态推送到所有追踪此区块的客户端（自定义包，可靠同步） */
    private void syncGridToClients() {
        if (level instanceof ServerLevel serverLevel) {
            var payload = new SyncGridPayload(worldPosition, gridState.save(level.registryAccess()));
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition), payload);
        }
    }

    /** 仿 control-panels：通知原版客户端 block 变更，触发 BlockEntity 数据同步 */
    private void blockChanged() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(worldPosition);
        }
    }

    /** 按钮按下（服务端调用，自动同步客户端） */
    public void pressModule(int id) {
        gridState.press(id);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
            syncGridToClients();
        }
    }

    /** 玩家点击按钮按下（服务端调用）：始终记录玩家点击；锁定时不改变按下状态、不播放音效（音效由 Lua press/release 触发）。 */
    public void pressModuleByPlayer(int id) {
        gridState.recordPlayerClick(id);
        if (gridState.isPlayerLocked(id)) return;
        gridState.press(id);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
            syncGridToClients();
        }
    }

    /** 按钮释放（服务端调用，自动同步客户端） */
    public void releaseModule(int id) {
        gridState.release(id);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_OFF,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
            syncGridToClients();
        }
    }

    /** 玩家释放按钮（服务端调用）：锁定时不改变状态、不播放音效（音效由 Lua press/release 触发）。 */
    public void releaseModuleByPlayer(int id) {
        if (gridState.isPlayerLocked(id)) return;
        releaseModule(id);
    }

    /** 设置按钮玩家互动开关（服务端调用）。enabled=false 时玩家无法按下，但点击仍被记录。 */
    public void setButtonPlayerControl(int id, boolean enabled) {
        gridState.setPlayerLocked(id, !enabled);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 设置按钮灯带亮度（0..1）并切换为代码控制（服务端调用）。 */
    public void setButtonLight(int id, float brightness) {
        gridState.setLightBrightness(id, brightness);
        gridState.setLightCodeControlled(id, true);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 设置按钮灯带是否由代码控制（服务端调用）。 */
    public void setButtonLightControl(int id, boolean codeControlled) {
        gridState.setLightCodeControlled(id, codeControlled);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    // ── 按钮表面标签（Lua 控制） ──

    /** 设置按钮表面标签文字（服务端调用，空串清除显示）。 */
    public void setButtonLabelText(int id, String text) {
        if (gridState.getModule(id) == null) return;
        gridState.setButtonLabelText(id, text);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 设置按钮表面标签位置偏移（服务端调用，MC 像素，0,0 = 居中）。 */
    public void setButtonLabelPosition(int id, double x, double y) {
        if (gridState.getModule(id) == null) return;
        gridState.setButtonLabelPosition(id, x, y);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 设置按钮表面标签字号（服务端调用，块/字体像素，默认 1/512）。 */
    public void setButtonLabelScale(int id, double scale) {
        if (gridState.getModule(id) == null) return;
        gridState.setButtonLabelScale(id, scale);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 设置按钮表面标签颜色（服务端调用，0xRRGGBB）。 */
    public void setButtonLabelColor(int id, int color) {
        if (gridState.getModule(id) == null) return;
        gridState.setButtonLabelColor(id, color);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 设置按钮表面标签是否绘制投影（服务端调用）。 */
    public void setButtonLabelDropShadow(int id, boolean dropShadow) {
        if (gridState.getModule(id) == null) return;
        gridState.setButtonLabelDropShadow(id, dropShadow);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 反转锁存状态（钮子开关等，服务端调用） */
    public void toggleModule(int id) {
        gridState.toggle(id);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, 0.3f, gridState.isPressed(id) ? 1.2f : 1.1f);
            syncGridToClients();
        }
    }

    /** 设置钮子开关的锁存状态（服务端调用，CC:T Lua 控制用），自动同步客户端。 */
    public void setToggleState(int id, boolean state) {
        if (gridState.getModule(id) == null) return;   // 模块已不存在
        if (gridState.isPressed(id) == state) return;  // 状态相同，无操作
        if (state) gridState.press(id); else gridState.release(id);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, 0.3f, state ? 1.2f : 1.1f);
            syncGridToClients();
        }
    }

    /** 设置模块/屏幕的 tooltip 文本（服务端调用，CC:T Lua 控制用），自动同步客户端。 */
    public void setTooltip(int id, String text) {
        if (gridState.getModule(id) != null) {
            // 普通模块：写配置 "text"（配置界面/悬停 tooltip）
            CompoundTag config = gridState.getModuleConfig(id).copy();
            config.putString("text", text);
            gridState.setModuleConfig(id, config);
        } else if (gridState.getScreenById(id) != null) {
            // 屏幕：写屏幕文本（悬停显示）
            gridState.updateScreen(id, id, text);
        } else {
            return;  // 无效 ID
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    /** 旋钮旋转（服务端调用），angle 为累计角度（度） */
    public void rotateKnob(int id, float angle) {
        int step = gridState.getDetentStep(id);
        if (step > 0) angle = GridState.snapToDetent(angle, step);
        gridState.setKnobAngle(id, angle);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    // ── 控件配置（模块 / 屏幕共用入口） ──

    /** 应用控件的 ID 与配置。name 为模块类型名或 "screen"。 */
    public void applyModuleConfig(String name, int oldId, int newId, CompoundTag config) {
        boolean changed;
        if ("screen".equals(name)) {
            changed = gridState.updateScreen(oldId, newId, config.getString("text"));
        } else {
            changed = gridState.trySetId(oldId, newId);
            if (changed) {
                gridState.setModuleConfig(newId, config);
                // 切换到卡位模式（或修改卡位角度）时，把旋钮吸附到最近档位
                if (ModuleType.KNOB == ModuleType.byName(name)) {
                    gridState.snapKnobToDetent(newId);
                }
            }
        }
        if (changed) {
            setChanged();
            if (level != null && !level.isClientSide) {
                syncGridToClients();
            }
        }
    }

    // ── 屏幕 ──

    /**
     * 新增一个屏幕（服务端调用），自动分配最小空闲 ID。
     * @return 新屏幕 ID，失败返回 -1
     */
    public int addScreen(int x1, int y1, int x2, int y2) {
        int id = gridState.addScreen(x1, y1, x2, y2);
        if (id >= 0) {
            setChanged();
            if (level != null && !level.isClientSide) {
                syncGridToClients();
                blockChanged();
            }
        }
        return id;
    }

    /** 移除指定格子所属的屏幕（服务端调用）。 */
    public boolean removeScreenAt(int gx, int gy) {
        if (gridState.removeScreenAt(gx, gy)) {
            setChanged();
            if (level != null && !level.isClientSide) {
                syncGridToClients();
                blockChanged();
            }
            return true;
        }
        return false;
    }

    // ── 屏幕文本（Lua 控制） ──

    /** 屏幕文本/矩形相对 9 宫格边框的四周内缩（MC 像素），每侧为 1/64 块。 */
    private static final float SCREEN_TEXT_INSET_PX = 0.25f;

    /** 计算屏幕可显示的行列数（按当前字号自动计算）。 */
    public int[] getScreenSize(int id) {
        GridState.ScreenRegion scr = gridState.getScreenById(id);
        if (scr == null) return null;
        double innerW = scr.width() - 2 * SCREEN_TEXT_INSET_PX;
        double innerH = scr.height() - 2 * SCREEN_TEXT_INSET_PX;
        ScreenText text = gridState.getScreenText(id);
        double scale = text != null ? text.getTextScale() : ScreenText.DEFAULT_SCALE;
        return new int[] { ScreenText.colsFor(innerW, scale), ScreenText.rowsFor(innerH, scale) };
    }

    /** 服务端能否修改指定屏幕。 */
    private boolean canMutateScreen(int id) {
        if (level == null || level.isClientSide) return false;
        return gridState.getScreenById(id) != null;
    }

    /** 在光标处写入文本（z 为空时用 ScreenText 的默认层级）。 */
    public void screenWrite(int id, String text, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        GridState.ScreenRegion scr = gridState.getScreenById(id);
        double innerW = (scr.width() - 2 * SCREEN_TEXT_INSET_PX) * ScreenText.RECT_UNITS_PER_PX;
        ScreenText t = gridState.getOrCreateScreenText(id);
        t.write(text, innerW, z != null ? z : t.getZIndex());
        setChanged();
        syncGridToClients();
    }

    /** 清空屏幕文本。 */
    public void screenClear(int id) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).clear();
        setChanged();
        syncGridToClients();
    }

    /** 设置光标（drawRect 坐标，原点在内区左上角，1 单位 = 1/128 块）。 */
    public void screenSetCursor(int id, double x, double y) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).setCursor(x, y);
        setChanged();
        syncGridToClients();
    }

    /** 设置整块屏幕的字号（仅影响之后写入的字形大小与换行推进量）。 */
    public void screenSetTextScale(int id, double scale) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).setTextScale(scale);
        setChanged();
        syncGridToClients();
    }

    /** 设置前景色（0xRRGGBB）。 */
    public void screenSetTextColour(int id, int colour) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).setTextColour(colour);
        setChanged();
        syncGridToClients();
    }

    /** 设置之后 write/drawRect 未显式指定 z 时使用的默认层级。 */
    public void screenSetZIndex(int id, double z) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).setZIndex(z);
        setChanged();
        syncGridToClients();
    }

    /** 设置超出一行时的处理模式（"truncate" / "ellipsis" / "wrap"）。 */
    public void screenSetOverflowMode(int id, String mode) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).setOverflowMode(ScreenText.OverflowMode.byName(mode));
        setChanged();
        syncGridToClients();
    }

    /** 在屏幕内区追加一个矩形（1/128 块坐标，原点在内区左上角；z 为空时用默认层级）。 */
    public void screenDrawRect(int id, double x, double y, double w, double h,
                               int colour, boolean solid, double lineWidth, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        ScreenText t = gridState.getOrCreateScreenText(id);
        t.addRect(x, y, w, h, colour, solid, lineWidth, z != null ? z : t.getZIndex());
        setChanged();
        syncGridToClients();
    }

    /** 清空屏幕上的所有矩形。 */
    public void screenClearRects(int id) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).clearRects();
        setChanged();
        syncGridToClients();
    }

    /** 在屏幕内区追加一条线段（1/128 块坐标；z 为空时用默认层级）。 */
    public void screenDrawLine(int id, double x1, double y1, double x2, double y2,
                               int colour, double lineWidth, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        ScreenText t = gridState.getOrCreateScreenText(id);
        t.addLine(x1, y1, x2, y2, colour, lineWidth, z != null ? z : t.getZIndex());
        setChanged();
        syncGridToClients();
    }

    /** 在屏幕内区追加一个圆（1/128 块坐标；z 为空时用默认层级）。 */
    public void screenDrawCircle(int id, double cx, double cy, double radius, int colour,
                                 boolean solid, double lineWidth, int segments, @Nullable Double z) {
        if (!canMutateScreen(id)) return;
        ScreenText t = gridState.getOrCreateScreenText(id);
        t.addCircle(cx, cy, radius, colour, solid, lineWidth, segments, z != null ? z : t.getZIndex());
        setChanged();
        syncGridToClients();
    }

    /** 清空屏幕上的所有图形（矩形 + 线段 + 圆），不影响文本。 */
    public void screenClearShapes(int id) {
        if (!canMutateScreen(id)) return;
        gridState.getOrCreateScreenText(id).clearShapes();
        setChanged();
        syncGridToClients();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("ScreenText", screenText);
        tag.putInt("Channel", channel);
        tag.putString("Background", background);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        tag.put("GridState", gridState.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        screenText = tag.getString("ScreenText");
        if (tag.contains("Channel")) channel = tag.getInt("Channel");
        if (tag.contains("Background")) {
            String bg = tag.getString("Background");
            background = MonitorBackground.isValid(bg) ? bg : MonitorBackground.DEFAULT;
        }
        if (tag.contains("OccupiedChannels")) occupiedChannels = tag.getIntArray("OccupiedChannels");
        if (tag.contains("GridState")) {
            gridState.load(registries, tag.getCompound("GridState"));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("Channel", channel);
        tag.putString("Background", background);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        tag.put("GridState", gridState.save(registries));
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
