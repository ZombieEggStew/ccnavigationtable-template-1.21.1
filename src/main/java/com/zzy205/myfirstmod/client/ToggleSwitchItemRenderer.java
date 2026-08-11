package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import com.zzy205.myfirstmod.block.MonitorPreloadedModels;
import com.zzy205.myfirstmod.monitor.ModuleType;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 钮子开关物品渲染器：底座（toggle_base）+ 拉杆（toggle）叠加。
 */
public class ToggleSwitchItemRenderer extends CustomRenderedItemModelRenderer {

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model,
                          PartialItemModelRenderer renderer,
                          ItemDisplayContext transformType, PoseStack ms,
                          MultiBufferSource buffer, int light, int overlay) {

        // ① 底座（JSON 方块模型）
        BakedModel baseModel = MonitorPreloadedModels.getModel(ModuleType.TOGGLE_SWITCH);
        if (baseModel != null) {
            renderer.render(baseModel, light);
        }

        // ② 拉杆（OBJ 模型），在底座上方，偏移
        BakedModel leverModel = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.TOGGLE_LEVER);
        if (leverModel != null) {
            ms.pushPose();
            ms.translate(1f / 32f, 0, 1f / 32f);
            renderer.render(leverModel, light);
            ms.popPose();
        }
    }
}
