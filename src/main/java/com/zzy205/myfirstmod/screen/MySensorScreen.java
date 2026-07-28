package com.zzy205.myfirstmod.screen;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.block.MySensorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

public class MySensorScreen extends AbstractContainerScreen<MySensorMenu> {

    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int LINE_HEIGHT = 10;

    private List<String> displayLines = new ArrayList<>();
    private int tickCounter = 0;
    private int pollInterval; // 从配置读取的轮询间隔

    public MySensorScreen(MySensorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 256;
        this.imageHeight = 300;
    }

    @Override
    protected void init() {
        super.init();
        this.pollInterval = Config.SENSOR_NBT_POLL_INTERVAL.get();
        formatNBTForDisplay();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (pollInterval <= 0) return; // 0 = 禁用自动刷新
        tickCounter++;
        if (tickCounter % pollInterval == 0) {
            // 通知服务端刷新 → 服务端通过网络包推送 → 客户端 BE 更新
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
            }
        }

        // 每 tick 都从客户端 BE 读取（服务端同步到达后自动更新）
        formatNBTForDisplay();
    }

    private void formatNBTForDisplay() {
        displayLines.clear();

        // 优先从客户端 BlockEntity 读取实时数据，回退到菜单中的初始快照
        CompoundTag nbt = getLiveNBT();

        if (nbt == null || nbt.isEmpty()) {
            displayLines.add(Component.translatable("gui.ccnavigationtable.sensor_nbt.empty").getString());
            return;
        }

        try {
            String snbt = NbtUtils.prettyPrint(nbt, true);
            String[] lines = snbt.split("\\n");
            for (String line : lines) {
                if (line.length() > 50) {
                    displayLines.add(line.substring(0, 47) + "...");
                } else {
                    displayLines.add(line);
                }
            }
        } catch (Exception e) {
            displayLines.add("Error formatting NBT: " + e.getMessage());
        }
    }

    /**
     * 优先从客户端 BlockEntity 获取实时 NBT，如果不可用则回退到菜单快照。
     */
    private CompoundTag getLiveNBT() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity be = this.minecraft.level.getBlockEntity(menu.getSensorPos());
            if (be instanceof MySensorBlockEntity sensorBE) {
                CompoundTag live = sensorBE.getCachedAttachedNBT();
                if (live != null && !live.isEmpty()) {
                    return live;
                }
            }
        }
        // 回退：菜单中网络传输的初始 NBT
        return menu.getAttachedNBT();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // 绘制半透明深色背景
        guiGraphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xC0101010);
        // 绘制边框
        guiGraphics.fill(x, y, x + this.imageWidth, y + 1, 0xFF555555);
        guiGraphics.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, 0xFF555555);
        guiGraphics.fill(x, y, x + 1, y + this.imageHeight, 0xFF555555);
        guiGraphics.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, 0xFF555555);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 绘制标题（浅色文字适配深色背景）
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFFFFFFF, false);

        // 绘制 NBT 内容
        int startX = 10;
        int startY = 20;

        for (int i = 0; i < displayLines.size(); i++) {
            int lineY = startY + i * LINE_HEIGHT;
            // 只在可见范围内绘制
            if (lineY > this.imageHeight - 15) break;
            guiGraphics.drawString(this.font, displayLines.get(i), startX, lineY, TEXT_COLOR, false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 或 E 键关闭
        if (keyCode == 256 || this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
