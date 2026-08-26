package com.zzy205.myfirstmod.block;

/**
 * 摇杆2（joystick_2）倾斜动画参数与目标计算（Flywheel Visual 与 BER 共用单一实现）。
 * <p>
 * 枢轴 (8,1,8) 为用户在 Blockbench 中确定的旋转中心（模型像素，÷16 转块单位；摇杆2 杆体
 * x7.3..8.7 / y0..8 / z7.3..8.7，杆底中心 (8,1,8) ≈ 底座板顶面）。
 * 倾斜方向与 joystick 一致：axisX（A/D）绕 Z 轴、axisY（W/S）绕 X 轴，最大 {@link #MAX_DEG} 度。
 * <p>
 * 分层约定与 joystick 相同（见 {@link JoystickTilt}）：**数值**（{@link ControlDeskBlockEntity}
 * 的 {@code joystick2AxisX/Y}，服务端权威）= 每 tick 线性累加（自由模式按下/回正、档位模式按下边沿
 * 步进，动力学全部复用 {@link JoystickTilt}）；**动画**（渲染层）= 各渲染端用
 * {@link JoystickTilt#approach} 指数逼近追逐轴值 × {@link #MAX_DEG}（帧时间修正）。
 */
public final class Joystick2Motion {

    /** 最大倾角（度，与 joystick 一致） */
    public static final float MAX_DEG = JoystickTilt.MAX_DEG;

    /** 枢轴（块单位）：Blockbench 旋转中心 (8,1,8) / 16（用户定稿） */
    public static final float PIVOT_X = 8f / 16f;
    public static final float PIVOT_Y = 1f / 16f;
    public static final float PIVOT_Z = 8f / 16f;

    private Joystick2Motion() {}

    /**
     * 目标倾角（度）{tiltX, tiltY}：读服务端权威轴值（BE 运行时状态，经 getUpdatePacket 同步，
     * 所有客户端一致）。tiltX 绕 Z 轴（A/D），tiltY 绕 X 轴（W/S）。
     */
    public static float[] targetDeg(ControlDeskBlockEntity be) {
        return new float[] {
                be.getJoystick2AxisX() * MAX_DEG,
                be.getJoystick2AxisY() * MAX_DEG
        };
    }
}
