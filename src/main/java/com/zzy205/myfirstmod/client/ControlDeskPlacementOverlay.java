package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.block.ControlDeskBlock;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.item.MyModItems;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * controlDesk 控件安装预览：手持踏板/操纵杆时，准星指向 controlDesk（接近即显示），
 * 在安装位用 Catnip Outliner 显示预览框（未安装绿色 / 已安装红色）。
 * 每 tick 重新 show，离开/换物品后自动消失（Outliner 语义）。
 */
public class ControlDeskPlacementOverlay {

    private static final int COLOR_VALID = 0x4CDA64;
    private static final int COLOR_INVALID = 0xFF5E5E;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ControlDeskPlacementOverlay::onClientTick);
    }

    @SubscribeEvent
    private static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;

        ItemStack held = mc.player.getMainHandItem();
        ControlDeskBlockEntity.ControlType type = controlTypeOf(held);
        if (type == null) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;

        // 已安装 → 红色（不可重复安装）；未安装 → 绿色
        BlockEntity be = mc.level.getBlockEntity(pos);
        boolean installed = be instanceof ControlDeskBlockEntity desk && desk.isInstalled(type);

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        List<AABB> bounds = ControlDeskBlock.installBounds(type, facing, pos);

        Outliner outliner = Outliner.getInstance();
        String keyPrefix = "control-desk-preview/" + pos.toShortString();
        for (int i = 0; i < bounds.size(); i++) {
            outliner.showAABB(keyPrefix + "/" + type + "/" + i, bounds.get(i))
                    .colored(installed ? COLOR_INVALID : COLOR_VALID)
                    .lineWidth(1 / 16f);
        }
    }

    private static ControlDeskBlockEntity.ControlType controlTypeOf(ItemStack stack) {
        if (stack.is(MyModItems.CONTROL_PEDAL.get())) return ControlDeskBlockEntity.ControlType.PEDAL;
        if (stack.is(MyModItems.CONTROL_JOYSTICK.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK;
        return null;
    }
}
