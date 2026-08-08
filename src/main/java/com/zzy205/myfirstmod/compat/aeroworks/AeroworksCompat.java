package com.zzy205.myfirstmod.compat.aeroworks;

import com.mred231.aeroworks.AeroworksSocketTypes;
import com.mred231.aeroworks.content.controls.ControlChannel;
import com.mred231.aeroworks.content.controls.ControlSummaryTooltip;
import com.mred231.aeroworks.content.controls.ModuleItem;
import com.mred231.aeroworks.content.controls.ModulePart;
import com.mred231.aeroworks.content.controls.ModuleType;
import com.mred231.aeroworks.content.controls.ModuleTypes;
import com.zzy205.myfirstmod.CCPeripheraExtender;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Aeroworks 可选联动兼容层。
 * <p>
 * 仅在 Aeroworks mod 已加载时注册自定义模块类型和物品。
 * 所有 Aeroworks 类型引用隔离在此文件中，主 mod 类不直接 import Aeroworks。
 * <p>
 * 新增模块：Toggle Throttle Quadrant（切换式油门台）<br>
 * - 复用 throttle_quadrant 的 3D 模型部件<br>
 * - 4 个拉杆，每个都是 Button 类型，只有 1 个按键槽位<br>
 * - 在模块配置中开启 LATCH 后：按一下键 → 拉杆推到最高(45°)，再按一下 → 拉回最低(0°)<br>
 * - 红石输出：ON=15, OFF=0<br>
 * - 默认按键：小键盘 1/2/3/4
 */
public final class AeroworksCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ccpe:AeroworksCompat");
    private static final String AEROWORKS_MODID = "aeroworks";

    /** 油门拉杆旋转 pivot（与 AeroworksModuleTypes.STICK_PIVOT 一致） */
    private static final Vec3 STICK_PIVOT = new Vec3(0.5, 0.0, 0.5);
    /** 切换式拉杆转角：Button 0→45°(OFF,前推), 1→-45°(ON,拉回)，全行程 90° */
    private static final float TOGGLE_DEGREES_PER_STEP = -90.0f;
    private static final float TOGGLE_BASE_OFFSET = 45.0f;

    private static boolean loaded = false;

    /** 模块对应的物品 DeferredRegister（注册到 ccpe 命名空间下） */
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CCPeripheraExtender.MOD_ID);

    public static final DeferredItem<ModuleItem> TOGGLE_THROTTLE_QUADRANT = ITEMS.register(
            "toggle_throttle_quadrant_module",
            () -> new ModuleItem(
                    ModuleTypes.get(ResourceLocation.fromNamespaceAndPath("ccpe", "toggle_throttle_quadrant")),
                    new Item.Properties().stacksTo(1)
            )
    );

    private AeroworksCompat() {}

    /**
     * 在主 mod 构造函数中调用。仅在 Aeroworks 已加载时执行注册。
     *
     * @param modEventBus 主 mod 的事件总线
     */
    public static void init(IEventBus modEventBus) {
        if (!ModList.get().isLoaded(AEROWORKS_MODID)) {
            LOGGER.info("Aeroworks not loaded, skipping toggle throttle quadrant registration.");
            return;
        }
        LOGGER.info("Aeroworks detected, registering toggle throttle quadrant module...");

        // 注册 ModuleType（必须在 ModuleTypes.freeze() 之前）
        ModuleType type = ModuleTypes.register(
                ResourceLocation.fromNamespaceAndPath("ccpe", "toggle_throttle_quadrant"),
                ModuleType.builder(AeroworksSocketTypes.LARGE)
                        .summary("ccpe.module.toggle_throttle_quadrant.summary")
                        .composedItemModel()
                        // 4 个切换式拉杆通道（Button 类型，单键，在模块配置中开启 LATCH 即可切换）
                        .channel(toggleButton("red", "key.keyboard.1"))
                        .channel(toggleButton("amber", "key.keyboard.2"))
                        .channel(toggleButton("green", "key.keyboard.3"))
                        .channel(toggleButton("blue", "key.keyboard.4"))
                        // 复用 throttle_quadrant 的 3D 模型部件
                        .part(staticPart("controls/throttle/base"))
                        .part(staticPart("controls/throttle/quadrant"))
                        .part(toggleLever("red", "controls/throttle/stick_r", -3.0))
                        .part(toggleLever("amber", "controls/throttle/stick_a", -1.0))
                        .part(toggleLever("green", "controls/throttle/stick_g", 1.0))
                        .part(toggleLever("blue", "controls/throttle/stick_b", 3.0))
        );

        // 注册 ModuleItem（需要在 register 前确保 ModuleType 已存在）
        ITEMS.register(modEventBus);

        // 注册 tooltip 事件监听（Aeroworks 的 ControlSummaryTooltip）
        NeoForge.EVENT_BUS.register(AeroworksCompat.class);

        loaded = true;
        LOGGER.info("Toggle throttle quadrant module registered successfully: {}", type);
    }

    /** @return Aeroworks 是否已加载且联动已初始化 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 为我们的 ModuleItem 添加 Aeroworks 风格的悬停提示 */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        var modifier = ControlSummaryTooltip.create(event.getItemStack().getItem());
        if (modifier != null) {
            modifier.modify(event);
        }
    }

    // ═══════════════ 模块构建辅助方法 ═══════════════

    /**
     * 创建切换式 Button 通道。
     * <p>
     * Button 类型只有 1 个按键绑定槽位（无正/负方向之分）。<br>
     * 在模块配置中开启 LATCH 后，每次按键在 0 ↔ 15 之间切换。<br>
     * Spring Back / Analog / Invert 等选项不适用于 Button，UI 中不会出现。
     */
    private static ControlChannel toggleButton(String id, String defaultKey) {
        return ControlChannel.button(id,
                "ccpe.module.toggle_throttle_quadrant.channel." + id, defaultKey);
    }

    /**
     * 创建拉杆模型部件（复用 throttle_quadrant 的杠杆模型）。
     * 固定 45° 前倾 + 通道驱动 -90°：
     * OFF (value=0) → 45°, ON (value=1) → -45°，全行程 90° 两极切换。
     */
    private static ModulePart toggleLever(String channel, String modelPath, double xPx) {
        return ModulePart.builder(aeroworksModel(modelPath))
                .rotate(new Vec3(1.0, 0.0, 0.0), TOGGLE_BASE_OFFSET, STICK_PIVOT)
                .rotate(channel, new Vec3(1.0, 0.0, 0.0), TOGGLE_DEGREES_PER_STEP, STICK_PIVOT)
                .translate(new Vec3(xPx / 16.0, 0.0, 0.0))
                .build();
    }

    /** 不带动画的静态模型部件 */
    private static ModulePart staticPart(String modelPath) {
        return new ModulePart(aeroworksModel(modelPath), List.of());
    }

    /** 构造 Aeroworks 命名空间下的模型 ResourceLocation */
    private static ResourceLocation aeroworksModel(String path) {
        return ResourceLocation.fromNamespaceAndPath("aeroworks", "block/" + path);
    }
}
