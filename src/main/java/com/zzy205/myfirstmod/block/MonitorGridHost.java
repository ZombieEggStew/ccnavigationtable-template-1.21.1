package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.ScreenText;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 网格宿主抽象：Monitor 方块实体与 controlDesk 的 monitor_2 模块都拥有一个 {@link GridState}
 * （网格 + 模块 + 屏幕），并提供相同的服务端操作方法（放置/移除/按压/旋钮/屏幕文本等）。
 * <p>
 * Lua 模块实例（{@code compat/cc/ModuleHandle} 系列）与外设查询（{@code compat/cc/MonitorPeripheral}）
 * 只依赖本接口，因此同一套 handle / 外设查询可以同时服务 Monitor 与 monitor_2，无需复制。
 * 网络 payload 处理器也按本接口统一分发。
 */
public interface MonitorGridHost {

    GridState getGridState();

    /** 宿主方块实体的世界（供外设音效等按位置操作；两实现类均为 BlockEntity，天然实现）。 */
    Level getLevel();

    /** 宿主方块位置（供外设音效/equals 等按位置操作）。 */
    BlockPos getBlockPos();

    // ── 模块 ──

    /** 尝试放置模块（服务端调用），成功返回 moduleId，失败返回 -1。 */
    int tryPlaceModule(int x, int y, ModuleType type);

    /** 移除模块，成功返回被移除的模块类型名，失败返回 null。 */
    String tryRemoveModule(int moduleId);

    /** 按钮按下（服务端，自动同步客户端）。 */
    void pressModule(int id);

    /** 按钮释放（服务端，自动同步客户端）。 */
    void releaseModule(int id);

    /** 玩家点击按钮按下（服务端调用）：始终记录玩家点击；锁定时不改变按下状态。 */
    void pressModuleByPlayer(int id);

    /** 玩家释放按钮（服务端调用）：锁定时不改变状态。 */
    void releaseModuleByPlayer(int id);

    /** 反转锁存状态（钮子开关等）。 */
    void toggleModule(int id);

    /** 设置钮子开关锁存状态。 */
    void setToggleState(int id, boolean state);

    /** 旋钮旋转（服务端），angle 为累计角度（度）。 */
    void rotateKnob(int id, float angle);

    /** 设置模块/屏幕 tooltip。 */
    void setTooltip(int id, String text);

    /** 应用控件的 ID 与配置（模块/屏幕共用入口）。 */
    void applyModuleConfig(String name, int oldId, int newId, net.minecraft.nbt.CompoundTag config);

    // ── 屏幕 ──

    /** 新增一个屏幕（服务端调用），自动分配最小空闲 ID；失败返回 -1。 */
    int addScreen(int x1, int y1, int x2, int y2);

    /** 移除指定格子所属的屏幕（服务端调用）。 */
    boolean removeScreenAt(int gx, int gy);

    // ── 按钮灯带 / 标签 / 玩家锁 ──

    void setButtonPlayerControl(int id, boolean enabled);

    void setButtonLight(int id, float brightness);

    void setButtonLightControl(int id, boolean codeControlled);

    void setButtonLabelText(int id, String text);

    void setButtonLabelPosition(int id, double x, double y);

    void setButtonLabelScale(int id, double scale);

    void setButtonLabelColor(int id, int color);

    void setButtonLabelDropShadow(int id, boolean dropShadow);

    // ── 屏幕（格子模型） ──

    void screenSetGrid(int id, int cols, int rows);

    /** 读取屏幕当前格子数，返回 {cols, rows}；屏幕不存在返回 null。 */
    int[] getScreenGrid(int id);

    void screenSetTextScale(int id, double scale, Double lineSpacing);

    void screenWrite(int id, String text);

    void screenClear(int id);

    void screenSetCursor(int id, int col, int row);

    void screenSetTextColour(int id, int colour);

    void screenSetZIndex(int id, double z);

    void screenSetOverflowMode(int id, String mode);

    /** 设置屏幕渲染开关（false = 整个屏幕 9 宫格与内容都不绘制）。 */
    void screenSetVisible(int id, boolean visible);

    void screenFill(int id, int col, int row, int w, int h, int colour);

    /** @param colour 可选，前景色（0xRRGGBB）；null 表示用屏幕当前前景色（setTextColour 设置） */
    void screenWriteField(int id, int col, int row, int width, String text, String align, Integer colour);

    void screenFillField(int id, int col, int row, int width, int count, int colour, String align);

    void screenDraw(int id, List<int[]> cells,
                    List<ScreenText.Rect> rects, List<ScreenText.Line> lines, List<ScreenText.Circle> circles);

    void screenReplaceCells(int id, List<int[]> cells);

    void screenReplaceShapes(int id, List<ScreenText.Rect> rects,
                             List<ScreenText.Line> lines, List<ScreenText.Circle> circles);

    void screenDrawRect(int id, double x, double y, double w, double h,
                        int colour, boolean solid, double lineWidth, Double z);

    void screenClearRects(int id);

    void screenDrawLine(int id, double x1, double y1, double x2, double y2,
                        int colour, double lineWidth, Double z);

    void screenDrawCircle(int id, double cx, double cy, double radius, int colour,
                          boolean solid, double lineWidth, int segments, Double z);

    void screenClearShapes(int id);
}
