package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.foundation.gui.MyIcons;
import com.zzy205.myfirstmod.foundation.gui.MyUIElements;
import com.zzy205.myfirstmod.foundation.gui.widget.DoubleInputBar;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ScrollValueBar;
import com.zzy205.myfirstmod.network.ThrottleConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 油门杆设置菜单 —— 背景复用 {@link MonitorModuleScreen}（gui_2.png 同区域）。
 * 打开方式：手持扳手右键 或 空手蹲下右键，准星命中已安装的油门杆（由客户端 ControlDeskPlacementOverlay 打开）。
 * 布局（自上而下）：
 * <ol>
 *   <li>前进/后退按键绑定条（{@link DoubleInputBar}，前进 = 模型空间 +x / 后退 = -x）</li>
 *   <li>档位切换节奏条（{@link ScrollValueBar}，按住满 N tick 进/退一档）</li>
 * </ol>
 * 前进/后退按键 + 档位切换节奏均已持久化（BE NBT 四路径 + getUpdatePacket 同步；
 * SeatControlListener 操作模式下读 BE 配置驱动油门档位）。
 */
public class ThrottleModuleScreen extends AbstractMonitorScreen {

    private static final int WIN_W = 192;
    private static final int WIN_H = 169;

    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 4;
    private static final int TITLE_COLOR = 0x404040;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 横条布局（自上而下） ──
    private static final int BAR_TEX_H = 28;
    private static final int KEY_BAR_Y = 18;                            // 1. 前进/后退按键绑定条
    private static final int TICKS_BAR_Y = KEY_BAR_Y + BAR_TEX_H;       // 2. 档位切换节奏条（按住满 N tick 进/退一档）

    private final BlockPos deskPos;

    /** 关闭后返回的上级菜单（控制台配置菜单）；null 则直接回到游戏 */
    @Nullable
    private Screen returnScreen;

    private DoubleInputBar inputBar;   // 1. 前进/后退按键绑定条
    private ScrollValueBar ticksBar;   // 2. 档位切换节奏条

    public ThrottleModuleScreen(BlockPos deskPos) {
        super(Component.empty());
        this.deskPos = deskPos;
    }

    /** 设置关闭后返回的上级菜单（链式）；由控制台配置菜单打开时传入，关闭后回到配置菜单而非游戏。 */
    public ThrottleModuleScreen withReturnTo(Screen returnScreen) {
        this.returnScreen = returnScreen;
        return this;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 从客户端 BE 读取当前配置（服务端权威数据经 getUpdatePacket / 区块加载同步到客户端）；BE 缺失时用默认值
        String keyForward = ControlDeskBlockEntity.DEFAULT_THROTTLE_KEY_FORWARD;
        String keyBack = ControlDeskBlockEntity.DEFAULT_THROTTLE_KEY_BACK;
        int ticksPerGear = ControlDeskBlockEntity.DEFAULT_THROTTLE_TICKS_PER_GEAR;
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(deskPos) instanceof ControlDeskBlockEntity desk) {
            keyForward = desk.getThrottleKeyForward();
            keyBack = desk.getThrottleKeyBack();
            ticksPerGear = desk.getThrottleTicksPerGear();
        }

        // 1. 前进/后退按键绑定条（前进 = 模型空间 +x / 后退 = -x）
        this.inputBar = new DoubleInputBar(
                winLeft, winTop + KEY_BAR_Y, WIN_W, BAR_TEX_H, MyIcons.UP, MyIcons.DOWN)
                .setLeftKey(keyForward).setRightKey(keyBack)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.bind_tip"));
        this.addRenderableWidget(this.inputBar);

        // 2. 档位切换节奏条（按住满 N tick 进/退一档，速度 = 1/数值 每 tick；范围常量统一在 ControlDeskBlockEntity 定义）
        this.ticksBar = new ScrollValueBar(
                winLeft, winTop + TICKS_BAR_Y, WIN_W, BAR_TEX_H, ticksPerGear, 0, new int[0])
                .withIcon(MyIcons.FREE_MODE)
                .range(ControlDeskBlockEntity.MIN_THROTTLE_TICKS_PER_GEAR, ControlDeskBlockEntity.MAX_THROTTLE_TICKS_PER_GEAR)
                .addToolTipTitle(Component.translatable("gui.ccpe.control_desk.throttle_ticks_per_gear"))
                .addToolTipInstruction(Component.translatable("gui.ccpe.control_desk.throttle_ticks_per_gear_tip"));
        this.addRenderableWidget(this.ticksBar);

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
        // 前进/后退按键 + 档位切换节奏写回服务端 BE（服务端权威：saveAdditional 落盘 + getUpdatePacket 同步 + 蓝图兼容）
        PacketDistributor.sendToServer(new ThrottleConfigPayload(deskPos,
                inputBar.getLeftKey(), inputBar.getRightKey(),
                ticksBar.getValue()));
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
        g.drawString(this.font, Component.translatable("item.ccpe.throttle"),
                winLeft + TITLE_X, winTop + TITLE_Y, TITLE_COLOR, false);
    }
}
