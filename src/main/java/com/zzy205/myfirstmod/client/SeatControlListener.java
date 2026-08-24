package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.ControlDeskSeatLink;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 坐垫操作模式按键监听（阶段二：debug 输出 + 操纵杆方向向量，payload / BE 状态待接入）。
 * <p>
 * 每 tick 现查操作模式（骑乘 Create 坐垫 + 坐垫四邻有 controlDesk，见 {@link ControlDeskSeatLink}）：
 * <ul>
 *   <li>进入操作模式时输出联动信息（坐垫位置 + 联动控制台列表），用于验证判定①</li>
 *   <li>玩家按下任一联动控制台<b>已安装控件</b>所配置的按键（边沿检测）→ debug 输出按键名 / 含义 / 归属控制台</li>
 *   <li>持续计算操纵杆方向向量（方向槽位并集 + 对角归一化）→ 写入 {@link SeatControlState}，供 HUD overlay 显示</li>
 * </ul>
 * 按键来源：联动各 controlDesk 的 BE 配置（客户端从 BE 读配置，服务端权威同步；未安装控件的控制台自动忽略）。
 */
public class SeatControlListener {

    /** 操纵杆方向槽位（用于把各控制台的按键绑定聚合成方向向量）。 */
    private enum JoyDir { UP, DOWN, LEFT, RIGHT }

    /** 上一次 tick 处于按下状态的按键名（InputConstants.Key.getName() 格式） */
    private static final Set<String> lastDown = new HashSet<>();
    /** 上一次 tick 的操作模式坐垫位置（用于检测进入/切换坐垫，重置边沿状态） */
    private static BlockPos lastSeatPos;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SeatControlListener::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (mc.screen != null) return; // GUI 打开时不判定按键

        BlockPos seatPos = ControlDeskSeatLink.seatPosOf(mc.player);
        if (seatPos == null) {
            reset();
            return;
        }

        List<ControlDeskBlockEntity> desks = ControlDeskSeatLink.findLinkedDesks(mc.level, seatPos);
        if (desks.isEmpty()) {
            // 坐垫四邻没有 controlDesk：仅当之前处于联动状态（lastSeatPos != null）时输出一次联动解除，
            // 否则坐在无控制台坐垫上每 tick 都会打日志
            if (lastSeatPos != null) {
                CCPeripheralExtender.LOGGER.info("[SeatControl] 坐垫@{} 联动解除（四邻无 controlDesk）", seatPos.toShortString());
            }
            lastSeatPos = null;
            lastDown.clear();
            SeatControlState.clear();
            return;
        }

        // 进入 / 切换坐垫 → 重置边沿状态并输出联动信息（仅转换瞬间打一次）
        if (!Objects.equals(lastSeatPos, seatPos)) {
            lastDown.clear();
            lastSeatPos = seatPos;
            StringBuilder sb = new StringBuilder("[SeatControl] 进入操作模式：坐垫@")
                    .append(seatPos.toShortString())
                    .append(" 联动 ").append(desks.size()).append(" 个 controlDesk：");
            for (ControlDeskBlockEntity desk : desks) {
                sb.append(" [").append(desk.getBlockPos().toShortString()).append(']');
            }
            CCPeripheralExtender.LOGGER.info(sb.toString());
        }

        List<Binding> bindings = collectBindings(desks);
        long window = mc.getWindow().getWindow();
        Set<String> nowDown = new HashSet<>();
        for (Binding binding : bindings) {
            if (isDown(window, binding.keyName())) nowDown.add(binding.keyName());
        }

        // 边沿检测：本次新按下的按键 → debug 输出
        for (String key : nowDown) {
            if (lastDown.contains(key)) continue;
            StringBuilder msg = new StringBuilder("[SeatControl] 按下 ")
                    .append(displayName(key)).append(" (").append(key).append(')');
            for (Binding binding : bindings) {
                if (binding.keyName().equals(key)) {
                    msg.append(" | ").append(binding.label())
                            .append(" @控制台").append(binding.deskPos().toShortString());
                }
            }
            CCPeripheralExtender.LOGGER.info(msg.toString());
        }

        // 操纵杆方向向量：方向槽位并集（任一联动控制台该方向绑定的键按下即生效），对角按下归一化
        boolean hasJoystick = false;
        float joyX = 0f, joyY = 0f;
        for (Binding binding : bindings) {
            if (binding.dir() == null) continue;
            hasJoystick = true;
            if (!nowDown.contains(binding.keyName())) continue;
            switch (binding.dir()) {
                case UP -> joyY += 1f;
                case DOWN -> joyY -= 1f;
                case LEFT -> joyX -= 1f;
                case RIGHT -> joyX += 1f;
            }
        }
        if (joyX != 0f && joyY != 0f) {
            float inv = 0.70710678f; // 1/sqrt(2)：对角归一化
            joyX *= inv;
            joyY *= inv;
        }
        SeatControlState.update(true, hasJoystick, joyX, joyY);

        lastDown.clear();
        lastDown.addAll(nowDown);
    }

    private static void reset() {
        lastDown.clear();
        lastSeatPos = null;
        SeatControlState.clear();
    }

    // ════════════════ 按键收集 ════════════════

    /** 一条按键绑定：按键名（getName() 格式）+ 含义 + 归属控制台位置 + 操纵杆方向槽位（踏板为 null）。 */
    private record Binding(String keyName, String label, BlockPos deskPos, JoyDir dir) {}

    /** 收集联动控制台已安装控件的全部按键绑定（未安装的控件忽略，空绑定跳过）。 */
    private static List<Binding> collectBindings(List<ControlDeskBlockEntity> desks) {
        List<Binding> out = new ArrayList<>();
        for (ControlDeskBlockEntity desk : desks) {
            BlockPos pos = desk.getBlockPos();
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.PEDAL)) {
                add(out, pos, desk.getPedalKeyLeftDown(), "左踏板 踩下", null);
                add(out, pos, desk.getPedalKeyLeftUp(), "左踏板 抬起", null);
                add(out, pos, desk.getPedalKeyRightDown(), "右踏板 踩下", null);
                add(out, pos, desk.getPedalKeyRightUp(), "右踏板 抬起", null);
            }
            if (desk.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK)) {
                add(out, pos, desk.getJoystickKeyUp(), "操纵杆 前推", JoyDir.UP);
                add(out, pos, desk.getJoystickKeyDown(), "操纵杆 后拉", JoyDir.DOWN);
                add(out, pos, desk.getJoystickKeyLeft(), "操纵杆 左摆", JoyDir.LEFT);
                add(out, pos, desk.getJoystickKeyRight(), "操纵杆 右摆", JoyDir.RIGHT);
            }
        }
        return out;
    }

    private static void add(List<Binding> out, BlockPos pos, String keyName, String label, JoyDir dir) {
        if (keyName != null && !keyName.isEmpty()) {
            out.add(new Binding(keyName, label, pos, dir));
        }
    }

    // ════════════════ 按键状态 ════════════════

    /** 键盘 / 鼠标按键当前是否按下（按键名 → 原始输入状态，参考 Create AllKeys）。 */
    private static boolean isDown(long window, String keyName) {
        try {
            InputConstants.Key key = InputConstants.getKey(keyName);
            if (key.getType() == InputConstants.Type.MOUSE) {
                return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            }
            return InputConstants.isKeyDown(window, key.getValue());
        } catch (Exception e) {
            return false;
        }
    }

    /** 按键名 → 可读显示名（与 DoubleInputBar 一致）。 */
    private static String displayName(String keyName) {
        try {
            return InputConstants.getKey(keyName).getDisplayName().getString();
        } catch (Exception e) {
            return keyName;
        }
    }
}
