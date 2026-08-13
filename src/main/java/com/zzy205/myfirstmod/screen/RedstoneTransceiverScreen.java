package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.channel.ChannelScrollHelper;
import com.zzy205.myfirstmod.network.ReceiverSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Receiver 右键菜单屏幕 —— banner 队列 + 添加按钮 + 裁剪滚动 + 长按删除。
 */
public class RedstoneTransceiverScreen extends AbstractContainerScreen<RedstoneTransceiverMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/test_gui.png");

    /** Create 模组提供的玩家背包贴图 */
    private static final ResourceLocation PLAYER_INV_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/player_inventory.png");
    private static final int BACKPACK_W = 175;
    private static final int BACKPACK_H = 108;

    // 窗口布局常量
    private static final int WIN_X = 0;
    private static final int WIN_W = 192;
    private static final int WIN_HEIGHT = 160;

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

    // ── 玩家物品栏 ──
    /** 窗口与物品栏间距 */
    private static final int INV_GAP = 5;
    /** 物品栏标签 Y（窗口相对坐标） */
    private static final int INV_LABEL_Y = WIN_HEIGHT + INV_GAP + 3;
    /** 物品栏区域高度 */
    private static final int INV_AREA_H = 100;
    /** 整体 GUI 高度 */
    private static final int IMAGE_HEIGHT = WIN_HEIGHT + INV_GAP + INV_AREA_H;

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
            Component.translatable("gui.ccpe.redstone_transceiver.hold_to_delete");

    // ── 频道选择 ──
    /** 频道数字区域 X（窗口相对坐标，banner 左侧） */
    private static final int CHANNEL_ZONE_X = 42;
    /** 频道数字区域宽度 */
    private static final int CHANNEL_ZONE_W = 34;
    /** 频道文字在 banner 内的 Y 偏移 */
    private static final int CHANNEL_TEXT_Y_OFFSET = 10;
    /** 频道文字 X 偏移（相对于 CHANNEL_ZONE_X） */
    private static final int CHANNEL_TEXT_X_OFFSET = 5;
    /** 频道号范围 */
    private static final int CHANNEL_MIN = 0;
    private static final int CHANNEL_MAX = 9999;

    // ── 幽灵物品槽 ──
    /** 幽灵槽 0 在 banner 内的 X（右侧第一格） */
    private static final int GHOST_SLOT_X = BANNER_W - 91;
    /** 幽灵槽 1 在 banner 内的 X（右侧第二格） */
    private static final int GHOST_SLOT_2_X = GHOST_SLOT_X + 18 + 3;
    /** 幽灵槽在 banner 内的 Y 偏移 */
    private static final int GHOST_SLOT_Y_OFFSET = 6;

    // ── 状态 ──
    /** 每个 banner 的频道号列表 */
    private final List<Integer> bannerChannels = new ArrayList<>();
    /** 每个 banner 的幽灵物品槽 0 */
    private final List<ItemStack> ghostItem0 = new ArrayList<>();
    /** 每个 banner 的幽灵物品槽 1 */
    private final List<ItemStack> ghostItem1 = new ArrayList<>();
    /** 滚动偏移（像素，>=0，越大内容越往上滚） */
    private int scrollOffset = 0;

    /** 当前鼠标悬停的 banner 频道区索引（-1=无） */
    private int hoveredChannelBanner = -1;
    /** 当前鼠标悬停的 banner 删除区索引（-1=无） */
    private int hoveredDeleteBanner = -1;
    /** 当前鼠标悬停的幽灵槽 [banner, slot]，-1=无 */
    private int hoveredGhostBanner = -1;
    private int hoveredGhostSlot = -1;
    /** 正在长按删除的 banner 索引（-1=无） */
    private int deleteHoldBanner = -1;
    /** 长按计时器（tick） */
    private int deleteHoldTimer = 0;
    /** 最后记录的鼠标屏幕坐标 */
    private double trackedMouseX, trackedMouseY;
    /** 打开 GUI 时从服务端加载的原始频道列表（用于区分"本 receiver 删除的"和"其他 receiver 占用的"） */
    private final Set<Integer> originalChannels = new HashSet<>();

    // ════════════════ 加载模式 ════════════════
    private int loadMode = 0;
    private boolean onPhysicsBody = false;
    /** 加载模式选择器窗口相对 Y */
    private static final int LOAD_MODE_Y = WIN_HEIGHT - 12;

    public RedstoneTransceiverScreen(RedstoneTransceiverMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = IMAGE_HEIGHT;
        this.inventoryLabelY = INV_LABEL_Y;
        this.titleLabelY = 3;
    }

    @Override
    protected void init() {
        super.init();
        // 从服务端发来的菜单 extraData 恢复 banner 数据
        CompoundTag data = menu.getBannerData();
        if (data != null && !data.isEmpty()) {
            deserializeBannerData(data);
        }
        this.loadMode = menu.getLoadMode();
        this.onPhysicsBody = menu.isOnPhysicsBody();
    }

    @Override
    public void onClose() {
        // 序列化当前 banner 数据并发送到服务端
        if (this.minecraft != null && this.minecraft.level != null) {
            CompoundTag data = serializeBannerData();
            PacketDistributor.sendToServer(new ReceiverSyncPayload(menu.getReceiverPos(), data, loadMode));
        }
        super.onClose();
    }

    /** 将 banner 列表序列化为 CompoundTag */
    private CompoundTag serializeBannerData() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Count", bannerChannels.size());
        ListTag channels = new ListTag();
        for (int ch : bannerChannels) {
            channels.add(net.minecraft.nbt.IntTag.valueOf(ch));
        }
        tag.put("Channels", channels);

        ListTag ghosts = new ListTag();
        if (this.minecraft != null && this.minecraft.level != null) {
            for (int i = 0; i < bannerChannels.size(); i++) {
                CompoundTag pair = new CompoundTag();
                if (!ghostItem0.get(i).isEmpty()) {
                    pair.put("G0", ghostItem0.get(i).save(this.minecraft.level.registryAccess()));
                }
                if (!ghostItem1.get(i).isEmpty()) {
                    pair.put("G1", ghostItem1.get(i).save(this.minecraft.level.registryAccess()));
                }
                ghosts.add(pair);
            }
        }
        tag.put("Ghosts", ghosts);
        return tag;
    }

    /** 从 CompoundTag 反序列化恢复 banner 列表 */
    private void deserializeBannerData(CompoundTag tag) {
        bannerChannels.clear();
        ghostItem0.clear();
        ghostItem1.clear();
        originalChannels.clear();

        ListTag channels = tag.getList("Channels", Tag.TAG_INT);
        for (int i = 0; i < channels.size(); i++) {
            int ch = ((net.minecraft.nbt.IntTag) channels.get(i)).getAsInt();
            bannerChannels.add(ch);
            originalChannels.add(ch);
        }

        ListTag ghosts = tag.getList("Ghosts", Tag.TAG_COMPOUND);
        for (int i = 0; i < ghosts.size(); i++) {
            CompoundTag pair = ghosts.getCompound(i);
            if (this.minecraft != null && this.minecraft.level != null) {
                ghostItem0.add(pair.contains("G0")
                        ? ItemStack.parse(this.minecraft.level.registryAccess(), pair.getCompound("G0")).orElse(ItemStack.EMPTY)
                        : ItemStack.EMPTY);
                ghostItem1.add(pair.contains("G1")
                        ? ItemStack.parse(this.minecraft.level.registryAccess(), pair.getCompound("G1")).orElse(ItemStack.EMPTY)
                        : ItemStack.EMPTY);
            } else {
                ghostItem0.add(ItemStack.EMPTY);
                ghostItem1.add(ItemStack.EMPTY);
            }
        }
    }

    // ════════════════════════════════════════════════
    //  渲染
    // ════════════════════════════════════════════════

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

        // ── 玩家物品栏（Create 背包贴图）──
        int backpackX = x + (WIN_W - BACKPACK_W) / 2;
        int backpackY = y + WIN_HEIGHT + INV_GAP;
        g.blit(PLAYER_INV_TEXTURE, backpackX, backpackY, 0, 0,
                BACKPACK_W, BACKPACK_H, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题居中
        int titleWidth = this.font.width(this.title);
        int titleX = WIN_X + (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, 3, 0xFFFFFFFF, false);

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
        int channelZoneScreenX1 = this.leftPos + CHANNEL_ZONE_X;
        int channelZoneScreenX2 = this.leftPos + CHANNEL_ZONE_X + CHANNEL_ZONE_W;
        int bannerY = CONTENT_TOP - scrollOffset;
        hoveredChannelBanner = -1; // 每帧重置
        hoveredDeleteBanner = -1;
        hoveredGhostBanner = -1;
        hoveredGhostSlot = -1;

        for (int i = 0; i < bannerChannels.size(); i++) {
            g.blit(GUI_TEXTURE, 0, bannerY, BANNER_U, BANNER_V, BANNER_W, BANNER_H, 256, 256);

            // ── 频道号 ──
            int ch = bannerChannels.get(i);
            g.drawString(this.font, String.valueOf(ch),
                    CHANNEL_ZONE_X + CHANNEL_TEXT_X_OFFSET, bannerY + CHANNEL_TEXT_Y_OFFSET, 0xFCFCEB, true);

            int bannerScreenY = this.topPos + bannerY;

            // ── 幽灵物品槽 ──
            boolean hoverG0 = isGhostSlotHovered(mouseX, mouseY, bannerScreenY, GHOST_SLOT_X);
            boolean hoverG1 = isGhostSlotHovered(mouseX, mouseY, bannerScreenY, GHOST_SLOT_2_X);
            if (hoverG0) { hoveredGhostBanner = i; hoveredGhostSlot = 0; }
            if (hoverG1) { hoveredGhostBanner = i; hoveredGhostSlot = 1; }
            renderGhostSlot(g, i, 0, GHOST_SLOT_X, bannerY + GHOST_SLOT_Y_OFFSET, hoverG0);
            renderGhostSlot(g, i, 1, GHOST_SLOT_2_X, bannerY + GHOST_SLOT_Y_OFFSET, hoverG1);

            // 检测鼠标是否在频道区
            if (mouseX >= channelZoneScreenX1 && mouseX <= channelZoneScreenX2
                    && mouseY >= bannerScreenY + 4 && mouseY < bannerScreenY + BANNER_H - 5) {
                hoveredChannelBanner = i;
            }
            // 检测鼠标是否在删除区
            if (mouseX >= deleteZoneScreenX1 && mouseX <= deleteZoneScreenX2
                    && mouseY >= bannerScreenY && mouseY < bannerScreenY + BANNER_H) {
                hoveredDeleteBanner = i;
            }

            // 长按进度条
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

        // ── 玩家物品栏标签 ──
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX + 3, this.inventoryLabelY, 0xFF3C3B47, false);

        // ── 加载模式 ──
        LoadModeHelper.renderLabel(g, this.font, WIN_W - LoadModeHelper.HIT_W, LOAD_MODE_Y, loadMode, onPhysicsBody);
    }

    /** 计算内容总高度（banner + 按钮）。渲染循环中每个 banner 后都加了 BANNER_H+BANNER_GAP，
     *  按钮紧接在最后一个 banner 的 BANNER_GAP 之后，因此总高 = n*(H+G) + BTN_H */
    private int getTotalContentHeight() {
        return bannerChannels.size() * (BANNER_H + BANNER_GAP) + BTN_H;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);

        // 加载模式 tooltip
        LoadModeHelper.renderTooltip(g, this.font,
                this.leftPos + WIN_W - LoadModeHelper.HIT_W,
                this.topPos + LOAD_MODE_Y,
                loadMode, onPhysicsBody, mouseX, mouseY);

        // 频道区悬浮提示
        if (hoveredChannelBanner >= 0) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.ccpe.peripheral_extender_channel")
                    .withStyle(Style.EMPTY.withColor(0x528FDE)));
            lines.add(Component.translatable("gui.ccpe.scroll_to_change")
                    .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
            lines.add(Component.translatable("gui.ccpe.shift_scroll_faster")
                    .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
            g.renderComponentTooltip(this.font, lines, (int) trackedMouseX, (int) trackedMouseY);
        }

        // 删除区悬停提示
        if (hoveredDeleteBanner >= 0) {
            g.renderComponentTooltip(this.font, List.of(DELETE_HINT), (int) trackedMouseX, (int) trackedMouseY);
        }
    }

    // ════════════════════════════════════════════════
    //  滚动
    // ════════════════════════════════════════════════

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        // 0) 加载模式区域
        int newMode = LoadModeHelper.handleScroll(
                this.leftPos + WIN_W - LoadModeHelper.HIT_W,
                this.topPos + LOAD_MODE_Y,
                loadMode, onPhysicsBody, mouseX, mouseY, scrollY);
        if (newMode >= 0) {
            loadMode = newMode;
            playClickSound();
            return true;
        }

        // 1) 频道区滚轮：调整该 banner 的频道号
        int chIdx = getChannelBannerAt(mouseX, mouseY);
        if (chIdx >= 0) {
            int dir = scrollY > 0 ? 1 : -1;
            int step = hasShiftDown() ? 10 : 1;
            int oldVal = bannerChannels.get(chIdx);
            int newVal = ChannelScrollHelper.next(oldVal, dir, step, oldVal, getAllOccupiedChannels());
            if (newVal != oldVal) {
                bannerChannels.set(chIdx, newVal);
                playClickSound();
            }
            return true;
        }

        // 2) 内容区滚轮：滚动列表
        int scrollLeft = this.leftPos + WIN_X + 4;
        int scrollRight = this.leftPos + WIN_X + WIN_W - 4;
        int scrollTop = this.topPos + CONTENT_TOP;
        int scrollBottom = this.topPos + CONTENT_BOTTOM;
        if (mouseX >= scrollLeft && mouseX <= scrollRight
                && mouseY >= scrollTop && mouseY <= scrollBottom) {
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

    // ════════════════════════════════════════════════
    //  交互
    // ════════════════════════════════════════════════

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
                bannerChannels.add(findFreeChannel());
                ghostItem0.add(ItemStack.EMPTY);
                ghostItem1.add(ItemStack.EMPTY);
                clampScroll();
                playClickSound();
                return true;
            }
            // 幽灵槽左键：持物则放入，空手则清空
            if (handleGhostSlotClick(mouseX, mouseY, false)) {
                return true;
            }
            // 删除区：开始长按计时
            int delIdx = getDeleteBannerAt(mouseX, mouseY);
            if (delIdx >= 0) {
                deleteHoldBanner = delIdx;
                deleteHoldTimer = 0;
                return true;
            }
        }
        if (button == 1) {
            // 幽灵槽右键：清空
            if (handleGhostSlotClick(mouseX, mouseY, true)) {
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

                // 每 3 tick 播放一次渐进音效
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
                // 鼠标移出删除区，取消
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
        return CONTENT_TOP + bannerChannels.size() * (BANNER_H + BANNER_GAP);
    }

    /** 返回鼠标所在 banner 的删除区索引（考虑滚动偏移），-1=不在任何删除区 */
    private int getDeleteBannerAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;
        int deleteZoneX1 = BANNER_W - 32;
        int deleteZoneX2 = BANNER_W - 16;
        if (relX < deleteZoneX1 || relX > deleteZoneX2) return -1;

        return getBannerAtY(relY);
    }

    /** 返回鼠标所在 banner 的频道区索引，-1=不在任何频道区 */
    private int getChannelBannerAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;
        if (relX < CHANNEL_ZONE_X || relX > CHANNEL_ZONE_X + CHANNEL_ZONE_W) return -1;

        return getBannerAtY(relY);
    }

    /** 从 Y 坐标查找 banner 索引（相对坐标，考虑滚动偏移） */
    private int getBannerAtY(int relY) {
        int bannerY = CONTENT_TOP - scrollOffset;
        for (int i = 0; i < bannerChannels.size(); i++) {
            if (relY >= bannerY && relY < bannerY + BANNER_H) return i;
            bannerY += BANNER_H + BANNER_GAP;
        }
        return -1;
    }

    /** 删除指定索引的 banner，重新钳位滚动 */
    private void removeBanner(int index) {
        bannerChannels.remove(index);
        ghostItem0.remove(index);
        ghostItem1.remove(index);
        clampScroll();
        playClickSound();
    }

    /** 合并菜单快照和本地频道列表。
     *  快照中的频道如果在 originalChannels（本 receiver 打开时的频道）但不在本地列表中，
     *  说明已被本 receiver 删除，不计入占用。其余快照频道属于其他 receiver，计入。 */
    private int[] getAllOccupiedChannels() {
        int[] fromMenu = menu.getOccupiedChannels();
        Set<Integer> set = new HashSet<>();
        for (int ch : fromMenu) {
            // 是本 receiver 原有的但已被删除，跳过（已释放）
            if (originalChannels.contains(ch) && !bannerChannels.contains(ch)) continue;
            set.add(ch);
        }
        // 本地当前频道始终计入
        for (int ch : bannerChannels) set.add(ch);
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 找到最小的未被占用的频道号（菜单快照 + 本地 banner 频道合并） */
    private int findFreeChannel() {
        return ChannelScrollHelper.findFree(getAllOccupiedChannels());
    }

    // ── 幽灵物品槽 ──

    /** 检测幽灵槽是否被鼠标悬停 */
    private boolean isGhostSlotHovered(int mouseX, int mouseY, int bannerScreenY, int slotX) {
        return mouseX >= this.leftPos + slotX && mouseX < this.leftPos + slotX + 16
                && mouseY >= bannerScreenY + GHOST_SLOT_Y_OFFSET
                && mouseY < bannerScreenY + GHOST_SLOT_Y_OFFSET + 16;
    }

    /** 渲染单个幽灵槽（默认全透明，hover 时半透明背景） */
    private void renderGhostSlot(GuiGraphics g, int bannerIdx, int slot, int x, int y, boolean hovered) {
        ItemStack stack = slot == 0 ? ghostItem0.get(bannerIdx) : ghostItem1.get(bannerIdx);
        // hover 背景
        if (hovered) {
            g.fill(x, y, x + 16, y + 16, 0x40AAAAAA);
        }
        if (!stack.isEmpty()) {
            g.renderItem(stack, x, y);
            g.renderItemDecorations(this.font, stack, x, y);
        }
    }

    /** 处理幽灵槽点击：左键空手/右键 → 清空；左键持物 → 放入副本 */
    private boolean handleGhostSlotClick(double mouseX, double mouseY, boolean rightClick) {
        int[] info = getGhostSlotAt(mouseX, mouseY);
        if (info == null) return false;
        int bannerIdx = info[0];
        int slot = info[1];

        if (this.minecraft == null || this.minecraft.player == null) return false;
        ItemStack carried = this.minecraft.player.containerMenu.getCarried();
        if (rightClick || carried.isEmpty()) {
            setGhostItem(bannerIdx, slot, ItemStack.EMPTY);
        } else {
            ItemStack copy = carried.copy();
            copy.setCount(1);
            setGhostItem(bannerIdx, slot, copy);
        }
        playClickSound();
        return true;
    }

    /** 返回鼠标所在的幽灵槽 [bannerIdx, slot]，null=不在任何幽灵槽 */
    private int[] getGhostSlotAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;

        int bannerY = CONTENT_TOP - scrollOffset;
        for (int i = 0; i < bannerChannels.size(); i++) {
            int slotY = bannerY + GHOST_SLOT_Y_OFFSET;
            if (relY >= slotY && relY < slotY + 16) {
                if (relX >= GHOST_SLOT_X && relX < GHOST_SLOT_X + 16) return new int[]{i, 0};
                if (relX >= GHOST_SLOT_2_X && relX < GHOST_SLOT_2_X + 16) return new int[]{i, 1};
            }
            bannerY += BANNER_H + BANNER_GAP;
        }
        return null;
    }

    /** 供 JEI 拖放调用：设置幽灵槽物品 */
    public void updateGhostSlot(int bannerIdx, int slot, ItemStack stack) {
        setGhostItem(bannerIdx, slot, stack);
    }

    /** 供 JEI 获取幽灵槽区域 */
    public Rect2i getGhostSlotBounds(int bannerIdx, int slot) {
        int bannerY = CONTENT_TOP - scrollOffset + bannerIdx * (BANNER_H + BANNER_GAP);
        int sx = this.leftPos + (slot == 0 ? GHOST_SLOT_X : GHOST_SLOT_2_X);
        int sy = this.topPos + bannerY + GHOST_SLOT_Y_OFFSET;
        return new Rect2i(sx, sy, 16, 16);
    }

    /** 供 JEI 获取当前 banner 数 */
    public int getBannerCount() { return bannerChannels.size(); }

    private void setGhostItem(int bannerIdx, int slot, ItemStack stack) {
        if (slot == 0) ghostItem0.set(bannerIdx, stack);
        else ghostItem1.set(bannerIdx, stack);
    }

    // ── 音效 ──

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
