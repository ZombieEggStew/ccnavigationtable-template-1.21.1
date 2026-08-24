package com.zzy205.myfirstmod.block;

/**
 * 脚踏板动画参数与目标计算（Flywheel Visual 与 BER 共用单一实现）。
 * <p>
 * 踩下/抬起 = <b>前后平移（不是旋转）</b>：踏板本体沿模型空间 <b>z</b> 轴平移
 * 轴值 × {@link #MAX_TRAVEL}（1px = 1/16 块）——踩下（+1）向 <b>+z</b>、抬起（-1）向 <b>-z</b>，
 * 随 FACING 旋转后仍朝向桌面（远离操作者 / 朝向操作者）。
 * <p>
 * 分层约定与操纵杆一致（见 {@link JoystickTilt}）：**数值**（{@link ControlDeskBlockEntity}
 * 的轴值 -1..1，服务端权威）= 踩下键按住按 {@link #PRESS_STEP} 累加（按下即满偏）、
 * 抬起键按住向 -1 累加、都不按按回正时间线性归零（{@link JoystickTilt#returnStep}）；
 * **动画**（渲染层）= 各渲染端用 {@link JoystickTilt#approach} 指数逼近追逐轴值 × {@link #MAX_TRAVEL}
 * （aeroworks SMOOTHED 模式，帧时间修正）。
 */
public final class PedalMotion {

    /** 最大行程（块单位）：满偏 = 向 z 轴平移 1px */
    public static final float MAX_TRAVEL = 1f / 16f;

    /** 数值层步长：踩下/抬起键按住即满偏（1 tick 到 ±1；回正仍受回正时间控制） */
    public static final float PRESS_STEP = 1f;

    private PedalMotion() {}

    /**
     * 目标平移量（块单位）{leftPx, rightPx}：读服务端权威轴值（BE 运行时状态，
     * 经 getUpdatePacket 同步，所有客户端一致）。
     */
    public static float[] targetPx(ControlDeskBlockEntity be) {
        return new float[] {
                be.getPedalLeftAxis() * MAX_TRAVEL,
                be.getPedalRightAxis() * MAX_TRAVEL
        };
    }
}
