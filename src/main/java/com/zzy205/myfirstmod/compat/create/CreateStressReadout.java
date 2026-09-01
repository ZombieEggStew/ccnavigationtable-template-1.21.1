package com.zzy205.myfirstmod.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * 从任意 {@link KineticBlockEntity} 读取其所在应力网络（Create 动力网络）的缓存读数。
 * <p>
 * Create 6.x 的 {@code KineticBlockEntity} 不公开网络总应力/总容量的 getter
 * （protected 字段 {@code stress}/{@code capacity}，仅子类如 {@code StressGaugeBlockEntity}
 * 可见，见其 {@code getNetworkStress()/getNetworkCapacity()}），因此这里用反射读这两个
 * 缓存字段（Create 自 5.x 起字段名稳定）；转速/过载/冲击等走公开 API。读取失败时返回
 * {@code null}（不抛异常），调用方按"读数不可用"处理。
 * <p>
 * 直接使用 Create 内部字段，无需 Mixin（对齐 {@link CreateRedstoneCompat} 的
 * "直接使用 Create API，无需 Mixin" 原则；仅此处 Create 未公开所需读数才用反射）。
 */
public final class CreateStressReadout {

    /** 网络总应力 su 与总容量 su（均为该 BE 所在网络的缓存值，同 Create 应力计读数） */
    public record StressInfo(float stress, float capacity) {}

    private static final Field STRESS = findField("stress");
    private static final Field CAPACITY = findField("capacity");

    private CreateStressReadout() {}

    /**
     * 读取 {@code be} 所在应力网络的总应力与总容量（缓存值，零开销）。
     *
     * @return 读数；字段缺失（Create 版本变更）或反射失败返回 null
     */
    public static @Nullable StressInfo stressOf(KineticBlockEntity be) {
        if (STRESS == null || CAPACITY == null) return null;
        try {
            return new StressInfo((float) STRESS.get(be), (float) CAPACITY.get(be));
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static @Nullable Field findField(String name) {
        try {
            Field f = KineticBlockEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            // 字段缺失 / setAccessible 被拒（模块限制等）→ 按"读数不可用"处理
            return null;
        }
    }
}
