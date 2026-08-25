package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.InstalledModulesList;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.network.ControlDeskChannelPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台配置菜单 —— 背景复用 {@link JoystickModuleScreen}（{@link MyUIElements#BACKGROUND} 192×169）。
 * 打开方式：手持扳手右键 或 空手蹲下右键，准星指向控制台（任意位置，由客户端 ControlDeskPlacementOverlay 打开）；
 * 扳手右键不再旋转控制台方块。
 * 布局（自上而下）：
 * <ol>
 *   <li>频道滚轮条（第一条配置，逻辑对齐 {@link MonitorMenuScreen}：跳过已占用频道，关闭时经 {@link ControlDeskChannelPayload} 保存）</li>
 *   <li>已安装控件列表（物品栏图标 + 控件名称，{@link InstalledModulesList}，数据来自客户端 BE 安装状态；点击行打开对应模块配置菜单）</li>
 * </ol>
 * 频道复用 PE/Monitor 共享的全局频道注册表（{@code GlobalChannelRegistry}），频道全局唯一。
 */
public class ControlDeskConfigScreen extends AbstractMonitorScreen {

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
    /** 频道条（第一条配置）相对窗口顶部的偏移（与 MonitorMenuScreen 的 bar_id 一致） */
    private static final int CHANNEL_BAR_Y = 18;
    /** 已安装控件列表相对窗口顶部的偏移（频道条下方，位置可微调） */
    private static final int MODULE_LIST_Y = 48;

    private final BlockPos deskPos;

    private ScrollValueBar channelBar;
    private InstalledModulesList moduleList;
    /** 已安装控件列表的行号 → 控件类型（点击行时按此打开对应模块配置菜单） */
    private final List<ControlDeskBlockEntity.ControlType> moduleTypes = new ArrayList<>();

    public ControlDeskConfigScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        ControlDeskBlockEntity desk = null;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity be) {
            desk = be;
        }
        int channel = desk != null ? desk.getChannel() : 0;
        int[] occupied = desk != null ? desk.getOccupiedChannels() : new int[0];

        // 1. 频道滚轮条（与 MonitorMenuScreen 第一条配置完全相同：跳过已占用频道）
        this.channelBar = new ScrollValueBar(
                winLeft, winTop + CHANNEL_BAR_Y, BAR_TEX_W, BAR_TEX_H,
                channel, channel, occupied)
            .withIcon(MyIcons.CHANNEL)
            .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.channel_title"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.scroll_to_change"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.shift_scroll_faster"));
        this.addRenderableWidget(this.channelBar);

        // 2. 已安装控件列表（物品栏图标 + 控件名称；按 ControlType 顺序列出已安装的控件，点击行打开对应模块配置菜单）
        this.moduleTypes.clear();
        List<InstalledModulesList.Entry> modules = new ArrayList<>();
        if (desk != null) {
            for (ControlDeskBlockEntity.ControlType type : ControlDeskBlockEntity.ControlType.values()) {
                if (desk.isInstalled(type)) {
                    ItemStack icon = new ItemStack(ControlDeskBlock.controlItem(type));
                    modules.add(new InstalledModulesList.Entry(icon, icon.getHoverName()));
                    moduleTypes.add(type);
                }
            }
        }
        this.moduleList = new InstalledModulesList(winLeft, winTop + MODULE_LIST_Y, WIN_W, modules)
                .withCallback(this::openModuleConfig);
        this.addRenderableWidget(this.moduleList);

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
    }

    /** 点击已安装控件列表的行：按控件类型打开对应的模块配置菜单（关闭后返回本配置菜单）。 */
    private void openModuleConfig(int index) {
        if (index < 0 || index >= moduleTypes.size() || this.minecraft == null) return;
        switch (moduleTypes.get(index)) {
            case JOYSTICK -> this.minecraft.setScreen(new JoystickModuleScreen(deskPos).withReturnTo(this));
            case PEDAL -> this.minecraft.setScreen(new PedalModuleScreen(deskPos).withReturnTo(this));
            case THROTTLE -> this.minecraft.setScreen(new ThrottleModuleScreen(deskPos).withReturnTo(this));
        }
    }

    @Override
    public void onClose() {
        // 频道写回服务端 BE（服务端权威：注册到全局频道注册表 + saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new ControlDeskChannelPayload(deskPos, channelBar.getValue()));
        super.onClose();
    }

    @Override
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 窗口背景（与 JoystickModuleScreen 同一贴图区域）
        MyUIElements.BACKGROUND.render(g, winLeft, winTop);

        // 标题：控制台
        g.drawString(this.font, Component.translatable("block.ccpe.my_control_desk"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
