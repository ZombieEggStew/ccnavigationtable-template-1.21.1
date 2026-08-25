package com.zzy205.myfirstmod.block;

import net.createmod.catnip.theme.Color;

/**
 * 油门杆档位与动画参数（Flywheel Visual 与 BER 共用单一实现）。
 * <p>
 * 前进/后退 = <b>手柄沿模型空间 <b>x</b> 轴平移</b>（BlockBench 中取的方向）：
 * <b>默认模型的 handle 在最底端（-x 端，即 0px）</b>，最多向 <b>+x</b> 平移
 * {@link #MAX_TRAVEL_PX}（11px）；<b>档位模式</b>：1px = 1 档，共 12 档（0..11），
 * 默认在最低档（0）——前进（空格，档位 +1）向 +x、后退（左Ctrl，档位 -1）向 -x 回底端；
 * 随 FACING 旋转后仍沿桌面方向。
 * <p>
 * 档位切换：按键按住需满配置的档位切换节奏（{@link ControlDeskBlockEntity#getThrottleTicksPerGear}，
 * 默认 {@link #TICKS_PER_GEAR} tick）才进/退一档（每档 = 1px），
 * 连续按住每满该 tick 数步进一档；无输入**锁存**（保持当前档位，不回正）。
 * **数值**（{@link ControlDeskBlockEntity#getThrottleAxis()} = 档位 / MAX_TRAVEL_PX，
 * 服务端权威）= 离散档位；**动画**（渲染层）= 各渲染端用 {@link #approachStep}
 * 快速逼近追逐档位位置（段落感，参考 Monitor knob 卡位模式，帧时间修正）。
 * <p>
 * 音效（服务端 BE 模拟触发）：每个档位切换播放一次 {@code LEVER_CLICK}，
 * 音调随档位位置单调上升（{@link #pitchForGear}）——前进（档位递增）从低到高、
 * 后退（档位递减）从高到低，最低档（0）不响。
 */
public final class ThrottleMotion {

    /** 最大档位/行程（px）：满前进 = 档位 11 = 沿模型空间 x 轴平移 11px（模型默认 handle 在底端，0 偏移起步） */
    public static final int MAX_TRAVEL_PX = 11;
    /** 最大行程（块单位） */
    public static final float MAX_TRAVEL = MAX_TRAVEL_PX / 16f;

    /** 档位切换节奏默认值（BE 配置默认，ThrottleModuleScreen 可调 1..100）：按键按住每 {@link #TICKS_PER_GEAR}（4）tick 进/退一档（每档 = 1px，满行程 = 11 × 4 = 44 tick） */
    public static final int TICKS_PER_GEAR = 4;

    /** 档位切换动画衰减（每 tick 剩余差距乘该系数；步进时"突然变快"到位，参考 knob 卡位） */
    public static final float STEP_DECAY = 0.1f;

    /** 张力比例：按住期间把手向下一档蠕动到档位间距的 1/3（参考 knob 卡位前半程微扭动） */
    public static final float TENSION_FRACTION = 1f / 3f;
    /** 张力最大偏移（块单位）= 档位间距 × 张力比例 */
    public static final float TENSION_PX = TENSION_FRACTION / 16f;

    /** 音效音调范围（档位 1 最低，档位 MAX 最高；前进从低到高、后退从高到低） */
    public static final float PITCH_HIGH = 1.5f;
    public static final float PITCH_LOW = 0.75f;

    /** 音效音量（服务端 playSound，方块音源） */
    public static final float SOUND_VOLUME = 0.3f;

    private ThrottleMotion() {}

    /** 目标平移量（块单位）：读服务端权威档位（BE 轴值 = 档位 / MAX_TRAVEL_PX，经 getUpdatePacket 同步，所有客户端一致）。 */
    public static float targetPx(ControlDeskBlockEntity be) {
        return be.getThrottleAxis() * MAX_TRAVEL;
    }

    /** 档位切换动画（动画层）：快速逼近目标位置（步进"突然变快"到位，参考 knob 卡位），帧时间修正。 */
    public static float approachStep(float current, float target, float frameTicks) {
        float remaining = target - current;
        if (Math.abs(remaining) < 0.01f) return target;
        return target - remaining * (float) Math.pow(STEP_DECAY, frameTicks);
    }

    /**
     * 档位切换"张力"偏移（块单位，操作者本地视觉）：按住前进/后退时把手向下一档方向
     * <b>稍微移动</b>（张力蠕动），满 {@link #TICKS_PER_GEAR} tick 档位步进（充电进度清零）后
     * 张力清零 → <b>突然快速</b>到位（参考 knob 卡位：吸附档位 + 前半程微扭动）。
     * <p>
     * <b>充电进度必须由渲染层用帧时间平滑推进</b>（每帧 {@code frameTicks / TICKS_PER_GEAR} 累加，
     * 而非游戏时间按整 tick 跳变——否则 60fps 渲染追逐 20Hz 目标会卡顿）。
     *
     * @param dir            操作方向：+1 前进（空格）/ -1 后退（左Ctrl）/ 0 无输入
     * @param chargeProgress 充电进度（0..1，帧时间平滑推进；档位步进/按键边沿时清零）
     * @param gearPx         当前档位位置（块单位，服务端权威）
     */
    public static float tensionPx(int dir, float chargeProgress, float gearPx) {
        if (dir == 0) return 0f;
        // 已到顶/底且继续向外按 → 无张力（把手不越界）
        if ((dir > 0 && gearPx >= MAX_TRAVEL) || (dir < 0 && gearPx <= 0f)) return 0f;
        return dir * chargeProgress * TENSION_PX;
    }

    /** 音调（档位越大越高）：档位 1 → {@link #PITCH_LOW}，档位 {@link #MAX_TRAVEL_PX} → {@link #PITCH_HIGH}，线性过渡。
     *  前进（档位递增）从低到高，后退（档位递减）从高到低。 */
    public static float pitchForGear(int gear) {
        float t = (gear - 1) / (float) Math.max(1, MAX_TRAVEL_PX - 1);
        return PITCH_LOW + (PITCH_HIGH - PITCH_LOW) * t;
    }

    /**
     * 指示灯颜色（ARGB）：随油门档位大小从暗红（熄灭，档位 0）→ 亮红（满油门，档位 MAX）线性过渡。
     * 渲染参考 Create analog lever 指示灯（{@code Color.mixColors} 暗红→亮红）/
     * Simulated throttle_lever 的 diode（SimColors.redstone 同款映射）；
     * BER 用 {@code SuperByteBuffer.color()}、Flywheel 用 {@code TransformedInstance.colorArgb()}。
     */
    public static int indicatorColor(int gear) {
        float frac = Math.max(0f, Math.min(1f, gear / (float) MAX_TRAVEL_PX));
        // SimColors.REDSTONE_OFF(86,1,1) → REDSTONE_ON(205,0,0)，带 0xFF alpha
        return Color.mixColors(0xFF560101, 0xFFCD0000, frac);
    }
}
