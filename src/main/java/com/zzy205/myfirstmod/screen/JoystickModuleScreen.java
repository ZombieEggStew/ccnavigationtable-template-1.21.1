package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleInputBar;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleScrollValueBar;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import com.zzy205.myfirstmod.network.ControlDeskConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 操纵杆设置菜单 —— 背景复用 {@link MonitorModuleScreen}（gui_2.png 同区域）。
 * 打开方式：手持扳手右键 或 空手蹲下右键，准星命中已安装的操纵杆（由客户端 ControlDeskPlacementOverlay 打开）。
 * 布局（自上而下）：
 * <ol>
 *   <li>前后键位绑定条（W/S）</li>
 *   <li>前后轴设置条（回正时间 + 档位模式，双滚轮条）</li>
 *   <li>左右键位绑定条（A/D）</li>
 *   <li>左右轴设置条（回正时间 + 档位模式，双滚轮条）</li>
 * </ol>
 * 全部配置已持久化（两轴回正时间 + 两轴档位模式 + 四向按键）。
 */
public class JoystickModuleScreen extends AbstractMonitorScreen {

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
    private static final int YAW_CONFIG_BAR_Y = YAW_KEY_BAR_Y + BAR_TEX_H -1 ;    // 4. 左右轴设置条
    // 档位模式与回正时间的默认值/范围常量统一在 ControlDeskBlockEntity 定义

    private final BlockPos deskPos;

    private DoubleInputBar inputBar;        // 1. W/S（前后）键位绑定条
    private DoubleScrollValueBar pitchBar;  // 2. 前后轴设置条（回正时间 + 档位模式）
    private DoubleInputBar inputBar2;       // 3. A/D（左右）键位绑定条
    private DoubleScrollValueBar yawBar;    // 4. 左右轴设置条（回正时间 + 档位模式）
    private ToggleButton gearTogglePitch;   // 前后轴档位模式开关（挂在 pitchBar 右图标位）
    private ToggleButton gearToggleYaw;     // 左右轴档位模式开关（挂在 yawBar 右图标位）

    public JoystickModuleScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        int returnTime = ControlDeskBlockEntity.DEFAULT_JOYSTICK_RETURN_TIME;
        int returnTimeYaw = ControlDeskBlockEntity.DEFAULT_JOYSTICK_RETURN_TIME;
        boolean gearModePitch = false;
        int gearCountPitch = ControlDeskBlockEntity.DEFAULT_GEAR_COUNT;
        boolean gearModeYaw = false;
        int gearCountYaw = ControlDeskBlockEntity.DEFAULT_GEAR_COUNT;
        String keyUp = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_UP;
        String keyDown = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_DOWN;
        String keyLeft = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_LEFT;
        String keyRight = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_RIGHT;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity desk) {
            returnTime = desk.getJoystickReturnTime();
            returnTimeYaw = desk.getJoystickReturnTimeYaw();
            gearModePitch = desk.isGearModePitch();
            gearCountPitch = desk.getGearCountPitch();
            gearModeYaw = desk.isGearModeYaw();
            gearCountYaw = desk.getGearCountYaw();
            keyUp = desk.getJoystickKeyUp();
            keyDown = desk.getJoystickKeyDown();
            keyLeft = desk.getJoystickKeyLeft();
            keyRight = desk.getJoystickKeyRight();
        }

        // 1. 前后键位绑定条（W/S）
        this.inputBar = new DoubleInputBar(
                winLeft, winTop + KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.UP, MyIcons.DOWN)
                .setLeftKey(keyUp).setRightKey(keyDown)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar);

        // 2. 前后轴设置条（回正时间 + 档位模式，均已持久化）
        this.gearTogglePitch = createGearToggle(gearModePitch);
        this.pitchBar = createAxisBar(winLeft, winTop, PITCH_CONFIG_BAR_Y, returnTime, gearCountPitch, this.gearTogglePitch);
        this.addRenderableWidget(this.pitchBar);

        // 3. 左右键位绑定条（A/D）
        this.inputBar2 = new DoubleInputBar(
                winLeft, winTop + YAW_KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.LEFT, MyIcons.RIGHT)
                .setLeftKey(keyLeft).setRightKey(keyRight)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar2);

        // 4. 左右轴设置条（回正时间 + 档位模式，均已持久化）
        this.gearToggleYaw = createGearToggle(gearModeYaw);
        this.yawBar = createAxisBar(winLeft, winTop, YAW_CONFIG_BAR_Y, returnTimeYaw, gearCountYaw, this.gearToggleYaw);
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

    /** 档位模式开关（ToggleButton，icon 用 INDEX，挂在轴设置条右图标位）。 */
    private static ToggleButton createGearToggle(boolean selected) {
        ToggleButton toggle = new ToggleButton(0, 0, MyIcons.INDEX, MyIcons.INDEX, 0x80FF80);
        toggle.setSelected(selected);
        toggle
            .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode_tip"))
            .addToolTipOnOff(
                Component.translatable("gui.ccpe.control_desk.toggle_on"),
                Component.translatable("gui.ccpe.control_desk.toggle_off"));
        toggle.withCallback(() -> toggle.setSelected(!toggle.isSelected()));
        return toggle;
    }

    /** 轴设置条（DoubleScrollValueBar）：左=回正时间（icon RECOVER），右=档位模式（ToggleButton）。 */
    private static DoubleScrollValueBar createAxisBar(int winLeft, int winTop, int y, int returnTime, int gearCount, ToggleButton gearToggle) {
        return new DoubleScrollValueBar(
                winLeft, winTop + y, WIN_W, BAR_TEX_H,
                MyIcons.RECOVER, MyIcons.INDEX, returnTime, gearCount)
                .rangeLeft(ControlDeskBlockEntity.MIN_JOYSTICK_RETURN_TIME, ControlDeskBlockEntity.MAX_JOYSTICK_RETURN_TIME)
                .rangeRight(ControlDeskBlockEntity.MIN_GEAR_COUNT, ControlDeskBlockEntity.MAX_GEAR_COUNT)
                .withToggleButtonRight(gearToggle)
                .addToolTipTitleLeft(Component.translatable("gui.ccpe.control_desk.joystick_return_time"))
                .addToolTipInstructionLeft(Component.translatable("gui.ccpe.control_desk.return_time_tip"))
                .addToolTipTitleRight(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode"))
                .addToolTipInstructionRight(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode_tip"));
    }

    @Override
    public void onClose() {
        // 两轴回正时间 + 两轴档位模式 + 四向按键写回服务端 BE（服务端权威：saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new ControlDeskConfigPayload(deskPos,
                pitchBar.getLeftValue(), yawBar.getLeftValue(),
                gearTogglePitch.isSelected(), pitchBar.getRightValue(),
                gearToggleYaw.isSelected(), yawBar.getRightValue(),
                inputBar.getLeftKey(), inputBar.getRightKey(),
                inputBar2.getLeftKey(), inputBar2.getRightKey()));
        super.onClose();
    }

    @Override
    protected void renderCustom(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 窗口背景（与 MonitorModuleScreen 同一贴图区域）
        MyUIElements.BACKGROUND.render(g, winLeft, winTop);

        // 标题：控件名
        g.drawString(this.font, Component.translatable("item.ccpe.joystick"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
