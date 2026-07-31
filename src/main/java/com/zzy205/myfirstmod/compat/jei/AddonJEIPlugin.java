package com.zzy205.myfirstmod.compat.jei;

import com.zzy205.myfirstmod.CCNavigationtable;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 闆嗘垚鎻掍欢锛氭敮鎸佷粠 JEI 闈㈡澘鎷栧姩鐗╁搧鍒颁紶鎰熷櫒 / 鎺ユ敹锟?GUI 鐨勫菇鐏电墿鍝佹Ы锟?
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
        registration.addGhostIngredientHandler(RedstoneTransceiverScreen.class, new ReceiverGhostHandler());
    }

    /** 鎺ユ敹锟?banner 骞界伒鐗╁搧妲芥嫋鏀惧鐞嗗櫒 */
    private static class ReceiverGhostHandler implements IGhostIngredientHandler<RedstoneTransceiverScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(RedstoneTransceiverScreen screen, ITypedIngredient<I> typed, boolean start) {
            List<Target<I>> targets = new ArrayList<>();
            for (int i = 0; i < screen.getBannerCount(); i++) {
                for (int s = 0; s < 2; s++) {
                    final int bannerIdx = i;
                    final int slotIdx = s;
                    Rect2i area = screen.getGhostSlotBounds(bannerIdx, slotIdx);
                    targets.add(new Target<I>() {
                        @Override
                        public Rect2i getArea() { return area; }
                        @Override
                        public void accept(I ingredient) {
                            if (ingredient instanceof ItemStack stack) {
                                screen.updateGhostSlot(bannerIdx, slotIdx, stack);
                            }
                        }
                    });
                }
            }
            return targets;
        }

        @Override
        public void onComplete() {}
    }
}
