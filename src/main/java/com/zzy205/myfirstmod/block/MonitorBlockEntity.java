package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.CCPeripheraExtender;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.network.SyncGridPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
    /** 14×12 棋盘网格 */
    private final GridState gridState = new GridState();

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.monitor_entity.get(), pos, state);
    }

    public String getScreenText() { return screenText; }
    public void setScreenText(String text) { this.screenText = text; setChanged(); }

    public GridState getGridState() { return gridState; }

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
            syncGridToClients();
        }
    }

    /** 按钮释放（服务端调用，自动同步客户端） */
    public void releaseModule(int id) {
        gridState.release(id);
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

    /** 旋钮旋转（服务端调用），angle 为累计角度（度） */
    public void rotateKnob(int id, float angle) {
        gridState.setKnobAngle(id, angle);
        setChanged();
        if (level != null && !level.isClientSide) {
            syncGridToClients();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("ScreenText", screenText);
        tag.put("GridState", gridState.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        screenText = tag.getString("ScreenText");
        if (tag.contains("GridState")) {
            gridState.load(registries, tag.getCompound("GridState"));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("GridState", gridState.save(registries));
        return tag;
    }
}
