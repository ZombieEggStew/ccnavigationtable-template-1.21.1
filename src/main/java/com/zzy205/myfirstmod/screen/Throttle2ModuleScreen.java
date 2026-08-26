package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleInputBar;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import com.zzy205.myfirstmod.network.Throttle2ConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 油门2（总距杆）设置菜单 —— 背景复用 {@link MonitorModuleScreen}（gui_2.png 同区域）。
 * 打开方式：控制台配置菜单点击已安装的油门2 行（由 {@link ControlDeskConfigScreen} 打开）。
 * 布局（自上而下）：
 * <ol>
 *   <li>上抬/下拉按键绑定条（{@link DoubleInputBar}，上抬 = 角度 + / 下拉 = 角度 -）</li>
 *   <li>满偏时间条（{@link ScrollValueBar}，按住满 N tick 从最底端到满偏 +30°，默认 20）</li>
 *   <li>回正开关 + 回正时间条（{@link ToggleButton} + {@link ScrollValueBar}）：开启后松开按键按回正时间线性回到中位 15°（默认关闭 = 锁存不回正，回正时间默认 2 tick）</li>
 * </ol>
 * 上抬/下拉按键 + 满偏时间 + 回正开关/时间均已持久化（BE NBT 四路径 + getUpdatePacket 同步；
 * SeatControlListener 操作模式下读 BE 配置驱动油门2 角度）。
 */
public class Throttle2ModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条布局（自上而下） ──
    private static final int BAR_TEX_H = 28;
    private static final int KEY_BAR_Y = 18;                            // 1. 上抬/下拉按键绑定条
    private static final int FREE_SPEED_BAR_Y = KEY_BAR_Y + BAR_TEX_H;  // 2. 满偏时间条（按住满 N tick 到满偏）
    private static final int RETURN_BAR_Y = FREE_SPEED_BAR_Y + BAR_TEX_H; // 3. 回正开关 + 回正时间条

    private final BlockPos deskPos;

    /** 关闭后返回的上级菜单（控制台配置菜单）；null 则直接回到游戏 */
    @Nullable
    private Screen returnScreen;

    private DoubleInputBar inputBar;      // 1. 上抬/下拉按键绑定条
    private ScrollValueBar freeSpeedBar;  // 2. 满偏时间条
    private ToggleButton returnToggle;    // 3. 回正开关（开启后松开按键回中位 15°）
    private ScrollValueBar returnBar;     // 3. 回正时间条

    public Throttle2ModuleScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    /** 设置关闭后返回的上级菜单（链式）；由控制台配置菜单打开时传入，关闭后回到配置菜单而非游戏。 */
    public Throttle2ModuleScreen withReturnTo(Screen returnScreen) {
        this.returnScreen = returnScreen;
        return this;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        String keyUp = ControlDeskBlockEntity.DEFAULT_THROTTLE_2_KEY_UP;
        String keyDown = ControlDeskBlockEntity.DEFAULT_THROTTLE_2_KEY_DOWN;
        int freeSpeed = ControlDeskBlockEntity.DEFAULT_THROTTLE_2_FREE_SPEED;
        boolean returnEnabled = false;
        int returnTime = ControlDeskBlockEntity.DEFAULT_THROTTLE_2_RETURN_TIME;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity desk) {
            keyUp = desk.getThrottle2KeyUp();
            keyDown = desk.getThrottle2KeyDown();
            freeSpeed = desk.getThrottle2FreeSpeed();
            returnEnabled = desk.isThrottle2ReturnEnabled();
            returnTime = desk.getThrottle2ReturnTime();
        }

        // 1. 上抬/下拉按键绑定条（上抬 = 角度 + / 下拉 = 角度 -）
        this.inputBar = new DoubleInputBar(
                winLeft, winTop + KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.UP, MyIcons.DOWN)
                .setLeftKey(keyUp).setRightKey(keyDown)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar);

        // 2. 满偏时间条（按住满 N tick 从最底端到满偏 +30°，速度 = 1/数值 每 tick；范围常量统一在 ControlDeskBlockEntity 定义）
        this.freeSpeedBar = new ScrollValueBar(
                winLeft, winTop + FREE_SPEED_BAR_Y, WIN_W, BAR_TEX_H, freeSpeed, 0, new int[0])
                .withIcon(MyIcons.FREE_MODE)
                .range(ControlDeskBlockEntity.MIN_THROTTLE_2_FREE_SPEED, ControlDeskBlockEntity.MAX_THROTTLE_2_FREE_SPEED)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.throttle_2_free_speed"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.throttle_2_free_speed_tip"));
        this.addRenderableWidget(this.freeSpeedBar);

        // 3. 回正开关 + 回正时间条（开启后松开按键按回正时间线性回到中位 15°；默认关闭 = 锁存不回正）
        this.returnToggle = new ToggleButton(0, 0, MyIcons.RECOVER, MyIcons.RECOVER, 0x80FF80);
        this.returnToggle.setSelected(returnEnabled);
        // ToggleButton 不自翻转选中状态，必须用回调手动切换（参考 JoystickModuleScreen 档位开关）
        this.returnToggle.withCallback(() -> this.returnToggle.setSelected(!this.returnToggle.isSelected()));
        this.returnToggle
            .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.throttle_2_return"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.throttle_2_return_tip"))
            .addToolTipOnOff(
                Component.translatable("gui.ccpe.control_desk.toggle_on"),
                Component.translatable("gui.ccpe.control_desk.toggle_off"));
        this.returnBar = new ScrollValueBar(
                winLeft, winTop + RETURN_BAR_Y, WIN_W, BAR_TEX_H, returnTime, 0, new int[0])
                .withToggleButton(this.returnToggle)
                .range(ControlDeskBlockEntity.MIN_THROTTLE_2_RETURN_TIME, ControlDeskBlockEntity.MAX_THROTTLE_2_RETURN_TIME)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.throttle_2_return_time"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.throttle_2_return_time_tip"));
        this.addRenderableWidget(this.returnBar);

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
        // 上抬/下拉按键 + 满偏时间 + 回正开关/时间写回服务端 BE（服务端权威：saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new Throttle2ConfigPayload(deskPos,
                inputBar.getLeftKey(), inputBar.getRightKey(),
                freeSpeedBar.getValue(),
                returnToggle.isSelected(), returnBar.getValue()));
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
        g.drawString(this.font, Component.translatable("item.ccpe.throttle_2"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
