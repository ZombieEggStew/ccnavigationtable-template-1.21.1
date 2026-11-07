package com.zzy205.myfirstmod.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 自动打包机的「打包间隔」数值行为。
 * <p>
 * 交互面板沿用 Create 的全局数值设置机制（{@code ValueSettingsInputHandler} + {@code ScrollValueRenderer}
 * + {@code ValueSettingsPacket}）：长按右键打开面板、滚轮/按键调整、剪贴板复制，本类不需要任何网络包。
 * <p>
 * 面板结构照 {@code create:pulse_timer} 的 BrassDiodeScrollValueBehaviour，但量程按要求改成
 * <b>1 秒 ~ 60 秒</b>，因此简化为单行「seconds」（上限 60、每 10 秒一个刻度）；内部一律按 tick 存
 * （{@link ScrollValueBehaviour} 的 value = 秒 × 20），持久化键仍是 "ScrollValue"。
 * <p>
 * 参考来源：api/create-1.21.1-6.0.10-280-sources 的
 * com/simibubi/create/content/redstone/diodes/BrassDiodeScrollValueBehaviour.java
 */
public class AutoPackagerScrollValueBehaviour extends ScrollValueBehaviour {

    /** 面板上限（秒）：60 */
    public static final int MAX_SECONDS = 60;

    public AutoPackagerScrollValueBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
        super(label, be, slot);
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(label, MAX_SECONDS, 10,
            CreateLang.translatedOptions("generic.unit", "seconds"),
            new ValueSettingsFormatter(this::formatSettings));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
        // 面板值 = 秒；内部存 tick（1 秒 = 20t），final clamp 交给 between(min, max)
        int seconds = Math.max(1, Math.min(MAX_SECONDS, valueSetting.value()));
        if (!valueSetting.equals(getValueSettings()))
            playFeedbackSound(this);
        setValue(seconds * 20);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, Math.max(1, Math.min(MAX_SECONDS, value / 20)));
    }

    public MutableComponent formatSettings(ValueSettings settings) {
        return Component.literal(Math.max(1, Math.min(MAX_SECONDS, settings.value())) + "s");
    }

    /** 悬停时数值框里显示的文本（秒）。 */
    public static String format(int ticks) {
        return (Math.max(AutoPackagerBlockEntity.MIN_INTERVAL, ticks) / 20) + "s";
    }

    @Override
    public String getClipboardKey() {
        return "Timings";
    }
}
