package com.zzy205.myfirstmod.screen;

import net.minecraft.nbt.CompoundTag;

/**
 * 每个模块类型专属配置区的抽象。
 * 由 {@link MonitorModuleScreen} 在公共区（ID + tooltip 文本）下方托管。
 * 每个实例由 {@link ModuleConfigSections} 新建，不共享（内部持有控件引用）。
 */
public interface ModuleConfigSection {

    /** 创建该类型专属控件。y 为公共区下方的绝对屏幕 Y。 */
    void init(MonitorModuleScreen screen, int y, CompoundTag config);

    /** 把控件值写回配置（关闭菜单时调用）。 */
    void save(CompoundTag config);

    /** 空实现：没有特殊设置的模块类型使用。 */
    final class Empty implements ModuleConfigSection {
        public static final Empty INSTANCE = new Empty();

        private Empty() {}

        @Override public void init(MonitorModuleScreen screen, int y, CompoundTag config) {}

        @Override public void save(CompoundTag config) {}
    }
}
