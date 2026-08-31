package com.zzy205.myfirstmod.compat.jei;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.screen.RedstoneTransceiverScreen;
import com.zzy205.myfirstmod.screen.ShortRangeLinkerScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JEI 集成插件：支持从 JEI 面板拖动物品到传感器 / 接收器 GUI 的幽灵物品槽
 */
@JeiPlugin
public class AddonJEIPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_ID =
            ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RedstoneTransceiverScreen.class, new ReceiverGhostHandler());
        registration.addGuiContainerHandler(ShortRangeLinkerScreen.class, new LinkerGuiHandler());
    }

    /**
     * 短程链接器 GUI：把窗口右侧区域声明为 GUI 扩展占用，让 JEI 物品栏/书签无处可放而隐藏
     * （与 control_desk 等纯 Screen 菜单的行为一致；窗口本身只有 144×68，不需要 JEI 面板）。
     */
    private static class LinkerGuiHandler implements IGuiContainerHandler<ShortRangeLinkerScreen> {
        @Override
        public List<Rect2i> getGuiExtraAreas(ShortRangeLinkerScreen screen) {
            int guiRight = screen.getGuiLeft() + screen.getXSize();
            if (guiRight >= screen.width) {
                return Collections.emptyList(); // GUI 已占满宽度，JEI 本就不会显示
            }
            return List.of(new Rect2i(guiRight, 0, screen.width - guiRight, screen.height));
        }
    }

    /** 接收器 banner 幽灵物品槽拖放处理器 */
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
