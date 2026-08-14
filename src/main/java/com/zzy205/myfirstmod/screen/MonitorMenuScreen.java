package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.network.MonitorBackgroundPayload;
import com.zzy205.myfirstmod.network.MonitorChannelPayload;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Monitor 自身菜单 —— 蹲下+右键 Monitor 空白处打开。
 * 布局复制 {@link MonitorModuleScreen}：相同背景面板与第一行 bar_id（滚轮选择频道），
 * 不包含第二行 bar_tooltip、文本输入框与类型专属配置区。
 */
public class MonitorMenuScreen extends Screen {

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

    // ── 横条尺寸（与 MonitorModuleScreen 的 bar_id 一致）──
    private static final int BAR_TEX_W = 256;
    private static final int BAR_TEX_H = 28;

    /** 首条横条（bar_id）相对窗口顶部的偏移 */
    private static final int BAR_ID_Y = 18;
    /** 横条之间的垂直间距 */
    private static final int BAR_MARGIN_Y = 2;

    private final BlockPos monitorPos;
    /** 打开菜单时的频道号 */
    private final int originalChannel;
    /** 全局已占用频道号（传感器 + 显示器） */
    private final int[] occupiedChannels;
    /** 打开菜单时的背景选项 */
    private final String background;

    private int winLeft;
    private int winTop;
    private ScrollValueBar channelBar;
    private ScrollValueBar backgroundBar;

    public MonitorMenuScreen(BlockPos monitorPos, int channel, int[] occupiedChannels, String background) {
        super(Component.empty());
        this.monitorPos = monitorPos;
        this.originalChannel = channel;
        this.occupiedChannels = occupiedChannels;
        this.background = background;
    }

    @Override
    protected void init() {
        this.winLeft = (this.width - WIN_W) / 2;
        this.winTop = (this.height - WIN_H) / 2;

        // 频道滚轮输入条
        this.channelBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y, BAR_TEX_W, BAR_TEX_H,
                originalChannel, originalChannel, occupiedChannels,
                MyIcons.CHANNEL)
            .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.id_title"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.shift_scroll_faster"));
        this.addRenderableWidget(this.channelBar);

        this.backgroundBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y + BAR_TEX_H + BAR_MARGIN_Y, BAR_TEX_W, BAR_TEX_H,
                MonitorBackground.indexOf(background), MonitorBackground.displayNames(),
                MyIcons.BACKGROUND)
                .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.background_title"))
                .addToolTipOptions()
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));
                
        this.addRenderableWidget(this.backgroundBar);

        // 右下角"完成"按钮（关闭时保存频道）
        HoverTintIconButton doneBtn = new HoverTintIconButton(
                winLeft + WIN_W - DONE_BTN_RIGHT,
                winTop + WIN_H - DONE_BTN_BOTTOM,
                (ScreenElement) AllIcons.I_CONFIRM,
                0x80FF80);
        doneBtn.setWidth(18);
        doneBtn.setHeight(18);
        doneBtn.withCallback(this::onClose);
        doneBtn.setToolTip(Component.translatable("gui.ccpe.monitor_menu.done"));
        this.addRenderableWidget(doneBtn);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 自定义背景面板
        g.blit(TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        // 标题
        Component title = Component.translatable("block.ccpe.my_monitor");
        g.drawString(this.font, title, winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);

        // 最后画控件（按钮、频道滚轮条等），确保在最上层
        super.render(g, mouseX, mouseY, partialTick);

        // tooltip（在最上层）
        channelBar.renderTooltip(g, mouseX, mouseY);
        backgroundBar.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new MonitorChannelPayload(monitorPos, channelBar.getValue()));
        PacketDistributor.sendToServer(new MonitorBackgroundPayload(monitorPos, MonitorBackground.keyAt(backgroundBar.getValue())));
        super.onClose();
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
