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
    /** 当前联动（坐垫四邻）的 controlDesk 位置，供各控制台动画判断是否被本地玩家操控 */
    private static final List<BlockPos> linkedDesks = new ArrayList<>(4);

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

    /** 离开操作模式时清零。 */
    public static void clear() {
        update(false, false, 0f, 0f, false, false, 0f, 0f);
        linkedDesks.clear();
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
