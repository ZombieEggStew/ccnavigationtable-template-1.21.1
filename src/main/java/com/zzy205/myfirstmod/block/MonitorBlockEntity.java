package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.compat.cc.GlobalChannelRegistry;
import com.zzy205.myfirstmod.compat.cc.MonitorPeripheral;
import com.zzy205.myfirstmod.compat.cc.MonitorRegistry;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
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

    /** 旋钮旋转（服务端调用），angle 为累计角度（度） */
    public void rotateKnob(int id, float angle) {
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
            if (changed) gridState.setModuleConfig(newId, config);
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
