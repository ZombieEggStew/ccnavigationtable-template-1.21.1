package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorGridHost;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import dan200.computercraft.api.lua.LuaFunction;
import net.minecraft.nbt.CompoundTag;

/**
 * 旋钮模块（knob）的 Lua 模块实例。
 * <p>
 * 角度单位为度：{@link #getAngle()} / {@link #getAbsoluteAngle()} 返回累计绝对角度
 * （开启物理限位时按限位夹紧，可能超过 360），{@link #getNormalizedAngle()} 返回
 * 归一化角度（0..360）。{@link #setAngle(double)} 遵循旋钮的卡位（detent）配置：
 * 开启卡位时自动吸附到最近档位。
 */
public final class KnobModuleHandle extends ModuleHandle {

    public KnobModuleHandle(MonitorGridHost be, MonitorModule module) {
        super(be, module.id(), module.type().name, module.gridX(), module.gridY(),
                module.getWidth(), module.getHeight());
    }

    /** 读取当前累计绝对角度（度）。开启物理限位时按限位夹紧，可能超过 360。 */
    @LuaFunction
    public final double getAngle() {
        return be.getGridState().getKnobAngle(id);
    }

    /** 读取累计绝对角度（度），与 {@link #getAngle()} 相同。 */
    @LuaFunction
    public final double getAbsoluteAngle() {
        return be.getGridState().getKnobAngle(id);
    }

    /** 读取归一化角度（度，0..360）：绝对角度折算到一圈内。 */
    @LuaFunction
    public final double getNormalizedAngle() {
        return GridState.normalizeKnobAngle(be.getGridState().getKnobAngle(id));
    }

    /**
     * 读取相对档位（int）：归一化角度 / 设定的卡位角度（四舍五入）。
     * 未开启卡位（卡位角度为 0）时返回 0。
     */
    @LuaFunction
    public final int getRelativeDetent() {
        int step = be.getGridState().getDetentStep(id);
        if (step <= 0) return 0;
        return (int) Math.round(getNormalizedAngle() / step);
    }

    /**
     * 读取绝对档位（int）：绝对角度 / 设定的卡位角度（四舍五入）。
     * 未开启卡位（卡位角度为 0）时返回 0。
     */
    @LuaFunction
    public final int getAbsoluteDetent() {
        int step = be.getGridState().getDetentStep(id);
        if (step <= 0) return 0;
        return (int) Math.round(be.getGridState().getKnobAngle(id) / step);
    }

    /**
     * 读取相对比例（0..1）：归一化角度 / 设定的最大旋转角度。
     */
    @LuaFunction
    public final double getRelativePercent() {
        return getNormalizedAngle() / maxRotationAngle();
    }

    /**
     * 读取绝对比例：绝对角度 / 设定的最大旋转角度。
     * 未开启物理限位时旋钮可转出设定范围，返回值可能超过 1 或为负数。
     */
    @LuaFunction
    public final double getAbsolutePercent() {
        return be.getGridState().getKnobAngle(id) / maxRotationAngle();
    }

    /** 设定的最大旋转角度（config "angle_limit"，未设置时默认 360）。 */
    private float maxRotationAngle() {
        CompoundTag cfg = be.getGridState().getModuleConfig(id);
        int limit = cfg.getInt("angle_limit");
        return limit > 0 ? limit : 360f;
    }

    /**
     * 设置角度（度）。开启卡位（detent）时自动吸附到最近档位。
     *
     * @param angle 目标角度（度），自动归一化到 0..360
     */
    @LuaFunction(mainThread = true)
    public final void setAngle(double angle) {
        be.rotateKnob(id, (float) angle);
    }
}
