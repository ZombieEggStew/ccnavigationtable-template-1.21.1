package com.zzy205.myfirstmod.screen;

import com.simibubi.create.foundation.gui.AllIcons;
import com.zzy205.myfirstmod.foundation.gui.widget.HoverTintIconButton;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 短程信号链接器 GUI。窗口 144×68，背景取自 gui_link.png 的 (0,0) 区域
 * （贴图文件总尺寸 144×112，上半 68px 为窗口背景，下半留待后续阶段使用）。
 * <p>
 * 当前阶段只显示背景：频道滚轮 / 「加载物理体」开关 / 非物理体提示将在后续阶段接入。
 */
public class ShortRangeLinkerScreen extends AbstractContainerScreen<ShortRangeLinkerMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/gui_link.png");

    /** 窗口尺寸（= 背景区域尺寸，贴图 (0,0) 起 144×68） */
    private static final int WIN_W = 144;
    private static final int WIN_H = 111;

    /** 贴图文件实际尺寸（g.blit 最后两个参数，用于 UV 归一化） */
    private static final int TEX_W = 144;
    private static final int TEX_H = 144;

    private static final int DONE_BTN_RIGHT = 25;
    private static final int DONE_BTN_BOTTOM = 24;

    @Override
    protected void init() {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
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

    public ShortRangeLinkerScreen(ShortRangeLinkerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 背景从贴图 (0,0) 起，整块 144×68（g.blit 最后两个参数是贴图文件实际尺寸）
        g.blit(GUI_TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题：先放在顶部居中（Y=6），位置参数由用户自行调整
        int titleWidth = this.font.width(this.title);
        int titleX = (WIN_W - titleWidth) / 2;
        g.drawString(this.font, this.title, titleX, 6, 0xFFFFFFFF, false);
        // 不画「物品栏」标签（playerInventoryTitle）
    }
}
