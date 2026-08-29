package com.zzy205.myfirstmod.block;

/**
 * 服务器权威舵机角度状态机（移植自 Propulsion Simulated 的 TiltAdapterMotionState）。
 * <p>
 * 与 TiltAdapter 相同：{@code requestedTarget}（Lua 可随时改）与 {@code activeTarget}
 * （段开始时锁定的终点）分离，段内不再重算方向，从而避免移动中反向翻转（DIR-FLIP）
 * 与 Create 最短角插值在 180° 的二义性。
 * <p>
 * 差异：本舵机为 ±180° 单圈最短路径，故 {@code requestedTarget} 存归一化角度，
 * 每次「段开始」时才按当前角计算最短带符号弧；{@code currentAngle}/{@code activeTarget}
 * 则保持「展开」值（可跨 ±180 累积），段内做纯线性推进。
 * <p>
 * 段内同向重瞄：{@link #reaimIfSameDirection()} 允许段进行中把终点实时延长/缩短
 * 到同方向的更新目标（零打断、零 flicker），反向请求仍等段结束，保持 DIR-FLIP 保护。
 */
final class ServoMotionState {
    static final float EPSILON = 0.001f;
    /** 单段最大转角，避免 Create 在恰好 180° 的最短角插值二义性 */
    static final float MAX_SEGMENT_ANGLE = 179f;

    /** Lua 请求的目标（归一化 ±180） */
    private float requestedTarget;
    /** 当前段终点（展开值） */
    private float activeTarget;
    /** 当前实际角度（展开值） */
    private float currentAngle;
    /** 当前段剩余转角 */
    private float remainingAngle;
    /** 当前段方向：-1 / 0 / +1 */
    private int activeDirection;

    float requestedTarget() {
        return requestedTarget;
    }

    float activeTarget() {
        return activeTarget;
    }

    float currentAngle() {
        return currentAngle;
    }

    float remainingAngle() {
        return remainingAngle;
    }

    int activeDirection() {
        return activeDirection;
    }

    /** 渲染目标：运动中为当前段终点，静止为当前角度（均为展开值，调用方自行 wrap） */
    float renderTarget() {
        return isActive() ? activeTarget : currentAngle;
    }

    boolean isActive() {
        return activeDirection != 0;
    }

    boolean needsSegment() {
        return !isActive() && Math.abs(shortestArc(wrap(currentAngle), requestedTarget)) > EPSILON;
    }

    /** 接收 Lua 设定的归一化目标（±180） */
    void requestTarget(float wrappedTarget) {
        requestedTarget = wrap(finiteOr(wrappedTarget, wrap(currentAngle)));
    }

    boolean startSegment() {
        return startSegment(Float.POSITIVE_INFINITY);
    }

    /** 从当前位置到请求目标开始一段（最多 maximumSegmentAngle 度） */
    boolean startSegment(float maximumSegmentAngle) {
        if (!needsSegment()) {
            return false;
        }

        float delta = shortestArc(wrap(currentAngle), requestedTarget);
        float segmentAngle = Float.isFinite(maximumSegmentAngle) && maximumSegmentAngle > EPSILON
            ? Math.min(Math.abs(delta), maximumSegmentAngle)
            : Math.abs(delta);

        activeDirection = (int) Math.signum(delta);
        remainingAngle = segmentAngle;
        activeTarget = currentAngle + activeDirection * segmentAngle;
        return true;
    }

    /**
     * 段内同向重瞄：若新请求目标位于当前前进方向（同号最短弧），
     * 直接延长/缩短本段终点到新目标，不打断段、不触发 flicker。
     * 反向请求保持段式保护（等段结束再开反向段，避免 DIR-FLIP）。
     *
     * @return 是否发生了重瞄
     */
    boolean reaimIfSameDirection() {
        if (!isActive()) {
            return false;
        }

        float arc = shortestArc(wrap(currentAngle), requestedTarget);
        if (Math.abs(arc) <= EPSILON) {
            return false;
        }
        if (Math.signum(arc) != activeDirection) {
            return false;
        }

        // 与 startSegment 相同：单段仍受 MAX_SEGMENT_ANGLE 限制，超出的部分由下一段继续
        float segmentAngle = Math.min(Math.abs(arc), MAX_SEGMENT_ANGLE);
        remainingAngle = segmentAngle;
        activeTarget = currentAngle + activeDirection * segmentAngle;
        return true;
    }

    /**
     * 推进当前段但不结束。调用方须先 detach 再调用 {@link #finishSegment()}。
     * 到达段终点返回 true。
     */
    boolean advance(float maximumStep) {
        if (!isActive() || maximumStep <= 0 || !Float.isFinite(maximumStep)) {
            return false;
        }

        float actualStep = Math.min(maximumStep, remainingAngle);
        currentAngle += actualStep * activeDirection;
        remainingAngle -= actualStep;

        if (remainingAngle <= EPSILON) {
            currentAngle = activeTarget;
            remainingAngle = 0;
            return true;
        }
        return false;
    }

    void finishSegment() {
        activeDirection = 0;
        remainingAngle = 0;
        activeTarget = currentAngle;
    }

    void cancelSegment() {
        finishSegment();
    }

    void restore(float requested, float active, float current, float remaining, int direction) {
        currentAngle = finiteOr(current, 0);
        requestedTarget = wrap(finiteOr(requested, wrap(currentAngle)));
        activeTarget = finiteOr(active, currentAngle);

        float activeDelta = activeTarget - currentAngle;
        int restoredDirection = (int) Math.signum(activeDelta);
        if (direction == restoredDirection && direction != 0 && finiteOr(remaining, 0) > EPSILON) {
            activeDirection = restoredDirection;
            // 终点为准，旧存档可能残留过期的 remaining 值
            remainingAngle = Math.abs(activeDelta);
        } else {
            finishSegment();
        }
    }

    /** 从 from 到 to 的最短带符号弧（±180），两端均为归一化角度 */
    static float shortestArc(float fromWrapped, float toWrapped) {
        return wrap(toWrapped - fromWrapped);
    }

    /** 归一化到 [-180, 180]（180 与 -180 保留原值，为同一物理位置的两个表示） */
    static float wrap(float angle) {
        angle %= 360f;
        if (angle > 180f) {
            angle -= 360f;
        }
        if (angle < -180f) {
            angle += 360f;
        }
        return angle;
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
