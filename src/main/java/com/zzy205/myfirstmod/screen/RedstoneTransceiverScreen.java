package com.zzy205.myfirstmod.screen;

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
 * Receiver 鍙抽敭鑿滃崟灞忓箷 鈥旓拷?banner 闃熷垪 + 娣诲姞鎸夐挳 + 瑁佸壀婊氬姩 + 闀挎寜鍒犻櫎锟?
 */
public class RedstoneTransceiverScreen extends AbstractContainerScreen<RedstoneTransceiverMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "textures/gui/test_gui.png");

    /** Create 妯＄粍鎻愪緵鐨勭帺瀹惰儗鍖呰创锟?*/
    private static final ResourceLocation PLAYER_INV_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/player_inventory.png");
    private static final int BACKPACK_W = 175;
    private static final int BACKPACK_H = 108;

    // 绐楀彛甯冨眬甯搁噺
    private static final int WIN_X = 0;
    private static final int WIN_W = 192;
    private static final int WIN_HEIGHT = 160;

    // 涔濆鏍肩汗鐞嗗潗锟?
    private static final int TEX_TOP_Y = 48;
    private static final int TEX_TOP_H = 16;
    private static final int TEX_MID_Y = 64;
    private static final int TEX_MID_H = 16;
    private static final int TEX_BOT_Y = 80;
    private static final int TEX_BOT_H = 16;

    // 鈹€鈹€ Banner 绾圭悊 鈹€鈹€
    private static final int BANNER_U = 0;
    private static final int BANNER_V = 128;
    private static final int BANNER_W = WIN_W;
    private static final int BANNER_H = 29;

    // 鈹€鈹€ 娣诲姞鎸夐挳绾圭悊 鈹€鈹€
    private static final int BTN_U = 0;
    private static final int BTN_V = 160;
    private static final int BTN_W = 33;
    private static final int BTN_H = 18;

    // 鈹€鈹€ 甯冨眬 鈹€鈹€
    /** 鍐呭鍖洪《閮紙绐楀彛鐩稿鍧愭爣锟?*/
    private static final int CONTENT_TOP = TEX_TOP_H + 4;
    /** 鍐呭鍖哄簳閮紙绐楀彛鐩稿鍧愭爣锟?*/
    private static final int CONTENT_BOTTOM = WIN_HEIGHT - TEX_BOT_H - 4;
    /** 鍐呭鍖哄彲瑙嗛珮锟?*/
    private static final int CONTENT_VISIBLE_H = CONTENT_BOTTOM - CONTENT_TOP;
    /** banner 涔嬮棿鐨勯棿锟?*/
    private static final int BANNER_GAP = 2;

    // 鈹€鈹€ 鐜╁鐗╁搧锟?鈹€鈹€
    /** 绐楀彛涓庣墿鍝佹爮闂磋窛 */
    private static final int INV_GAP = 5;
    /** 鐗╁搧鏍忔爣锟?Y锛堢獥鍙ｇ浉瀵瑰潗鏍囷級 */
    private static final int INV_LABEL_Y = WIN_HEIGHT + INV_GAP + 3;
    /** 鐗╁搧鏍忓尯鍩熼珮锟?*/
    private static final int INV_AREA_H = 100;
    /** 鏁翠綋 GUI 楂樺害 */
    private static final int IMAGE_HEIGHT = WIN_HEIGHT + INV_GAP + INV_AREA_H;

    /** 婊氬姩锟?*/
    private static final int SCROLLBAR_X = WIN_X + WIN_W - 6;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_MIN_THUMB = 8;

    // 鈹€鈹€ 鍒犻櫎鍖哄煙 鈹€鈹€
    /** banner 鍙充晶鐨勫垹闄よЕ鍙戝尯瀹藉害 */
    private static final int DELETE_ZONE_W = 16;
    /** 闀挎寜鍒犻櫎闃堝€硷紙tick锟?0=1绉掞級 */
    private static final int DELETE_HOLD_TICKS = 20;
    private static final Component DELETE_HINT =
            Component.translatable("gui.ccnavigationtable.redstone_transceiver.hold_to_delete");

    // 鈹€鈹€ 棰戦亾閫夋嫨 鈹€鈹€
    /** 棰戦亾鏁板瓧鍖哄煙 X锛堢獥鍙ｇ浉瀵瑰潗鏍囷紝banner 宸︿晶锟?*/
    private static final int CHANNEL_ZONE_X = 42;
    /** 棰戦亾鏁板瓧鍖哄煙瀹藉害 */
    private static final int CHANNEL_ZONE_W = 34;
    /** 棰戦亾鏂囧瓧锟?banner 鍐呯殑 Y 鍋忕Щ */
    private static final int CHANNEL_TEXT_Y_OFFSET = 10;
    /** 棰戦亾鏂囧瓧 X 鍋忕Щ锛堢浉瀵逛簬 CHANNEL_ZONE_X锟?*/
    private static final int CHANNEL_TEXT_X_OFFSET = 5;
    /** 棰戦亾鍙疯寖锟?*/
    private static final int CHANNEL_MIN = 0;
    private static final int CHANNEL_MAX = 9999;

    // 鈹€鈹€ 骞界伒鐗╁搧锟?鈹€鈹€
    /** 骞界伒锟?0 锟?banner 鍐呯殑 X锛堝彸渚х涓€鏍硷級 */
    private static final int GHOST_SLOT_X = BANNER_W - 91;
    /** 骞界伒锟?1 锟?banner 鍐呯殑 X锛堝彸渚х浜屾牸锟?*/
    private static final int GHOST_SLOT_2_X = GHOST_SLOT_X + 18 + 3;
    /** 骞界伒妲藉湪 banner 鍐呯殑 Y 鍋忕Щ */
    private static final int GHOST_SLOT_Y_OFFSET = 6;

    // 鈹€鈹€ 鐘讹拷?鈹€鈹€
    /** 姣忎釜 banner 鐨勯閬撳彿鍒楄〃 */
    private final List<Integer> bannerChannels = new ArrayList<>();
    /** 姣忎釜 banner 鐨勫菇鐏电墿鍝佹Ы 0 */
    private final List<ItemStack> ghostItem0 = new ArrayList<>();
    /** 姣忎釜 banner 鐨勫菇鐏电墿鍝佹Ы 1 */
    private final List<ItemStack> ghostItem1 = new ArrayList<>();
    /** 婊氬姩鍋忕Щ锛堝儚绱狅紝>=0锛岃秺澶у唴瀹硅秺寰€涓婃粴锟?*/
    private int scrollOffset = 0;

    /** 褰撳墠榧犳爣鎮仠锟?banner 棰戦亾鍖虹储寮曪紙-1=鏃狅級 */
    private int hoveredChannelBanner = -1;
    /** 褰撳墠榧犳爣鎮仠锟?banner 鍒犻櫎鍖虹储寮曪紙-1=鏃狅級 */
    private int hoveredDeleteBanner = -1;
    /** 褰撳墠榧犳爣鎮仠鐨勫菇鐏垫Ы [banner, slot]锟?1=锟?*/
    private int hoveredGhostBanner = -1;
    private int hoveredGhostSlot = -1;
    /** 姝ｅ湪闀挎寜鍒犻櫎锟?banner 绱㈠紩锟?1=鏃狅級 */
    private int deleteHoldBanner = -1;
    /** 闀挎寜璁℃椂鍣紙tick锟?*/
    private int deleteHoldTimer = 0;
    /** 鏈€鍚庤褰曠殑榧犳爣灞忓箷鍧愭爣 */
    private double trackedMouseX, trackedMouseY;
    /** 鎵撳紑 GUI 鏃朵粠鏈嶅姟绔姞杞界殑鍘熷棰戦亾鍒楄〃锛堢敤浜庡尯锟?锟?receiver 鍒犻櫎锟?锟?鍏朵粬 receiver 鍗犵敤锟?锟?*/
    private final Set<Integer> originalChannels = new HashSet<>();

    // 鈺愨晲锟?鍔犺浇妯″紡 鈺愨晲锟?
    private int loadMode = 0;
    private boolean onPhysicsBody = false;
    /** 鍔犺浇妯″紡閫夋嫨鍣ㄧ獥鍙ｇ浉锟?Y */
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
        // 浠庢湇鍔＄鍙戞潵鐨勮彍锟?extraData 鎭㈠ banner 鏁版嵁
        CompoundTag data = menu.getBannerData();
        if (data != null && !data.isEmpty()) {
            deserializeBannerData(data);
        }
        this.loadMode = menu.getLoadMode();
        this.onPhysicsBody = menu.isOnPhysicsBody();
    }

    @Override
    public void onClose() {
        // 搴忓垪鍖栧綋锟?banner 鏁版嵁骞跺彂閫佸埌鏈嶅姟锟?
        CompoundTag data = serializeBannerData();
        PacketDistributor.sendToServer(new ReceiverSyncPayload(menu.getReceiverPos(), data, loadMode));
        super.onClose();
    }

    /** 锟?banner 鍒楄〃搴忓垪鍖栦负 CompoundTag */
    private CompoundTag serializeBannerData() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Count", bannerChannels.size());
        ListTag channels = new ListTag();
        for (int ch : bannerChannels) {
            channels.add(net.minecraft.nbt.IntTag.valueOf(ch));
        }
        tag.put("Channels", channels);

        ListTag ghosts = new ListTag();
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
        tag.put("Ghosts", ghosts);
        return tag;
    }

    /** 锟?CompoundTag 鍙嶅簭鍒楀寲鎭㈠ banner 鍒楄〃 */
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
            ghostItem0.add(pair.contains("G0")
                    ? ItemStack.parse(this.minecraft.level.registryAccess(), pair.getCompound("G0")).orElse(ItemStack.EMPTY)
                    : ItemStack.EMPTY);
            ghostItem1.add(pair.contains("G1")
                    ? ItemStack.parse(this.minecraft.level.registryAccess(), pair.getCompound("G1")).orElse(ItemStack.EMPTY)
                    : ItemStack.EMPTY);
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?
    //  娓叉煋
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 涔濆鏍肩獥鍙ｈ儗锟?
        g.blit(GUI_TEXTURE, x, y, 0, TEX_TOP_Y, WIN_W, TEX_TOP_H, 256, 256);

        int midY = y + TEX_TOP_H;
        int midEnd = y + WIN_HEIGHT - TEX_BOT_H;
        while (midY < midEnd) {
            g.blit(GUI_TEXTURE, x, midY, 0, TEX_MID_Y, WIN_W, TEX_MID_H, 256, 256);
            midY += TEX_MID_H;
        }

        g.blit(GUI_TEXTURE, x, y + WIN_HEIGHT - TEX_BOT_H, 0, TEX_BOT_Y, WIN_W, TEX_BOT_H, 256, 256);

        // 鈹€鈹€ 鍐呭鍖洪€忔槑鑳屾櫙 鈹€鈹€
        g.fill(x + 4, y + CONTENT_TOP, x + WIN_W - 4, y + CONTENT_BOTTOM, 0x18000000);

        // 鈹€鈹€ 鐜╁鐗╁搧鏍忥紙Create 鑳屽寘璐村浘锟?鈹€鈹€
        int backpackX = x + (WIN_W - BACKPACK_W) / 2;
        int backpackY = y + WIN_HEIGHT + INV_GAP;
        g.blit(PLAYER_INV_TEXTURE, backpackX, backpackY, 0, 0,
                BACKPACK_W, BACKPACK_H, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 鏍囬灞呬腑
        int titleWidth = this.font.width(this.title);
        int titleX = WIN_X + (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, 3, 0xFFFFFFFF, false);

        // 鈹€鈹€ Scissor 瑁佸壀鍐呭锟?鈹€鈹€
        g.enableScissor(
                this.leftPos + 4,
                this.topPos + CONTENT_TOP,
                this.leftPos + WIN_W - 4,
                this.topPos + CONTENT_BOTTOM
        );

        // 鈹€鈹€ 缁樺埗 banner 闃熷垪锛堟粴鍔ㄥ亸绉伙級 鈹€鈹€
        int deleteZoneScreenX1 = this.leftPos + BANNER_W - 32;
        int deleteZoneScreenX2 = this.leftPos + BANNER_W - 16;
        int channelZoneScreenX1 = this.leftPos + CHANNEL_ZONE_X;
        int channelZoneScreenX2 = this.leftPos + CHANNEL_ZONE_X + CHANNEL_ZONE_W;
        int bannerY = CONTENT_TOP - scrollOffset;
        hoveredChannelBanner = -1; // 姣忓抚閲嶇疆
        hoveredDeleteBanner = -1;
        hoveredGhostBanner = -1;
        hoveredGhostSlot = -1;

        for (int i = 0; i < bannerChannels.size(); i++) {
            g.blit(GUI_TEXTURE, 0, bannerY, BANNER_U, BANNER_V, BANNER_W, BANNER_H, 256, 256);

            // 鈹€鈹€ 棰戦亾锟?鈹€鈹€
            int ch = bannerChannels.get(i);
            g.drawString(this.font, String.valueOf(ch),
                    CHANNEL_ZONE_X + CHANNEL_TEXT_X_OFFSET, bannerY + CHANNEL_TEXT_Y_OFFSET, 0xFCFCEB, true);

            int bannerScreenY = this.topPos + bannerY;

            // 鈹€鈹€ 骞界伒鐗╁搧锟?鈹€鈹€
            boolean hoverG0 = isGhostSlotHovered(mouseX, mouseY, bannerScreenY, GHOST_SLOT_X);
            boolean hoverG1 = isGhostSlotHovered(mouseX, mouseY, bannerScreenY, GHOST_SLOT_2_X);
            if (hoverG0) { hoveredGhostBanner = i; hoveredGhostSlot = 0; }
            if (hoverG1) { hoveredGhostBanner = i; hoveredGhostSlot = 1; }
            renderGhostSlot(g, i, 0, GHOST_SLOT_X, bannerY + GHOST_SLOT_Y_OFFSET, hoverG0);
            renderGhostSlot(g, i, 1, GHOST_SLOT_2_X, bannerY + GHOST_SLOT_Y_OFFSET, hoverG1);

            // 妫€娴嬮紶鏍囨槸鍚﹀湪棰戦亾锟?
            if (mouseX >= channelZoneScreenX1 && mouseX <= channelZoneScreenX2
                    && mouseY >= bannerScreenY + 4 && mouseY < bannerScreenY + BANNER_H - 5) {
                hoveredChannelBanner = i;
            }
            // 妫€娴嬮紶鏍囨槸鍚﹀湪鍒犻櫎锟?
            if (mouseX >= deleteZoneScreenX1 && mouseX <= deleteZoneScreenX2
                    && mouseY >= bannerScreenY && mouseY < bannerScreenY + BANNER_H) {
                hoveredDeleteBanner = i;
            }

            // 闀挎寜杩涘害锟?
            if (i == deleteHoldBanner) {
                float progress = (float) deleteHoldTimer / DELETE_HOLD_TICKS;
                int filledH = (int) (BANNER_H * progress);
                int fillY = bannerY + BANNER_H - filledH;
                g.fill(BANNER_W - 32, fillY, BANNER_W - 16, bannerY + BANNER_H, 0x60FF0000);
            }

            bannerY += BANNER_H + BANNER_GAP;
        }

        // 鈹€鈹€ 娣诲姞鎸夐挳锛堝缁堝湪闃熷垪鏈熬锛屼篃闅忔粴鍔級 鈹€鈹€
        g.blit(GUI_TEXTURE, 0, bannerY, BTN_U, BTN_V, BTN_W, BTN_H, 256, 256);

        g.disableScissor();

        // 鈹€鈹€ 婊氬姩锟?鈹€鈹€
        int totalH = getTotalContentHeight();
        renderScrollbar(g, totalH);

        // 鈹€鈹€ 鐜╁鐗╁搧鏍忔爣锟?鈹€鈹€
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX + 3, this.inventoryLabelY, 0xFF3C3B47, false);

        // 鈹€鈹€ 鍔犺浇妯″紡 鈹€鈹€
        LoadModeHelper.renderLabel(g, this.font, WIN_W - LoadModeHelper.HIT_W, LOAD_MODE_Y, loadMode, onPhysicsBody);
    }

    /** 璁＄畻鍐呭鎬婚珮搴︼紙banner + 鎸夐挳锛夈€傛覆鏌撳惊鐜腑姣忎釜 banner 鍚庨兘鍔犱簡 BANNER_H+BANNER_GAP锟?
     *  鎸夐挳绱ф帴鍦ㄦ渶鍚庝竴锟?banner 锟?BANNER_GAP 涔嬪悗锛屽洜姝ゆ€婚珮 = n*(H+G) + BTN_H */
    private int getTotalContentHeight() {
        return bannerChannels.size() * (BANNER_H + BANNER_GAP) + BTN_H;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);

        // 鍔犺浇妯″紡 tooltip
        LoadModeHelper.renderTooltip(g, this.font,
                this.leftPos + WIN_W - LoadModeHelper.HIT_W,
                this.topPos + LOAD_MODE_Y,
                loadMode, onPhysicsBody, mouseX, mouseY);

        // 棰戦亾鍖烘偓鍋滄彁锟?
        if (hoveredChannelBanner >= 0) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.ccnavigationtable.sensor_channel")
                    .withStyle(Style.EMPTY.withColor(0x528FDE)));
            lines.add(Component.translatable("gui.ccnavigationtable.scroll_to_change")
                    .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
            lines.add(Component.translatable("gui.ccnavigationtable.shift_scroll_faster")
                    .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
            g.renderComponentTooltip(this.font, lines, (int) trackedMouseX, (int) trackedMouseY);
        }

        // 鍒犻櫎鍖烘偓鍋滄彁锟?
        if (hoveredDeleteBanner >= 0) {
            g.renderComponentTooltip(this.font, List.of(DELETE_HINT), (int) trackedMouseX, (int) trackedMouseY);
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?
    //  婊氬姩
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        // 0) 鍔犺浇妯″紡鍖哄煙
        int newMode = LoadModeHelper.handleScroll(
                this.leftPos + WIN_W - LoadModeHelper.HIT_W,
                this.topPos + LOAD_MODE_Y,
                loadMode, onPhysicsBody, mouseX, mouseY, scrollY);
        if (newMode >= 0) {
            loadMode = newMode;
            playClickSound();
            return true;
        }

        // 1) 棰戦亾鍖烘粴锟?锟?璋冩暣锟?banner 鐨勯閬撳彿
        int chIdx = getChannelBannerAt(mouseX, mouseY);
        if (chIdx >= 0) {
            int dir = scrollY > 0 ? 1 : -1;
            int step = hasShiftDown() ? 10 : 1;
            int oldVal = bannerChannels.get(chIdx);
            int newVal = oldVal + dir * step;
            if (newVal < CHANNEL_MIN) newVal = CHANNEL_MIN;
            if (newVal > CHANNEL_MAX) newVal = CHANNEL_MAX;
            // 璺宠繃宸茶鍗犵敤鐨勯锟?
            newVal = skipOccupiedChannels(newVal, dir, oldVal);
            if (newVal < CHANNEL_MIN) newVal = CHANNEL_MIN;
            if (newVal > CHANNEL_MAX) newVal = CHANNEL_MAX;
            if (newVal != oldVal) {
                bannerChannels.set(chIdx, newVal);
                playClickSound();
            }
            return true;
        }

        // 2) 鍐呭鍖烘粴锟?锟?婊氬姩鍒楄〃
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

    /** 缁樺埗婊氬姩鏉★紙鍙充晶 4px 瀹斤級 */
    private void renderScrollbar(GuiGraphics g, int totalContentHeight) {
        if (totalContentHeight <= CONTENT_VISIBLE_H) return;

        int trackTop = CONTENT_TOP;
        int trackH = CONTENT_VISIBLE_H;

        // 杞ㄩ亾鑳屾櫙
        g.fill(SCROLLBAR_X, trackTop, SCROLLBAR_X + SCROLLBAR_W, trackTop + trackH, 0x20FFFFFF);

        // 婊戝潡
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * trackH / totalContentHeight);
        int maxScroll = totalContentHeight - CONTENT_VISIBLE_H;
        int thumbY = trackTop + (trackH - thumbH) * scrollOffset / Math.max(1, maxScroll);
        g.fill(SCROLLBAR_X, thumbY, SCROLLBAR_X + SCROLLBAR_W, thumbY + thumbH, 0x80AAAAAA);
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?
    //  浜や簰
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

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
            // 娣诲姞鎸夐挳
            if (isMouseOverAddButton(mouseX, mouseY)) {
                bannerChannels.add(findFreeChannel());
                ghostItem0.add(ItemStack.EMPTY);
                ghostItem1.add(ItemStack.EMPTY);
                clampScroll();
                playClickSound();
                return true;
            }
            // 骞界伒妲藉乏閿細鎸佺墿鍒欐斁鍏ワ紝绌烘墜鍒欐竻锟?
            if (handleGhostSlotClick(mouseX, mouseY, false)) {
                return true;
            }
            // 鍒犻櫎锟?锟?寮€濮嬮暱鎸夎锟?
            int delIdx = getDeleteBannerAt(mouseX, mouseY);
            if (delIdx >= 0) {
                deleteHoldBanner = delIdx;
                deleteHoldTimer = 0;
                return true;
            }
        }
        if (button == 1) {
            // 骞界伒妲藉彸閿細娓呯┖
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
        // 鏉炬墜鍗冲彇娑堥暱锟?
        deleteHoldBanner = -1;
        deleteHoldTimer = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        // 闀挎寜鍒犻櫎璁℃椂
        if (deleteHoldBanner >= 0) {
            int idx = getDeleteBannerAt(trackedMouseX, trackedMouseY);
            if (idx == deleteHoldBanner) {
                deleteHoldTimer++;

                // 锟?3 tick 鎾斁涓€娆℃笎杩涢煶锟?
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
                // 榧犳爣绉诲嚭鍒犻櫎锟?锟?鍙栨秷
                deleteHoldBanner = -1;
                deleteHoldTimer = 0;
            }
        }
    }

    /** 鍒ゆ柇榧犳爣鏄惁鎮仠鍦ㄦ坊鍔犳寜閽笂锛堣€冭檻婊氬姩鍋忕Щ锟?*/
    private boolean isMouseOverAddButton(double mouseX, double mouseY) {
        int btnScreenX = this.leftPos;
        int btnScreenY = this.topPos + getButtonY() - scrollOffset;
        return mouseX >= btnScreenX && mouseX < btnScreenX + BTN_W
                && mouseY >= btnScreenY && mouseY < btnScreenY + BTN_H;
    }

    /** 璁＄畻鎸夐挳锟?Y 鍧愭爣锛堢獥鍙ｇ浉瀵瑰潗鏍囷紝涓嶅惈婊氬姩鍋忕Щ锛夛拷?
     *  涓庢覆鏌撳惊鐜榻愶細buttonY = CONTENT_TOP + n*(BANNER_H + BANNER_GAP) */
    private int getButtonY() {
        return CONTENT_TOP + bannerChannels.size() * (BANNER_H + BANNER_GAP);
    }

    /** 杩斿洖榧犳爣鎵€锟?banner 鐨勫垹闄ゅ尯绱㈠紩锛堣€冭檻婊氬姩鍋忕Щ锛夛紝-1=涓嶅湪浠讳綍鍒犻櫎锟?*/
    private int getDeleteBannerAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;
        int deleteZoneX1 = BANNER_W - 32;
        int deleteZoneX2 = BANNER_W - 16;
        if (relX < deleteZoneX1 || relX > deleteZoneX2) return -1;

        return getBannerAtY(relY);
    }

    /** 杩斿洖榧犳爣鎵€锟?banner 鐨勯閬撳尯绱㈠紩锟?1=涓嶅湪浠讳綍棰戦亾锟?*/
    private int getChannelBannerAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;
        if (relX < CHANNEL_ZONE_X || relX > CHANNEL_ZONE_X + CHANNEL_ZONE_W) return -1;

        return getBannerAtY(relY);
    }

    /** 锟?Y 鍧愭爣鏌ユ壘 banner 绱㈠紩锛堢浉瀵瑰潗鏍囷紝鑰冭檻婊氬姩鍋忕Щ锟?*/
    private int getBannerAtY(int relY) {
        int bannerY = CONTENT_TOP - scrollOffset;
        for (int i = 0; i < bannerChannels.size(); i++) {
            if (relY >= bannerY && relY < bannerY + BANNER_H) return i;
            bannerY += BANNER_H + BANNER_GAP;
        }
        return -1;
    }

    /** 鍒犻櫎鎸囧畾绱㈠紩锟?banner锛岄噸鏂伴挸浣嶆粴锟?*/
    private void removeBanner(int index) {
        bannerChannels.remove(index);
        ghostItem0.remove(index);
        ghostItem1.remove(index);
        clampScroll();
        playClickSound();
    }

    /** 鍚堝苟鑿滃崟蹇収鍜屾湰鍦伴閬擄拷?
     *  蹇収涓殑棰戦亾濡傛灉锟?originalChannels锛堟湰 receiver 鎵撳紑鏃剁殑棰戦亾锛変絾涓嶅湪鏈湴鍒楄〃涓紝
     *  璇存槑宸茶锟?receiver 鍒犻櫎 锟?涓嶈鍏ュ崰鐢ㄣ€傚叾浣欏揩鐓ч閬撳睘浜庡叾锟?receiver 锟?璁″叆锟?*/
    private int[] getAllOccupiedChannels() {
        int[] fromMenu = menu.getOccupiedChannels();
        Set<Integer> set = new HashSet<>();
        for (int ch : fromMenu) {
            // 鏄湰 receiver 鍘熸湁鐨勪絾宸茶鍒犻櫎 锟?璺宠繃锛堝凡閲婃斁锟?
            if (originalChannels.contains(ch) && !bannerChannels.contains(ch)) continue;
            set.add(ch);
        }
        // 鏈湴褰撳墠棰戦亾濮嬬粓璁″叆
        for (int ch : bannerChannels) set.add(ch);
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 鎵惧埌鏈€灏忕殑鏈鍗犵敤鐨勯閬撳彿锛堣彍鍗曞揩锟?+ 鏈湴 banner 棰戦亾鍚堝苟锟?*/
    private int findFreeChannel() {
        int[] occupied = getAllOccupiedChannels();
        int ch = 0;
        while (true) {
            boolean blocked = false;
            for (int oc : occupied) { if (oc == ch) { blocked = true; break; } }
            if (!blocked) return ch;
            ch++;
        }
    }

    /** 璺宠繃宸茶鍏朵粬 receiver 鍗犵敤鐨勯閬擄紙鑿滃崟蹇収 + 鏈湴棰戦亾鍚堝苟锛屾帓闄よ嚜宸憋級锟?
     *  鑻ユ壘涓嶅埌鍙敤棰戦亾鍒欒繑鍥炲師鍊间笉鍙橈拷?*/
    private int skipOccupiedChannels(int value, int dir, int myChannel) {
        int[] occupied = getAllOccupiedChannels();
        int safety = 0;
        int candidate = value;
        while (safety < 10000) {
            boolean blocked = false;
            for (int ch : occupied) {
                if (ch == candidate && ch != myChannel) { blocked = true; break; }
            }
            if (!blocked) return candidate;
            candidate += dir;
            if (candidate < CHANNEL_MIN || candidate > CHANNEL_MAX) break;
            safety++;
        }
        // 鎵句笉鍒板彲鐢ㄩ锟?锟?淇濇寔鍘熼閬撲笉锟?
        return myChannel;
    }

    // 鈹€鈹€ 骞界伒鐗╁搧锟?鈹€鈹€

    /** 妫€娴嬪菇鐏垫Ы鏄惁琚紶鏍囨偓锟?*/
    private boolean isGhostSlotHovered(int mouseX, int mouseY, int bannerScreenY, int slotX) {
        return mouseX >= this.leftPos + slotX && mouseX < this.leftPos + slotX + 16
                && mouseY >= bannerScreenY + GHOST_SLOT_Y_OFFSET
                && mouseY < bannerScreenY + GHOST_SLOT_Y_OFFSET + 16;
    }

    /** 娓叉煋鍗曚釜骞界伒妲斤紙榛樿鍏ㄩ€忔槑锛宧over 鏃跺崐閫忔槑鑳屾櫙锟?*/
    private void renderGhostSlot(GuiGraphics g, int bannerIdx, int slot, int x, int y, boolean hovered) {
        ItemStack stack = slot == 0 ? ghostItem0.get(bannerIdx) : ghostItem1.get(bannerIdx);
        // hover 鑳屾櫙
        if (hovered) {
            g.fill(x, y, x + 16, y + 16, 0x40AAAAAA);
        }
        if (!stack.isEmpty()) {
            g.renderItem(stack, x, y);
            g.renderItemDecorations(this.font, stack, x, y);
        }
    }

    /** 澶勭悊骞界伒妲界偣鍑伙細宸﹂敭绌烘墜/鍙抽敭 锟?娓呯┖锛涘乏閿寔锟?锟?鏀惧叆鍓湰 */
    private boolean handleGhostSlotClick(double mouseX, double mouseY, boolean rightClick) {
        int[] info = getGhostSlotAt(mouseX, mouseY);
        if (info == null) return false;
        int bannerIdx = info[0];
        int slot = info[1];

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

    /** 杩斿洖榧犳爣鎵€鍦ㄧ殑骞界伒锟?[bannerIdx, slot]锛宯ull=涓嶅湪浠讳綍骞界伒锟?*/
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

    /** 锟?JEI 鎷栨斁璋冪敤锛氳缃菇鐏垫Ы鐗╁搧 */
    public void updateGhostSlot(int bannerIdx, int slot, ItemStack stack) {
        setGhostItem(bannerIdx, slot, stack);
    }

    /** 锟?JEI 鑾峰彇骞界伒妲藉尯锟?*/
    public Rect2i getGhostSlotBounds(int bannerIdx, int slot) {
        int bannerY = CONTENT_TOP - scrollOffset + bannerIdx * (BANNER_H + BANNER_GAP);
        int sx = this.leftPos + (slot == 0 ? GHOST_SLOT_X : GHOST_SLOT_2_X);
        int sy = this.topPos + bannerY + GHOST_SLOT_Y_OFFSET;
        return new Rect2i(sx, sy, 16, 16);
    }

    /** 锟?JEI 鑾峰彇褰撳墠 banner 锟?*/
    public int getBannerCount() { return bannerChannels.size(); }

    private void setGhostItem(int bannerIdx, int slot, ItemStack stack) {
        if (slot == 0) ghostItem0.set(bannerIdx, stack);
        else ghostItem1.set(bannerIdx, stack);
    }

    // 鈹€鈹€ 闊虫晥 鈹€鈹€

    private void playClickSound() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
        }
    }

    /** 闀挎寜杩涘害闊虫晥锛歂OTE_BLOCK_HAT锛岄煶璋冮殢杩涘害閫愭笎鍗囬珮 */
    private void playHoldTickSound(float pitch) {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.3f));
        }
    }
}
