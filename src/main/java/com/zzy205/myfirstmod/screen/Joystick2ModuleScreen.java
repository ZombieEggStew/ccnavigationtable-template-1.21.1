package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleInputBar;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleScrollValueBar;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import com.zzy205.myfirstmod.network.Joystick2ConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 摇杆2 设置菜单 —— 布局与 {@link JoystickModuleScreen} 完全相同（照抄），配置独立于 joystick
 * （{@code joystick2} 系列 BE 字段 / {@link Joystick2ConfigPayload}，两控件可同时安装、各自配置）。
 * 打开方式：控制台配置菜单中点击已安装的摇杆2 行（由 {@link ControlDeskConfigScreen} 分发）。
 * 布局（自上而下）：
 * <ol>
 *   <li>前后键位绑定条（W/S）</li>
 *   <li>前后轴设置条（回正时间 + 档位/自由模式，双滚轮条）</li>
 *   <li>左右键位绑定条（A/D）</li>
 *   <li>左右轴设置条（回正时间 + 档位/自由模式，双滚轮条）</li>
 * </ol>
 * 全部配置已持久化（两轴回正时间 + 两轴档位模式/档位数 + 两轴自由模式累加速度 + 四向按键）。
 * 右槽数值随档位开关切换含义：未选中（自由模式）显示满偏 tick 数，选中（档位模式）显示档位数，两值独立记忆。
 */
public class Joystick2ModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条布局（交替：键位绑定条 ↔ 轴设置条） ──
    private static final int BAR_TEX_H = 28;
    private static final int KEY_BAR_Y = 18;                                      // 1. 前后键位绑定条（W/S）
    private static final int PITCH_CONFIG_BAR_Y = KEY_BAR_Y + BAR_TEX_H - 1;      // 2. 前后轴设置条
    private static final int YAW_KEY_BAR_Y = PITCH_CONFIG_BAR_Y + BAR_TEX_H + 2;  // 3. 左右键位绑定条（A/D）
    private static final int YAW_CONFIG_BAR_Y = YAW_KEY_BAR_Y + BAR_TEX_H - 1;    // 4. 左右轴设置条
    // 档位模式与回正时间的默认值/范围常量统一在 ControlDeskBlockEntity 定义（joystick2 系列）

    private final BlockPos deskPos;

    /** 关闭后返回的上级菜单（控制台配置菜单）；null 则直接回到游戏 */
    @Nullable
    private Screen returnScreen;

    private DoubleInputBar inputBar;        // 1. W/S（前后）键位绑定条
    private DoubleScrollValueBar pitchBar;  // 2. 前后轴设置条（回正时间 + 档位模式）
    private DoubleInputBar inputBar2;       // 3. A/D（左右）键位绑定条
    private DoubleScrollValueBar yawBar;    // 4. 左右轴设置条（回正时间 + 档位模式）
    private ToggleButton gearTogglePitch;   // 前后轴档位模式开关（挂在 pitchBar 右图标位）
    private ToggleButton gearToggleYaw;     // 左右轴档位模式开关（挂在 yawBar 右图标位）
    // 右槽两模式各自记忆的数值（随 toggle 状态切换显示：自由模式=满偏 tick 数，档位模式=档位数）
    private int gearCountPitch;
    private int gearCountYaw;
    private int freeSpeedPitch;
    private int freeSpeedYaw;

    public Joystick2ModuleScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    /** 设置关闭后返回的上级菜单（链式）；由控制台配置菜单打开时传入，关闭后回到配置菜单而非游戏。 */
    public Joystick2ModuleScreen withReturnTo(Screen returnScreen) {
        this.returnScreen = returnScreen;
        return this;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        int returnTime = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_RETURN_TIME;
        int returnTimeYaw = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_RETURN_TIME;
        boolean gearModePitch = false;
        int gearCountPitch = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_GEAR_COUNT;
        boolean gearModeYaw = false;
        int gearCountYaw = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_GEAR_COUNT;
        int freeSpeedPitch = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_FREE_SPEED;
        int freeSpeedYaw = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_FREE_SPEED;
        String keyUp = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_KEY_UP;
        String keyDown = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_KEY_DOWN;
        String keyLeft = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_KEY_LEFT;
        String keyRight = ControlDeskBlockEntity.DEFAULT_JOYSTICK2_KEY_RIGHT;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity desk) {
            returnTime = desk.getJoystick2ReturnTime();
            returnTimeYaw = desk.getJoystick2ReturnTimeYaw();
            gearModePitch = desk.isGear2ModePitch();
            gearCountPitch = desk.getGear2CountPitch();
            gearModeYaw = desk.isGear2ModeYaw();
            gearCountYaw = desk.getGear2CountYaw();
            freeSpeedPitch = desk.getJoystick2FreeSpeedPitch();
            freeSpeedYaw = desk.getJoystick2FreeSpeedYaw();
            keyUp = desk.getJoystick2KeyUp();
            keyDown = desk.getJoystick2KeyDown();
            keyLeft = desk.getJoystick2KeyLeft();
            keyRight = desk.getJoystick2KeyRight();
        }
        this.gearCountPitch = gearCountPitch;
        this.gearCountYaw = gearCountYaw;
        this.freeSpeedPitch = freeSpeedPitch;
        this.freeSpeedYaw = freeSpeedYaw;

        // 1. 前后键位绑定条（W/S）
        this.inputBar = new DoubleInputBar(
                winLeft, winTop + KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.UP, MyIcons.DOWN)
                .setLeftKey(keyUp).setRightKey(keyDown)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar);

        // 2. 前后轴设置条（回正时间 + 档位/自由模式，均已持久化）
        this.gearTogglePitch = createGearToggle(gearModePitch);
        this.pitchBar = createAxisBar(winLeft, winTop, PITCH_CONFIG_BAR_Y, returnTime, gearCountPitch, freeSpeedPitch, this.gearTogglePitch);
        this.gearTogglePitch.withCallback(() -> onGearToggle(false));
        this.addRenderableWidget(this.pitchBar);

        // 3. 左右键位绑定条（A/D）
        this.inputBar2 = new DoubleInputBar(
                winLeft, winTop + YAW_KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.LEFT, MyIcons.RIGHT)
                .setLeftKey(keyLeft).setRightKey(keyRight)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar2);

        // 4. 左右轴设置条（回正时间 + 档位/自由模式，均已持久化）
        this.gearToggleYaw = createGearToggle(gearModeYaw);
        this.yawBar = createAxisBar(winLeft, winTop, YAW_CONFIG_BAR_Y, returnTimeYaw, gearCountYaw, freeSpeedYaw, this.gearToggleYaw);
        this.gearToggleYaw.withCallback(() -> onGearToggle(true));
        this.addRenderableWidget(this.yawBar);

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

    /** 档位模式开关（ToggleButton：未选中=自由模式 icon FREE_MODE，选中=档位模式 icon INDEX）。 */
    private static ToggleButton createGearToggle(boolean selected) {
        ToggleButton toggle = new ToggleButton(0, 0, MyIcons.INDEX, MyIcons.FREE_MODE, 0x80FF80);
        toggle.setSelected(selected);
        toggle
            .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode_tip"))
            .addToolTipOnOff(
                Component.translatable("gui.ccpe.control_desk.toggle_on"),
                Component.translatable("gui.ccpe.control_desk.toggle_off"));
        return toggle;
    }

    /**
     * 轴设置条（DoubleScrollValueBar）：左=回正时间（icon RECOVER），
     * 右=ToggleButton（自由/档位模式）+ 对应模式的数值（随 toggle 状态切换，两值独立记忆）。
     */
    private static DoubleScrollValueBar createAxisBar(int winLeft, int winTop, int y,
                                                      int returnTime, int gearCount, int freeSpeed, ToggleButton gearToggle) {
        boolean gearMode = gearToggle.isSelected();
        DoubleScrollValueBar bar = new DoubleScrollValueBar(
                winLeft, winTop + y, WIN_W, BAR_TEX_H,
                MyIcons.RECOVER, MyIcons.INDEX, returnTime, gearMode ? gearCount : freeSpeed)
                .rangeLeft(ControlDeskBlockEntity.MIN_JOYSTICK_RETURN_TIME, ControlDeskBlockEntity.MAX_JOYSTICK_RETURN_TIME)
                .withToggleButtonRight(gearToggle)
                .addToolTipTitleLeft(Component.translatable("gui.ccpe.control_desk.joystick_return_time"))
                .addToolTipInstructionLeft(Component.translatable("gui.ccpe.control_desk.return_time_tip"));
        // 右槽数值含义随模式变化：范围 + tooltip 按当前模式设置
        applyRightMode(bar, gearMode, gearCount, freeSpeed);
        return bar;
    }

    /** 档位模式开关点击：切换模式，右槽数值/范围/tooltip 随状态切换（两模式数值独立记忆）。 */
    private void onGearToggle(boolean yaw) {
        ToggleButton toggle = yaw ? gearToggleYaw : gearTogglePitch;
        DoubleScrollValueBar bar = yaw ? yawBar : pitchBar;
        // 先保存当前显示模式的数值，再切换
        if (yaw) {
            if (toggle.isSelected()) gearCountYaw = bar.getRightValue();
            else freeSpeedYaw = bar.getRightValue();
        } else {
            if (toggle.isSelected()) gearCountPitch = bar.getRightValue();
            else freeSpeedPitch = bar.getRightValue();
        }
        boolean gearMode = !toggle.isSelected();
        toggle.setSelected(gearMode);
        applyRightMode(bar, gearMode, yaw ? gearCountYaw : gearCountPitch, yaw ? freeSpeedYaw : freeSpeedPitch);
    }

    /** 按模式恢复右槽数值（数值 + 范围 + tooltip 一并切换）。 */
    private static void applyRightMode(DoubleScrollValueBar bar, boolean gearMode, int gearCount, int freeSpeed) {
        if (gearMode) {
            bar.setRightValue(gearCount).rangeRight(
                    ControlDeskBlockEntity.MIN_GEAR_COUNT, ControlDeskBlockEntity.MAX_GEAR_COUNT);
            bar.setRightTooltip(
                    Component.translatable("gui.ccpe.control_desk.joystick_gear_mode"),
                    Component.translatable("gui.ccpe.control_desk.joystick_gear_mode_tip"));
        } else {
            bar.setRightValue(freeSpeed).rangeRight(
                    ControlDeskBlockEntity.MIN_JOYSTICK_FREE_SPEED, ControlDeskBlockEntity.MAX_JOYSTICK_FREE_SPEED);
            bar.setRightTooltip(
                    Component.translatable("gui.ccpe.control_desk.joystick_free_mode"),
                    Component.translatable("gui.ccpe.control_desk.joystick_free_mode_tip"));
        }
    }

    @Override
    public void onClose() {
        // 先保存当前显示模式的数值（右槽数值随 toggle 状态切换含义）
        if (gearTogglePitch.isSelected()) gearCountPitch = pitchBar.getRightValue();
        else freeSpeedPitch = pitchBar.getRightValue();
        if (gearToggleYaw.isSelected()) gearCountYaw = yawBar.getRightValue();
        else freeSpeedYaw = yawBar.getRightValue();
        // 两轴回正时间 + 两轴档位模式/档位数 + 两轴自由速度 + 四向按键写回服务端 BE（独立于 joystick）
        // （服务端权威：saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new Joystick2ConfigPayload(deskPos,
                pitchBar.getLeftValue(), yawBar.getLeftValue(),
                gearTogglePitch.isSelected(), gearCountPitch, freeSpeedPitch,
                gearToggleYaw.isSelected(), gearCountYaw, freeSpeedYaw,
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
        g.drawString(this.font, Component.translatable("item.ccpe.joystick_2"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
