package com.zzy205.myfirstmod.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * Receiver 右键菜单屏幕 —— banner 队列 + 添加按钮 + 裁剪滚动 + 长按删除。
 */
public class MyReceiverScreen extends AbstractContainerScreen<MyReceiverMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "textures/gui/test_gui.png");

    // 窗口布局常量
    private static final int WIN_X = 0;
    private static final int WIN_W = 192;
    private static final int WIN_HEIGHT = 192;

    // 九宫格纹理坐标
    private static final int TEX_TOP_Y = 48;
    private static final int TEX_TOP_H = 16;
    private static final int TEX_MID_Y = 64;
    private static final int TEX_MID_H = 16;
    private static final int TEX_BOT_Y = 80;
    private static final int TEX_BOT_H = 16;

    // ── Banner 纹理 ──
    private static final int BANNER_U = 0;
    private static final int BANNER_V = 128;
    private static final int BANNER_W = WIN_W;
    private static final int BANNER_H = 29;

    // ── 添加按钮纹理 ──
    private static final int BTN_U = 0;
    private static final int BTN_V = 160;
    private static final int BTN_W = 33;
    private static final int BTN_H = 18;

    // ── 布局 ──
    /** 内容区顶部（窗口相对坐标） */
    private static final int CONTENT_TOP = TEX_TOP_H + 4;
    /** 内容区底部（窗口相对坐标） */
    private static final int CONTENT_BOTTOM = WIN_HEIGHT - TEX_BOT_H - 4;
    /** 内容区可视高度 */
    private static final int CONTENT_VISIBLE_H = CONTENT_BOTTOM - CONTENT_TOP;
    /** banner 之间的间距 */
    private static final int BANNER_GAP = 2;

    /** 滚动条 */
    private static final int SCROLLBAR_X = WIN_X + WIN_W - 6;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_MIN_THUMB = 8;

    // ── 删除区域 ──
    /** banner 右侧的删除触发区宽度 */
    private static final int DELETE_ZONE_W = 16;
    /** 长按删除阈值（tick，20=1秒） */
    private static final int DELETE_HOLD_TICKS = 20;
    private static final Component DELETE_HINT =
            Component.translatable("gui.ccnavigationtable.receiver.hold_to_delete");

    // ── 状态 ──
    /** 当前 banner 数量 */
    private int bannerCount = 0;
    /** 滚动偏移（像素，>=0，越大内容越往上滚） */
    private int scrollOffset = 0;

    /** 当前鼠标悬停的 banner 删除区索引（-1=无） */
    private int hoveredDeleteBanner = -1;
    /** 正在长按删除的 banner 索引（-1=无） */
    private int deleteHoldBanner = -1;
    /** 长按计时器（tick） */
    private int deleteHoldTimer = 0;
    /** 最后记录的鼠标屏幕坐标 */
    private double trackedMouseX, trackedMouseY;

    public MyReceiverScreen(MyReceiverMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_HEIGHT;
    }

    // ═══════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 九宫格窗口背景
        g.blit(GUI_TEXTURE, x, y, 0, TEX_TOP_Y, WIN_W, TEX_TOP_H, 256, 256);

        int midY = y + TEX_TOP_H;
        int midEnd = y + WIN_HEIGHT - TEX_BOT_H;
        while (midY < midEnd) {
            g.blit(GUI_TEXTURE, x, midY, 0, TEX_MID_Y, WIN_W, TEX_MID_H, 256, 256);
            midY += TEX_MID_H;
        }

        g.blit(GUI_TEXTURE, x, y + WIN_HEIGHT - TEX_BOT_H, 0, TEX_BOT_Y, WIN_W, TEX_BOT_H, 256, 256);

        // ── 内容区透明背景 ──
        g.fill(x + 4, y + CONTENT_TOP, x + WIN_W - 4, y + CONTENT_BOTTOM, 0x18000000);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题居中
        int titleWidth = this.font.width(this.title);
        int titleX = WIN_X + (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, 6, 0xFFFFFFFF, false);

        // ── Scissor 裁剪内容区 ──
        g.enableScissor(
                this.leftPos + 4,
                this.topPos + CONTENT_TOP,
                this.leftPos + WIN_W - 4,
                this.topPos + CONTENT_BOTTOM
        );

        // ── 绘制 banner 队列（滚动偏移） ──
        int deleteZoneScreenX1 = this.leftPos + BANNER_W - 32;
        int deleteZoneScreenX2 = this.leftPos + BANNER_W - 16;
        int bannerY = CONTENT_TOP - scrollOffset;
        hoveredDeleteBanner = -1; // 每帧重置

        for (int i = 0; i < bannerCount; i++) {
            g.blit(GUI_TEXTURE, 0, bannerY, BANNER_U, BANNER_V, BANNER_W, BANNER_H, 256, 256);

            // 检测鼠标是否在删除区（mouseX/Y 是屏幕坐标）
            int bannerScreenY = this.topPos + bannerY;
            if (mouseX >= deleteZoneScreenX1 && mouseX <= deleteZoneScreenX2
                    && mouseY >= bannerScreenY && mouseY < bannerScreenY + BANNER_H) {
                hoveredDeleteBanner = i;
            }

            // 长按进度条：从下到上填满整个 banner 的透明红色
            if (i == deleteHoldBanner) {
                float progress = (float) deleteHoldTimer / DELETE_HOLD_TICKS;
                int filledH = (int) (BANNER_H * progress);
                int fillY = bannerY + BANNER_H - filledH;
                g.fill(BANNER_W - 32, fillY, BANNER_W - 16, bannerY + BANNER_H, 0x60FF0000);
            }

            bannerY += BANNER_H + BANNER_GAP;
        }

        // ── 添加按钮（始终在队列末尾，也随滚动） ──
        g.blit(GUI_TEXTURE, 0, bannerY, BTN_U, BTN_V, BTN_W, BTN_H, 256, 256);

        g.disableScissor();

        // ── 滚动条 ──
        int totalH = getTotalContentHeight();
        renderScrollbar(g, totalH);
    }

    /** 计算内容总高度（banner + 按钮）。渲染循环中每个 banner 后都加了 BANNER_H+BANNER_GAP，
     *  按钮紧接在最后一个 banner 的 BANNER_GAP 之后，因此总高 = n*(H+G) + BTN_H */
    private int getTotalContentHeight() {
        return bannerCount * (BANNER_H + BANNER_GAP) + BTN_H;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);

        // 删除区悬停提示
        if (hoveredDeleteBanner >= 0) {
            g.renderComponentTooltip(this.font, List.of(DELETE_HINT), (int) trackedMouseX, (int) trackedMouseY);
        }
    }

    // ═══════════════════════════════════════════
    //  滚动
    // ═══════════════════════════════════════════

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int scrollLeft = this.leftPos + WIN_X + 4;
        int scrollRight = this.leftPos + WIN_X + WIN_W - 4;
        int scrollTop = this.topPos + CONTENT_TOP;
        int scrollBottom = this.topPos + CONTENT_BOTTOM;
        if (mouseX >= scrollLeft && mouseX <= scrollRight
                && mouseY >= scrollTop && mouseY <= scrollBottom
                && scrollY != 0) {
            scrollOffset -= scrollY > 0 ? 10 : -10;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int totalH = getTotalContentHeight();
        int maxScroll = Math.max(0, totalH - CONTENT_VISIBLE_H);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    /** 绘制滚动条（右侧 4px 宽） */
    private void renderScrollbar(GuiGraphics g, int totalContentHeight) {
        if (totalContentHeight <= CONTENT_VISIBLE_H) return;

        int trackTop = CONTENT_TOP;
        int trackH = CONTENT_VISIBLE_H;

        // 轨道背景
        g.fill(SCROLLBAR_X, trackTop, SCROLLBAR_X + SCROLLBAR_W, trackTop + trackH, 0x20FFFFFF);

        // 滑块
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * trackH / totalContentHeight);
        int maxScroll = totalContentHeight - CONTENT_VISIBLE_H;
        int thumbY = trackTop + (trackH - thumbH) * scrollOffset / Math.max(1, maxScroll);
        g.fill(SCROLLBAR_X, thumbY, SCROLLBAR_X + SCROLLBAR_W, thumbY + thumbH, 0x80AAAAAA);
    }

    // ═══════════════════════════════════════════
    //  交互
    // ═══════════════════════════════════════════

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        trackedMouseX = mouseX;
        trackedMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        trackedMouseX = mouseX;
        trackedMouseY = mouseY;

        if (button == 0) {
            // 添加按钮
            if (isMouseOverAddButton(mouseX, mouseY)) {
                bannerCount++;
                clampScroll();
                playClickSound();
                return true;
            }
            // 删除区 → 开始长按计时
            int delIdx = getDeleteBannerAt(mouseX, mouseY);
            if (delIdx >= 0) {
                deleteHoldBanner = delIdx;
                deleteHoldTimer = 0;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        trackedMouseX = mouseX;
        trackedMouseY = mouseY;
        // 松手即取消长按
        deleteHoldBanner = -1;
        deleteHoldTimer = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        // 长按删除计时
        if (deleteHoldBanner >= 0) {
            int idx = getDeleteBannerAt(trackedMouseX, trackedMouseY);
            if (idx == deleteHoldBanner) {
                deleteHoldTimer++;

                // 每 3 tick 播放一次渐进音调
                if (deleteHoldTimer % 3 == 0) {
                    float pitch = 1.15f + 0.5f * deleteHoldTimer / (float) DELETE_HOLD_TICKS;
                    playHoldTickSound(pitch);
                }

                if (deleteHoldTimer >= DELETE_HOLD_TICKS) {
                    removeBanner(deleteHoldBanner);
                    deleteHoldBanner = -1;
                    deleteHoldTimer = 0;
                }
            } else {
                // 鼠标移出删除区 → 取消
                deleteHoldBanner = -1;
                deleteHoldTimer = 0;
            }
        }
    }

    /** 判断鼠标是否悬停在添加按钮上（考虑滚动偏移） */
    private boolean isMouseOverAddButton(double mouseX, double mouseY) {
        int btnScreenX = this.leftPos;
        int btnScreenY = this.topPos + getButtonY() - scrollOffset;
        return mouseX >= btnScreenX && mouseX < btnScreenX + BTN_W
                && mouseY >= btnScreenY && mouseY < btnScreenY + BTN_H;
    }

    /** 计算按钮的 Y 坐标（窗口相对坐标，不含滚动偏移）。
     *  与渲染循环对齐：buttonY = CONTENT_TOP + n*(BANNER_H + BANNER_GAP) */
    private int getButtonY() {
        return CONTENT_TOP + bannerCount * (BANNER_H + BANNER_GAP);
    }

    /** 返回鼠标所在 banner 的删除区索引（考虑滚动偏移），-1=不在任何删除区 */
    private int getDeleteBannerAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;
        int deleteZoneX1 = BANNER_W - 32;
        int deleteZoneX2 = BANNER_W - 16;
        if (relX < deleteZoneX1 || relX > deleteZoneX2) return -1;

        int bannerY = CONTENT_TOP - scrollOffset;
        for (int i = 0; i < bannerCount; i++) {
            if (relY >= bannerY && relY < bannerY + BANNER_H) return i;
            bannerY += BANNER_H + BANNER_GAP;
        }
        return -1;
    }

    /** 删除指定索引的 banner，重新钳位滚动 */
    private void removeBanner(int index) {
        bannerCount--;
        clampScroll();
        playClickSound();
    }

    private void playClickSound() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
        }
    }

    /** 长按进度音效：NOTE_BLOCK_HAT，音调随进度逐渐升高 */
    private void playHoldTickSound(float pitch) {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.3f));
        }
    }
}
