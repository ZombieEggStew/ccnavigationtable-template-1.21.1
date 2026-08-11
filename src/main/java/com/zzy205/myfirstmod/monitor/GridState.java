package com.zzy205.myfirstmod.monitor;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 14×12 棋盘网格状态。
 * grid[x][y] = moduleId（-1 表示空格）。
 */
public class GridState {

    public static final int GRID_WIDTH = 14;
    public static final int GRID_HEIGHT = 12;

    private final int[][] grid = new int[GRID_WIDTH][GRID_HEIGHT];
    private final Map<Integer, MonitorModule> modules = new LinkedHashMap<>();
    private final Set<Integer> pressedModules = new HashSet<>();
    /** 旋钮模块的角度（度），moduleId → Y 轴旋转角度 */
    private final Map<Integer, Float> knobAngles = new java.util.HashMap<>();
    private int nextId = 0;

    public GridState() {
        for (int x = 0; x < GRID_WIDTH; x++) {
            for (int y = 0; y < GRID_HEIGHT; y++) {
                grid[x][y] = -1;
            }
        }
    }

    // ── 访问器 ──

    public int getCell(int x, int y) {
        if (x < 0 || x >= GRID_WIDTH || y < 0 || y >= GRID_HEIGHT) return -1;
        return grid[x][y];
    }

    public MonitorModule getModule(int moduleId) {
        return modules.get(moduleId);
    }

    public Map<Integer, MonitorModule> getAllModules() {
        return modules;
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    // ── 放置 / 移除 ──

    /** 检查矩形区域是否可放置。 */
    public boolean canPlace(int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > GRID_WIDTH || y + h > GRID_HEIGHT) return false;
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                if (grid[x + dx][y + dy] != -1) return false;
            }
        }
        return true;
    }

    /** 放置模块，返回生成的 moduleId；失败返回 -1。 */
    public int tryPlace(int x, int y, ModuleType type) {
        if (!canPlace(x, y, type.width, type.height)) return -1;
        int id = nextId++;
        MonitorModule mod = new MonitorModule(id, type, x, y);
        modules.put(id, mod);
        for (int dx = 0; dx < type.width; dx++) {
            for (int dy = 0; dy < type.height; dy++) {
                grid[x + dx][y + dy] = id;
            }
        }
        return id;
    }

    /** 移除模块，返回被移除的模块信息；不存在返回 null。 */
    public MonitorModule tryRemove(int moduleId) {
        MonitorModule mod = modules.remove(moduleId);
        if (mod == null) return null;
        pressedModules.remove(moduleId);
        knobAngles.remove(moduleId);
        for (int dx = 0; dx < mod.getWidth(); dx++) {
            for (int dy = 0; dy < mod.getHeight(); dy++) {
                grid[mod.gridX() + dx][mod.gridY() + dy] = -1;
            }
        }
        return mod;
    }

    // ── 按钮按下 / 释放 / 切换 ──

    public void press(int moduleId) {
        if (modules.containsKey(moduleId)) pressedModules.add(moduleId);
    }

    public void release(int moduleId) {
        pressedModules.remove(moduleId);
    }

    /** 反转锁存状态（钮子开关等） */
    public void toggle(int moduleId) {
        if (!modules.containsKey(moduleId)) return;
        if (pressedModules.contains(moduleId))
            pressedModules.remove(moduleId);
        else
            pressedModules.add(moduleId);
    }

    public boolean isPressed(int moduleId) {
        return pressedModules.contains(moduleId);
    }

    // ── 旋钮角度 ──

    public void setKnobAngle(int moduleId, float angle) {
        if (modules.containsKey(moduleId)) knobAngles.put(moduleId, angle);
    }

    public float getKnobAngle(int moduleId) {
        return knobAngles.getOrDefault(moduleId, 0f);
    }

    // ── NBT ──

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("nextId", nextId);

        ListTag modList = new ListTag();
        for (MonitorModule mod : modules.values()) {
            CompoundTag modTag = new CompoundTag();
            modTag.putInt("id", mod.id());
            modTag.putString("type", mod.type().name);
            modTag.putInt("x", mod.gridX());
            modTag.putInt("y", mod.gridY());
            modTag.putBoolean("pressed", pressedModules.contains(mod.id()));
            float ka = knobAngles.getOrDefault(mod.id(), 0f);
            if (ka != 0f) modTag.putFloat("knobAngle", ka);
            modList.add(modTag);
        }
        tag.put("modules", modList);
        return tag;
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        modules.clear();
        pressedModules.clear();
        knobAngles.clear();
        for (int x = 0; x < GRID_WIDTH; x++) {
            for (int y = 0; y < GRID_HEIGHT; y++) {
                grid[x][y] = -1;
            }
        }

        nextId = tag.getInt("nextId");
        ListTag modList = tag.getList("modules", Tag.TAG_COMPOUND);
        for (int i = 0; i < modList.size(); i++) {
            CompoundTag modTag = modList.getCompound(i);
            int id = modTag.getInt("id");
            ModuleType type = ModuleType.byName(modTag.getString("type"));
            if (type == null) continue;
            int x = modTag.getInt("x");
            int y = modTag.getInt("y");
            MonitorModule mod = new MonitorModule(id, type, x, y);
            modules.put(id, mod);
            if (modTag.getBoolean("pressed")) pressedModules.add(id);
            if (modTag.contains("knobAngle")) knobAngles.put(id, modTag.getFloat("knobAngle"));
            for (int dx = 0; dx < type.width; dx++) {
                for (int dy = 0; dy < type.height; dy++) {
                    grid[x + dx][y + dy] = id;
                }
            }
        }
    }
}
