package com.zzy205.myfirstmod.block;

/**
 * 油门2（throttle_2）总距杆（直升机 collective 类型）动画参数与目标计算（Flywheel Visual 与 BER 共用单一实现）。
 * <p>
 * 手柄绕枢轴 (4,2,8) 旋转（Blockbench 旋转中心，模型像素，÷16 转块；用户定稿），
 * 角度范围 <b>0°（最底端，放置后的默认位置）→ {@link #MAX_DEG}（+30°，上抬满偏）</b>——
 * 单边行程（总距杆物理：地面位 = 最底端，拉起 = 向上）。
 * <p>
 * 输入（默认键，可经 Throttle2ModuleScreen 配置）：<b>空格 = 上抬（角度 +）/ 左Ctrl = 下拉（角度 -）</b>；
 * 无输入<b>锁存不回正</b>（直升机总距杆机械锁存语义，与 throttle 档位一致）。
 * 满偏时间默认 {@link #FREE_SPEED_TICKS}（6 tick 从底端到满偏），可经配置菜单调整
 * （模拟读 BE {@code getThrottle2FreeSpeed}，见 {@link ControlDeskBlockEntity#simulateThrottle2}）。
 * <p>
 * 分层约定：**数值**（{@link ControlDeskBlockEntity} 的 {@code throttle2Angle}，服务端权威，
 * 经 getUpdatePacket 同步，所有客户端一致）= 每 tick 线性累加；**动画**（渲染层）= 各渲染端用
 * {@link JoystickTilt#approach} 指数逼近追逐角度（aeroworks SMOOTHED 模式，帧时间修正）。
 */
public final class Throttle2Motion {

    /** 最大角度（度）：上抬满偏 = +30°（用户定稿） */
    public static final float MAX_DEG = 30f;

    /** 枢轴（块单位）：Blockbench 旋转中心 (4,2,8) / 16（用户定稿，handle 杆底中心） */
    public static final float PIVOT_X = 4f / 16f;
    public static final float PIVOT_Y = 2f / 16f;
    public static final float PIVOT_Z = 8f / 16f;

    /** 满偏时间（tick）默认值：按住满 20 tick 从最底端（0°）到满偏（+30°），步进 = 1/20 每 tick（用户定稿；可经 Throttle2ModuleScreen 配置，模拟读 BE {@code getThrottle2FreeSpeed}） */
    public static final int FREE_SPEED_TICKS = 20;

    /** 中位角度（度）：回正开关开启时，松开按键后自动回正到的位置 = 满偏的一半 15°（用户定稿） */
    public static final float NEUTRAL_DEG = MAX_DEG / 2f;

    private Throttle2Motion() {}

    /** 目标角度（度）：读服务端权威角度（0..MAX_DEG，经 getUpdatePacket 同步，所有客户端一致）。 */
    public static float targetDeg(ControlDeskBlockEntity be) {
        return be.getThrottle2Angle();
    }
}
