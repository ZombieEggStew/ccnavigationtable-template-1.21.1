package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.ShortRangeLinkerBlockEntity;
import com.zzy205.myfirstmod.channel.ChannelScrollHelper;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.foundation.gui.widget.ToggleButton;
import com.zzy205.myfirstmod.network.ShortRangeLinkerConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 短程信号链接器 GUI。窗口 144×92，背景取自 gui_link.png 的 (0,0) 区域，
 * 在物理体上时额外绘制控件区条带（贴图 (0,96) 起 144×40）。
 * <p>
 * 逻辑：频道滚轮（跳过同链占用，Shift 加速）+ 「加载物理体」共享开关（ToggleButton），
 * 关闭 GUI 时经 {@link ShortRangeLinkerConfigPayload} 保存到服务端；非物理体时只显示提示。
 * <p>
 * 所有控件位置常量均为「窗口相对坐标」，布局请自行调整（视觉由用户负责）。
 */
public class ShortRangeLinkerScreen extends AbstractContainerScreen<ShortRangeLinkerMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/gui_link.png");

    /** 贴图文件实际尺寸（g.blit 最后两个参数，用于 UV 归一化） */
    private static final int TEX_W = 144;
    private static final int TEX_H = 144;

    /** 窗口尺寸（= 背景区域尺寸，贴图 (0,0) 起 144×92） */
    private static final int WIN_W = 144;
    private static final int WIN_H = 92;

    /** 控件区贴图：贴图 (0,96) 起 144×40 */
    private static final int CTRL_U = 0;
    private static final int CTRL_V = 96;
    private static final int CTRL_W = 144;
    private static final int CTRL_H = 18;
    /** 控件区绘制位置（窗口相对 Y，先放在背景正下方，位置参数由用户自行调整） */
    private static final int CTRL_Y_OFFSET = 18;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    // ── 频道滚轮（窗口相对坐标，位置由用户自行调整）──
    /** 频道数字区域 X（滚轮命中区） */
    private static final int CHANNEL_HIT_X = 42;
    private static final int CHANNEL_HIT_W = 36;
    /** 频道数字区域 Y（滚轮命中区） */
    private static final int CHANNEL_HIT_Y = 24;
    private static final int CHANNEL_HIT_H = 16;
    /** 频道数字文本绘制位置 */
    private static final int CHANNEL_TEXT_X = 47;
    private static final int CHANNEL_TEXT_Y = 28;

    // ── 「加载物理体」开关（窗口相对坐标，位置由用户自行调整）──
    private static final int LOAD_TOGGLE_RIGHT = 55;

    // ── 状态 ──
    /** 滚轮驱动的频道号（打开时从菜单 extraData 恢复，关闭时随 payload 保存） */
    private int scrolledValue = 0;
    /** 「加载物理体」开关（链上共享，last-toggle-wins）；非物理体时为 null */
    private ToggleButton loadToggle;

    public ShortRangeLinkerScreen(ShortRangeLinkerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
    }

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;

        // 频道初始值：优先菜单 extraData（与 GUI 打开包同一帧到达，保证最新），fallback 客户端 BE
        this.scrolledValue = getMyChannel();

        // 「加载物理体」开关：仅在物理体上创建（非物理体不注册，也不提供开关）
        this.loadToggle = null;
        if (this.menu.isOnPhysicsBody()) {
            this.loadToggle = new ToggleButton(
                    winLeft + WIN_W - LOAD_TOGGLE_RIGHT, 
                    winTop + WIN_H - DONE_BTN_BOTTOM,
                    AllIcons.I_ACTIVE, AllIcons.I_PASSIVE, 0x80FF80);
            this.loadToggle.setWidth(18);
            this.loadToggle.setHeight(18);
            this.loadToggle.setSelected(this.menu.isBodyLoad());
            this.loadToggle.withCallback(() -> {
                this.loadToggle.setSelected(!this.loadToggle.isSelected());
            });
            this.loadToggle
                    .addToolTipTitle(Component.translatable("gui.ccpe.short_range_linker.load_body"))
                    .addToolTipInstruction(Component.translatable("gui.ccpe.short_range_linker.load_body_tip"))
                    .addToolTipOnOff(
                            Component.translatable("gui.ccpe.short_range_linker.on"),
                            Component.translatable("gui.ccpe.short_range_linker.off"));
            this.addRenderableWidget(this.loadToggle);
        }

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

    // ═══════════════ 频道数据源（菜单 extraData 优先，fallback 客户端 BE） ═══════════════

    /** 当前链接器自己的频道号（滚轮跳过占用时自己的号不算占用） */
    private int getMyChannel() {
        int menuChannel = this.menu.getLinkerChannel();
        if (menuChannel >= 0) return menuChannel;
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(this.menu.getLinkerPos());
            if (be instanceof ShortRangeLinkerBlockEntity linkerBE) {
                return linkerBE.getScrolledValue();
            }
        }
        return 0;
    }

    /** 链内占用频道快照（滚轮跳过占用用） */
    private int[] getOccupiedChannelsSnapshot() {
        int[] fromMenu = this.menu.getOccupiedChannels();
        if (fromMenu.length > 0) return fromMenu;
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(this.menu.getLinkerPos());
            if (be instanceof ShortRangeLinkerBlockEntity linkerBE) {
                return linkerBE.getOccupiedChannels();
            }
        }
        return new int[0];
    }

    // ═══════════════ 渲染 ═══════════════

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 覆盖默认：跳过 renderTransparentBackground（整屏暗色遮罩），背景不变暗，直接画窗口贴图
        this.renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 背景从贴图 (0,0) 起，整块 144×92（g.blit 最后两个参数是贴图文件实际尺寸）
        g.blit(GUI_TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        if (this.menu.isOnPhysicsBody()) {
            // 在物理体上：绘制控件区贴图（贴图 (0,96) 起 144×40）
            g.blit(GUI_TEXTURE, winLeft, winTop + CTRL_Y_OFFSET, CTRL_U, CTRL_V, CTRL_W, CTRL_H, TEX_W, TEX_H);
        } else {
            // 非物理体：显示「只在物理体上可用」提示
            Component msg = Component.translatable("gui.ccpe.short_range_linker.require_body");
            int tx = winLeft + (WIN_W - this.font.width(msg)) / 2;
            g.drawString(this.font, msg, tx, winTop + 38, 0xFFFFFF, true);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);

        // 频道数字区域悬浮提示（标题 频道选择、滚动修改（斜体）、Shift 加速（斜体））
        if (this.menu.isOnPhysicsBody()) {
            int hitX = this.leftPos + CHANNEL_HIT_X;
            int hitY = this.topPos + CHANNEL_HIT_Y;
            if (mouseX >= hitX && mouseX <= hitX + CHANNEL_HIT_W
                    && mouseY >= hitY && mouseY <= hitY + CHANNEL_HIT_H) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.translatable("gui.ccpe.peripheral_extender_channel")
                        .withStyle(Style.EMPTY.withColor(0x528FDE)));
                lines.add(Component.translatable("gui.ccpe.scroll_to_change")
                        .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
                lines.add(Component.translatable("gui.ccpe.shift_scroll_faster")
                        .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
                g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // renderLabels 已在窗口原点：以下坐标均为窗口相对坐标
        // 标题：顶部居中（Y=3，位置由用户自行调整）
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        g.drawString(this.font, this.title, winLeft + 4, winTop + 3, 0xFFFFFFFF, true);


        // 频道号（控件区条带内，位置由用户自行调整）
        if (this.menu.isOnPhysicsBody()) {
            g.drawString(this.font, String.valueOf(this.scrolledValue), winLeft + CHANNEL_TEXT_X, winTop + CHANNEL_TEXT_Y, 0xfcfceb, true);
        }
        // 不画「物品栏」标签（playerInventoryTitle）
    }

    // ═══════════════ 交互 ═══════════════

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 频道区域滚轮：±1，Shift=±10，跳过同链已占用频道
        if (this.menu.isOnPhysicsBody() && scrollY != 0) {
            int hitX = this.leftPos + CHANNEL_HIT_X;
            int hitY = this.topPos + CHANNEL_HIT_Y;
            if (mouseX >= hitX && mouseX <= hitX + CHANNEL_HIT_W
                    && mouseY >= hitY && mouseY <= hitY + CHANNEL_HIT_H) {
                int dir = scrollY > 0 ? 1 : -1;
                int jump = hasShiftDown() ? 10 : 1;
                int newValue = ChannelScrollHelper.next(scrolledValue, dir, jump, getMyChannel(), getOccupiedChannelsSnapshot());
                if (newValue != scrolledValue) {
                    scrolledValue = newValue;
                    playScrollSound();
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 || this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // 关闭时保存频道 + 加载物理体开关（非物理体不发送，服务端也拒绝处理）
        if (this.menu.isOnPhysicsBody()) {
            boolean bodyLoadOn = this.loadToggle != null && this.loadToggle.isSelected();
            PacketDistributor.sendToServer(
                    new ShortRangeLinkerConfigPayload(this.menu.getLinkerPos(), this.scrolledValue, bodyLoadOn));
        }
        super.onClose();
    }

    private void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }
}
