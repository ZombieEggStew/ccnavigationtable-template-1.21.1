package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.block.AdvancedThrustNozzleBlockEntity;
import com.zzy205.myfirstmod.channel.ChannelScrollHelper;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;
import com.zzy205.myfirstmod.network.AdvancedThrustNozzleConfigPayload;

import net.createmod.catnip.gui.element.ScreenElement;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
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
 * 矢量喷管 GUI：<b>只有一个频道滚轮</b>（用户定稿：不做「加载物理体」开关）。
 * <p>
 * 窗口 144×96，背景复用短程链接器的 {@code gui_link.png}（(0,0) 起 144×96 区域、
 * 控件条带为 (0,96) 起 144×18）；视觉后续由用户重画，这里只保证功能与位置常量集中。
 * <p>
 * 逻辑照 {@link ShortRangeLinkerScreen}：频道滚轮（跳过同链占用 + Shift 加速）→ 关闭时经
 * {@link AdvancedThrustNozzleConfigPayload} 保存到服务端；非物理体时只显示提示、不发包。
 */
public class AdvancedThrustNozzleScreen extends AbstractContainerScreen<AdvancedThrustNozzleMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/gui_link.png");

    /** 贴图文件实际尺寸（g.blit 最后两个参数，用于 UV 归一化） */
    private static final int TEX_W = 144;
    private static final int TEX_H = 144;

    /** 窗口尺寸 */
    private static final int WIN_W = 144;
    private static final int WIN_H = 96;

    /** 控件区条带（贴图 (0,96) 起 144×18） */
    private static final int CTRL_U = 0;
    private static final int CTRL_V = 96;
    private static final int CTRL_W = 144;
    private static final int CTRL_H = 18;
    private static final int CTRL_Y_OFFSET = 20;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 26;

    // ── 频道滚轮（窗口相对坐标）──
    private static final int CHANNEL_HIT_X = 48;
    private static final int CHANNEL_HIT_W = 46;
    private static final int CHANNEL_HIT_Y = 18;
    private static final int CHANNEL_HIT_H = 18;
    private static final int CHANNEL_TEXT_X = 52;
    private static final int CHANNEL_TEXT_Y = 25;

    /** 滚轮驱动的频道号（打开时从菜单 extraData 恢复，关闭时随 payload 保存） */
    private int scrolledValue = 0;

    public AdvancedThrustNozzleScreen(final AdvancedThrustNozzleMenu menu, final Inventory playerInv,
                                      final Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
    }

    @Override
    protected void init() {
        final int winLeft = (this.width - WIN_W) / 2;
        final int winTop = (this.height - WIN_H) / 2;

        // 频道初始值：优先菜单 extraData（与 GUI 打开包同一帧到达），fallback 客户端 BE
        this.scrolledValue = getMyChannel();

        // 右下角「完成」按钮
        final HoverTintIconButton doneBtn = new HoverTintIconButton(
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

    private int getMyChannel() {
        final int menuChannel = this.menu.getChannel();
        if (menuChannel >= 0) {
            return menuChannel;
        }

        if (this.minecraft != null && this.minecraft.level != null) {
            final BlockEntity be = this.minecraft.level.getBlockEntity(this.menu.getNozzlePos());
            if (be instanceof final AdvancedThrustNozzleBlockEntity nozzle) {
                return Math.max(0, nozzle.getChannel());
            }
        }

        return 0;
    }

    private int[] getOccupiedChannelsSnapshot() {
        final int[] fromMenu = this.menu.getOccupiedChannels();
        if (fromMenu.length > 0) {
            return fromMenu;
        }

        if (this.minecraft != null && this.minecraft.level != null) {
            final BlockEntity be = this.minecraft.level.getBlockEntity(this.menu.getNozzlePos());
            if (be instanceof final AdvancedThrustNozzleBlockEntity nozzle) {
                return nozzle.getOccupiedChannels();
            }
        }

        return new int[0];
    }

    // ═══════════════ 渲染 ═══════════════

    @Override
    public void renderBackground(final GuiGraphics g, final int mouseX, final int mouseY, final float partialTick) {
        // 覆盖默认：跳过整屏暗色遮罩，直接画窗口贴图（与链接器 GUI 一致）
        this.renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(final GuiGraphics g, final float partialTick, final int mouseX, final int mouseY) {
        final int winLeft = (this.width - WIN_W) / 2;
        final int winTop = (this.height - WIN_H) / 2;
        g.blit(GUI_TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        if (this.menu.isOnPhysicsBody()) {
            g.blit(GUI_TEXTURE, winLeft, winTop + CTRL_Y_OFFSET, CTRL_U, CTRL_V, CTRL_W, CTRL_H, TEX_W, TEX_H);
        } else {
            // 非物理体：只在物理体上生效
            final Component msg = Component.translatable("gui.ccpe.advanced_thrust_nozzle.require_body");
            final int tx = winLeft + (WIN_W - this.font.width(msg)) / 2;
            g.drawString(this.font, msg, tx, winTop + 38, 0xFFFFFF, true);
        }
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);

        // 子控件 tooltip 前景层（必须在 super.render 之后，否则被后加的「完成」按钮盖住）
        for (final var widget : this.renderables) {
            if (widget instanceof final AbstractSimiWidget simi && simi.isMouseOver(mouseX, mouseY)) {
                final var tooltip = simi.getToolTip();
                if (!tooltip.isEmpty()) {
                    g.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
                }
            }
        }

        // 频道数字区域悬浮提示
        if (this.menu.isOnPhysicsBody()) {
            final int hitX = (this.width - WIN_W) / 2 + CHANNEL_HIT_X;
            final int hitY = (this.height - WIN_H) / 2 + CHANNEL_HIT_Y;
            if (mouseX >= hitX && mouseX <= hitX + CHANNEL_HIT_W
                    && mouseY >= hitY && mouseY <= hitY + CHANNEL_HIT_H) {
                final List<Component> lines = new ArrayList<>();
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
    protected void renderLabels(final GuiGraphics g, final int mouseX, final int mouseY) {
        final int winLeft = (this.width - WIN_W) / 2;
        final int winTop = (this.height - WIN_H) / 2;
        g.drawString(this.font, this.title, winLeft + 4, winTop + 4, 0x3c3b47, false);

        if (this.menu.isOnPhysicsBody()) {
            g.drawString(this.font, String.valueOf(this.scrolledValue),
                    winLeft + CHANNEL_TEXT_X, winTop + CHANNEL_TEXT_Y, 0xfcfceb, true);
        }
        // 不画「物品栏」标签
    }

    // ═══════════════ 交互 ═══════════════

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (this.menu.isOnPhysicsBody() && scrollY != 0) {
            final int hitX = (this.width - WIN_W) / 2 + CHANNEL_HIT_X;
            final int hitY = (this.height - WIN_H) / 2 + CHANNEL_HIT_Y;
            if (mouseX >= hitX && mouseX <= hitX + CHANNEL_HIT_W
                    && mouseY >= hitY && mouseY <= hitY + CHANNEL_HIT_H) {
                final int dir = scrollY > 0 ? 1 : -1;
                final int jump = hasShiftDown() ? 10 : 1;
                final int newValue = ChannelScrollHelper.next(this.scrolledValue, dir, jump,
                        getMyChannel(), getOccupiedChannelsSnapshot());
                if (newValue != this.scrolledValue) {
                    this.scrolledValue = newValue;
                    playScrollSound();
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == 256 || Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // 只在物理体上发包（服务端也会二次校验）
        if (this.menu.isOnPhysicsBody()) {
            PacketDistributor.sendToServer(
                    new AdvancedThrustNozzleConfigPayload(this.menu.getNozzlePos(), this.scrolledValue));
        }
        super.onClose();
    }

    private void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }
}
