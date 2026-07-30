package com.zzy205.myfirstmod.compat.jei;

import com.zzy205.myfirstmod.CCNavigationtable;
import com.zzy205.myfirstmod.screen.MySensorMenu;
import com.zzy205.myfirstmod.screen.MySensorScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 集成插件：支持从 JEI 面板拖动物品到传感器 GUI 的幽灵物品槽。
 * 仅在 JEI 存在时由 JEI 加载。
 */
@JeiPlugin
public class AddonJEIPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_ID =
            ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // 幽灵物品已注释
        // registration.addGhostIngredientHandler(MySensorScreen.class, new SensorGhostHandler());
    }

    // /** 幽灵物品槽拖放处理器。 */  // 幽灵物品已注释
    // private static class SensorGhostHandler implements IGhostIngredientHandler<MySensorScreen> {
    //     @Override
    //     public <I> List<Target<I>> getTargetsTyped(MySensorScreen screen, ITypedIngredient<I> typed, boolean start) {
    //         ...  // 已注释
    //     }
    //     @Override
    //     public void onComplete() {}
    // }
}
