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
        registration.addGhostIngredientHandler(MySensorScreen.class, new SensorGhostHandler());
    }

    /**
     * 幽灵物品槽拖放处理器。
     * 将 JEI 面板中拖出的物品放入传感器 GUI 的幽灵槽位。
     */
    private static class SensorGhostHandler implements IGhostIngredientHandler<MySensorScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(MySensorScreen screen, ITypedIngredient<I> typed, boolean start) {
            var stack = typed.getIngredient(VanillaTypes.ITEM_STACK);
            if (stack.isEmpty()) return List.of();

            ItemStack item = stack.get();
            List<Target<I>> targets = new ArrayList<>(2);

            // 槽位 0
            if (screen.ghostSlot0Bounds != null) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return screen.ghostSlot0Bounds;
                    }

                    @Override
                    public void accept(I ingredient) {
                        screen.updateGhostSlot(0, item);
                    }
                });
            }

            // 槽位 1
            if (screen.ghostSlot1Bounds != null) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return screen.ghostSlot1Bounds;
                    }

                    @Override
                    public void accept(I ingredient) {
                        screen.updateGhostSlot(1, item);
                    }
                });
            }

            return targets;
        }

        @Override
        public void onComplete() {
        }
    }
}
