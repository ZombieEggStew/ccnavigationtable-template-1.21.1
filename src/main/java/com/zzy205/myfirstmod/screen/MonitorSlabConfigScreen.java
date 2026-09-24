package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.MonitorSlabBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.network.MonitorSlabChannelPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 板式监视器（monitor_slab）配置菜单 —— 右键菜单布局抄 {@link ControlDeskConfigScreen}，但<b>只保留第一行的
 * 频道滚轮条</b>（已安装控件列表不适用：slab 表面是 Monitor 棋盘网格，模块配置走 {@link MonitorModuleScreen}）。
 * <p>
 * 打开方式（由客户端 {@code MonitorSlabGridOverlay} 打开）：扳手右键（不蹲下）命中顶面任意位置，或空手蹲下右键；
 * 扳手右键不再旋转 FACING（对齐 {@code ControlDeskBlock}）。
 * <p>
 * 频道走<b>全局频道系统</b>（{@code GlobalChannelRegistry}，与显示器/传感器共享同一命名空间，对齐
 * {@link MonitorMenuScreen} 的频道条）：跳过已占用频道，关闭时经 {@link MonitorSlabChannelPayload} 保存；
 * 自动分配与冲突顺延在服务端 {@code MonitorSlabBlockEntity} 完成。
 */
public class MonitorSlabConfigScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条布局 ──
    private static final int BAR_TEX_W = 192;
    private static final int BAR_TEX_H = 28;
    /** 频道条（第一条配置）相对窗口顶部的偏移（与 ControlDeskConfigScreen / MonitorMenuScreen 一致） */
    private static final int CHANNEL_BAR_Y = 18;

    private final BlockPos slabPos;

    private ScrollValueBar channelBar;

    public MonitorSlabConfigScreen(BlockPos slabPos) {
        super(Component.empty());
        this.slabPos = slabPos;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        MonitorSlabBlockEntity slab = null;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(slabPos) instanceof MonitorSlabBlockEntity be) {
            slab = be;
        }
        int channel = slab != null ? slab.getChannel() : 0;
        int[] occupied = slab != null ? slab.getOccupiedChannels() : new int[0];

        // 1. 频道滚轮条（第一条配置，与 MonitorMenuScreen 完全一致：跳过已占用频道，全局频道命名空间）
        this.channelBar = new ScrollValueBar(
                winLeft, winTop + CHANNEL_BAR_Y, BAR_TEX_W, BAR_TEX_H,
                channel, channel, occupied)
            .withIcon(MyIcons.CHANNEL)
            .addToolTipTitle(Component.translatable("gui.ccpe.monitor_slab.channel_title"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.shift_scroll_faster"));
        this.addRenderableWidget(this.channelBar);

        // 右下角"完成"按钮（关闭时保存频道）
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
    }

    @Override
    public void onClose() {
        // 频道写回服务端 BE（服务端权威：注册到全局频道注册表 + 冲突顺延 + NBT 落盘 + 同步客户端）
        if (this.channelBar != null) {
            PacketDistributor.sendToServer(new MonitorSlabChannelPayload(slabPos, channelBar.getValue()));
        }
        super.onClose();
    }

    @Override
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 窗口背景（与 ControlDeskConfigScreen 同一贴图区域）
        MyUIElements.BACKGROUND.render(g, winLeft, winTop);

        // 标题：板式监视器
        g.drawString(this.font, Component.translatable("block.ccpe.monitor_slab"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
