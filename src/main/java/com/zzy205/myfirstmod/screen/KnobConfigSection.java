package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 旋钮专属配置：角度范围（0-360）滚轮条 + 卡位开关。
 */
public class KnobConfigSection implements ModuleConfigSection {

    private static final int BAR_W = 256;
    private static final int BAR_H = 28;

    private ScrollValueBar angleBar;
    private ToggleButton detentToggle;

    @Override
    public void init(MonitorModuleScreen screen, int y, CompoundTag config) {
        detentToggle = new ToggleButton(0, 0, MyIcons.KNOB, MyIcons.KNOB, 0x80FF80);
        detentToggle.setSelected(config.getBoolean("detent"));
        detentToggle
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.knob_detent"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_detent_tip"))
            .addToolTipOnOff(
                Component.translatable("gui.ccpe.module_config.knob_detent_on"),
                Component.translatable("gui.ccpe.module_config.knob_detent_off"));
        detentToggle.withCallback(() -> detentToggle.setSelected(!detentToggle.isSelected()));

        int angle = clampAngle(config.getInt("angle"));
        angleBar = new ScrollValueBar(screen.getWinLeft(), y, BAR_W, BAR_H,
                angle, angle, new int[0])
            .range(0, 360)
            .withToggleButton(detentToggle)
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.knob_angle"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_angle_tip"));
        screen.addSectionWidget(angleBar);
    }

    @Override
    public void save(CompoundTag config) {
        if (angleBar != null) config.putInt("angle", angleBar.getValue());
        if (detentToggle != null) config.putBoolean("detent", detentToggle.isSelected());
    }

    private static int clampAngle(int angle) {
        return Math.max(0, Math.min(360, angle));
    }
}
