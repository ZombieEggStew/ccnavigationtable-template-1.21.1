package com.zzy205.myfirstmod.foundation.gui;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public class MyUIElements implements ScreenElement{

    public static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/gui_2.png");

    private static final int ATLAS_SIZE_X = 192;
    private static final int ATLAS_SIZE_Y = 384;

    // 黑色背景
    public static final MyUIElements BAR_BACKGROUND = new MyUIElements(0, 320, ATLAS_SIZE_X, 28);
    // 滚轮输入短框背景
    public static final MyUIElements SCROLL_INPUT_SHORT = new MyUIElements(0, 208, ATLAS_SIZE_X, 18);
    // 输入长框背景
    public static final MyUIElements INPUT_LONG = new MyUIElements(0, 240, ATLAS_SIZE_X, 18);
    // 滚轮输入长框背景
    public static final MyUIElements SCROLL_INPUT_LONG = new MyUIElements(0, 272, ATLAS_SIZE_X, 18);

    public static final MyUIElements INPUT_DOUBLE = new MyUIElements(0, 176, ATLAS_SIZE_X, 18);
    public static final MyUIElements SCROLL_DOUBLE = new MyUIElements(0, 352, ATLAS_SIZE_X, 18);



    public static final MyUIElements BACKGROUND = new MyUIElements(0, 0, ATLAS_SIZE_X, 169);

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    private MyUIElements(int x, int y, int width, int height) 
    {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }


    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(ATLAS, x, y, this.x, this.y, this.width, this.height, ATLAS_SIZE_X, ATLAS_SIZE_Y);
    }
}
