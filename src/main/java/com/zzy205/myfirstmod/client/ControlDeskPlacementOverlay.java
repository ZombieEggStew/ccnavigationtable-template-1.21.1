package com.zzy205.myfirstmod.client;

import com.simibubi.create.AllItems;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;

import java.util.List;

/**
 * controlDesk 交互预览：
 * <ul>
 *   <li>手持踏板/操纵杆 → 准星指向 controlDesk 时在安装位显示预览框（绿=可装 / 红=已装）</li>
 *   <li>手持扳手 → 准星指向 controlDesk 时显示已安装控件的安装位（默认绿）；视角命中安装位变红，蹲下右键拆对应模块</li>
 * </ul>
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
        if (type != null) {
            showInstallPreview(mc, hit, type);
            return;
        }
        if (isWrench(held)) {
            showRemovePreview(mc, hit);
        }
    }

    /** 手持控件物品：在安装位显示预览框（绿=可装 / 红=已装）。 */
    private static void showInstallPreview(Minecraft mc, BlockHitResult hit, ControlDeskBlockEntity.ControlType type) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;

        BlockEntity be = mc.level.getBlockEntity(pos);
        boolean installed = be instanceof ControlDeskBlockEntity desk && desk.isInstalled(type);

        showBounds(pos, type, state.getValue(ControlDeskBlock.FACING),
                installed ? COLOR_INVALID : COLOR_VALID, "install");
    }

    /** 手持扳手：已安装控件显示绿色预览框；准星视角命中安装位（点击位置在框内）的模块变红，蹲下右键即可拆除。 */
    private static void showRemovePreview(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControlDeskBlock)) return;

        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof ControlDeskBlockEntity desk)) return;

        Direction facing = state.getValue(ControlDeskBlock.FACING);
        Vec3 click = hit.getLocation();
        for (ControlDeskBlockEntity.ControlType type : ControlDeskBlockEntity.ControlType.values()) {
            if (!desk.isInstalled(type)) continue;
            List<AABB> bounds = ControlDeskBlock.installBounds(type, facing, pos);
            // 命中判断与服务端拆除判定共用 ControlDeskBlock.hitBounds（闭区间+容差）
            boolean hovered = ControlDeskBlock.hitBounds(bounds, click);
            showBounds(pos, type, facing, hovered ? COLOR_INVALID : COLOR_VALID, "remove");
        }
    }

    private static void showBounds(BlockPos pos, ControlDeskBlockEntity.ControlType type,
                                   Direction facing, int color, String mode) {
        List<AABB> bounds = ControlDeskBlock.installBounds(type, facing, pos);
        Outliner outliner = Outliner.getInstance();
        String keyPrefix = "control-desk/" + mode + "/" + pos.toShortString();
        for (int i = 0; i < bounds.size(); i++) {
            outliner.showAABB(keyPrefix + "/" + type + "/" + i, bounds.get(i))
                    .colored(color)
                    .lineWidth(1 / 16f);
        }
    }

    private static ControlDeskBlockEntity.ControlType controlTypeOf(ItemStack stack) {
        if (stack.is(MyModItems.CONTROL_PEDAL.get())) return ControlDeskBlockEntity.ControlType.PEDAL;
        if (stack.is(MyModItems.CONTROL_JOYSTICK.get())) return ControlDeskBlockEntity.ControlType.JOYSTICK;
        return null;
    }

    private static boolean isWrench(ItemStack stack) {
        return stack.is(AllItems.WRENCH.get()) || stack.is(Tags.Items.TOOLS_WRENCH);
    }
}
