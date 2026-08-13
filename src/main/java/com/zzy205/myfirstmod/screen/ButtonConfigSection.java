package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 按钮专属配置：是否开启锁存模式（示例 section）。
 */
public class ButtonConfigSection implements ModuleConfigSection {

    private ToggleButton latchToggle;

    @Override
    public void init(MonitorModuleScreen screen, int y, CompoundTag config) {
        // 图标暂用 SHOW_TOOLTIP 占位，后续替换为锁存图标
        latchToggle = new ToggleButton(screen.getWinLeft() + 22, y,
                MyIcons.SHOW_TOOLTIP, MyIcons.SHOW_TOOLTIP, 0x80FF80);
        latchToggle.setSelected(config.getBoolean("latch"));
        latchToggle.setToolTip(Component.translatable("gui.ccpe.module_config.button_latch"));
        latchToggle.withCallback(() -> latchToggle.setSelected(!latchToggle.isSelected()));
        screen.addSectionWidget(latchToggle);
    }

    @Override
    public void save(CompoundTag config) {
        if (latchToggle != null) {
            config.putBoolean("latch", latchToggle.isSelected());
        }
    }
}
