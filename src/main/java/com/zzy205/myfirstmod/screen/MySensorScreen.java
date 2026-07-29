package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.network.SensorFilterPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

public class MySensorScreen extends AbstractContainerScreen<MySensorMenu> {

    // ═══ 布局常量 ═══
    // 原版玩家背包纹理（Create 提供，运行时可用）
    private static final ResourceLocation BASE_TEXTURE =
            ResourceLocation.parse("create:textures/gui/player_inventory.png");

    // 自定义 NBT 窗口纹理：顶部(32,0)-192×18, 中部(32,26)-192×19可平铺, 底部(32,55)-192×8
    private static final ResourceLocation NBT_WINDOW =
            ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "textures/gui/test_gui.png");

    private static final int BACKPACK_WIDTH  = 175;
    private static final int BACKPACK_HEIGHT = 108;
    private static final int BACKPACK_TOP    = 194;

    // NBT 窗口九宫格参数
    private static final int WIN_X      = 32;   // 窗口 X（居中: (256-192)/2=32）
    private static final int WIN_W      = 192;  // 窗口宽度
    private static final int WIN_TOP    = 10;   // 窗口顶部 Y
    private static final int WIN_BOTTOM = BACKPACK_TOP - 10;  // 背包上方 10px

    // 纹理切片坐标
    private static final int TEX_TOP_Y    = 0;
    private static final int TEX_TOP_H    = 18;
    private static final int TEX_MID_Y    = 26;
    private static final int TEX_MID_H    = 19;
    private static final int TEX_BOT_Y    = 55;
    private static final int TEX_BOT_H    = 8;

    // NBT 文本（窗口内部）
    private static final int TEXT_START_X = WIN_X + 8;           // 左边距 8px
    private static final int TEXT_START_Y = WIN_TOP + 60;  // 顶边框下方 2px
    private static final int LINE_HEIGHT  = 10;
    // ═══════════════════════════════════════════════════

    private List<String> displayLines = new ArrayList<>();
    private int tickCounter = 0;
    private int pollInterval;

    // 滚轮驱动数值
    private int scrolledValue = 0;

    // 滚轮选择菜单
    private final String[] selectOptions = {"关闭",  "接收" , "发送"};
    private int selectIndex = 0;

    // 滚轮检测区域（X/宽度各自不同，Y/高度一致）
    private static final int OVERLAY_Y_OFFSET = 24;          // 覆盖层内 Y 偏移
    private static final int HIT_HEIGHT = 19;                 // 检测区域高度
    private static final int VALUE_HIT_X = WIN_X + 25;       // 数值区域 X
    private static final int VALUE_HIT_W = 34;               // 数值区域宽度
    private static final int SCROLL_HIT_X = WIN_X + 75;      // 选项区域 X
    private static final int SCROLL_HIT_W = 52;              // 选项区域宽度


    public MySensorScreen(MySensorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 256;
        this.imageHeight = BACKPACK_TOP + BACKPACK_HEIGHT + 6;  // 208
        this.titleLabelY = 6;
        this.inventoryLabelY = this.imageHeight - 94;  // 114
    }

    @Override
    protected void init() {
        super.init();
        this.pollInterval = Config.SENSOR_NBT_POLL_INTERVAL.get();

        // 恢复上次保存的数值和选项
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof MySensorBlockEntity sensorBE) {
                this.scrolledValue = sensorBE.getScrolledValue();
                this.selectIndex = sensorBE.getSelectIndex();
            }
        }

        formatNBTForDisplay();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // this.filterBox.tick();
        if (pollInterval <= 0) return;
        tickCounter++;
        if (tickCounter % pollInterval == 0) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
            }
        }
        formatNBTForDisplay();
    }

    private void formatNBTForDisplay() {
        displayLines.clear();
        CompoundTag nbt = getLiveNBT();
        if (nbt == null || nbt.isEmpty()) {
            displayLines.add(Component.translatable("gui.ccnavigationtable.sensor_nbt.empty").getString());
            return;
        }
        try {
            String snbt = NbtUtils.prettyPrint(nbt, true);
            for (String line : snbt.split("\\n")) {
                displayLines.add(line.length() > 50 ? line.substring(0, 47) + "..." : line);
            }
        } catch (Exception e) {
            displayLines.add("Error: " + e.getMessage());
        }
    }

    private CompoundTag getLiveNBT() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof MySensorBlockEntity sensorBE) {
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

        // ── 背包区域：Create 原版纹理 ──
        int backpackX = x + (this.imageWidth - BACKPACK_WIDTH) / 2;
        int backpackY = y + BACKPACK_TOP;
        g.blit(BASE_TEXTURE, backpackX, backpackY, 0, 0,
                BACKPACK_WIDTH, BACKPACK_HEIGHT, 256, 256);
    }

    /** 绘制 NBT 九宫格窗口：顶部→平铺中部→底部 */
    private void renderNbtWindow(GuiGraphics g, int x, int y) {
        int winY = y + WIN_TOP;
        int winEnd = y + WIN_BOTTOM;

        // 顶部
        g.blit(NBT_WINDOW, x + WIN_X, winY, WIN_X, TEX_TOP_Y, WIN_W, TEX_TOP_H, 256, 256);

        // 中部（纵向平铺）
        int midY = winY + TEX_TOP_H;
        int midEnd = winEnd - TEX_BOT_H;
        while (midY < midEnd) {
            int h = Math.min(TEX_MID_H, midEnd - midY);
            g.blit(NBT_WINDOW, x + WIN_X, midY, WIN_X, TEX_MID_Y, WIN_W, h, 256, 256);
            midY += h;
        }

        // 底部
        g.blit(NBT_WINDOW, x + WIN_X, winEnd - TEX_BOT_H, WIN_X, TEX_BOT_Y, WIN_W, TEX_BOT_H, 256, 256);

        // 小窗口
        winY += 20;
        g.blit(NBT_WINDOW, x + WIN_X, winY, WIN_X, 92, WIN_W, 30, 256, 256);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
        renderValueTooltip(g, mouseX, mouseY);
        renderSelectionTooltip(g, mouseX, mouseY);
    }

    /** 数值区域悬浮提示：标题 频道选择、滚动修改（斜体）、Shift 加速（斜体） */
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

    /** 鼠标悬浮在选中项上时显示 Create 风格的滚轮选择提示 */
    private void renderSelectionTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int overlayY = this.topPos + WIN_TOP + OVERLAY_Y_OFFSET;
        int hitX = this.leftPos + SCROLL_HIT_X;
        if (mouseX < hitX || mouseX > hitX + SCROLL_HIT_W
                || mouseY < overlayY || mouseY > overlayY + HIT_HEIGHT) return;

        List<Component> lines = new ArrayList<>();
        // 标题 0xFF528FDE
        lines.add(Component.translatable("gui.ccnavigationtable.sensor_select_mode")
                .withStyle(Style.EMPTY.withColor(0x528FDE)));
        for (int i = 0; i < selectOptions.length; i++) {
            boolean sel = i == selectIndex;
            // -> 选中 0xFFFCFCFC, > 未选 0xFFA8A8A8
            lines.add(Component.literal((sel ? "-> " : "> ") + selectOptions[i])
                    .withStyle(Style.EMPTY.withColor(sel ? 0xFCFCFC : 0xA8A8A8)));
        }
        // 底部提示 0xFF545454 斜体
        lines.add(Component.translatable("gui.ccnavigationtable.scroll_to_select")
                .withStyle(Style.EMPTY.withColor(0x545454).withItalic(true)));
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题（窗口内居中）
        int titleWidth = this.font.width(this.title);
        int titleX = WIN_X + (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, WIN_TOP + 4, 0xFFFFFFFF, false);
        // 背包标签（与 Create Redstone Link 一致的灰色）
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0xFF3C3B47, false);

        // NBT 文本（窗口内部，自动适配高度）
        int maxLines = (WIN_BOTTOM - WIN_TOP - TEX_TOP_H - TEX_BOT_H) / LINE_HEIGHT;
        for (int i = 0; i < displayLines.size() && i < maxLines; i++) {
            g.drawString(this.font, displayLines.get(i), TEXT_START_X, TEXT_START_Y + i * LINE_HEIGHT, 0xFFE0E0E0, false);
        }

        // 滚轮驱动数值（覆盖层左侧，与 VALUE_HIT_X 对齐）
        g.drawString(this.font, String.valueOf(scrolledValue), VALUE_HIT_X + 5, WIN_TOP + 30, 0xfcfceb, true);

        // 滚轮选择菜单（覆盖层右侧）
        String selected = selectOptions[selectIndex];
        g.drawString(this.font, selected, WIN_X + 80, WIN_TOP + 30, 0xfcfceb, true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int valueHitX = this.leftPos + VALUE_HIT_X;
        int selectHitX = this.leftPos + SCROLL_HIT_X;
        int overlayY = this.topPos + WIN_TOP + OVERLAY_Y_OFFSET;
        if (mouseY < overlayY || mouseY > overlayY + HIT_HEIGHT) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        int dir = scrollY > 0 ? 1 : -1;

        // 数值区域：±1，Shift=±10
        if (mouseX >= valueHitX && mouseX <= valueHitX + VALUE_HIT_W) {
            int step = hasShiftDown() ? 10 : 1;
            scrolledValue += dir * step;
            if (scrolledValue < 0) scrolledValue = 0;
            if (scrolledValue > 9999) scrolledValue = 9999;
            playScrollSound();
            return true;
        }

        // 选项区域：循环切换
        if (mouseX >= selectHitX && mouseX <= selectHitX + SCROLL_HIT_W) {
            selectIndex = (selectIndex + dir + selectOptions.length) % selectOptions.length;
            playScrollSound();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void playScrollSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.5f));
    }

    @Override
    public void onClose() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new SensorFilterPayload(menu.getSensorPos(), scrolledValue, selectIndex));
        super.onClose();
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
