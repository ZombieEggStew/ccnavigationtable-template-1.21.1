package com.zzy205.myfirstmod.block;

/**
 * 操纵杆倾斜动画参数与目标计算（Flywheel Visual 与 BER 共用单一实现）。
 * <p>
 * 枢轴 (8,6,3) 为用户在 Blockbench 中确定的旋转中心（模型像素，÷16 转块单位）；
 * 倾斜方向：axisX（A/D）绕 Z 轴、axisY（W/S）绕 X 轴，最大 {@link #MAX_DEG} 度。
 * <p>
 * 分层约定：**数值**（{@link ControlDeskBlockEntity} 的轴值，服务端权威）= 每 tick 线性累加
 * （自由模式：按下按 1/满偏tick 累加、松开按回正时间线性归零；档位模式：关闭回正，
 * 按键按下边沿 ±1 档步进；CC 接口直接读）；**动画**（渲染层）= 各渲染端用
 * {@link #approach} 指数逼近追逐轴值（aeroworks SMOOTHED 模式，帧时间修正）。
 * <p>
 * 本类的 {@link #stepAxis}/{@link #stepGear}/{@link #pressStep}/{@link #returnStep}/{@link #gearStep}
 * 为服务端 BE 模拟与客户端本地模拟（HUD overlay）共用的轴动力学实现。
 */
public final class JoystickTilt {

    /** 最大倾角（度） */
    public static final float MAX_DEG = 15f;

    /** 枢轴（块单位）：Blockbench 旋转中心 (8,6,3) / 16 */
    public static final float PIVOT_X = 8f / 16f;
    public static final float PIVOT_Y = 6f / 16f;
    public static final float PIVOT_Z = 3f / 16f;

    /** 动画指数逼近衰减系数（每 tick 剩余差距乘该系数，越小越跟手；参考 aeroworks ScrollAnimation 0.3） */
    public static final float SMOOTH_DECAY = 0.3f;

    /** 与目标差距小于该值时直接贴目标（度） */
    private static final float SNAP_DISTANCE = 0.01f;

    private JoystickTilt() {}

    /**
     * 目标倾角（度）{tiltX, tiltY}：读服务端权威轴值（BE 运行时状态，经 getUpdatePacket 同步，
     * 所有客户端一致）。联动作用域/档位保持由服务端模拟天然保证（服务端只更新坐垫四邻的 BE，
     * 未联动的保持原值）。tiltX 绕 Z 轴（A/D），tiltY 绕 X 轴（W/S）。
     */
    public static float[] targetDeg(ControlDeskBlockEntity be) {
        return new float[] {
                be.getJoystickAxisX() * MAX_DEG,
                be.getJoystickAxisY() * MAX_DEG
        };
    }

    /** 每轴线性累加（数值层）：按下向目标累加 pressStep（1 = 满偏）；松开向 0 累加 returnStep（0 = 关闭回正保持不动）。 */
    public static float stepAxis(float value, float target, float pressStep, float returnStep) {
        if (target > 0f) return Math.min(1f, value + pressStep);
        if (target < 0f) return Math.max(-1f, value - pressStep);
        if (returnStep <= 0f) return value;
        if (value > 0f) return Math.max(0f, value - returnStep);
        if (value < 0f) return Math.min(0f, value + returnStep);
        return 0f;
    }

    /**
     * 档位步进（数值层，档位模式）：轴值吸附为离散档位 pos(k) = -1 + 2k/(档位数-1)，
     * 按下边沿进/退一档（相邻档间隔 = 2/(档位数-1)，钳位两端）。无方向或同时按两个方向 → 保持当前档位。
     */
    public static float stepGear(float value, boolean forward, boolean backward, int gearCount) {
        if (forward == backward) return value;
        float step = gearStep(gearCount);
        if (step <= 0f) return value;
        // 当前档位索引（轴值已在档位上时换算精确），按下方向 ±1 并钳位两端
        int k = Math.round((value + 1f) * (gearCount - 1) / 2f);
        k = Math.max(0, Math.min(gearCount - 1, k));
        if (forward) k = Math.min(gearCount - 1, k + 1);
        else k = Math.max(0, k - 1);
        return -1f + 2f * k / (gearCount - 1);
    }

    /**
     * 回正线性步长（数值层）：每 tick 向 0 累加 1/回正时间（恰好回正时间 tick 归零）；
     * 0 = 关闭回正（保持当前值不归零）。
     */
    public static float returnStep(int ticks) {
        return ticks <= 0 ? 0f : 1f / ticks;
    }

    /**
     * 按下线性步长（数值层，自由模式）：每 tick 向目标累加 1/满偏 tick 数
     * （恰好满偏 tick 数后到达 ±1；1 = 按下即满偏）。
     */
    public static float pressStep(int ticks) {
        return 1f / Math.max(1, ticks);
    }

    /**
     * 档位步长（数值层，档位模式）：相邻两档间隔 = 2/(档位数-1)。
     * N 个档位均匀分布在 [-1,1]：pos(k) = -1 + 2k/(N-1)（2 档 = {-1,1}，3 档 = {-1,0,1}，4 档 = {-1,-1/3,1/3,1}）；
     * {@code gearCount <= 1} 时步长为 0（不步进，轴保持当前位置）。
     */
    public static float gearStep(int gearCount) {
        return gearCount <= 1 ? 0f : 2f / (gearCount - 1);
    }

    /** 指数逼近（动画层，帧时间修正）：向 target 逼近，差距 &lt; SNAP 直接贴目标。 */
    public static float approach(float current, float target, float frameTicks) {
        float remaining = target - current;
        if (Math.abs(remaining) < SNAP_DISTANCE) return target;
        return target - remaining * (float) Math.pow(SMOOTH_DECAY, frameTicks);
    }
}
