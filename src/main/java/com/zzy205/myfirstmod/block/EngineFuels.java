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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎燃料表（P1，数据驱动）：{@code data/<namespace>/engine_fuel/*.json}。
 * <p>
 * 每个文件描述一种可作为流体燃烧室燃料的流体（方案见 memo/engine-module.md）：
 * <pre>
 * {
 *   "fluid": "minecraft:water",   // 流体 id
 *   "consumption": 1.0,           // 消耗速度：每个流体燃烧室 mb/s
 *   "heat": 1.0,                  // 发热倍率（P3 过热逻辑用；P1 只解析不消费）
 *   "stress": 1.0,                // 产生应力倍率（容量 = 燃烧室数 × 4096 × stress）
 *   "priority": 0.0               // 可选：多个燃料可用时的选择优先级（越大越优先）
 * }
 * </pre>
 * <b>P7 燃料体系分家：本表只服务流体燃烧室</b>（消耗/发热/应力）。蒸汽引擎燃料 = 纯原版解析：
 * 桶物品的原版熔炉燃烧时长（熔岩桶 20000 tick；添加蒸汽燃料 = 按原版为熔炉添加燃料），见 {@link #vanillaBucketBurnTicks}。
 * <b>P7 温度全部引擎固定</b>：过热 200°C / 过冷 100°C / 经济目标 155°C（{@code ENGINE_T_OPT}）——均不随燃料、不随油门。
 * 重载监听器在服务端数据包重载时触发（{@link AddReloadListenerEvent}）；
 * 燃烧/消耗逻辑只在服务端 controller 运行，客户端只消费同步的 {@code Running} 状态，
 * 因此本表无需同步到客户端。
 * <p>
 * 参考来源：CDG {@code FuelType}（数据驱动燃料表思路，简化为服务端直读 + NBT 状态同步）。
 */
public class EngineFuels {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String FOLDER = "engine_fuel";

    private static final Map<ResourceLocation, Entry> FUELS = new HashMap<>();
    private static List<Entry> sorted = List.of();

    /** 单个燃料条目（仅流体燃烧室用；蒸汽引擎燃料走原版解析，见 {@link #vanillaBucketBurnTicks}） */
    public record Entry(ResourceLocation fluid, float consumption, float heat, float stress,
                        float priority) {}

    public static void registerAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new ReloadListener());
    }

    /** 按流体查询燃料条目；未知流体返回 null */
    public static Entry get(Fluid fluid) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        return key == null ? null : FUELS.get(key);
    }

    /**
     * 蒸汽引擎流体燃料的原版解析（P7）：一桶该流体在熔炉里能烧的 tick 数 = 桶物品的原版熔炉燃烧时长
     * （{@code ItemStack#getBurnTime(RecipeType.SMELTING)}）。熔岩桶 = 20000 tick（原版熔炉值）；水桶 = 0（非燃料）。
     * <b>添加蒸汽燃料 = 按原版为熔炉添加燃料</b>（给桶物品设置熔炉燃烧时长），与 engine_fuel datapack 无关。
     */
    public static int vanillaBucketBurnTicks(ResourceLocation fluidId) {
        Fluid fluid = BuiltInRegistries.FLUID.get(fluidId);
        if (fluid == null)
            return 0;
        Item bucket = fluid.getBucket();
        if (bucket == null || bucket == Items.AIR)
            return 0;
        return new ItemStack(bucket).getBurnTime(RecipeType.SMELTING);
    }

    /** 按 priority 降序（其次 id 升序）的燃料表，即"第一个可用燃料"的选择顺序 */
    public static List<Entry> sortedByPriority() {
        return sorted;
    }

    /** 已加载燃料数量（调试用） */
    public static int size() {
        return FUELS.size();
    }

    /** 已加载的流体 id 列表（调试用） */
    public static List<ResourceLocation> loadedFluids() {
        return new ArrayList<>(FUELS.keySet());
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
                    float consumption = json.get("consumption").getAsFloat();
                    float heat = json.has("heat") ? json.get("heat").getAsFloat() : 1f;
                    float stress = json.has("stress") ? json.get("stress").getAsFloat() : 1f;
                    float priority = json.has("priority") ? json.get("priority").getAsFloat() : 0f;
                    parsed.put(fluid, new Entry(fluid, consumption, heat, stress, priority));
                } catch (Exception ex) {
                    LOGGER.error("Failed to parse engine fuel {}", e.getKey(), ex);
                }
            }
            FUELS.clear();
            FUELS.putAll(parsed);
            sorted = new ArrayList<>(FUELS.values());
            sorted.sort(Comparator.comparingDouble(Entry::priority).reversed()
                    .thenComparing(Entry::fluid));
            LOGGER.info("[EngineFuels] reload: loaded {} fuels {}", sorted.size(), sorted.stream().map(Entry::fluid).toList());
        }
    }
}
