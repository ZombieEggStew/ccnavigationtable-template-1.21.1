package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.channel.ChannelScrollHelper;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.network.ModuleConfigPayload;

import com.simibubi.create.foundation.gui.AllIcons;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Monitor 模块配置界面 —— 蹲下+右键模块时打开。
 * 窗口 192×127，纹理位于 textures/gui/gui_2.png。
 */
public class MonitorModuleScreen extends Screen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 159;
    private static final int TEX_W = 256;
    private static final int TEX_H = 384;

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/gui_2.png");

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条：背景 + 图标 + 输入框 ──
    // 纹理坐标（源贴图中的位置）
    private static final int BAR_TEX_X = 0;
    private static final int BAR_TEX_Y = 160;
    private static final int BAR_TEX_W = 256;
    private static final int BAR_TEX_H = 28;
    private static final int INPUT_BG_TEX_X = 0;
    private static final int INPUT_BG_TEX_Y = 208;
    private static final int INPUT_BG_LONG_TEX_X = 0;
    private static final int INPUT_BG_LONG_TEX_Y = 240;
    private static final int INPUT_BG_TEX_W = 256;
    private static final int INPUT_BG_TEX_H = 18;

    private static final int BAR_MARGIN_Y = 2;  // 横条之间的垂直间距

    // ── ID 滚轮选择 ──

    private static final int ID_HIT_W = 46;                         // 命中区宽度
    private static final int ID_HIT_H = INPUT_BG_TEX_H;              // 命中区高度

    // ── 文本输入框（滚轮滑条下方，位置可微调）──

    private static final int TEXT_INPUT_X = 48;                 // 窗口内 X 偏移
    private static final int TEXT_INPUT_Y = 58;
    private static final int TEXT_INPUT_W = 109;               // 输入框宽度
    private static final int TEXT_INPUT_H = 10;                // 输入框高度


    private final BlockPos monitorPos;
    private final GridState grid;
    private final String name;        // 模块类型名或 "screen"
    private final int originalId;     // 打开菜单时的 ID
    private final String initialText; // 打开菜单时的 tooltip 文本

    private ModuleConfigSection section = ModuleConfigSection.Empty.INSTANCE;

    private int winLeft;
    private int winTop;
    private int idValue = 0;  // 滚轮控制的模块 ID
    private ToggleButton showTooltipToggle;
    private EditBox textInput;     // 文本输入框

    private int bar_id_y = 0;

    private static final int SECTION_Y_OFFSET = 80;  // 特殊设置区起始 Y（窗口相对）

    public MonitorModuleScreen(BlockPos monitorPos, GridState grid, String name, int originalId, String initialText) {
        super(Component.empty());
        this.monitorPos = monitorPos;
        this.grid = grid;
        this.name = name;
        this.originalId = originalId;
        this.initialText = initialText;
    }

    @Override
    protected void init() {
        this.winLeft = (this.width - WIN_W) / 2;
        this.winTop = (this.height - WIN_H) / 2;

        // 以当前 ID 初始化滚轮值
        this.idValue = originalId;

        // 右下角"完成"按钮
        HoverTintIconButton doneBtn = new HoverTintIconButton(
                winLeft + WIN_W - DONE_BTN_RIGHT,
                winTop + WIN_H - DONE_BTN_BOTTOM,
                (ScreenElement) AllIcons.I_CONFIRM,
                0x80FF80);
        doneBtn.setWidth(18);
        doneBtn.setHeight(18);
        doneBtn.withCallback(this::onClose);
        doneBtn.setToolTip(Component.translatable("gui.ccpe.module_config.done"));
        this.addRenderableWidget(doneBtn);

        // 显示提示 ToggleButton
        this.showTooltipToggle = new ToggleButton(
                winLeft + 22, winTop + 18 + BAR_TEX_H + BAR_MARGIN_Y + 5,
                MyIcons.SHOW_TOOLTIP,
                MyIcons.SHOW_TOOLTIP,
                0x80FF80);
        this.showTooltipToggle.setToolTip(tooltipToggleText(false));
        this.showTooltipToggle.withCallback(() -> {
            showTooltipToggle.setSelected(!showTooltipToggle.isSelected());
            showTooltipToggle.setToolTip(tooltipToggleText(showTooltipToggle.isSelected()));
        });
        this.addRenderableWidget(this.showTooltipToggle);

        // 文本输入框（滚轮滑条下方）
        this.textInput = new EditBox(this.font,
                winLeft + TEXT_INPUT_X, winTop + TEXT_INPUT_Y,
                TEXT_INPUT_W, TEXT_INPUT_H, Component.empty());
        this.textInput.setMaxLength(50);
        this.textInput.setBordered(false);
        this.textInput.setTextColor(0xFFFFFF);
        this.textInput.setValue(initialText);
        this.textInput.setHint(Component.translatable("gui.ccpe.module_config.text_hint"));
        this.addRenderableWidget(this.textInput);

        // 每类型特殊设置区
        this.section = ModuleConfigSections.create(name);
        this.section.init(this, winTop + SECTION_Y_OFFSET, grid.getModuleConfig(originalId));
    }



    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int _currentY = winTop;
        // 先画自定义背景面板
        g.blit(TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        // 标题：仅显示名称
        Component titleText = Component.translatable("module.ccpe." + name);
        g.drawString(this.font, titleText, winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
        // ID

        _currentY += 18;
        _currentY = renderBar_id(g, _currentY);

        // 显示提示
        _currentY += BAR_MARGIN_Y;
        _currentY = renderBar_ShowTooltip(g, _currentY);

        _currentY += BAR_MARGIN_Y;

        // 最后画控件（按钮、输入框等），确保在最上层
        super.render(g, mouseX, mouseY, partialTick);

        // ID tooltip（在最上层）
        renderIdTooltip(g, mouseX, mouseY);

        // 文本输入框 tooltip（在最上层）
        renderTextInputTooltip(g, mouseX, mouseY);
    }

    public int renderBar_id(GuiGraphics g,int currentY) {

        bar_id_y = currentY;
        // ── 横条：背景 + 输入框背景 ──
        renderBar_bg(g, winLeft, currentY);

        // 左侧 16×16 图标
        MyIcons.CHANNEL.render(g, winLeft + 22, currentY + 6);

        // 输入短框背景
        g.blit(TEXTURE, winLeft, currentY + 5,
                INPUT_BG_TEX_X, INPUT_BG_TEX_Y, INPUT_BG_TEX_W, INPUT_BG_TEX_H, TEX_W, TEX_H);

        // ID 数值（居中于输入框内）
        String valueText = String.valueOf(idValue);
        g.drawString(this.font, valueText, winLeft + 50, currentY + 10, 0xFCFCEB, true);

        return currentY + BAR_TEX_H;
    }


    public int renderBar_ShowTooltip(GuiGraphics g,int currentY){
        //背景
        renderBar_bg(g, winLeft, currentY);

        // 输入长框背景
        g.blit(TEXTURE, winLeft, currentY + 5,
                INPUT_BG_LONG_TEX_X, INPUT_BG_LONG_TEX_Y, INPUT_BG_TEX_W, INPUT_BG_TEX_H, TEX_W, TEX_H);
        return currentY + BAR_TEX_H;
    }

    public void renderBar_bg(GuiGraphics g , int x, int y) {
        // 横条底层背景
        g.blit(TEXTURE, x, y, BAR_TEX_X, BAR_TEX_Y, BAR_TEX_W, BAR_TEX_H, TEX_W, TEX_H);
    }

    /** ID 滚轮命中检测 */
    private boolean inIdHitArea(double mouseX, double mouseY) {
        int hitX = winLeft + 45;
        int hitY = bar_id_y + 5;
        return mouseX >= hitX && mouseX < hitX + ID_HIT_W
                && mouseY >= hitY && mouseY < hitY + ID_HIT_H;
    }
    /** 文本输入框命中检测 */
    private boolean inTextInputArea(double mouseX, double mouseY) {
        int hitX = winLeft + TEXT_INPUT_X;
        int hitY = winTop + TEXT_INPUT_Y;
        return mouseX >= hitX && mouseX < hitX + TEXT_INPUT_W
                && mouseY >= hitY && mouseY < hitY + TEXT_INPUT_H;
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!inIdHitArea(mouseX, mouseY) || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int dir = scrollY > 0 ? 1 : -1;
        int jump = hasShiftDown() ? 10 : 1;
        int newValue = ChannelScrollHelper.next(idValue, dir, jump, getMyId(), getOccupiedIds());
        if (newValue != idValue) {
            idValue = newValue;
            playScrollSound();
        }
        return true;
    }

    /** 当前控件自己的 ID（打开 GUI 时的值） */
    private int getMyId() {
        return originalId;
    }

    /** 本 monitor 内所有控件（模块 + 屏幕）占用的 ID。 */
    private int[] getOccupiedIds() {
        return grid.getOccupiedIds();
    }

    /** 供 ModuleConfigSection 添加自己的控件。 */
    public void addSectionWidget(AbstractWidget widget) {
        this.addRenderableWidget(widget);
    }

    public int getWinLeft() { return winLeft; }
    public int getWinTop() { return winTop; }

    @Override
    public void onClose() {
        // 汇总公共配置（tooltip 文本）+ 每类型特殊配置，一次性发送
        CompoundTag config = new CompoundTag();
        config.putString("text", textInput.getValue());
        section.save(config);
        PacketDistributor.sendToServer(new ModuleConfigPayload(monitorPos, name, originalId, idValue, config));
        super.onClose();
    }

    /** ID 数值 tooltip */
    private void renderIdTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!inIdHitArea(mouseX, mouseY)) return;
        List<Component> lines = List.of(
                Component.translatable("gui.ccpe.module_config.id_title")
                        .withStyle(Style.EMPTY.withColor(0x528FDE)),
                Component.translatable("gui.ccpe.scroll_to_change")
                        .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)),
                Component.translatable("gui.ccpe.shift_scroll_faster")
                        .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** 文本输入框 tooltip（格式与频道 tooltip 一致） */
    private void renderTextInputTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!inTextInputArea(mouseX, mouseY)) return;
        List<Component> lines = List.of(
                Component.translatable("gui.ccpe.module_config.text_title")
                        .withStyle(Style.EMPTY.withColor(0x528FDE)),
                Component.translatable("gui.ccpe.module_config.text_tip")
                        .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** 显示提示开关 tooltip 文本 */
    private static Component tooltipToggleText(boolean selected) {
        return Component.translatable(selected
                ? "gui.ccpe.module_config.show_tooltip_on"
                : "gui.ccpe.module_config.show_tooltip_off");
    }



    private void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 禁用原版半透明渐变背景，使用自定义贴图代替
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
