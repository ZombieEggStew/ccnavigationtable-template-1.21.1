package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.client.MonitorBackgrounds;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.zzy205.myfirstmod.network.MonitorBackgroundPayload;
import com.zzy205.myfirstmod.network.MonitorChannelPayload;
import com.zzy205.myfirstmod.network.MonitorTransformPayload;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Monitor 自身菜单 —— 蹲下+右键 Monitor 空白处打开。
 * 布局复制 {@link MonitorModuleScreen}：相同背景面板与第一行 bar_id（滚轮选择频道），
 * 并在原频道/背景两条横条下追加俯仰 / 偏航 / 前后偏移三条滚轮。
 */
public class MonitorMenuScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 200;
    private static final int TEX_W = 192;
    private static final int TEX_H = 384;

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/gui_2.png");

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 背景面板：原图窗口为 192×159，顶部 16px、底部 29px 边框，中部为纯色主体 ──
    private static final int PANEL_TOP_H = 16;
    private static final int PANEL_BOTTOM_H = 29;
    private static final int PANEL_BODY_COLOR = 0xFF404040;

    // ── 横条尺寸（与 MonitorModuleScreen 的 bar_id 一致）──
    private static final int BAR_TEX_W = 192;
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
        private java.util.List<String> customBackgroundKeys;
    /** 打开菜单时的可动变换 */
    private final int initialPitch;
    private final int initialYaw;
    private final int initialOffset;

    private int winLeft;
    private int winTop;
    private ScrollValueBar channelBar;
    private ScrollValueBar backgroundBar;
    private ScrollValueBar pitchBar;
    private ScrollValueBar yawBar;
    private ScrollValueBar offsetBar;

    public MonitorMenuScreen(BlockPos monitorPos, int channel, int[] occupiedChannels, String background,
                             int pitch, int yaw, int offset) {
        super(Component.empty());
        this.monitorPos = monitorPos;
        this.originalChannel = channel;
        this.occupiedChannels = occupiedChannels;
        this.background = background;
        this.initialPitch = pitch;
        this.initialYaw = yaw;
        this.initialOffset = offset;
    }

    @Override
    protected void init() {
        this.winLeft = (this.width - WIN_W) / 2;
        this.winTop = (this.height - WIN_H) / 2;

        // 频道滚轮输入条
        this.channelBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y, BAR_TEX_W, BAR_TEX_H,
                originalChannel, originalChannel, occupiedChannels)
            .withIcon(MyIcons.CHANNEL)
            .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.id_title"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.shift_scroll_faster"));
        this.addRenderableWidget(this.channelBar);

        this.backgroundBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y + BAR_TEX_H + BAR_MARGIN_Y, BAR_TEX_W, BAR_TEX_H,
                backgroundIndex(), MonitorBackground.displayNames(customBackgroundKeys))
                .withIcon(MyIcons.BACKGROUND)
                .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.background_title"))
                .addToolTipOptions()
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));

        this.addRenderableWidget(this.backgroundBar);

        // 俯仰角度条：-90 ~ +90
        this.pitchBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y + 2 * (BAR_TEX_H + BAR_MARGIN_Y), BAR_TEX_W, BAR_TEX_H,
                initialPitch, 0, new int[0])
                .range(-90, 90)
                .withIcon(MyIcons.PITCH)
                .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.pitch_title"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));
        this.addRenderableWidget(this.pitchBar);

        // 偏航角度条：-180 ~ +180
        this.yawBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y + 3 * (BAR_TEX_H + BAR_MARGIN_Y), BAR_TEX_W, BAR_TEX_H,
                initialYaw, 0, new int[0])
                .range(-180, 180)
                .withIcon(MyIcons.YAW)
                .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.yaw_title"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));
        this.addRenderableWidget(this.yawBar);

        // 前后偏移条：-6 ~ +6（单位 1/16 方块 = 1px）
        this.offsetBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y + 4 * (BAR_TEX_H + BAR_MARGIN_Y), BAR_TEX_W, BAR_TEX_H,
                initialOffset, 0, new int[0])
                .range(-6, 6)
                .withIcon(MyIcons.OFFSET)
                .addToolTipTitle(Component.translatable("gui.ccpe.monitor_menu.offset_title"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));
        this.addRenderableWidget(this.offsetBar);

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
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 自定义背景面板：顶部/底部边框 + 中部主体填充（原图窗口只有 159 高，加长到 200）
        g.blit(TEXTURE, winLeft, winTop, 0, 0, WIN_W, PANEL_TOP_H, TEX_W, TEX_H);
        int bodyTop = winTop + PANEL_TOP_H;
        int bodyBottom = winTop + WIN_H - PANEL_BOTTOM_H;
        g.fill(winLeft + 1, bodyTop, winLeft + WIN_W - 1, bodyBottom, PANEL_BODY_COLOR);
        g.fill(winLeft + 1, bodyTop, winLeft + 2, bodyBottom, 0xFF000000);
        g.fill(winLeft + 2, bodyTop, winLeft + 3, bodyBottom, 0xFFEAEAEA);
        g.fill(winLeft + WIN_W - 2, bodyTop, winLeft + WIN_W - 1, bodyBottom, 0xFF000000);
        g.fill(winLeft + WIN_W - 3, bodyTop, winLeft + WIN_W - 2, bodyBottom, 0xFFEAEAEA);
        g.blit(TEXTURE, winLeft, winTop + WIN_H - PANEL_BOTTOM_H, 0, 140, WIN_W, PANEL_BOTTOM_H, TEX_W, TEX_H);

        // 标题
        Component title = Component.translatable("block.ccpe.my_monitor");
        g.drawString(this.font, title, winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new MonitorChannelPayload(monitorPos, channelBar.getValue()));
        PacketDistributor.sendToServer(new MonitorBackgroundPayload(
                monitorPos, MonitorBackground.keyAt(backgroundBar.getValue(), customBackgroundKeys)));
        PacketDistributor.sendToServer(new MonitorTransformPayload(
                monitorPos, (float) pitchBar.getValue(), (float) yawBar.getValue(), offsetBar.getValue()));
        super.onClose();
    }

        private int backgroundIndex() {
                this.customBackgroundKeys = MonitorBackgrounds.keys();
                int customIndex = customBackgroundKeys.indexOf(background);
                return customIndex >= 0 ? MonitorBackground.KEYS.length + customIndex : MonitorBackground.indexOf(background);
        }
}
