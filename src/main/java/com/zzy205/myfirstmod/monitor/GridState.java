package com.zzy205.myfirstmod.monitor;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

/**
 * 12×10 棋盘网格状态（屏幕面板 14×12，四周各留 1 格边框）。
 * grid[x][y] = moduleId（-1 表示空格，-2 表示屏幕占用）。
 */
public class GridState {

    public static final int GRID_WIDTH = 12;
    public static final int GRID_HEIGHT = 10;
    /** 屏幕占用的格子标记（与 -1 空和 ≥0 moduleId 区分） */
    public static final int SCREEN_CELL_MARKER = -2;
    /** 屏幕最小尺寸（格） */
    public static final int SCREEN_MIN_SIZE = 2;
    /** 模块 ID 范围（同一 monitor 内唯一） */
    public static final int MODULE_ID_MIN = 0;
    public static final int MODULE_ID_MAX = 9999;

    private final int[][] grid = new int[GRID_WIDTH][GRID_HEIGHT];
    private final Map<Integer, MonitorModule> modules = new LinkedHashMap<>();
    private final Set<Integer> pressedModules = new HashSet<>();
    /** 按钮模块的玩家点击计数，moduleId → 累计点击次数（瞬时态，不持久化） */
    private final Map<Integer, Integer> buttonClickCounts = new java.util.HashMap<>();
    /** 按钮模块存在未读玩家点击的集合（边沿检测，Lua wasClicked() 读取后清除） */
    private final Set<Integer> clickedModules = new HashSet<>();
    /** 玩家互动锁定的按钮集合（true = Lua 控制，玩家按下/释放不改变按下状态） */
    private final Set<Integer> playerLockedModules = new HashSet<>();
    /** 按钮灯带亮度（0..1），moduleId → 亮度 */
    private final Map<Integer, Float> lightBrightness = new java.util.HashMap<>();
    /** 按钮灯带由代码控制的集合（true = 灯带亮度只随 Lua 变化） */
    private final Set<Integer> lightCodeControlledModules = new HashSet<>();
    /** 旋钮模块的角度（度），moduleId → Y 轴旋转角度 */
    private final Map<Integer, Float> knobAngles = new java.util.HashMap<>();
    /** 每个模块的额外配置（tooltip 文本 + 各类型专属键），moduleId → config */
    private final Map<Integer, CompoundTag> moduleConfigs = new java.util.HashMap<>();
    /** 按钮模块表面的标签文字，moduleId → 标签数据（仅 button_1 使用） */
    private final Map<Integer, ButtonLabel> buttonLabels = new java.util.HashMap<>();

    /** 屏幕区域列表（一个 Monitor 可放置多个屏幕） */
    private final List<ScreenRegion> screenRegions = new ArrayList<>();
    /** 每个屏幕的字符缓冲（显示文本），screenId → ScreenText */
    private final Map<Integer, ScreenText> screenTexts = new java.util.HashMap<>();

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

    /** 获取模块的额外配置（无则返回空 tag）。 */
    public CompoundTag getModuleConfig(int moduleId) {
        return moduleConfigs.getOrDefault(moduleId, new CompoundTag());
    }

    /** 设置模块的额外配置。 */
    public void setModuleConfig(int moduleId, CompoundTag config) {
        if (modules.containsKey(moduleId)) moduleConfigs.put(moduleId, config.copy());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    // ── 放置 / 移除 ──

    /** 检查矩形区域是否可放置模块。屏幕占用的格子也不可放置。 */
    public boolean canPlace(int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > GRID_WIDTH || y + h > GRID_HEIGHT) return false;
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                int cell = grid[x + dx][y + dy];
                if (cell != -1) return false;  // 被模块(≥0)或屏幕(-2)占用
            }
        }
        return true;
    }

    /** 检查矩形区域是否可放置屏幕（只检查模块占用，不检查屏幕自身）。 */
    public boolean canPlaceScreen(int minX, int minY, int maxX, int maxY) {
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                if (grid[x][y] >= 0) return false;  // 已有模块占用
        return true;
    }

    /** 放置模块，自动分配最小空闲 moduleId；失败返回 -1。 */
    public int tryPlace(int x, int y, ModuleType type) {
        if (!canPlace(x, y, type.width, type.height)) return -1;
        int id = findFreeId();
        if (id < 0) return -1;
        MonitorModule mod = new MonitorModule(id, type, x, y);
        modules.put(id, mod);
        for (int dx = 0; dx < type.width; dx++) {
            for (int dy = 0; dy < type.height; dy++) {
                grid[x + dx][y + dy] = id;
            }
        }
        return id;
    }

    /** 找到最小空闲 ID（0..9999，模块与屏幕共用命名空间）。 */
    private int findFreeId() {
        for (int id = MODULE_ID_MIN; id <= MODULE_ID_MAX; id++) {
            if (!isIdUsed(id)) return id;
        }
        return -1;
    }

    /** 判断 ID 是否已被模块或屏幕占用。 */
    private boolean isIdUsed(int id) {
        if (modules.containsKey(id)) return true;
        for (var sr : screenRegions) {
            if (sr.id() == id) return true;
        }
        return false;
    }

    /** 本 monitor 内所有控件（模块 + 屏幕）占用的 ID。 */
    public int[] getOccupiedIds() {
        Set<Integer> ids = new HashSet<>(modules.keySet());
        for (var sr : screenRegions) ids.add(sr.id());
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 修改模块 ID（同一 monitor 内唯一）。成功返回 true。 */
    public boolean trySetId(int oldId, int newId) {
        if (oldId == newId) return true;
        MonitorModule mod = modules.get(oldId);
        if (mod == null) return false;
        if (newId < MODULE_ID_MIN || newId > MODULE_ID_MAX) return false;
        if (isIdUsed(newId)) return false;

        modules.remove(oldId);
        modules.put(newId, new MonitorModule(newId, mod.type(), mod.gridX(), mod.gridY()));

        for (int x = 0; x < GRID_WIDTH; x++) {
            for (int y = 0; y < GRID_HEIGHT; y++) {
                if (grid[x][y] == oldId) grid[x][y] = newId;
            }
        }

        if (pressedModules.remove(oldId)) pressedModules.add(newId);
        Integer cc = buttonClickCounts.remove(oldId);
        if (cc != null) buttonClickCounts.put(newId, cc);
        if (clickedModules.remove(oldId)) clickedModules.add(newId);
        if (playerLockedModules.remove(oldId)) playerLockedModules.add(newId);
        Float lb = lightBrightness.remove(oldId);
        if (lb != null) lightBrightness.put(newId, lb);
        if (lightCodeControlledModules.remove(oldId)) lightCodeControlledModules.add(newId);
        Float ka = knobAngles.remove(oldId);
        if (ka != null) knobAngles.put(newId, ka);
        CompoundTag cfg = moduleConfigs.remove(oldId);
        if (cfg != null) moduleConfigs.put(newId, cfg);
        ButtonLabel bl = buttonLabels.remove(oldId);
        if (bl != null) buttonLabels.put(newId, bl);

        return true;
    }

    /** 移除模块，返回被移除的模块信息；不存在返回 null。 */
    public MonitorModule tryRemove(int moduleId) {
        MonitorModule mod = modules.remove(moduleId);
        if (mod == null) return null;
        pressedModules.remove(moduleId);
        buttonClickCounts.remove(moduleId);
        clickedModules.remove(moduleId);
        playerLockedModules.remove(moduleId);
        lightBrightness.remove(moduleId);
        lightCodeControlledModules.remove(moduleId);
        knobAngles.remove(moduleId);
        moduleConfigs.remove(moduleId);
        buttonLabels.remove(moduleId);
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

    // ── 玩家点击检测（按钮） ──

    /** 记录一次玩家点击（按钮按下边沿），累计计数并置位"未读点击"标志。 */
    public void recordPlayerClick(int moduleId) {
        if (!modules.containsKey(moduleId)) return;
        buttonClickCounts.put(moduleId, buttonClickCounts.getOrDefault(moduleId, 0) + 1);
        clickedModules.add(moduleId);
    }

    /** 玩家累计点击次数（Lua 调 press() 不计数）。 */
    public int getClickCount(int moduleId) {
        return buttonClickCounts.getOrDefault(moduleId, 0);
    }

    /** 读取并清除"未读点击"标志（边沿检测）。 */
    public boolean consumeClick(int moduleId) {
        return clickedModules.remove(moduleId);
    }

    /** 清除"未读点击"标志（不读取）。 */
    public void clearClick(int moduleId) {
        clickedModules.remove(moduleId);
    }

    // ── 玩家互动锁（按钮） ──

    /** 设置按钮是否锁定玩家互动（true = Lua 控制，玩家按下/释放不改变状态，但点击仍记录）。 */
    public void setPlayerLocked(int moduleId, boolean locked) {
        if (!modules.containsKey(moduleId)) return;
        if (locked) playerLockedModules.add(moduleId);
        else playerLockedModules.remove(moduleId);
    }

    public boolean isPlayerLocked(int moduleId) {
        return playerLockedModules.contains(moduleId);
    }

    // ── 灯带控制（按钮） ──

    /** 设置按钮灯带亮度（0..1，自动 clamp）。 */
    public void setLightBrightness(int moduleId, float brightness) {
        if (!modules.containsKey(moduleId)) return;
        lightBrightness.put(moduleId, clamp01(brightness));
    }

    /** 当前灯带亮度（0..1，默认 0）。 */
    public float getLightBrightness(int moduleId) {
        return lightBrightness.getOrDefault(moduleId, 0f);
    }

    /** 设置按钮灯带是否由代码控制。 */
    public void setLightCodeControlled(int moduleId, boolean controlled) {
        if (!modules.containsKey(moduleId)) return;
        if (controlled) lightCodeControlledModules.add(moduleId);
        else lightCodeControlledModules.remove(moduleId);
    }

    public boolean isLightCodeControlled(int moduleId) {
        return lightCodeControlledModules.contains(moduleId);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ── 旋钮角度 ──

    public void setKnobAngle(int moduleId, float angle) {
        if (modules.containsKey(moduleId)) knobAngles.put(moduleId, normalizeKnobAngle(angle));
    }

    public float getKnobAngle(int moduleId) {
        return knobAngles.getOrDefault(moduleId, 0f);
    }

    private static float normalizeKnobAngle(float angle) {
        float normalized = angle % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    /**
     * 旋钮卡位步长（度）。卡位开启且角度 &gt; 0 时返回步长，否则返回 0（自由旋转）。
     */
    public int getDetentStep(int moduleId) {
        CompoundTag cfg = moduleConfigs.get(moduleId);
        if (cfg == null || !cfg.getBoolean("detent")) return 0;
        int angle = cfg.getInt("angle");
        return angle > 0 ? angle : 0;
    }

    /** 把角度吸附到最近卡位档位（0-360，360 归一为 0）。 */
    public static float snapToDetent(float angle, int step) {
        if (step <= 0) return normalizeKnobAngle(angle);
        float norm = normalizeKnobAngle(angle);
        float q = norm / step;
        int base = (int) Math.floor(q);
        float frac = q - base;
        // 严格超过半程才吸附到下一档：半程点仍停在当前档位（微扭动峰值处）
        int idx = frac > 0.5f ? base + 1 : base;
        return (idx * step) % 360f;
    }

    /** 卡位开启时把旋钮当前角度吸附到最近档位（配置变更时调用）。 */
    public void snapKnobToDetent(int moduleId) {
        if (!modules.containsKey(moduleId)) return;
        int step = getDetentStep(moduleId);
        if (step <= 0) return;
        knobAngles.put(moduleId, snapToDetent(getKnobAngle(moduleId), step));
    }

    // ── 按钮表面标签 ──

    /** 按钮表面标签（不存在时返回空标签）。 */
    public ButtonLabel getButtonLabel(int moduleId) {
        return buttonLabels.getOrDefault(moduleId, ButtonLabel.EMPTY);
    }

    private ButtonLabel requireButtonLabel(int moduleId) {
        return buttonLabels.getOrDefault(moduleId, ButtonLabel.EMPTY);
    }

    /** 设置按钮标签文字（空串清除显示，但保留位置/字号/颜色/投影）。 */
    public void setButtonLabelText(int moduleId, String text) {
        if (!modules.containsKey(moduleId)) return;
        ButtonLabel l = requireButtonLabel(moduleId);
        buttonLabels.put(moduleId, new ButtonLabel(
                text == null ? "" : text, l.x(), l.y(), l.scale(), l.color(), l.dropShadow()));
    }

    /** 设置按钮标签位置偏移（MC 像素，+x 右、+y 上，0,0 = 标签原点）。 */
    public void setButtonLabelPosition(int moduleId, double x, double y) {
        if (!modules.containsKey(moduleId)) return;
        ButtonLabel l = requireButtonLabel(moduleId);
        buttonLabels.put(moduleId, new ButtonLabel(
                l.text(), x, y, l.scale(), l.color(), l.dropShadow()));
    }

    /** 设置按钮标签字号（块/字体像素，默认 1/512）。 */
    public void setButtonLabelScale(int moduleId, double scale) {
        if (!modules.containsKey(moduleId)) return;
        ButtonLabel l = requireButtonLabel(moduleId);
        buttonLabels.put(moduleId, new ButtonLabel(
                l.text(), l.x(), l.y(), ButtonLabel.clampScale(scale), l.color(), l.dropShadow()));
    }

    /** 设置按钮标签颜色（0xRRGGBB）。 */
    public void setButtonLabelColor(int moduleId, int color) {
        if (!modules.containsKey(moduleId)) return;
        ButtonLabel l = requireButtonLabel(moduleId);
        buttonLabels.put(moduleId, new ButtonLabel(
                l.text(), l.x(), l.y(), l.scale(), ButtonLabel.clampColor(color), l.dropShadow()));
    }

    /** 设置按钮标签是否绘制投影。 */
    public void setButtonLabelDropShadow(int moduleId, boolean dropShadow) {
        if (!modules.containsKey(moduleId)) return;
        ButtonLabel l = requireButtonLabel(moduleId);
        buttonLabels.put(moduleId, new ButtonLabel(
                l.text(), l.x(), l.y(), l.scale(), l.color(), dropShadow));
    }

    // ── 屏幕区域 ──

    /** 屏幕矩形。min 为左上角（较小坐标），max 为右下角（较大坐标）。 */
    public record ScreenRegion(int id, int minX, int minY, int maxX, int maxY, String tooltipText) {
        /** 初始化阶段不传入文本（表面渲染字符尚未实现），tooltip 默认为空。 */
        public ScreenRegion(int id, int minX, int minY, int maxX, int maxY) {
            this(id, minX, minY, maxX, maxY, "");
        }
        public int width()  { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }
    }

    public List<ScreenRegion> getScreenRegions() { return screenRegions; }

    public boolean hasScreen() { return !screenRegions.isEmpty(); }

    /** 屏幕的字符缓冲，不存在返回 null（只读用）。 */
    @javax.annotation.Nullable
    public ScreenText getScreenText(int id) {
        return screenTexts.get(id);
    }

    /** 屏幕的字符缓冲，不存在则创建（写入用）。 */
    public ScreenText getOrCreateScreenText(int id) {
        ScreenText text = screenTexts.get(id);
        if (text == null) {
            text = new ScreenText();
            screenTexts.put(id, text);
        }
        return text;
    }

    /** 根据格子坐标查找所属屏幕，未找到返回 null */
    @javax.annotation.Nullable
    public ScreenRegion getScreenAt(int gx, int gy) {
        for (var sr : screenRegions)
            if (gx >= sr.minX() && gx <= sr.maxX() && gy >= sr.minY() && gy <= sr.maxY())
                return sr;
        return null;
    }

    /** 按 ID 查找屏幕，未找到返回 null。 */
    @javax.annotation.Nullable
    public ScreenRegion getScreenById(int id) {
        for (var sr : screenRegions)
            if (sr.id() == id) return sr;
        return null;
    }

    /** 更新屏幕的 ID 与 tooltip 文本（同一 monitor 内 ID 唯一）。成功返回 true。 */
    public boolean updateScreen(int oldId, int newId, String tooltipText) {
        int idx = -1;
        for (int i = 0; i < screenRegions.size(); i++) {
            if (screenRegions.get(i).id() == oldId) { idx = i; break; }
        }
        if (idx < 0) return false;

        ScreenRegion sr = screenRegions.get(idx);
        if (newId != oldId) {
            if (newId < MODULE_ID_MIN || newId > MODULE_ID_MAX) return false;
            if (isIdUsed(newId)) return false;
        }
        screenRegions.set(idx, new ScreenRegion(newId, sr.minX(), sr.minY(), sr.maxX(), sr.maxY(), tooltipText));
        if (newId != oldId) {
            ScreenText text = screenTexts.remove(oldId);
            if (text != null) screenTexts.put(newId, text);
        }
        return true;
    }

    /**
     * 新增一个屏幕（不再替换已有屏幕），自动分配最小空闲 ID。
     * @return 新屏幕 ID，失败返回 -1
     */
    public int addScreen(int x1, int y1, int x2, int y2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);

        if (minX < 0 || maxX >= GRID_WIDTH || minY < 0 || maxY >= GRID_HEIGHT) return -1;
        if (maxX - minX + 1 < SCREEN_MIN_SIZE || maxY - minY + 1 < SCREEN_MIN_SIZE) return -1;
        if (!canPlaceScreen(minX, minY, maxX, maxY)) return -1;

        int id = findFreeId();
        if (id < 0) return -1;

        ScreenRegion sr = new ScreenRegion(id, minX, minY, maxX, maxY);
        screenRegions.add(sr);
        screenTexts.put(id, new ScreenText());
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                grid[x][y] = SCREEN_CELL_MARKER;
        return id;
    }

    /** 移除指定格子所属的屏幕。成功返回 true。 */
    public boolean removeScreenAt(int gx, int gy) {
        ScreenRegion sr = getScreenAt(gx, gy);
        if (sr == null) return false;
        screenRegions.remove(sr);
        screenTexts.remove(sr.id());
        for (int x = sr.minX(); x <= sr.maxX(); x++)
            for (int y = sr.minY(); y <= sr.maxY(); y++)
                if (grid[x][y] == SCREEN_CELL_MARKER)
                    grid[x][y] = -1;
        return true;
    }

    /** 清除所有屏幕 */
    public void clearAllScreens() {
        for (var sr : screenRegions)
            for (int x = sr.minX(); x <= sr.maxX(); x++)
                for (int y = sr.minY(); y <= sr.maxY(); y++)
                    if (grid[x][y] == SCREEN_CELL_MARKER)
                        grid[x][y] = -1;
        screenRegions.clear();
        screenTexts.clear();
    }

    // ── NBT ──

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();

        ListTag modList = new ListTag();
        for (MonitorModule mod : modules.values()) {
            CompoundTag modTag = new CompoundTag();
            modTag.putInt("id", mod.id());
            modTag.putString("type", mod.type().name);
            modTag.putInt("x", mod.gridX());
            modTag.putInt("y", mod.gridY());
            modTag.putBoolean("pressed", pressedModules.contains(mod.id()));
            if (mod.type() == ModuleType.KNOB)
                modTag.putFloat("knobAngle", getKnobAngle(mod.id()));
            if (playerLockedModules.contains(mod.id())) modTag.putBoolean("playerLocked", true);
            if (lightCodeControlledModules.contains(mod.id())) modTag.putBoolean("lightCodeControlled", true);
            float lb = lightBrightness.getOrDefault(mod.id(), 0f);
            if (lb > 0f) modTag.putFloat("lightBrightness", lb);
            CompoundTag cfg = moduleConfigs.get(mod.id());
            if (cfg != null && !cfg.isEmpty()) modTag.put("config", cfg);
            if (mod.type() == ModuleType.BUTTON_1X1) {
                ButtonLabel label = buttonLabels.get(mod.id());
                if (label != null && !label.isDefault()) {
                    modTag.putString("labelText", label.text());
                    modTag.putDouble("labelX", label.x());
                    modTag.putDouble("labelY", label.y());
                    modTag.putDouble("labelScale", label.scale());
                    modTag.putInt("labelColor", label.color());
                    modTag.putBoolean("labelDropShadow", label.dropShadow());
                }
            }
            modList.add(modTag);
        }
        tag.put("modules", modList);

        // 屏幕区域列表
        ListTag scrList = new ListTag();
        for (var sr : screenRegions) {
            CompoundTag scrTag = new CompoundTag();
            scrTag.putInt("id", sr.id());
            scrTag.putInt("minX", sr.minX());
            scrTag.putInt("minY", sr.minY());
            scrTag.putInt("maxX", sr.maxX());
            scrTag.putInt("maxY", sr.maxY());
            if (!sr.tooltipText().isEmpty()) scrTag.putString("tooltipText", sr.tooltipText());
            scrList.add(scrTag);
        }
        tag.put("screens", scrList);

        // 屏幕字符缓冲
        ListTag txtList = new ListTag();
        for (var sr : screenRegions) {
            ScreenText text = screenTexts.get(sr.id());
            if (text == null) continue;
            CompoundTag txtTag = text.save();
            txtTag.putInt("id", sr.id());
            txtList.add(txtTag);
        }
        tag.put("screenTexts", txtList);

        return tag;
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        modules.clear();
        pressedModules.clear();
        buttonClickCounts.clear();
        clickedModules.clear();
        playerLockedModules.clear();
        lightBrightness.clear();
        lightCodeControlledModules.clear();
        knobAngles.clear();
        moduleConfigs.clear();
        buttonLabels.clear();
        screenRegions.clear();
        screenTexts.clear();
        for (int x = 0; x < GRID_WIDTH; x++) {
            for (int y = 0; y < GRID_HEIGHT; y++) {
                grid[x][y] = -1;
            }
        }

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
            if (modTag.getBoolean("playerLocked")) playerLockedModules.add(id);
            if (modTag.getBoolean("lightCodeControlled")) lightCodeControlledModules.add(id);
            if (modTag.contains("lightBrightness")) lightBrightness.put(id, clamp01(modTag.getFloat("lightBrightness")));
            if (type == ModuleType.KNOB) {
                float angle = modTag.contains("knobAngle")
                        ? modTag.getFloat("knobAngle") : 0f;
                knobAngles.put(id, normalizeKnobAngle(angle));
            }
            if (modTag.contains("config")) moduleConfigs.put(id, modTag.getCompound("config"));
            if (type == ModuleType.BUTTON_1X1 && modTag.contains("labelText")) {
                buttonLabels.put(id, new ButtonLabel(
                        modTag.getString("labelText"),
                        modTag.getDouble("labelX"),
                        modTag.getDouble("labelY"),
                        ButtonLabel.clampScale(modTag.contains("labelScale")
                                ? modTag.getDouble("labelScale") : ButtonLabel.DEFAULT_SCALE),
                        ButtonLabel.clampColor(modTag.contains("labelColor")
                                ? modTag.getInt("labelColor") : ButtonLabel.DEFAULT_COLOR),
                        !modTag.contains("labelDropShadow")
                                || modTag.getBoolean("labelDropShadow")));
            }
            for (int dx = 0; dx < type.width; dx++) {
                for (int dy = 0; dy < type.height; dy++) {
                    grid[x + dx][y + dy] = id;
                }
            }
        }

        // 恢复屏幕区域列表
        if (tag.contains("screens")) {
            ListTag scrList = tag.getList("screens", Tag.TAG_COMPOUND);
            for (int i = 0; i < scrList.size(); i++) {
                CompoundTag scrTag = scrList.getCompound(i);
                int id = scrTag.contains("id") ? scrTag.getInt("id") : findFreeId();
                int minX = scrTag.getInt("minX");
                int minY = scrTag.getInt("minY");
                int maxX = scrTag.getInt("maxX");
                int maxY = scrTag.getInt("maxY");
                String tooltipText = scrTag.getString("tooltipText");
                screenRegions.add(new ScreenRegion(id, minX, minY, maxX, maxY, tooltipText));
                for (int x = minX; x <= maxX; x++)
                    for (int y = minY; y <= maxY; y++)
                        grid[x][y] = SCREEN_CELL_MARKER;
            }
        }

        // 恢复屏幕字符缓冲
        if (tag.contains("screenTexts")) {
            ListTag txtList = tag.getList("screenTexts", Tag.TAG_COMPOUND);
            for (int i = 0; i < txtList.size(); i++) {
                CompoundTag txtTag = txtList.getCompound(i);
                int id = txtTag.getInt("id");
                ScreenText text = new ScreenText();
                text.load(txtTag);
                screenTexts.put(id, text);
            }
        }
    }
}
