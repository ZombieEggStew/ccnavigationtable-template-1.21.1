package com.zzy205.myfirstmod.block;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷却液表（数据驱动）：{@code data/<namespace>/coolant/*.json}，写法与 {@link EngineFuels} 完全同构。
 * <p>
 * 每个文件描述一种可以作为<b>水冷模块冷却液</b>的流体：
 * <pre>
 * {
 *   "fluid": "minecraft:water",   // 流体 id（唯一键）
 *   "efficiency": 1.0,            // 冷却效率倍率（默认 1.0，必须 > 0）：直接乘进水冷散热贡献
 *   "boilMultiplier": 1.0         // 沸腾失液倍率（可选，默认 1.0，必须 > 0）：越大越容易在高热区间蒸发损失
 * }
 * </pre>
 * <b>双重作用</b>：
 * <ul>
 *   <li><b>白名单</b>：水冷模块的储罐 {@code isFluidValid} 直接查本表——表外流体（管道输入、手持容器）
 *       一律被拒绝，装不进去；</li>
 *   <li><b>数值</b>：{@code efficiency} 是冷却效率倍率（水 1.0、{@code aeronautics:levitite_blend} 1.5），
 *       当前只用于 goggle 显示，<b>还没接入引擎温度模型</b>（接入时在
 *       {@code EngineCoreBlockEntity} 的温度/散热计算里消费，见 memo/engine-module.md）。</li>
 * </ul>
 * 重载监听器挂在服务端数据包重载（{@link AddReloadListenerEvent}），{@code /reload} 后立即生效。
 * <b>表与流体注册表解耦</b>：数据包里可以写本档没装的 mod 的流体 id（如
 * {@code aeronautics:levitite_blend}）——加载时只在日志里提示"该流体未注册"，条目保留（以后装上 mod
 * 或有别的数据包用到时照样生效），不会崩。
 * <p>
 * 参考来源：本项目 {@link EngineFuels}（datapack 表 + 服务端热重载思路）。
 */
public class EngineCoolants {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 数据包目录名：{@code data/<namespace>/coolant/*.json} */
    public static final String FOLDER = "coolant";

    private static final Map<ResourceLocation, Entry> COOLANTS = new HashMap<>();

    /** 单条冷却液：流体 id + 冷却效率倍率 + 沸腾失液倍率 */
    public record Entry(ResourceLocation fluid, float efficiency, float boilMultiplier) {}

    public static void registerAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new ReloadListener());
    }

    /** 按流体查询冷却液条目；表外流体返回 null */
    @Nullable
    public static Entry get(@Nullable Fluid fluid) {
        if (fluid == null)
            return null;
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        return key == null ? null : COOLANTS.get(key);
    }

    /** 按流体栈查询冷却液条目；空栈 / 表外流体返回 null */
    @Nullable
    public static Entry get(@Nullable FluidStack stack) {
        return stack == null || stack.isEmpty() ? null : get(stack.getFluid());
    }

    /** 该流体是否在冷却液表里 = 水冷模块的填充白名单（管道输入与手持容器都走这里） */
    public static boolean isCoolant(FluidStack stack) {
        return get(stack) != null;
    }

    /** 冷却效率倍率；表外流体返回 0（调用方应先 {@link #isCoolant} 判定） */
    public static float efficiencyOf(FluidStack stack) {
        Entry entry = get(stack);
        return entry == null ? 0f : entry.efficiency();
    }

    /** 沸腾失液倍率（P9）；表外流体返回 1.0 */
    public static float boilMultiplierOf(FluidStack stack) {
        Entry entry = get(stack);
        return entry == null ? 1f : entry.boilMultiplier();
    }

    /** 已加载冷却液数量（调试用） */
    public static int size() {
        return COOLANTS.size();
    }

    /** 已加载的流体 id 列表（调试用） */
    public static List<ResourceLocation> loadedFluids() {
        return new ArrayList<>(COOLANTS.keySet());
    }

    private static class ReloadListener extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        public ReloadListener() {
            super(GSON, FOLDER);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, Entry> parsed = new HashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                try {
                    JsonObject json = e.getValue().getAsJsonObject();
                    ResourceLocation fluid = ResourceLocation.parse(json.get("fluid").getAsString());
                    float efficiency = json.has("efficiency") ? json.get("efficiency").getAsFloat() : 1f;
                    float boilMultiplier = json.has("boilMultiplier") ? json.get("boilMultiplier").getAsFloat() : 1f;
                    if (!Float.isFinite(efficiency) || efficiency <= 0f) {
                        LOGGER.error("Ignoring coolant {}: efficiency must be a positive number (got {})",
                                e.getKey(), efficiency);
                        continue;
                    }
                    if (!Float.isFinite(boilMultiplier) || boilMultiplier <= 0f) {
                        LOGGER.error("Ignoring coolant {}: boilMultiplier must be a positive number (got {})",
                                e.getKey(), boilMultiplier);
                        continue;
                    }
                    if (parsed.put(fluid, new Entry(fluid, efficiency, boilMultiplier)) != null)
                        LOGGER.warn("Duplicate coolant entry for fluid {} - file {} overrides it", fluid, e.getKey());
                } catch (Exception ex) {
                    LOGGER.error("Failed to parse coolant {}", e.getKey(), ex);
                }
            }
            COOLANTS.clear();
            COOLANTS.putAll(parsed);
            // 允许引用本档没装的 mod 的流体 id：只提示，不剔除（装上 mod 后 /reload 即生效）
            for (ResourceLocation id : COOLANTS.keySet()) {
                if (!BuiltInRegistries.FLUID.containsKey(id))
                    LOGGER.warn("[EngineCoolants] fluid {} is not registered in this instance - entry kept but unused",
                            id);
            }
            LOGGER.info("[EngineCoolants] reload: loaded {} coolants {}", COOLANTS.size(),
                    COOLANTS.values().stream().map(Entry::fluid).toList());
        }
    }
}
