package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleInputBar;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 脚踏板设置菜单 —— 背景复用 {@link MonitorModuleScreen}（gui_2.png 同区域），
 * 双按键绑定条对应「左踏板 / 右踏板」。
 * 打开方式：手持扳手右键 或 空手蹲下右键，准星命中已安装的脚踏板（由客户端 ControlDeskPlacementOverlay 打开）。
 * 当前阶段：窗口背景 + 标题 + 双输入条（渲染骨架，按键配置保存到 BE 待接入）。
 */
public class PedalModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private final BlockPos deskPos;

    public PedalModuleScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 双按键绑定条：左踏板 / 右踏板（捕获逻辑在 DoubleInputBar；捕获结果后续接入 BE 配置）
        DoubleInputBar inputBar = new DoubleInputBar(
                winLeft, winTop + 18, WIN_W, 28, MyIcons.PEDAL_LEFT_UP, MyIcons.PEDAL_RIGHT_UP)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(inputBar);
    }

    @Override
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 窗口背景（与 MonitorModuleScreen 同一贴图区域）
        MyUIElements.BACKGROUND.render(g, winLeft, winTop);

        // 标题：控件名
        g.drawString(this.font, Component.translatable("item.ccpe.pedal"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
