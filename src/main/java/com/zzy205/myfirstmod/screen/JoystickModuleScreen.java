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
 * 当前阶段：窗口背景 + 标题 + 两条按键双输入条（W/S 前后、A/D 左右）+ 双滚轮条（左=回正时间[已持久化]，右=档位模式[待持久化]）。
 */
public class JoystickModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条布局 ──
    private static final int BAR_TEX_H = 28;
    private static final int KEY_BAR_Y = 18; // 首条（按键绑定条）相对窗口顶部的偏移
    private static final int CONFIG_BAR_Y = KEY_BAR_Y + 2 * BAR_TEX_H + 2; // 双滚轮条（两条绑定条下方，留 2px 间距）

    // 档位模式（档位数）：默认 4，范围 1..8
    private static final int GEAR_DEFAULT = 4;
    private static final int GEAR_MIN = 1;
    private static final int GEAR_MAX = 8;

    private final BlockPos deskPos;

    private DoubleInputBar inputBar;        // W/S（前后）双按键绑定条
    private DoubleInputBar inputBar2;       // A/D（左右）双按键绑定条
    private ToggleButton gearToggle;        // 档位模式开关（挂在双滚轮条右图标位）
    private DoubleScrollValueBar configBar; // 双滚轮条：左=回正时间（icon RECOVER），右=档位模式

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
        String keyUp = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_UP;
        String keyDown = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_DOWN;
        String keyLeft = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_LEFT;
        String keyRight = ControlDeskBlockEntity.DEFAULT_JOYSTICK_KEY_RIGHT;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity desk) {
            returnTime = desk.getJoystickReturnTime();
            keyUp = desk.getJoystickKeyUp();
            keyDown = desk.getJoystickKeyDown();
            keyLeft = desk.getJoystickKeyLeft();
            keyRight = desk.getJoystickKeyRight();
        }

        // 双按键绑定条：上条 W/S（前后），下条 A/D（左右）；onClose 时经 getLeftKey/getRightKey 写回 BE
        this.inputBar = new DoubleInputBar(
                winLeft, winTop + KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.UP, MyIcons.DOWN)
                .setLeftKey(keyUp).setRightKey(keyDown)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar);

        this.inputBar2 = new DoubleInputBar(
                winLeft, winTop + KEY_BAR_Y + BAR_TEX_H, WIN_W, BAR_TEX_H, MyIcons.LEFT, MyIcons.RIGHT)
                .setLeftKey(keyLeft).setRightKey(keyRight)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar2);

        // 档位模式开关（ToggleButton 挂在双滚轮条右图标位，icon 用 INDEX）
        this.gearToggle = new ToggleButton(0, 0, MyIcons.INDEX, MyIcons.INDEX, 0x80FF80);
        this.gearToggle
            .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode"))
            .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode_tip"))
            .addToolTipOnOff(
                Component.translatable("gui.ccpe.control_desk.toggle_on"),
                Component.translatable("gui.ccpe.control_desk.toggle_off"));
        this.gearToggle.withCallback(() -> this.gearToggle.setSelected(!this.gearToggle.isSelected()));

        // 双滚轮条：左=回正时间（普通 icon RECOVER，已持久化），右=档位模式（ToggleButton，待持久化）
        this.configBar = new DoubleScrollValueBar(
                winLeft, winTop + CONFIG_BAR_Y, WIN_W, BAR_TEX_H,
                MyIcons.RECOVER, MyIcons.INDEX, returnTime, GEAR_DEFAULT)
                .rangeLeft(ControlDeskBlockEntity.MIN_JOYSTICK_RETURN_TIME, ControlDeskBlockEntity.MAX_JOYSTICK_RETURN_TIME)
                .rangeRight(GEAR_MIN, GEAR_MAX)
                .withToggleButtonRight(this.gearToggle)
                .addToolTipTitleLeft(Component.translatable("gui.ccpe.control_desk.joystick_return_time"))
                .addToolTipInstructionLeft(Component.translatable("gui.ccpe.control_desk.joystick_return_time_tip"))
                .addToolTipTitleRight(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode"))
                .addToolTipInstructionRight(Component.translatable("gui.ccpe.control_desk.joystick_gear_mode_tip"));
        this.addRenderableWidget(this.configBar);

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
        // 回正时间（左槽位）+ 四向按键写回服务端 BE（服务端权威：saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new ControlDeskConfigPayload(deskPos,
                configBar.getLeftValue(),
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
