package com.zzy205.myfirstmod.client;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 坐垫操作模式共享状态（客户端 tick 由 {@link SeatControlListener} 更新，控件动画与 HUD overlay 每帧读取）。
 * <p>
 * 操纵杆数据分三层（为 CC 接口预留）：
 * <ul>
 *   <li>原始值 {@code rawX/rawY}（0/1）：该轴有无按键动作</li>
 *   <li>轴值 {@code analogX/analogY}（0..1）：模拟量幅度（= |axis|）</li>
 *   <li>模拟轴 {@code axisX/axisY}（-1..1 带符号）：<b>真实的模拟量</b>——每 tick <b>线性累加</b>
 *       （按下即满偏、松开每 tick 向 0 累加 1/回正时间）；动画/overlay 用指数逼近追逐该值，
 *       CC 接口直接读该值</li>
 * </ul>
 * 后续踏板状态也放这里。
 */
public final class SeatControlState {

    private static boolean operating;    // 操作模式（骑乘坐垫 + 四邻有 controlDesk）
    private static boolean hasJoystick;  // 联动控制台中至少一个装了操纵杆
    private static float axisX;          // 模拟轴 X（每 tick 线性累加）：+1 = 右摆(D)，-1 = 左摆(A)
    private static float axisY;          // 模拟轴 Y（每 tick 线性累加）：+1 = 前推(W)，-1 = 后拉(S)
    private static boolean rawX;         // 原始值：X 轴有无按键动作
    private static boolean rawY;         // 原始值：Y 轴有无按键动作
    private static float analogX;        // 轴值 0..1：X 轴模拟量幅度
    private static float analogY;        // 轴值 0..1：Y 轴模拟量幅度
    /** 档位保持（每 tick 由监听器按档位模式写入）：该轴处于档位模式时，离开坐垫不清零轴值（物理换挡杆语义）。 */
    private static boolean gearHoldX;
    private static boolean gearHoldY;
    /** 当前联动（坐垫四邻）的 controlDesk 位置，供各控制台动画判断是否被本地玩家操控 */
    private static final List<BlockPos> linkedDesks = new ArrayList<>(4);
    // ── 油门张力（操作者本地视觉，供油门手柄渲染"按住蠕动 + 步进突然到位"）──
    /** 油门操作方向：+1 前进（空格）/ -1 后退（左Ctrl）/ 0 无输入（每 tick 由监听器写入） */
    private static int throttleDir;

    private SeatControlState() {}

    public static void update(boolean operating, boolean hasJoystick,
                              float axisX, float axisY,
                              boolean rawX, boolean rawY,
                              float analogX, float analogY) {
        SeatControlState.axisX = axisX;
        SeatControlState.axisY = axisY;
        SeatControlState.operating = operating;
        SeatControlState.hasJoystick = hasJoystick;
        SeatControlState.rawX = rawX;
        SeatControlState.rawY = rawY;
        SeatControlState.analogX = analogX;
        SeatControlState.analogY = analogY;
    }

    /** 更新联动控制台列表（操作模式下每 tick 由监听器写入）。 */
    public static void setLinkedDesks(List<BlockPos> desks) {
        linkedDesks.clear();
        linkedDesks.addAll(desks);
    }

    /** 该控制台是否在当前联动集合中（本地玩家正坐在这张坐垫附近操作）。 */
    public static boolean isLinkedDesk(BlockPos pos) {
        return linkedDesks.contains(pos);
    }

    /**
     * 离开操作模式时清零；档位保持的轴保留轴值（模拟量同步为 |axis|），
     * 且任一轴处于档位保持时保留联动列表（供 {@link #isLinkedDesk} 判定「曾联动的控制台」以显示档位保持）。
     */
    public static void clear() {
        float keepX = gearHoldX ? axisX : 0f;
        float keepY = gearHoldY ? axisY : 0f;
        update(false, false, keepX, keepY, false, false, Math.abs(keepX), Math.abs(keepY));
        throttleDir = 0;
        if (!gearHoldX && !gearHoldY) {
            linkedDesks.clear();
        }
    }

    /** 设置两轴档位保持标志（操作模式下每 tick 由监听器按档位模式写入）。 */
    public static void setGearHold(boolean x, boolean y) {
        gearHoldX = x;
        gearHoldY = y;
    }

    /** 更新油门操作方向（每 tick 由监听器写入；渲染层自行检测方向变化并重置张力充电进度）。 */
    public static void setThrottleDir(int dir) {
        throttleDir = dir;
    }

    public static int getThrottleDir() {
        return throttleDir;
    }

    public static boolean isGearHoldX() {
        return gearHoldX;
    }

    public static boolean isGearHoldY() {
        return gearHoldY;
    }

    public static boolean isOperating() {
        return operating;
    }

    public static boolean hasJoystick() {
        return hasJoystick;
    }

    public static float getAxisX() {
        return axisX;
    }

    public static float getAxisY() {
        return axisY;
    }

    public static boolean isRawX() {
        return rawX;
    }

    public static boolean isRawY() {
        return rawY;
    }

    public static float getAnalogX() {
        return analogX;
    }

    public static float getAnalogY() {
        return analogY;
    }
}
