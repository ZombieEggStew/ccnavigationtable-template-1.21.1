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
    private ToggleButton detentDisplayToggle;
    private ScrollValueBar limitBar;
    private ToggleButton physicalLimitToggle;

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
        detentToggle.withCallback(() -> {
            detentToggle.setSelected(!detentToggle.isSelected());
            if (!detentToggle.isSelected()) {
                detentDisplayToggle.setSelected(false);
            }
            detentDisplayToggle.setDisabled(!detentToggle.isSelected());
        });

        detentDisplayToggle = new ToggleButton(0, 0, MyIcons.INDEX, MyIcons.INDEX, 0x80FF80);
        detentDisplayToggle.setSelected(config.getBoolean("detent_display"));
        detentDisplayToggle
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.knob_detent_display"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_detent_display_tip"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_detent_display_requires_detent"));
        detentDisplayToggle.withCallback(
            () -> detentDisplayToggle.setSelected(!detentDisplayToggle.isSelected()));
        detentDisplayToggle.setDisabled(!detentToggle.isSelected());

        int angle = clampAngle(config.getInt("angle"));
        angleBar = new ScrollValueBar(screen.getWinLeft(), y, BAR_W, BAR_H,
                angle, angle, new int[0])
            .range(0, 360)
            .withToggleButton(detentToggle)
            .withToggleButtonBackward(detentDisplayToggle)
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.knob_angle"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_angle_tip"));
        screen.addSectionWidget(angleBar);

        physicalLimitToggle = new ToggleButton(0, 0, MyIcons.ANGLE_LIMIT, MyIcons.ANGLE_LIMIT, 0x80FF80);
        physicalLimitToggle.setSelected(config.getBoolean("physical_limit"));
        physicalLimitToggle
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.knob_physical_limit"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_physical_limit_tip"))
            .addToolTipOnOff(
                Component.translatable("gui.ccpe.module_config.knob_physical_limit_on"),
                Component.translatable("gui.ccpe.module_config.knob_physical_limit_off"));
        physicalLimitToggle.withCallback(
            () -> physicalLimitToggle.setSelected(!physicalLimitToggle.isSelected()));

        int limit = clampLimit(config.getInt("angle_limit"));
        limitBar = new ScrollValueBar(screen.getWinLeft(), y + BAR_H, BAR_W, BAR_H,
                limit, limit, new int[0])
            .range(360, 3600)
            .withToggleButton(physicalLimitToggle)
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.knob_physical_limit_value"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.knob_physical_limit_value_tip"));
        screen.addSectionWidget(limitBar);
    }

    @Override
    public void save(CompoundTag config) {
        if (angleBar != null) config.putInt("angle", angleBar.getValue());
        if (detentToggle != null) config.putBoolean("detent", detentToggle.isSelected());
        if (detentDisplayToggle != null) {
            config.putBoolean("detent_display", detentDisplayToggle.isSelected());
        }
        if (physicalLimitToggle != null) config.putBoolean("physical_limit", physicalLimitToggle.isSelected());
        if (limitBar != null) config.putInt("angle_limit", limitBar.getValue());
    }

    private static int clampAngle(int angle) {
        return Math.max(0, Math.min(360, angle));
    }

    private static int clampLimit(int limit) {
        return Math.max(360, Math.min(3600, limit > 0 ? limit : 360));
    }
}
