package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.network.SensorFilterPayload;
import com.zzy205.myfirstmod.network.SensorItemPayload;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // ═══ 可折叠 NBT 树形视图 ═══
    private List<NbtTreeNode> nbtRoots = new ArrayList<>();
    /** 记录用户手动展开的路径（不在集合中=默认收起） */
    private final Set<String> expandedPaths = new HashSet<>();
    /** NBT 树形视图滚动偏移（像素，负值=向上滚动，不低于0） */
    private int nbtScrollOffset = 0;
    /** 上次渲染时树的总高度（行数），用于限制向下滚动 */
    private int nbtTotalLines = 0;
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

    /** JEI 幽灵拖放目标区域（由 init() 根据 leftPos/topPos 计算，JEI 插件需要访问） */
    public Rect2i ghostSlot0Bounds;
    public Rect2i ghostSlot1Bounds;


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

        // 初始化 JEI 幽灵拖放目标区域
        this.ghostSlot0Bounds = new Rect2i(
                leftPos + MySensorMenu.GHOST_SLOT_X,
                topPos + MySensorMenu.GHOST_SLOT_Y,
                16, 16);
        this.ghostSlot1Bounds = new Rect2i(
                leftPos + MySensorMenu.GHOST_SLOT_2_X,
                topPos + MySensorMenu.GHOST_SLOT_Y,
                16, 16);

        formatNBTForDisplay();
    }

    /**
     * 由 JEI 拖放或中键点击触发：更新幽灵槽位物品并发送网络包。
     */
    public void updateGhostSlot(int slotIndex, ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        // 本地更新（客户端 BE 缓存）
        BlockEntity be = minecraft.level.getBlockEntity(menu.getSensorPos());
        if (be instanceof MySensorBlockEntity sensorBE) {
            sensorBE.setDisplayItem(slotIndex, copy);
        }
        // 发送网络包到服务端
        PacketDistributor.sendToServer(new SensorItemPayload(menu.getSensorPos(), copy, slotIndex));
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
        nbtRoots.clear();
        CompoundTag nbt = getLiveNBT();
        if (nbt == null || nbt.isEmpty()) return;
        for (String key : nbt.getAllKeys()) {
            nbtRoots.add(buildTree(key, nbt.get(key), 0, key));
        }
    }

    /** 递归构建 NBT 树节点，path 为从根到此节点的完整路径（用 / 连接） */
    private NbtTreeNode buildTree(String key, Tag tag, int depth, String path) {
        NbtTreeNode node = new NbtTreeNode(key, tag, depth);
        // 恢复展开状态：仅 expandedPaths 中记录的路径才展开
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

        // NBT 文本区域透明背景
        int textTop = y + TEXT_START_Y - 5;
        int textBottom = y + 170;
        g.fill(x + WIN_X + 4, textTop, x + WIN_X + WIN_W - 4, textBottom, 0x18000000);

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

        // NBT 树形视图（窗口内部，支持滚轮滚动）
        int[] lineY = {TEXT_START_Y - nbtScrollOffset};
        int maxLines = (WIN_BOTTOM - WIN_TOP - TEX_TOP_H - TEX_BOT_H) / LINE_HEIGHT;
        int[] rendered = {0};
        if (nbtRoots.isEmpty()) {
            String empty = Component.translatable("gui.ccnavigationtable.sensor_nbt.empty").getString();
            g.drawString(this.font, empty, TEXT_START_X, TEXT_START_Y, 0xFFE0E0E0, false);
        } else {
            for (NbtTreeNode root : nbtRoots) {
                renderTreeNode(g, root, lineY, maxLines, rendered);
            }
        }
        nbtTotalLines = lineY[0] - (TEXT_START_Y - nbtScrollOffset);  // 原始内容总像素高

        // 滚轮驱动数值（覆盖层左侧，与 VALUE_HIT_X 对齐）
        g.drawString(this.font, String.valueOf(scrolledValue), VALUE_HIT_X + 5, WIN_TOP + 30, 0xfcfceb, true);

        // 滚轮选择菜单（覆盖层右侧）
        String selected = selectOptions[selectIndex];
        g.drawString(this.font, selected, WIN_X + 80, WIN_TOP + 30, 0xfcfceb, true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // NBT 文本区域：滚轮驱动树形视图上下滚动（与背景区域一致）
        int scrollLeft = this.leftPos + WIN_X + 4;
        int scrollRight = this.leftPos + WIN_X + WIN_W - 4;
        int scrollTop = this.topPos + TEXT_START_Y - 5;
        int scrollBottom = this.topPos + 170;
        if (mouseX >= scrollLeft && mouseX <= scrollRight
                && mouseY >= scrollTop && mouseY <= scrollBottom
                && scrollY != 0) {
            nbtScrollOffset -= scrollY > 0 ? LINE_HEIGHT : -LINE_HEIGHT;
            if (nbtScrollOffset < 0) nbtScrollOffset = 0;
            return true;
        }
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

    /** 递归渲染树节点 */
    private void renderTreeNode(GuiGraphics g, NbtTreeNode node, int[] lineY, int maxLines, int[] rendered) {
        // 底部裁剪：超过窗口底部停止
        if (lineY[0] >= 165) return;
        // 顶部裁剪：在可见区域上方则只计数不绘制
        boolean above = lineY[0] + LINE_HEIGHT <= TEXT_START_Y;

        int depth = node.depth;
        int x = TEXT_START_X + depth * 8;

        // 前缀符号
        String prefix = node.isLeaf() ? "   " : (node.expanded ? "▼ " : "▶ ");

        // 键名颜色：数字类型用金色，字符串用绿色，化合物用白色
        int keyColor = getKeyColor(node.tag);

        // 构建显示文本
        String text;
        if (node.isLeaf()) {
            text = prefix + node.key + ": " + node.getValueString();
        } else if (node.expanded) {
            text = prefix + node.key;
        } else {
            text = prefix + node.key + " {...}";
        }

        int maxW = WIN_W - 16 - depth * 8;
        if (!above) {
            // 截断过长文本
            while (this.font.width(text) > maxW && text.length() > 4) {
                text = text.substring(0, text.length() - 4) + "...";
            }
            node.screenY = lineY[0];
            g.drawString(this.font, text, x, lineY[0], keyColor, false);
        }

        lineY[0] += LINE_HEIGHT;
        rendered[0]++;

        // 递归渲染子节点
        if (node.expanded) {
            for (NbtTreeNode child : node.children) {
                renderTreeNode(g, child, lineY, maxLines, rendered);
            }
        }
    }

    /** 根据 NBT 类型返回键名颜色 */
    private static int getKeyColor(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG,
                 Tag.TAG_FLOAT, Tag.TAG_DOUBLE -> 0xFFFFD700;  // 金色: 数字
            case Tag.TAG_STRING -> 0xFF55FF55;                   // 绿色: 字符串
            case Tag.TAG_BYTE_ARRAY, Tag.TAG_INT_ARRAY,
                 Tag.TAG_LONG_ARRAY, Tag.TAG_LIST -> 0xFFFFAA55; // 橙色: 数组/列表
            default -> 0xFFE0E0E0;                                // 白色: 化合物等
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int relX = (int) mouseX - this.leftPos;
            int relY = (int) mouseY - this.topPos;
            if (relX >= WIN_X && relX <= WIN_X + WIN_W
                    && relY >= WIN_TOP && relY <= WIN_BOTTOM) {
                NbtTreeNode clicked = findNodeAtY(nbtRoots, relY);
                if (clicked != null && !clicked.isLeaf()) {
                    // 切换展开/折叠并记录到持久集合
                    String path = getNodePath(clicked);
                    if (clicked.expanded) {
                        expandedPaths.remove(path);
                        clicked.expanded = false;
                    } else {
                        expandedPaths.add(path);
                        clicked.expanded = true;
                    }
                    playScrollSound();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 向上追溯父节点构造完整路径 */
    private String getNodePath(NbtTreeNode node) {
        // 由于树节点不存父引用，我们从 nbtRoots 中查找路径
        // 更简单的方法：在 findNodeAtY 时顺带返回路径
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

    /** 在可见节点中查找指定 Y 坐标对应的节点 */
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

    // ═══ NBT 树节点 ═══
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
            // 默认全部收起
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
