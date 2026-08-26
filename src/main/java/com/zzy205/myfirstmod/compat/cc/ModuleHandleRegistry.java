package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorGridHost;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorModule;

import java.util.EnumMap;
import java.util.Map;

/**
 * 模块类型 → Lua 模块实例（{@link ModuleHandle} 子类）工厂的注册表。
 * <p>
 * 每个 {@link ModuleType} 在此注册自己的 handle 工厂；
 * {@link MonitorPeripheral#getCellModule(int, int)} / {@link MonitorPeripheral#getModule(int)}
 * 通过 {@link #create(MonitorGridHost, MonitorModule)} 分派到对应类型的模块实例。
 * <p>
 * 为某类型添加专属控制方法（例如钮子开关的 setToggleState）时，在对应子类中补充
 * {@code @LuaFunction} 方法即可；新增模块类型时在此增加一条注册。
 */
public final class ModuleHandleRegistry {

    /** 模块实例工厂：由 {@link MonitorGridHost} + {@link MonitorModule} 构建 handle。 */
    @FunctionalInterface
    public interface Factory {
        ModuleHandle create(MonitorGridHost be, MonitorModule module);
    }

    private static final Map<ModuleType, Factory> FACTORIES = new EnumMap<>(ModuleType.class);

    static {
        // 每个模块类型在此注册其 handle 工厂（控制方法加到对应子类）
        register(ModuleType.BUTTON_1X1, ButtonModuleHandle::new);
        register(ModuleType.TOGGLE_SWITCH, ToggleSwitchModuleHandle::new);
        register(ModuleType.KNOB, KnobModuleHandle::new);
    }

    private ModuleHandleRegistry() {}

    /** 注册某模块类型的 handle 工厂（同类型重复注册会覆盖）。 */
    public static void register(ModuleType type, Factory factory) {
        FACTORIES.put(type, factory);
    }

    /** 为指定模块创建 Lua 模块实例；未注册的类型直接抛异常（保证每种类型都有对应 handle）。 */
    public static ModuleHandle create(MonitorGridHost be, MonitorModule module) {
        Factory factory = FACTORIES.get(module.type());
        if (factory == null) {
            throw new IllegalStateException(
                    "No ModuleHandle factory registered for type: " + module.type().name);
        }
        return factory.create(be, module);
    }
}
