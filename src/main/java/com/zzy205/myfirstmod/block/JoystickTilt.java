package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.client.SeatControlState;

/**
 * 操纵杆倾斜动画参数与目标计算（Flywheel Visual 与 BER 共用单一实现）。
 * <p>
 * 枢轴 (8,3,3) 为用户在 Blockbench 中确定的旋转中心（模型像素，÷16 转块单位）；
 * 倾斜方向：axisX（A/D）绕 Z 轴、axisY（W/S）绕 X 轴，最大 {@link #MAX_DEG} 度。
 * <p>
 * 分层约定：**数值**（{@link SeatControlState} 的轴值）= 每 tick 线性累加（按下即满偏、
 * 松开按回正时间线性归零，CC 接口直接读）；**动画**（渲染层）= 各渲染端用
 * {@link #approach} 指数逼近追逐轴值（aeroworks SMOOTHED 模式，帧时间修正）。
 */
public final class JoystickTilt {

    /** 最大倾角（度） */
    public static final float MAX_DEG = 15f;

    /** 枢轴（块单位）：Blockbench 旋转中心 (8,3,3) / 16 */
    public static final float PIVOT_X = 8f / 16f;
    public static final float PIVOT_Y = 3f / 16f;
    public static final float PIVOT_Z = 3f / 16f;

    /** 按下线性步长：每 tick 向目标累加该值（1 = 按下即满偏） */
    public static final float PRESS_STEP = 1f;

    /** 动画指数逼近衰减系数（每 tick 剩余差距乘该系数，越小越跟手；参考 aeroworks ScrollAnimation 0.3） */
    public static final float SMOOTH_DECAY = 0.3f;

    /** 与目标差距小于该值时直接贴目标（度） */
    private static final float SNAP_DISTANCE = 0.01f;

    private JoystickTilt() {}

    /**
     * 目标倾角（度）{tiltX, tiltY}：本地玩家操作模式下联动该控制台 → 模拟轴（tick 值）× 最大角，
     * 否则 0（回正）。tiltX 绕 Z 轴（A/D），tiltY 绕 X 轴（W/S）。
     */
    public static float[] targetDeg(ControlDeskBlockEntity be) {
        if (SeatControlState.isOperating() && SeatControlState.isLinkedDesk(be.getBlockPos())) {
            return new float[] {
                    SeatControlState.getAxisX() * MAX_DEG,
                    SeatControlState.getAxisY() * MAX_DEG
            };
        }
        return new float[] { 0f, 0f };
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
     * （恰好满偏 tick 数后到达 ±1；1 = 按下即满偏，与 {@link #PRESS_STEP} 一致）。
     */
    public static float pressStep(int ticks) {
        return 1f / Math.max(1, ticks);
    }

    /**
     * 档位吸附（数值层，档位模式）：把轴值量化为最近的档位位置。
     * 档位 = [-1, 1] 上均匀分布的 {@code gearCount} 个位置，且 0（中位）始终作为候选
     * —— 偶数档位没有正中间档，强制含 0 保证松开后能回中停住（而不是越过中心吸附到反向档位）。
     * {@code gearCount <= 1} 时只有中位 0。
     */
    public static float nearestGear(float value, int gearCount) {
        if (gearCount <= 1) return 0f;
        float best = 0f;
        float bestDist = Math.abs(value);
        for (int k = 0; k < gearCount; k++) {
            float pos = -1f + 2f * k / (gearCount - 1);
            float dist = Math.abs(value - pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    /** 指数逼近（动画层，帧时间修正）：向 target 逼近，差距 &lt; SNAP 直接贴目标。 */
    public static float approach(float current, float target, float frameTicks) {
        float remaining = target - current;
        if (Math.abs(remaining) < SNAP_DISTANCE) return target;
        return target - remaining * (float) Math.pow(SMOOTH_DECAY, frameTicks);
    }
}
