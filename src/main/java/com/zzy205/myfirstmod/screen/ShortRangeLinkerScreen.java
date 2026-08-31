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

    /** 贴图文件实际尺寸（g.blit 最后两个参数，用于 UV 归一化） */
    private static final int TEX_W = 144;
    private static final int TEX_H = 144;

    /** 窗口尺寸（= 背景区域尺寸，贴图 (0,0) 起 144×68） */
    private static final int WIN_W = 144;
    private static final int WIN_H = 92;

    /** 控件区贴图：贴图 (0,96) 起 144×40 */
    private static final int CTRL_U = 0;
    private static final int CTRL_V = 96;
    private static final int CTRL_W = 144;
    private static final int CTRL_H = 40;
    /** 控件区绘制位置（相对窗口顶部，先放在背景正下方，位置参数由用户自行调整） */
    private static final int CTRL_Y_OFFSET = 18;

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
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 覆盖默认：跳过 renderTransparentBackground（整屏暗色遮罩），背景不变暗，直接画窗口贴图
        this.renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        // 背景从贴图 (0,0) 起，整块 144×68（g.blit 最后两个参数是贴图文件实际尺寸）
        g.blit(GUI_TEXTURE, winLeft, winTop, 0, 0, WIN_W, WIN_H, TEX_W, TEX_H);

        if (this.menu.isOnPhysicsBody()) {
            // 在物理体上：绘制控件区贴图（贴图 (0,96) 起 144×40）
            g.blit(GUI_TEXTURE, winLeft, winTop + CTRL_Y_OFFSET, CTRL_U, CTRL_V, CTRL_W, CTRL_H, TEX_W, TEX_H);
        } else {
            // 非物理体：显示「只在物理体上可用」提示
            Component msg = Component.translatable("gui.ccpe.short_range_linker.require_body");
            int tx = winLeft + (WIN_W - this.font.width(msg)) / 2;
            g.drawString(this.font, msg, tx, winTop + 38, 0xFFFFFF, true);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题：先放在顶部居中（Y=6），位置参数由用户自行调整
        int winLeft = (this.width - WIN_W) / 2;
        int winTop = (this.height - WIN_H) / 2;
        g.drawString(this.font, this.title, winLeft + 4, winTop + 3, 0xFFFFFFFF, true);
        // 不画「物品栏」标签（playerInventoryTitle）
    }
}
