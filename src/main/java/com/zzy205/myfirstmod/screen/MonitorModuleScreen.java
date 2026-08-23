package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.foundation.gui.widget.TextInputBar;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.network.ModuleConfigPayload;

import com.simibubi.create.foundation.gui.AllIcons;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Monitor 模块配置界面 —— 蹲下+右键模块时打开。
 * 窗口 192×127，纹理位于 textures/gui/gui_2.png。
 */
public class MonitorModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;


    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条：背景 + 图标 + 输入框 ──
    private static final int BAR_TEX_W = 192;
    private static final int BAR_TEX_H = 28;
    private static final int BAR_ID_Y = 18;  // 首条横条（ID 滚轮）相对窗口顶部的偏移
    private static final int BAR_MARGIN_Y = 2;  // 横条之间的垂直间距

    private final BlockPos monitorPos;
    private final GridState grid;
    private final String name;        // 模块类型名或 "screen"
    private final int originalId;     // 打开菜单时的 ID
    private final String initialText; // 打开菜单时的 tooltip 文本

    private ModuleConfigSection section = ModuleConfigSection.Empty.INSTANCE;

    private int winLeft;
    private int winTop;
    private ScrollValueBar idBar;  // ID 滚轮输入条
    private TextInputBar textBar;  // 悬浮文本输入条

    private static final int SECTION_Y_OFFSET = 78;  // 特殊设置区起始 Y（窗口相对）

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

        // ID 滚轮输入条（与 MonitorMenuScreen 频道条一致）
        this.idBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y, BAR_TEX_W, BAR_TEX_H,
                originalId, originalId, grid.getOccupiedIds())
            .withIcon(MyIcons.ID)
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.id_title"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.shift_scroll_faster"));
        this.addRenderableWidget(this.idBar);

        // 悬浮文本输入条（滚轮滑条下方）
        this.textBar = new TextInputBar(
                winLeft, winTop + BAR_ID_Y + BAR_TEX_H + BAR_MARGIN_Y,
                BAR_TEX_W, BAR_TEX_H, initialText, 50, MyIcons.SHOW_TOOLTIP)
            .setHint(Component.translatable("gui.ccpe.module_config.text_hint"))
            .addToolTipTitle(Component.translatable("gui.ccpe.module_config.text_title"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.module_config.text_tip"));
        this.addRenderableWidget(this.textBar);
        
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





        // 每类型特殊设置区
        this.section = ModuleConfigSections.create(name);
        this.section.init(this, winTop + SECTION_Y_OFFSET, grid.getModuleConfig(originalId));
    }



    @Override
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 先画自定义背景面板
        MyUIElements.BACKGROUND.render(g, winLeft, winTop);
        // g.blit(TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        // 标题：仅显示名称
        Component titleText = Component.translatable("module.ccpe." + name);
        g.drawString(this.font, titleText, winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
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
        config.putString("text", textBar.getValue());
        section.save(config);
        PacketDistributor.sendToServer(new ModuleConfigPayload(monitorPos, name, originalId, idBar.getValue(), config));
        super.onClose();
    }
}
