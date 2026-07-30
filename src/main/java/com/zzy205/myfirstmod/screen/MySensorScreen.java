package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import com.zzy205.myfirstmod.network.SensorFilterPayload;
// import com.zzy205.myfirstmod.network.SensorItemPayload;
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
    // 原版玩家背包纹理（Create 提供）
    // private static final ResourceLocation BASE_TEXTURE =
    //         ResourceLocation.parse("create:textures/gui/player_inventory.png");

    // 自定义 NBT 窗口纹理：顶部(32,0)-192×18, 中部(32,26)-192×19可平铺, 底部(32,55)-192×8
    private static final ResourceLocation NBT_WINDOW =
            ResourceLocation.fromNamespaceAndPath("ccnavigationtable", "textures/gui/test_gui.png");

    // private static final int BACKPACK_WIDTH  = 175;
    // private static final int BACKPACK_HEIGHT = 108;

    // NBT 窗口九宫格参数
    private static final int WIN_X      = 0;    // 窗口贴画布左边界
    private static final int WIN_W      = 256;  // 窗口宽度
    private static final int WIN_TOP    = 0;    // 窗口贴画布顶部
    private static final int WIN_BOTTOM = 192;  // 窗口底部 = 画布高度
    private static final int WIN_HEIGHT = WIN_BOTTOM - WIN_TOP;  // 窗口高度

    // 纹理切片坐标
    private static final int TEX_TOP_Y    = 0;
    private static final int TEX_TOP_H    = 16;

    private static final int TEX_MID_Y    = 16;
    private static final int TEX_MID_H    = 16;
    
    private static final int TEX_BOT_Y    = 32;
    private static final int TEX_BOT_H    = 16;

    private static final int TEX_BANNER_Y = 96;
    private static final int TEX_BANNER_H = 30;
    private static final int BANNER_Y_OFFSET =  TEX_TOP_H + 4;

    // NBT 文本（窗口内部）

    private static final int TEXT_BG_TOP_OFFSET = BANNER_Y_OFFSET + TEX_BANNER_H + 4;
    private static final int TEXT_START_X = WIN_X + 8;           // 左边距 8px
    private static final int TEXT_START_Y = TEXT_BG_TOP_OFFSET + 4;  // 顶边框下方 2px

    private static final int LINE_HEIGHT  = 10;

    /** NBT 文本区域可视高度 */
    private static final int TEXT_BG_BOTTOM = WIN_HEIGHT - TEX_BOT_H - 4;
    private static final int TEXT_BG_HEIGHT = TEXT_BG_BOTTOM - TEXT_BG_TOP_OFFSET;

    /** 滚动条 */
    private static final int SCROLLBAR_X = WIN_X + WIN_W - 6;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_MIN_THUMB = 8;

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

    /** 复制路径提示消息，非空时在窗口底部显示，倒计时结束后清空 */
    private String copiedMessage = null;
    private int copiedMessageTimer = 0;

    // 滚轮驱动数值
    private int scrolledValue = 0;

    // 滚轮检测区域（X/宽度各自不同，Y/高度一致）
    private static final int OVERLAY_Y_OFFSET = 24;          // 覆盖层内 Y 偏移
    private static final int HIT_HEIGHT = 19;                 // 检测区域高度
    private static final int VALUE_HIT_X = WIN_X + 42;       // 数值区域 X
    private static final int VALUE_HIT_W = 34;               // 数值区域宽度


    // /** JEI 幽灵拖放目标区域 */  // 幽灵物品已注释
    // public Rect2i ghostSlot0Bounds;
    // public Rect2i ghostSlot1Bounds;


    public MySensorScreen(MySensorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_BOTTOM;
        // this.titleLabelY = 6;
        // this.inventoryLabelY = this.imageHeight - 94; 
    }

    @Override
    protected void init() {
        super.init();
        this.pollInterval = Config.SENSOR_NBT_POLL_INTERVAL.get();

        // 恢复上次保存的数值和选项
        // 优先使用菜单 extraData（和 GUI 打开包同一帧到达，保证最新），
        // 客户端 BE 数据可能因同步延迟而未更新，仅作为 fallback
        int menuChannel = menu.getSensorChannel();
        if (menuChannel >= 0) {
            this.scrolledValue = menuChannel;
        } else if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof MySensorBlockEntity sensorBE) {
                this.scrolledValue = sensorBE.getScrolledValue();
            }
        }

        formatNBTForDisplay();
        // this.ghostSlot0Bounds = new Rect2i(
        //         leftPos + MySensorMenu.GHOST_SLOT_X,
        //         topPos + MySensorMenu.GHOST_SLOT_Y,
        //         16, 16);
        // this.ghostSlot1Bounds = new Rect2i(
        //         leftPos + MySensorMenu.GHOST_SLOT_2_X,
        //         topPos + MySensorMenu.GHOST_SLOT_Y,
        //         16, 16);

        formatNBTForDisplay();
    }

    /**
     * 由 JEI 拖放或中键点击触发：更新幽灵槽位物品并发送网络包。
     */
    // public void updateGhostSlot(int slotIndex, ItemStack stack) {
    //     ItemStack copy = stack.copy();
    //     copy.setCount(1);
    //     // 本地更新（客户端 BE 缓存）
    //     BlockEntity be = minecraft.level.getBlockEntity(menu.getSensorPos());
    //     if (be instanceof MySensorBlockEntity sensorBE) {
    //         sensorBE.setDisplayItem(slotIndex, copy);
    //     }
    //     // 发送网络包到服务端
    //     PacketDistributor.sendToServer(new SensorItemPayload(menu.getSensorPos(), copy, slotIndex));
    // }

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
        // 复制提示倒计时
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
        int textTop = y + TEXT_BG_TOP_OFFSET;
        int textBottom = y + TEXT_BG_BOTTOM;
        g.fill(x + 4, textTop, x + WIN_W - 4, textBottom, 0x18000000);

        // // ── 背包区域：Create 原版纹理 ── 
        // int backpackX = x + (this.imageWidth - BACKPACK_WIDTH) / 2;
        // int backpackY = y + BACKPACK_TOP;
        // g.blit(BASE_TEXTURE, backpackX, backpackY, 0, 0,
        //         BACKPACK_WIDTH, BACKPACK_HEIGHT, 256, 256);

    }

    // g.blit(
    //     texture,    // ① 纹理文件
    //     x,          // ② 屏幕 X（画到哪里）
    //     y,          // ③ 屏幕 Y
    //     u,          // ④ 纹理 U（从纹理哪里开始取）
    //     v,          // ⑤ 纹理 V
    //     width,      // ⑥ 画多宽
    //     height,     // ⑦ 画多高
    //     texW,       // ⑧ 纹理文件总宽（用于 UV 归一化）
    //     texH        // ⑨ 纹理文件总高
    // );
    /** 绘制 NBT 九宫格窗口：顶部→平铺中部→底部 */
    private void renderNbtWindow(GuiGraphics g, int winX, int winY) {
        // int winEnd = y + WIN_BOTTOM;

        // 顶部
        g.blit(NBT_WINDOW, winX , winY, 0, TEX_TOP_Y, WIN_W, TEX_TOP_H, 256, 256);

        // 中部（纵向平铺）
        int midY = winY + TEX_TOP_H;
        int midEnd = winY + WIN_HEIGHT - TEX_BOT_H;
        while (midY < midEnd) {
            g.blit(NBT_WINDOW, winX, midY, 0, TEX_MID_Y, WIN_W, TEX_MID_H, 256, 256);
            midY += TEX_MID_H;
        }

        // 底部
        g.blit(NBT_WINDOW, winX, winY + WIN_HEIGHT - TEX_BOT_H, 0, TEX_BOT_Y, WIN_W, TEX_BOT_H, 256, 256);

        // Banner
        g.blit(NBT_WINDOW, winX, winY + BANNER_Y_OFFSET, 0, TEX_BANNER_Y, WIN_W, TEX_BANNER_H, 256, 256);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
        renderValueTooltip(g, mouseX, mouseY);
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

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题（窗口内居中）
        int titleWidth = this.font.width(this.title);
        int titleX = WIN_X + (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, WIN_TOP + 4, 0xFFFFFFFF, false);
        // 背包标签  // 玩家物品栏已注释
        // g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0xFF3C3B47, false);

        // NBT 树形视图（scissor 裁剪 + 滚轮滚动）
        g.enableScissor(this.leftPos + 4, this.topPos + TEXT_BG_TOP_OFFSET,
                this.leftPos + WIN_W - 4, this.topPos + TEXT_BG_BOTTOM);

        int[] lineY = {TEXT_START_Y - nbtScrollOffset};
        int relY = mouseY - this.topPos;  // 转相对坐标，对齐绘制坐标系
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

        // 滚动条
        renderScrollbar(g, nbtTotalLines);

        // 底部复制提示
        if (copiedMessage != null) {
            int msgX = WIN_X + 5;
            int msgY = WIN_HEIGHT - TEX_BOT_H + 5;
            g.drawString(this.font, copiedMessage, msgX, msgY, 0xFF55FF55, false);
        }

        // 滚轮驱动数值（覆盖层左侧，与 VALUE_HIT_X 对齐）
        g.drawString(this.font, String.valueOf(scrolledValue), VALUE_HIT_X + 5, WIN_TOP + 30, 0xfcfceb, true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // NBT 文本区域：滚轮驱动树形视图上下滚动（与背景区域一致）
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
        int valueHitX = this.leftPos + VALUE_HIT_X;
        int overlayY = this.topPos + WIN_TOP + OVERLAY_Y_OFFSET;
        if (mouseY < overlayY || mouseY > overlayY + HIT_HEIGHT) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        int dir = scrollY > 0 ? 1 : -1;

        // 数值区域：±1，Shift=±10，跳过已被其他传感器占用的频道
        if (mouseX >= valueHitX && mouseX <= valueHitX + VALUE_HIT_W) {
            int step = hasShiftDown() ? 1 : 1;  // Shift 加速改为跳步长
            int jump = hasShiftDown() ? 10 : 1;
            int newValue = scrolledValue + dir * jump;
            if (newValue < 0) newValue = 0;
            if (newValue > 9999) newValue = 9999;
            // 跳过已被其他传感器占用的频道
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
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.5f));
    }

    @Override
    public void onClose() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new SensorFilterPayload(menu.getSensorPos(), scrolledValue));
        super.onClose();
    }

    /** 递归渲染树节点（scissor 裁剪，无需手动边界检查） */
    private void renderTreeNode(GuiGraphics g, NbtTreeNode node, int[] lineY, int mouseY) {
        int depth = node.depth;
        int x = TEXT_START_X + depth * 8;

        String prefix = node.isLeaf() ? "   " : (node.expanded ? "▼ " : "▶ ");
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
        // 鼠标悬浮高亮背景
        if (mouseY >= lineY[0] && mouseY < lineY[0] + LINE_HEIGHT) {
            g.fill(TEXT_START_X, lineY[0] - 1, WIN_X + WIN_W - 8, lineY[0] + LINE_HEIGHT - 1, 0x30FFFFFF);
        }
        // 截断过长文本
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

    /** 绘制滚动条（右侧 4px 宽） */
    private void renderScrollbar(GuiGraphics g, int totalContentHeight) {
        int visibleH = TEXT_BG_BOTTOM - TEXT_START_Y;
        if (totalContentHeight <= visibleH) return;

        int trackTop = TEXT_BG_TOP_OFFSET;
        int trackH = TEXT_BG_HEIGHT;

        // 轨道背景
        g.fill(SCROLLBAR_X, trackTop, SCROLLBAR_X + SCROLLBAR_W, trackTop + trackH, 0x20FFFFFF);

        // 滑块
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * trackH / totalContentHeight);
        int maxScroll = totalContentHeight - visibleH;
        int thumbY = trackTop + (trackH - thumbH) * nbtScrollOffset / Math.max(1, maxScroll);
        g.fill(SCROLLBAR_X, thumbY, SCROLLBAR_X + SCROLLBAR_W, thumbY + thumbH, 0x80AAAAAA);
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
        // 左键或右键：在 NBT 窗口内
        if (button == 0 || button == 1) {
            int relX = (int) mouseX - this.leftPos;
            int relY = (int) mouseY - this.topPos;
            if (relX >= WIN_X && relX <= WIN_X + WIN_W
                    && relY >= WIN_TOP && relY <= WIN_BOTTOM) {
                NbtTreeNode clicked = findNodeAtY(nbtRoots, relY);
                if (clicked != null) {
                    if (clicked.isLeaf()) {
                        // 叶子节点 → 复制 Lua 路径到剪贴板
                        copyLuaPathToClipboard(clicked);
                        return true;
                    } else {
                        // 非叶子节点 → 展开/折叠（仅左键）
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
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 将叶子节点的 Lua 路径复制到剪贴板 */
    private void copyLuaPathToClipboard(NbtTreeNode leaf) {
        String internalPath = getNodePath(leaf);
        if (internalPath == null) return;

        String luaPath = internalToLuaPath(internalPath);
        int channel = getMyChannel();
        String code = "sensors.get(" + channel + ",\"" + luaPath + "\")";

        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        copiedMessage = "copied: " + code;
        copiedMessageTimer = 60; // 3 秒 @20tps
        playScrollSound();
    }

    /** 将内部路径 "Items/[0]/Count" 转为 Lua 路径 "Items[0].Count" */
    static String internalToLuaPath(String internal) {
        StringBuilder sb = new StringBuilder();
        String[] parts = internal.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) {
                // 如果当前段是 [n] 则不加点，否则加点
                if (!part.startsWith("[")) {
                    sb.append(".");
                }
            }
            sb.append(part);
        }
        return sb.toString();
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

    // ═══ 频道滚动：跳过已占用频道 ═══

    /**
     * 以当前传感器自己的频道号为基准，跳过已被其他传感器占用的频道。
     * @param value    当前值
     * @param dir      滚动方向：1=增大, -1=减小
     * @return 跳过占用频道后的值
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

    /** 当前传感器自己的频道号（优先菜单 extraData，fallback 客户端 BE） */
    private int getMyChannel() {
        int menuChannel = menu.getSensorChannel();
        if (menuChannel >= 0) return menuChannel;
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof MySensorBlockEntity sensorBE) {
                return sensorBE.getScrolledValue();
            }
        }
        return -1;
    }

    /** 检查频道是否被"其他"传感器占用（优先菜单 extraData，fallback 客户端 BE） */
    private boolean isOccupiedByOther(int channel, int myChannel) {
        if (channel == myChannel) return false;
        // 优先从菜单 extraData（包含服务端同时发来的快照）
        int[] menuOccupied = menu.getOccupiedChannels();
        if (menuOccupied.length > 0) {
            for (int ch : menuOccupied) {
                if (ch == channel) return true;
            }
            return false;
        }
        // Fallback: 客户端 BE（可能由 updateTag 后续同步更新）
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof MySensorBlockEntity sensorBE) {
                return sensorBE.isChannelOccupiedByOther(channel);
            }
        }
        return false;
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
