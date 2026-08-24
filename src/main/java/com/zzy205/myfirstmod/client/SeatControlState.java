package com.zzy205.myfirstmod.client;

/**
 * 坐垫操作模式共享状态（客户端 tick 由 {@link SeatControlListener} 更新，HUD overlay 每帧读取）。
 * 后续踏板状态、CC API 轴值也放这里。
 */
public final class SeatControlState {

    private static boolean operating;    // 操作模式（骑乘坐垫 + 四邻有 controlDesk）
    private static boolean hasJoystick;  // 联动控制台中至少一个装了操纵杆
    private static float joyX;           // 操纵杆方向向量 X：+1 = 右摆(D)，-1 = 左摆(A)
    private static float joyY;           // 操纵杆方向向量 Y：+1 = 前推(W)，-1 = 后拉(S)

    private SeatControlState() {}

    public static void update(boolean operating, boolean hasJoystick, float joyX, float joyY) {
        SeatControlState.operating = operating;
        SeatControlState.hasJoystick = hasJoystick;
        SeatControlState.joyX = joyX;
        SeatControlState.joyY = joyY;
    }

    /** 离开操作模式时清零。 */
    public static void clear() {
        update(false, false, 0f, 0f);
    }

    public static boolean isOperating() {
        return operating;
    }

    public static boolean hasJoystick() {
        return hasJoystick;
    }

    public static float getJoyX() {
        return joyX;
    }

    public static float getJoyY() {
        return joyY;
    }
}
