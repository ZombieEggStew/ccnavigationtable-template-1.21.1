package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleInputBar;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.network.PedalConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 脚踏板设置菜单 —— 背景复用 {@link MonitorModuleScreen}（gui_2.png 同区域）。
 * 打开方式：手持扳手右键 或 空手蹲下右键，准星命中已安装的脚踏板（由客户端 ControlDeskPlacementOverlay 打开）。
 * 布局（自上而下）：
 * <ol>
 *   <li>左踏板按键绑定条（PEDAL_LEFT_UP / PEDAL_LEFT_DOWN）</li>
 *   <li>右踏板按键绑定条（PEDAL_RIGHT_UP / PEDAL_RIGHT_DOWN）</li>
 *   <li>回正时间条（ScrollValueBar，icon RECOVER，左右两个踏板共用）</li>
 *   <li>满偏时间条（ScrollValueBar，icon FREE_MODE，踩下/抬起按住到满偏所需 tick 数）</li>
 * </ol>
 * 全部配置已持久化（回正时间 + 满偏时间 + 四个按键绑定）。
 */
public class PedalModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条布局（自上而下） ──
    private static final int BAR_TEX_H = 28;
    private static final int LEFT_KEY_BAR_Y = 18;                          // 1. 左踏板按键绑定条
    private static final int RIGHT_KEY_BAR_Y = LEFT_KEY_BAR_Y + BAR_TEX_H; // 2. 右踏板按键绑定条
    private static final int RETURN_BAR_Y = RIGHT_KEY_BAR_Y + BAR_TEX_H;   // 3. 回正时间条（两踏板共用）
    private static final int FREE_SPEED_BAR_Y = RETURN_BAR_Y + BAR_TEX_H;  // 4. 满偏时间条（两踏板共用）

    private final BlockPos deskPos;

    /** 关闭后返回的上级菜单（控制台配置菜单）；null 则直接回到游戏 */
    @Nullable
    private Screen returnScreen;

    private DoubleInputBar inputBar;      // 1. 左踏板按键绑定条
    private DoubleInputBar inputBar2;     // 2. 右踏板按键绑定条
    private ScrollValueBar returnBar;     // 3. 回正时间条（icon RECOVER）
    private ScrollValueBar freeSpeedBar;  // 4. 满偏时间条（icon FREE_MODE）

    public PedalModuleScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    /** 设置关闭后返回的上级菜单（链式）；由控制台配置菜单打开时传入，关闭后回到配置菜单而非游戏。 */
    public PedalModuleScreen withReturnTo(Screen returnScreen) {
        this.returnScreen = returnScreen;
        return this;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        int returnTime = ControlDeskBlockEntity.DEFAULT_PEDAL_RETURN_TIME;
        int freeSpeed = ControlDeskBlockEntity.DEFAULT_PEDAL_FREE_SPEED;
        String leftUp = ControlDeskBlockEntity.DEFAULT_PEDAL_KEY_LEFT_UP;
        String leftDown = ControlDeskBlockEntity.DEFAULT_PEDAL_KEY_LEFT_DOWN;
        String rightUp = ControlDeskBlockEntity.DEFAULT_PEDAL_KEY_RIGHT_UP;
        String rightDown = ControlDeskBlockEntity.DEFAULT_PEDAL_KEY_RIGHT_DOWN;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity desk) {
            returnTime = desk.getPedalReturnTime();
            freeSpeed = desk.getPedalFreeSpeed();
            leftUp = desk.getPedalKeyLeftUp();
            leftDown = desk.getPedalKeyLeftDown();
            rightUp = desk.getPedalKeyRightUp();
            rightDown = desk.getPedalKeyRightDown();
        }

        // 1. 左踏板按键绑定条
        this.inputBar = new DoubleInputBar(
                winLeft, winTop + LEFT_KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.PEDAL_LEFT_UP, MyIcons.PEDAL_LEFT_DOWN)
                .setLeftKey(leftUp).setRightKey(leftDown)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar);

        // 2. 右踏板按键绑定条
        this.inputBar2 = new DoubleInputBar(
                winLeft, winTop + RIGHT_KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.PEDAL_RIGHT_UP, MyIcons.PEDAL_RIGHT_DOWN)
                .setLeftKey(rightUp).setRightKey(rightDown)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar2);

        // 3. 回正时间条（左右两个踏板共用；范围常量统一在 ControlDeskBlockEntity 定义）
        this.returnBar = new ScrollValueBar(
                winLeft, winTop + RETURN_BAR_Y, WIN_W, BAR_TEX_H, returnTime, 0, new int[0])
                .withIcon(MyIcons.RECOVER)
                .range(ControlDeskBlockEntity.MIN_PEDAL_RETURN_TIME, ControlDeskBlockEntity.MAX_PEDAL_RETURN_TIME)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.pedal_return_time"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.return_time_tip"));
        this.addRenderableWidget(this.returnBar);

        // 4. 满偏时间条（踩下/抬起按住到满偏所需 tick 数，速度 = 1/数值 每 tick；左右两个踏板共用）
        this.freeSpeedBar = new ScrollValueBar(
                winLeft, winTop + FREE_SPEED_BAR_Y, WIN_W, BAR_TEX_H, freeSpeed, 0, new int[0])
                .withIcon(MyIcons.FREE_MODE)
                .range(ControlDeskBlockEntity.MIN_PEDAL_FREE_SPEED, ControlDeskBlockEntity.MAX_PEDAL_FREE_SPEED)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.pedal_free_speed"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.pedal_free_speed_tip"));
        this.addRenderableWidget(this.freeSpeedBar);

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

    @Override
    public void onClose() {
        // 回正时间 + 满偏时间 + 四个按键绑定写回服务端 BE（服务端权威：saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new PedalConfigPayload(deskPos,
                returnBar.getValue(),
                freeSpeedBar.getValue(),
                inputBar.getLeftKey(), inputBar.getRightKey(),
                inputBar2.getLeftKey(), inputBar2.getRightKey()));
        // 有上级菜单（配置菜单）时返回它，否则直接回到游戏
        if (returnScreen != null && this.minecraft != null) {
            this.minecraft.setScreen(returnScreen);
        } else {
            super.onClose();
        }
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
