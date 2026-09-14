package com.zzy205.myfirstmod.item;

import com.simibubi.create.AllSoundEvents;
import com.zzy205.myfirstmod.block.EngineCoreBlockEntity;
import com.zzy205.myfirstmod.block.MyModBlocks;
import com.zzy205.myfirstmod.block.SteamPowerChamberBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 燃烧室物品（流体/蒸汽共用）：P6 物理排斥的<b>主拦截点</b>。
 * <p>
 * 覆写 {@link #place(BlockPlaceContext)}（同 create:factory_gauge 的
 * {@code FactoryPanelBlockItem#place} 模式）：在 {@code super.place} 之前检查——
 * 贴附的引擎模块已挂<b>另一种</b>燃烧室时，DENY 音效 + 状态条提示 + 返回 FAIL 拒绝放置。
 * {@code place} 是 {@code BlockItem.useOn} 的终点（返回 FAIL 无后续回退），真正拦死放置。
 * <p>
 * 贴附核心的定位：燃烧室 FACING = 点击面，背面 = 放置格反方向。
 * <b>注意</b>：{@code BlockPlaceContext#getClickedPos()} 在点击不可替换方块（引擎核心）时
 * 返回的是<b>放置格</b>而非被点击方块（{@code replaceClicked=false → relativePos}），
 * 因此贴附核心 = {@code getClickedPos().relative(getClickedFace().getOpposite())}。
 */
public class ChamberBlockItem extends BlockItem {

    public ChamberBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        // 放置格背后的方块 = 燃烧室背面贴附的方块（FACING 反方向）
        BlockPos attachPos = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        BlockState attachState = level.getBlockState(attachPos);
        if (attachState.is(MyModBlocks.engine_core.get())) {
            boolean isSteam = getBlock() instanceof SteamPowerChamberBlock;
            boolean blocked = isSteam
                    ? EngineCoreBlockEntity.moduleHasFluidChambers(level, attachPos)
                    : EngineCoreBlockEntity.moduleHasSteamChambers(level, attachPos);
            if (blocked) {
                AllSoundEvents.DENY.playOnServer(level, context.getClickedPos());
                Player player = context.getPlayer();
                if (player != null)
                    player.displayClientMessage(Component.translatable("tooltip.ccpe.engine.chamber_type_conflict"), true);
                return InteractionResult.FAIL;
            }
        }
        return super.place(context);
    }
}
