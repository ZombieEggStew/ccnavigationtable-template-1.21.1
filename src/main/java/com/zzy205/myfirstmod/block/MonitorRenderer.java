package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Monitor 的 BER — 当前为最小骨架，后续模块化部件渲染在此扩展。
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(MonitorBlockEntity be, float partialTicks, PoseStack ms,
                       MultiBufferSource buffer, int light, int overlay) {
        // 暂无动态部件 — 后续在此添加模块化面板/按钮/滑条渲染
    }
}
