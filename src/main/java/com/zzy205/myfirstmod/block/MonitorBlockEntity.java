package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return id;
    }

    /** 移除模块，成功返回被移除的模块类型名，失败返回 null。 */
    public String tryRemoveModule(int moduleId) {
        var mod = gridState.tryRemove(moduleId);
        if (mod != null) {
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return mod.type().name;
        }
        return null;
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
