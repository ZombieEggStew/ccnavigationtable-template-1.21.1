package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.PeripheralExtenderBlockEntity;
import com.zzy205.myfirstmod.network.SensorFilterPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PeripheralExtenderScreen extends AbstractContainerScreen<PeripheralExtenderMenu> {

    // 鈺愨晲锟?甯冨眬甯搁噺 鈺愨晲锟?
    private static final ResourceLocation NBT_WINDOW =
            ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "textures/gui/test_gui.png");

    // NBT 绐楀彛涔濆鏍煎弬锟?
    private static final int WIN_X      = 0;    // 绐楀彛璐寸敾甯冨乏杈圭晫
    private static final int WIN_W      = 256;  // 绐楀彛瀹藉害
    private static final int WIN_TOP    = 0;    // 绐楀彛璐寸敾甯冮《锟?
    private static final int WIN_BOTTOM = 192;  // 绐楀彛搴曢儴 = 鐢诲竷楂樺害
    private static final int WIN_HEIGHT = WIN_BOTTOM - WIN_TOP;  // 绐楀彛楂樺害
    private static final int TITLE_Y    = WIN_TOP + 3;  // 鏍囬 Y锛堢獥鍙ｅ唴锟?

    // 绾圭悊鍒囩墖鍧愭爣
    private static final int TEX_TOP_Y    = 0;
    private static final int TEX_TOP_H    = 16;

    private static final int TEX_MID_Y    = 16;
    private static final int TEX_MID_H    = 16;
    
    private static final int TEX_BOT_Y    = 32;
    private static final int TEX_BOT_H    = 16;

    private static final int TEX_BANNER_Y = 96;
    private static final int TEX_BANNER_H = 30;
    private static final int BANNER_Y_OFFSET =  TEX_TOP_H + 4;

    // NBT 鏂囨湰锛堢獥鍙ｅ唴閮級

    private static final int TEXT_BG_TOP_OFFSET = BANNER_Y_OFFSET + TEX_BANNER_H + 4;
    private static final int TEXT_START_X = WIN_X + 8;           // 宸﹁竟锟?8px
    private static final int TEXT_START_Y = TEXT_BG_TOP_OFFSET + 4;  // 椤惰竟妗嗕笅锟?2px

    private static final int LINE_HEIGHT  = 10;

    /** NBT 鏂囨湰鍖哄煙鍙楂樺害 */
    private static final int TEXT_BG_BOTTOM = WIN_HEIGHT - TEX_BOT_H - 4;
    private static final int TEXT_BG_HEIGHT = TEXT_BG_BOTTOM - TEXT_BG_TOP_OFFSET;

    /** 婊氬姩锟?*/
    private static final int SCROLLBAR_X = WIN_X + WIN_W - 6;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_MIN_THUMB = 8;

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    // 鈺愨晲锟?鍙姌锟?NBT 鏍戝舰瑙嗗浘 鈺愨晲锟?
    private List<NbtTreeNode> nbtRoots = new ArrayList<>();
    /** 璁板綍鐢ㄦ埛鎵嬪姩灞曞紑鐨勮矾寰勶紙涓嶅湪闆嗗悎锟?榛樿鏀惰捣锟?*/
    private final Set<String> expandedPaths = new HashSet<>();
    /** NBT 鏍戝舰瑙嗗浘婊氬姩鍋忕Щ锛堝儚绱狅紝璐燂拷?鍚戜笂婊氬姩锛屼笉浣庝簬0锟?*/
    private int nbtScrollOffset = 0;
    /** 涓婃娓叉煋鏃舵爲鐨勬€婚珮搴︼紙琛屾暟锛夛紝鐢ㄤ簬闄愬埗鍚戜笅婊氬姩 */
    private int nbtTotalLines = 0;
    private int tickCounter = 0;
    private int pollInterval;

    /** 澶嶅埗璺緞鎻愮ず娑堟伅锛岄潪绌烘椂鍦ㄧ獥鍙ｅ簳閮ㄦ樉绀猴紝鍊掕鏃剁粨鏉熷悗娓呯┖ */
    private String copiedMessage = null;
    private int copiedMessageTimer = 0;

    // 婊氳疆椹卞姩鏁帮拷?
    private int scrolledValue = 0;

    // 鈺愨晲锟?鍔犺浇妯″紡婊氬姩閫夋嫨 鈺愨晲锟?
    /** 0=鍏抽棴, 1=鍔犺浇鍖哄潡, 2=鍔犺浇鐗╃悊锟?*/
    private int loadMode = 0;
    private boolean onPhysicsBody = false;
    private static final int LOAD_MODE_Y = WIN_HEIGHT - 12;

    // 婊氳疆妫€娴嬪尯鍩燂紙X/瀹藉害鍚勮嚜涓嶅悓锛孻/楂樺害涓€鑷达級
    private static final int OVERLAY_Y_OFFSET = 24;          // 瑕嗙洊灞傚唴 Y 鍋忕Щ
    private static final int HIT_HEIGHT = 19;                 // 妫€娴嬪尯鍩熼珮锟?
    private static final int VALUE_HIT_X = WIN_X + 42;       // 鏁板€煎尯锟?X
    private static final int VALUE_HIT_W = 34;               // 鏁板€煎尯鍩熷锟?



    public PeripheralExtenderScreen(PeripheralExtenderMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_BOTTOM;
    }

    @Override
    protected void init() {
        super.init();
        this.pollInterval = Config.SENSOR_NBT_POLL_INTERVAL.get();

        // 鎭㈠涓婃淇濆瓨鐨勬暟鍊煎拰閫夐」
        // 浼樺厛浣跨敤鑿滃崟 extraData锛堝拰 GUI 鎵撳紑鍖呭悓涓€甯у埌杈撅紝淇濊瘉鏈€鏂帮級锟?
        // 瀹㈡埛锟?BE 鏁版嵁鍙兘鍥犲悓姝ュ欢杩熻€屾湭鏇存柊锛屼粎浣滀负 fallback
        int menuChannel = menu.getSensorChannel();
        if (menuChannel >= 0) {
            this.scrolledValue = menuChannel;
        } else if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                this.scrolledValue = sensorBE.getScrolledValue();
            }
        }

        formatNBTForDisplay();

        // 浠庤彍锟?extraData 璇诲彇鍔犺浇妯″紡锛堜笌鏈嶅姟绔墦寮€ GUI 鍚屼竴甯у埌杈撅級
        this.loadMode = menu.getLoadMode();
        this.onPhysicsBody = menu.isOnPhysicsBody();
    }


    @Override
    protected void containerTick() {
        super.containerTick();
        if (pollInterval <= 0) return;
        tickCounter++;
        if (tickCounter % pollInterval == 0) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
            }
        }
        // 澶嶅埗鎻愮ず鍊掕锟?
        if (copiedMessageTimer > 0) {
            copiedMessageTimer--;
            if (copiedMessageTimer == 0) copiedMessage = null;
        }
        formatNBTForDisplay();
    }

    private void formatNBTForDisplay() {
        nbtRoots.clear();
        CompoundTag nbt = getLiveNBT();
        if (nbt == null || nbt.isEmpty()) return;
        for (String key : nbt.getAllKeys()) {
            nbtRoots.add(buildTree(key, nbt.get(key), 0, key));
        }
    }

    /** 閫掑綊鏋勫缓 NBT 鏍戣妭鐐癸紝path 涓轰粠鏍瑰埌姝よ妭鐐圭殑瀹屾暣璺緞锛堢敤 / 杩炴帴锟?*/
    private NbtTreeNode buildTree(String key, Tag tag, int depth, String path) {
        NbtTreeNode node = new NbtTreeNode(key, tag, depth);
        // 鎭㈠灞曞紑鐘舵€侊細锟?expandedPaths 涓褰曠殑璺緞鎵嶅睍寮€
        if (expandedPaths.contains(path)) {
            node.expanded = true;
        }
        if (tag instanceof CompoundTag compound) {
            for (String childKey : compound.getAllKeys()) {
                node.children.add(buildTree(childKey, compound.get(childKey), depth + 1, path + "/" + childKey));
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                node.children.add(buildTree("[" + i + "]", list.get(i), depth + 1, path + "/[" + i + "]"));
            }
        }
        return node;
    }

    private CompoundTag getLiveNBT() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                CompoundTag live = sensorBE.getCachedAttachedNBT();
                if (live != null && !live.isEmpty()) return live;
            }
        }
        return menu.getAttachedNBT();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        renderNbtWindow(g, x, y);

        // NBT 鏂囨湰鍖哄煙閫忔槑鑳屾櫙
        int textTop = y + TEXT_BG_TOP_OFFSET;
        int textBottom = y + TEXT_BG_BOTTOM;
        g.fill(x + 4, textTop, x + WIN_W - 4, textBottom, 0x18000000);

    }

    // g.blit(
    //     texture,    // 锟?绾圭悊鏂囦欢
    //     x,          // 锟?灞忓箷 X锛堢敾鍒板摢閲岋級
    //     y,          // 锟?灞忓箷 Y
    //     u,          // 锟?绾圭悊 U锛堜粠绾圭悊鍝噷寮€濮嬪彇锟?
    //     v,          // 锟?绾圭悊 V
    //     width,      // 锟?鐢诲锟?
    //     height,     // 锟?鐢诲锟?
    //     texW,       // 锟?绾圭悊鏂囦欢鎬诲锛堢敤锟?UV 褰掍竴鍖栵級
    //     texH        // 锟?绾圭悊鏂囦欢鎬婚珮
    // );
    /** 缁樺埗 NBT 涔濆鏍肩獥鍙ｏ細椤堕儴鈫掑钩閾轰腑閮ㄢ啋搴曢儴 */
    private void renderNbtWindow(GuiGraphics g, int winX, int winY) {
        // int winEnd = y + WIN_BOTTOM;

        // 椤堕儴
        g.blit(NBT_WINDOW, winX , winY, 0, TEX_TOP_Y, WIN_W, TEX_TOP_H, 256, 256);

        // 涓儴锛堢旱鍚戝钩閾猴級
        int midY = winY + TEX_TOP_H;
        int midEnd = winY + WIN_HEIGHT - TEX_BOT_H;
        while (midY < midEnd) {
            g.blit(NBT_WINDOW, winX, midY, 0, TEX_MID_Y, WIN_W, TEX_MID_H, 256, 256);
            midY += TEX_MID_H;
        }

        // 搴曢儴
        g.blit(NBT_WINDOW, winX, winY + WIN_HEIGHT - TEX_BOT_H, 0, TEX_BOT_Y, WIN_W, TEX_BOT_H, 256, 256);

        // Banner
        g.blit(NBT_WINDOW, winX, winY + BANNER_Y_OFFSET, 0, TEX_BANNER_Y, WIN_W, TEX_BANNER_H, 256, 256);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
        renderValueTooltip(g, mouseX, mouseY);
        LoadModeHelper.renderTooltip(g, this.font,
                this.leftPos + WIN_W - LoadModeHelper.HIT_W,
                this.topPos + LOAD_MODE_Y,
                loadMode, onPhysicsBody, mouseX, mouseY);
    }

    /** 鏁板€煎尯鍩熸偓娴彁绀猴細鏍囬 棰戦亾閫夋嫨銆佹粴鍔ㄤ慨鏀癸紙鏂滀綋锛夈€丼hift 鍔犻€燂紙鏂滀綋锟?*/
    private void renderValueTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int hitX = this.leftPos + VALUE_HIT_X;
        int overlayY = this.topPos + WIN_TOP + OVERLAY_Y_OFFSET;
        if (mouseX < hitX || mouseX > hitX + VALUE_HIT_W
                || mouseY < overlayY || mouseY > overlayY + HIT_HEIGHT) return;

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.ccnavigationtable.sensor_channel")
                .withStyle(Style.EMPTY.withColor(0x528FDE)));
        lines.add(Component.translatable("gui.ccnavigationtable.scroll_to_change")
                .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        lines.add(Component.translatable("gui.ccnavigationtable.shift_scroll_faster")
                .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 鏍囬锛堢獥鍙ｅ唴灞呬腑锟?
        int titleWidth = this.font.width(this.title);
        int titleX = WIN_X + (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, TITLE_Y, 0xFFFFFFFF, false);
        // 鑳屽寘鏍囩  // 鐜╁鐗╁搧鏍忓凡娉ㄩ噴
        // g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0xFF3C3B47, false);

        // NBT 鏍戝舰瑙嗗浘锛坰cissor 瑁佸壀 + 婊氳疆婊氬姩锟?
        g.enableScissor(this.leftPos + 4, this.topPos + TEXT_BG_TOP_OFFSET,
                this.leftPos + WIN_W - 4, this.topPos + TEXT_BG_BOTTOM);

        int[] lineY = {TEXT_START_Y - nbtScrollOffset};
        int relY = mouseY - this.topPos;  // 杞浉瀵瑰潗鏍囷紝瀵归綈缁樺埗鍧愭爣锟?
        if (nbtRoots.isEmpty()) {
            String empty = Component.translatable("gui.ccnavigationtable.sensor_nbt.empty").getString();
            g.drawString(this.font, empty, TEXT_START_X, TEXT_START_Y, 0xFFE0E0E0, false);
        } else {
            for (NbtTreeNode root : nbtRoots) {
                renderTreeNode(g, root, lineY, relY);
            }
        }
        nbtTotalLines = lineY[0] - (TEXT_START_Y - nbtScrollOffset);

        g.disableScissor();

        // 婊氬姩锟?
        renderScrollbar(g, nbtTotalLines);

        // 搴曢儴澶嶅埗鎻愮ず
        if (copiedMessage != null) {
            int msgX = WIN_X + 5;
            int msgY = WIN_HEIGHT - TEX_BOT_H + 5;
            g.drawString(this.font, copiedMessage, msgX, msgY, 0xFF55FF55, false);
        }

        // 婊氳疆椹卞姩鏁板€硷紙瑕嗙洊灞傚乏渚э紝锟?VALUE_HIT_X 瀵归綈锟?
        g.drawString(this.font, String.valueOf(scrolledValue), VALUE_HIT_X + 5, WIN_TOP + 30, 0xfcfceb, true);

        // 鍔犺浇妯″紡锛堥閬撳彿涓嬫柟锟?
        LoadModeHelper.renderLabel(g, this.font, WIN_W - LoadModeHelper.HIT_W, LOAD_MODE_Y, loadMode, onPhysicsBody);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // NBT 鏂囨湰鍖哄煙锛氭粴杞┍鍔ㄦ爲褰㈣鍥句笂涓嬫粴鍔紙涓庤儗鏅尯鍩熶竴鑷达級
        int scrollLeft = this.leftPos + WIN_X + 4;
        int scrollRight = this.leftPos + WIN_X + WIN_W - 4;
        int scrollTop = this.topPos + TEXT_BG_TOP_OFFSET;
        int scrollBottom = this.topPos + TEXT_BG_BOTTOM;
        if (mouseX >= scrollLeft && mouseX <= scrollRight
                && mouseY >= scrollTop && mouseY <= scrollBottom
                && scrollY != 0) {
            nbtScrollOffset -= scrollY > 0 ? LINE_HEIGHT : -LINE_HEIGHT;
            if (nbtScrollOffset < 0) nbtScrollOffset = 0;
            int visibleHeight = TEXT_BG_BOTTOM - TEXT_START_Y;
            int maxScroll = Math.max(0, nbtTotalLines - visibleHeight);
            if (nbtScrollOffset > maxScroll) nbtScrollOffset = maxScroll;
            return true;
        }

        // 鍔犺浇妯″紡鍖哄煙锛氭粴鍔ㄥ垏锟?
        int newMode = LoadModeHelper.handleScroll(
                this.leftPos + WIN_W - LoadModeHelper.HIT_W,
                this.topPos + LOAD_MODE_Y,
                loadMode, onPhysicsBody, mouseX, mouseY, scrollY);
        if (newMode >= 0) {
            loadMode = newMode;
            playScrollSound();
            return true;
        }

        int valueHitX = this.leftPos + VALUE_HIT_X;
        int overlayY = this.topPos + WIN_TOP + OVERLAY_Y_OFFSET;
        if (mouseY < overlayY || mouseY > overlayY + HIT_HEIGHT) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        int dir = scrollY > 0 ? 1 : -1;

        // 鏁板€煎尯鍩燂細卤1锛孲hift=卤10锛岃烦杩囧凡琚叾浠栦紶鎰熷櫒鍗犵敤鐨勯锟?
        if (mouseX >= valueHitX && mouseX <= valueHitX + VALUE_HIT_W) {
            int step = hasShiftDown() ? 1 : 1;  // Shift 鍔犻€熸敼涓鸿烦姝ラ暱
            int jump = hasShiftDown() ? 10 : 1;
            int newValue = scrolledValue + dir * jump;
            if (newValue < 0) newValue = 0;
            if (newValue > 9999) newValue = 9999;
            // 璺宠繃宸茶鍏朵粬浼犳劅鍣ㄥ崰鐢ㄧ殑棰戦亾
            newValue = skipOccupiedChannels(newValue, dir);
            if (newValue < 0) newValue = 0;
            if (newValue > 9999) newValue = 9999;
            if (newValue != scrolledValue) {
                scrolledValue = newValue;
                playScrollSound();
            }
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.25f, 0.3f));
    }

    @Override
    public void onClose() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new SensorFilterPayload(menu.getSensorPos(), scrolledValue, loadMode));
        super.onClose();
    }

    /** 閫掑綊娓叉煋鏍戣妭鐐癸紙scissor 瑁佸壀锛屾棤闇€鎵嬪姩杈圭晫妫€鏌ワ級 */
    private void renderTreeNode(GuiGraphics g, NbtTreeNode node, int[] lineY, int mouseY) {
        int depth = node.depth;
        int x = TEXT_START_X + depth * 8;

        String prefix = node.isLeaf() ? "   " : (node.expanded ? "锟?" : "锟?");
        int keyColor = getKeyColor(node.tag);

        String text;
        if (node.isLeaf()) {
            text = prefix + node.key + ": " + node.getValueString();
        } else if (node.expanded) {
            text = prefix + node.key;
        } else {
            text = prefix + node.key + " {...}";
        }

        int maxW = WIN_W - 20 - depth * 8;
        // 榧犳爣鎮诞楂樹寒鑳屾櫙
        if (mouseY >= lineY[0] && mouseY < lineY[0] + LINE_HEIGHT) {
            g.fill(TEXT_START_X, lineY[0] - 1, WIN_X + WIN_W - 8, lineY[0] + LINE_HEIGHT - 1, 0x30FFFFFF);
        }
        // 鎴柇杩囬暱鏂囨湰
        while (this.font.width(text) > maxW && text.length() > 4) {
            text = text.substring(0, text.length() - 4) + "...";
        }
        node.screenY = lineY[0];
        g.drawString(this.font, text, x, lineY[0], keyColor, false);

        lineY[0] += LINE_HEIGHT;

        if (node.expanded) {
            for (NbtTreeNode child : node.children) {
                renderTreeNode(g, child, lineY, mouseY);
            }
        }
    }

    /** 缁樺埗婊氬姩鏉★紙鍙充晶 4px 瀹斤級 */
    private void renderScrollbar(GuiGraphics g, int totalContentHeight) {
        int visibleH = TEXT_BG_BOTTOM - TEXT_START_Y;
        if (totalContentHeight <= visibleH) return;

        int trackTop = TEXT_BG_TOP_OFFSET;
        int trackH = TEXT_BG_HEIGHT;

        // 杞ㄩ亾鑳屾櫙
        g.fill(SCROLLBAR_X, trackTop, SCROLLBAR_X + SCROLLBAR_W, trackTop + trackH, 0x20FFFFFF);

        // 婊戝潡
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * trackH / totalContentHeight);
        int maxScroll = totalContentHeight - visibleH;
        int thumbY = trackTop + (trackH - thumbH) * nbtScrollOffset / Math.max(1, maxScroll);
        g.fill(SCROLLBAR_X, thumbY, SCROLLBAR_X + SCROLLBAR_W, thumbY + thumbH, 0x80AAAAAA);
    }

    /** 鏍规嵁 NBT 绫诲瀷杩斿洖閿悕棰滆壊 */
    private static int getKeyColor(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG,
                 Tag.TAG_FLOAT, Tag.TAG_DOUBLE -> 0xFFFFD700;  // 閲戣壊: 鏁板瓧
            case Tag.TAG_STRING -> 0xFF55FF55;                   // 缁胯壊: 瀛楃锟?
            case Tag.TAG_BYTE_ARRAY, Tag.TAG_INT_ARRAY,
                 Tag.TAG_LONG_ARRAY, Tag.TAG_LIST -> 0xFFFFAA55; // 姗欒壊: 鏁扮粍/鍒楄〃
            default -> 0xFFE0E0E0;                                // 鐧借壊: 鍖栧悎鐗╃瓑
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 宸﹂敭鎴栧彸閿細锟?NBT 绐楀彛锟?
        if (button == 0 || button == 1) {
            int relX = (int) mouseX - this.leftPos;
            int relY = (int) mouseY - this.topPos;
            if (relX >= WIN_X && relX <= WIN_X + WIN_W
                    && relY >= WIN_TOP && relY <= WIN_BOTTOM) {
                NbtTreeNode clicked = findNodeAtY(nbtRoots, relY);
                if (clicked != null) {
                    if (clicked.isLeaf()) {
                        // 鍙跺瓙鑺傜偣 锟?澶嶅埗 Lua 璺緞鍒板壀璐存澘
                        copyLuaPathToClipboard(clicked);
                        return true;
                    } else {
                        // 闈炲彾瀛愯妭鐐癸細宸﹂敭灞曞紑/鎶樺彔锛屽彸閿鍒惰矾锟?
                        if (button == 0) {
                            String path = getNodePath(clicked);
                            if (clicked.expanded) {
                                expandedPaths.remove(path);
                                clicked.expanded = false;
                            } else {
                                expandedPaths.add(path);
                                clicked.expanded = true;
                            }
                            playScrollSound();
                        } else {
                            copyLuaPathToClipboard(clicked);
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 灏嗗彾瀛愯妭鐐圭殑 Lua 璺緞澶嶅埗鍒板壀璐存澘 */
    private void copyLuaPathToClipboard(NbtTreeNode leaf) {
        String internalPath = getNodePath(leaf);
        if (internalPath == null) return;

        String luaPath = internalToLuaPath(internalPath);
        int channel = getMyChannel();
        String code = "sensors.get(" + channel + ",\"" + luaPath + "\")";

        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        copiedMessage = "copied: " + code;
        copiedMessageTimer = 60; // 3 锟?@20tps
        playScrollSound();
    }

    /** 灏嗗唴閮ㄨ矾锟?"Items/[0]/Count" 杞负 Lua 璺緞 "Items[0].Count" */
    static String internalToLuaPath(String internal) {
        StringBuilder sb = new StringBuilder();
        String[] parts = internal.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) {
                // 濡傛灉褰撳墠娈垫槸 [n] 鍒欎笉鍔犵偣锛屽惁鍒欏姞锟?
                if (!part.startsWith("[")) {
                    sb.append(".");
                }
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** 鍚戜笂杩芥函鐖惰妭鐐规瀯閫犲畬鏁磋矾锟?*/
    private String getNodePath(NbtTreeNode node) {
        // 鐢变簬鏍戣妭鐐逛笉瀛樼埗寮曠敤锛屾垜浠粠 nbtRoots 涓煡鎵捐矾锟?
        // 鏇寸畝鍗曠殑鏂规硶锛氬湪 findNodeAtY 鏃堕『甯﹁繑鍥炶矾锟?
        return findNodePath(nbtRoots, node, "");
    }

    private String findNodePath(List<NbtTreeNode> nodes, NbtTreeNode target, String prefix) {
        for (NbtTreeNode n : nodes) {
            String path = prefix.isEmpty() ? n.key : prefix + "/" + n.key;
            if (n == target) return path;
            String found = findNodePath(n.children, target, path);
            if (found != null) return found;
        }
        return null;
    }

    /** 鍦ㄥ彲瑙佽妭鐐逛腑鏌ユ壘鎸囧畾 Y 鍧愭爣瀵瑰簲鐨勮妭锟?*/
    private NbtTreeNode findNodeAtY(List<NbtTreeNode> nodes, int targetY) {
        for (NbtTreeNode node : nodes) {
            if (node.screenY >= 0 && targetY >= node.screenY && targetY < node.screenY + LINE_HEIGHT) {
                return node;
            }
            if (node.expanded) {
                NbtTreeNode found = findNodeAtY(node.children, targetY);
                if (found != null) return found;
            }
        }
        return null;
    }

    // 鈺愨晲锟?棰戦亾婊氬姩锛氳烦杩囧凡鍗犵敤棰戦亾 鈺愨晲锟?

    /**
     * 浠ュ綋鍓嶄紶鎰熷櫒鑷繁鐨勯閬撳彿涓哄熀鍑嗭紝璺宠繃宸茶鍏朵粬浼犳劅鍣ㄥ崰鐢ㄧ殑棰戦亾锟?
     * @param value    褰撳墠锟?
     * @param dir      婊氬姩鏂瑰悜锟?=澧炲ぇ, -1=鍑忓皬
     * @return 璺宠繃鍗犵敤棰戦亾鍚庣殑锟?
     */
    private int skipOccupiedChannels(int value, int dir) {
        int myChannel = getMyChannel();
        int safety = 0;
        while (safety < 10000 && isOccupiedByOther(value, myChannel)) {
            value += dir;
            if (value < 0 || value > 9999) break;
            safety++;
        }
        return value;
    }

    /** 褰撳墠浼犳劅鍣ㄨ嚜宸辩殑棰戦亾鍙凤紙浼樺厛鑿滃崟 extraData锛宖allback 瀹㈡埛锟?BE锟?*/
    private int getMyChannel() {
        int menuChannel = menu.getSensorChannel();
        if (menuChannel >= 0) return menuChannel;
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                return sensorBE.getScrolledValue();
            }
        }
        return -1;
    }

    /** 妫€鏌ラ閬撴槸鍚﹁"鍏朵粬"浼犳劅鍣ㄥ崰鐢紙浼樺厛鑿滃崟 extraData锛宖allback 瀹㈡埛锟?BE锟?*/
    private boolean isOccupiedByOther(int channel, int myChannel) {
        if (channel == myChannel) return false;
        // 浼樺厛浠庤彍锟?extraData锛堝寘鍚湇鍔＄鍚屾椂鍙戞潵鐨勫揩鐓э級
        int[] menuOccupied = menu.getOccupiedChannels();
        if (menuOccupied.length > 0) {
            for (int ch : menuOccupied) {
                if (ch == channel) return true;
            }
            return false;
        }
        // Fallback: 瀹㈡埛锟?BE锛堝彲鑳界敱 updateTag 鍚庣画鍚屾鏇存柊锟?
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof PeripheralExtenderBlockEntity sensorBE) {
                return sensorBE.isChannelOccupiedByOther(channel);
            }
        }
        return false;
    }

    // 鈺愨晲锟?NBT 鏍戣妭锟?鈺愨晲锟?
    static class NbtTreeNode {
        final String key;
        final Tag tag;
        final int depth;
        final List<NbtTreeNode> children = new ArrayList<>();
        boolean expanded;
        int screenY = -1;

        NbtTreeNode(String key, Tag tag, int depth) {
            this.key = key;
            this.tag = tag;
            this.depth = depth;
            // 榛樿鍏ㄩ儴鏀惰捣
            this.expanded = false;
        }

        boolean isLeaf() {
            return !(tag instanceof CompoundTag) && !(tag instanceof ListTag);
        }

        String getValueString() {
            return tag.getAsString();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 || this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
