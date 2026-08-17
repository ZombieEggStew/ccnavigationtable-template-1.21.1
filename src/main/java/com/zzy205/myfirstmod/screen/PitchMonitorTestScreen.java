package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.network.PitchMonitorAnglePayload;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 测试 monitor 的菜单 —— 布局复制 {@link MonitorMenuScreen}：
 * 相同背景面板与标题，但内容为两条滚轮数值条（俯仰 / 偏航）。
 */
public class PitchMonitorTestScreen extends AbstractMonitorScreen {

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

    // ── 横条尺寸（与 MonitorMenuScreen 一致）──
    private static final int BAR_TEX_W = 256;
    private static final int BAR_TEX_H = 28;

    /** 首条横条（俯仰）相对窗口顶部的偏移 */
    private static final int BAR_ID_Y = 18;
    /** 横条之间的垂直间距 */
    private static final int BAR_MARGIN_Y = 2;

    private final BlockPos monitorPos;
    private final int initialPitch;
    private final int initialYaw;

    private int winLeft;
    private int winTop;
    private ScrollValueBar pitchBar;
    private ScrollValueBar yawBar;

    public PitchMonitorTestScreen(BlockPos monitorPos, int pitch, int yaw) {
        super(Component.empty());
        this.monitorPos = monitorPos;
        this.initialPitch = pitch;
        this.initialYaw = yaw;
    }

    @Override
    protected void init() {
        this.winLeft = (this.width - WIN_W) / 2;
        this.winTop = (this.height - WIN_H) / 2;

        // 俯仰角度条：-90 ~ +90
        this.pitchBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y, BAR_TEX_W, BAR_TEX_H,
                initialPitch, 0, new int[0])
                .range(-90, 90)
                .addToolTipTitle(Component.literal("Pitch"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));
        this.addRenderableWidget(this.pitchBar);

        // 偏航角度条：-180 ~ +180（暂未实装到渲染）
        this.yawBar = new ScrollValueBar(
                winLeft, winTop + BAR_ID_Y + BAR_TEX_H + BAR_MARGIN_Y, BAR_TEX_W, BAR_TEX_H,
                initialYaw, 0, new int[0])
                .range(-180, 180)
                .addToolTipTitle(Component.literal("Yaw"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"));
        this.addRenderableWidget(this.yawBar);

        // 右下角"完成"按钮（点击后保存角度并关闭）
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
        g.blit(TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        Component title = Component.translatable("block.ccpe.test_pitch_monitor");
        g.drawString(this.font, title, winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new PitchMonitorAnglePayload(
                monitorPos, (float) pitchBar.getValue(), (float) yawBar.getValue()));
        super.onClose();
    }
}
