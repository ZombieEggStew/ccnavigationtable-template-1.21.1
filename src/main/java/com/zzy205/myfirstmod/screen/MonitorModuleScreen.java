package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;

import org.joml.Vector2d;

import com.simibubi.create.foundation.gui.AllIcons;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    // 屏幕坐标（窗口内偏移）
    private static final int CHANNEL_BAR_Y = 32;
    private static final int INPUT_OFFSET_X = 32;   // 输入框在横条内 X 偏移
    private static final int INPUT_OFFSET_Y = 7;    // 输入框在横条内 Y 偏移（垂直居中）

    private final BlockPos monitorPos;
    private final MonitorModule module;
    private final GridState grid;

    private int winLeft;
    private int winTop;
    private EditBox nameInput;
    private ToggleButton showTooltipToggle;

    public MonitorModuleScreen(BlockPos monitorPos, MonitorModule module, GridState grid) {
        super(Component.empty());
        this.monitorPos = monitorPos;
        this.module = module;
        this.grid = grid;
    }

    @Override
    protected void init() {
        this.winLeft = (this.width - WIN_W) / 2;
        this.winTop = (this.height - WIN_H) / 2;

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

        // 横条中的输入框
        int inputX = winLeft + INPUT_OFFSET_X;
        int inputY = winTop + CHANNEL_BAR_Y + INPUT_OFFSET_Y;
        this.nameInput = new EditBox(this.font, inputX + 2, inputY + 1, INPUT_BG_TEX_W - 4, INPUT_BG_TEX_H - 2,
                Component.empty());
        this.nameInput.setMaxLength(50);
        this.nameInput.setBordered(false);
        this.nameInput.setTextColor(0xFFFFFF);
        this.addRenderableWidget(this.nameInput);

        // 显示提示 ToggleButton
        this.showTooltipToggle = new ToggleButton(
                winLeft + 22, winTop + 18 + BAR_TEX_H + BAR_MARGIN_Y + 5,
                MyIcons.SHOW_TOOLTIP,
                MyIcons.SHOW_TOOLTIP,
                0x80FF80);
        this.showTooltipToggle.withCallback(() ->
                showTooltipToggle.setSelected(!showTooltipToggle.isSelected()));
        this.addRenderableWidget(this.showTooltipToggle);
    }



    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int _currentY = winTop;
        // 先画自定义背景面板
        g.blit(TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        // 标题：翻译后的模块名 + " #" + ID
        Component titleText = Component.translatable("gui.ccpe.module_config.title",
                Component.translatable("module.ccpe." + module.type().name),
                Integer.toString(module.id()));
        g.drawString(this.font, titleText, winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
        // 频道

        _currentY += 18;
        _currentY = renderBar_channel(g, _currentY);

        // 显示提示
        _currentY += BAR_MARGIN_Y;
        _currentY = renderBar_ShowTooltip(g, _currentY);

        _currentY += BAR_MARGIN_Y;

        // 最后画控件（按钮、输入框等），确保在最上层
        super.render(g, mouseX, mouseY, partialTick);
    }

    public int renderBar_channel(GuiGraphics g,int currentY) {


        // ── 横条：背景 + 输入框背景 ──
        renderBar_bg(g, winLeft, currentY);

        // 左侧 16×16 图标
        MyIcons.CHANNEL.render(g, winLeft + 22, currentY + 6);

        // 输入短框背景
        g.blit(TEXTURE, winLeft, currentY + 5,
                INPUT_BG_TEX_X, INPUT_BG_TEX_Y, INPUT_BG_TEX_W, INPUT_BG_TEX_H, TEX_W, TEX_H);
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

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 禁用原版半透明渐变背景，使用自定义贴图代替
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
