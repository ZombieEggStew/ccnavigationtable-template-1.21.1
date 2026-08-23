package com.zzy205.myfirstmod.foundation.gui;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 自定义图标精灵表 — 来自 textures/gui/icons/my_icons.png。
 * 每格 16×16，按列/行索引。
 * 
 * <pre>{@code
 * // 用法：
 * ToggleButton btn = new ToggleButton(x, y,
 *         MyIcons.LOCKED,    // 选中图标
 *         MyIcons.UNLOCKED,  // 未选中图标
 *         0x80FF80);
 * }</pre>
 */
public class MyIcons implements ScreenElement {

    public static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath("ccpe", "textures/gui/my_icons.png");

    private static final int CELL = 16;

    // ═══════ 在这里添加你的图标 ═══════
    // 参数：new MyIcons(列号, 行号)
    // 列0行0 = 贴图左上角第一个 16×16 格

    public static final MyIcons ID = new MyIcons(0, 0);
    public static final MyIcons SHOW_TOOLTIP = new MyIcons(1, 0);
    public static final MyIcons CHANNEL = new MyIcons(2, 0);
    public static final MyIcons BACKGROUND = new MyIcons(3, 0);
    
    public static final MyIcons KNOB = new MyIcons(0, 1);
    public static final MyIcons LEVER = new MyIcons(1, 1);
    public static final MyIcons YAW = new MyIcons(2, 1);
    public static final MyIcons PITCH = new MyIcons(3, 1);

    public static final MyIcons OFFSET = new MyIcons(0, 2);
    public static final MyIcons PERCENT = new MyIcons(1, 2);
    public static final MyIcons INDEX = new MyIcons(2, 2);
    public static final MyIcons ANGLE_LIMIT = new MyIcons(3, 2);

    public static final MyIcons MOUSE = new MyIcons(0, 3);
    public static final MyIcons KEY_BOARD = new MyIcons(1, 3);
    public static final MyIcons UP = new MyIcons(2, 3);
    public static final MyIcons DOWN = new MyIcons(3, 3);

    public static final MyIcons LEFT = new MyIcons(0, 4);
    public static final MyIcons RIGHT = new MyIcons(1, 4);
    public static final MyIcons PEDAL_LEFT_UP = new MyIcons(2, 4);
    public static final MyIcons PEDAL_LEFT_DOWN = new MyIcons(3, 4);

    public static final MyIcons PEDAL_RIGHT_UP = new MyIcons(0, 5);
    public static final MyIcons PEDAL_RIGHT_DOWN = new MyIcons(1, 5);
    public static final MyIcons RECOVER = new MyIcons(2, 5);



    private final int u;
    private final int v;

    private MyIcons(int column, int row) {
        this.u = column * CELL;
        this.v = row * CELL;
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(ATLAS, x, y, u, v, CELL, CELL, 64, 128);
    }
}
