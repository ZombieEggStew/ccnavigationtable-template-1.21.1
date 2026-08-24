package com.zzy205.myfirstmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.block.ControlDeskBlockEntity;
import com.zzy205.myfirstmod.block.ControlDeskSeatLink;
import com.zzy205.myfirstmod.block.JoystickTilt;
import com.zzy205.myfirstmod.network.SeatInputPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 坐垫操作模式按键监听（阶段二：debug 输出 + 操纵杆方向向量 + 运行时输入上报）。
 * <p>
 * 每 tick 现查操作模式（骑乘 Create 坐垫 + 坐垫四邻有 controlDesk，见 {@link ControlDeskSeatLink}）：
 * <ul>
 *   <li>进入操作模式时输出联动信息（坐垫位置 + 联动控制台列表），用于验证判定①</li>
 *   <li>玩家按下任一联动控制台<b>已安装控件</b>所配置的按键（边沿检测）→ debug 输出按键名 / 含义 / 归属控制台</li>
 *   <li>持续计算操纵杆方向向量（方向槽位并集）→ 写入 {@link SeatControlState}（仅 HUD overlay 用），
 *       并通过 {@link SeatInputPayload} 每 tick 上报服务端 —— 服务端是操纵杆轴状态的权威来源（见
 *       {@link ControlDeskBlockEntity#tickServer}），渲染直接读 BE 轴值</li>
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
        if (mc.screen != null) {
            // GUI 打开时不判定按键；向服务端发一次释放（自由模式回正 / 档位模式保持），避免操纵杆卡在按住状态
            if (lastSeatPos != null) {
                sendInput(lastSeatPos, false, false, false, false);
            }
            return;
        }

        BlockPos seatPos = ControlDeskSeatLink.seatPosOf(mc.player);
        if (seatPos == null) {
            // 离开坐垫：发一次释放（服务端租约校验也会兜底清除）
            if (lastSeatPos != null) {
                sendInput(lastSeatPos, false, false, false, false);
            }
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

        // 联动控制台列表写入共享状态（供各控制台动画判断是否被本地玩家操控）
        SeatControlState.setLinkedDesks(desks.stream()
                .map(ControlDeskBlockEntity::getBlockPos).toList());

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

        // ── 操纵杆模拟轴（真实的模拟量，每 tick 线性累加）──
        // 每个轴独立：目标 = 方向键（方向槽位并集，任一联动控制台该方向绑定的键按下即生效）；
        // 自由模式：按下按 1/满偏tick 累加（速度可配置），松开每 tick 向 0 累加 1/回正时间（0 = 关闭回正，保持不动）；
        // 档位模式：关闭自动回正，检测按键按下边沿（上一 tick 该方向无键按下），进/退一档（轴值 = -1 + 2k/(档位数-1)，步长 2/(档位数-1)）；
        //   离开坐垫时档位保持（物理换挡杆语义，见 SeatControlState.gearHold*）。
        // 配置取联动中第一个装了操纵杆的控制台（X 轴用 Yaw 配置，Y 轴用 Pitch 配置）。
        ControlDeskBlockEntity joyDesk = desks.stream()
                .filter(d -> d.isInstalled(ControlDeskBlockEntity.ControlType.JOYSTICK))
                .findFirst().orElse(null);
        boolean hasJoystick = joyDesk != null;

        boolean rawX = false, rawY = false;
        boolean up = false, down = false, left = false, right = false;
        for (Binding binding : bindings) {
            if (binding.dir() == null || !nowDown.contains(binding.keyName())) continue;
            switch (binding.dir()) {
                case UP -> { up = true; rawY = true; }
                case DOWN -> { down = true; rawY = true; }
                case LEFT -> { left = true; rawX = true; }
                case RIGHT -> { right = true; rawX = true; }
            }
        }
        float targetX = right && !left ? 1f : (left && !right ? -1f : 0f);
        float targetY = up && !down ? 1f : (down && !up ? -1f : 0f);
        // 档位模式需要按键「按下边沿」（当前按下 且 上一 tick 该方向无键按下）
        boolean upEdge = up && !anyDirDown(bindings, JoyDir.UP, lastDown);
        boolean downEdge = down && !anyDirDown(bindings, JoyDir.DOWN, lastDown);
        boolean leftEdge = left && !anyDirDown(bindings, JoyDir.LEFT, lastDown);
        boolean rightEdge = right && !anyDirDown(bindings, JoyDir.RIGHT, lastDown);
        int returnTicksX = joyDesk != null ? joyDesk.getJoystickReturnTimeYaw()
                : ControlDeskBlockEntity.DEFAULT_JOYSTICK_RETURN_TIME;
        int returnTicksY = joyDesk != null ? joyDesk.getJoystickReturnTime()
                : ControlDeskBlockEntity.DEFAULT_JOYSTICK_RETURN_TIME;
        boolean gearModeX = joyDesk != null && joyDesk.isGearModeYaw();
        boolean gearModeY = joyDesk != null && joyDesk.isGearModePitch();
        int gearCountX = joyDesk != null ? joyDesk.getGearCountYaw() : ControlDeskBlockEntity.DEFAULT_GEAR_COUNT;
        int gearCountY = joyDesk != null ? joyDesk.getGearCountPitch() : ControlDeskBlockEntity.DEFAULT_GEAR_COUNT;
        int freeSpeedTicksX = joyDesk != null ? joyDesk.getJoystickFreeSpeedYaw()
                : ControlDeskBlockEntity.DEFAULT_JOYSTICK_FREE_SPEED;
        int freeSpeedTicksY = joyDesk != null ? joyDesk.getJoystickFreeSpeedPitch()
                : ControlDeskBlockEntity.DEFAULT_JOYSTICK_FREE_SPEED;

        // 自由模式：按下按 1/满偏tick 累加（速度可配置）；档位模式：无自动回正，按下边沿进/退一档
        // （本地模拟仅供 HUD overlay；服务端用同一套 JoystickTilt 动力学权威模拟 BE 轴值）
        float axisX = gearModeX
                ? JoystickTilt.stepGear(SeatControlState.getAxisX(), rightEdge, leftEdge, gearCountX)
                : JoystickTilt.stepAxis(SeatControlState.getAxisX(), targetX,
                        JoystickTilt.pressStep(freeSpeedTicksX), JoystickTilt.returnStep(returnTicksX));
        float axisY = gearModeY
                ? JoystickTilt.stepGear(SeatControlState.getAxisY(), upEdge, downEdge, gearCountY)
                : JoystickTilt.stepAxis(SeatControlState.getAxisY(), targetY,
                        JoystickTilt.pressStep(freeSpeedTicksY), JoystickTilt.returnStep(returnTicksY));

        // 档位模式开启的轴：离开坐垫（clear）时保持档位不归零（仅 overlay 本地模拟）
        SeatControlState.setGearHold(gearModeX, gearModeY);
        SeatControlState.update(true, hasJoystick, axisX, axisY, rawX, rawY, Math.abs(axisX), Math.abs(axisY));

        // 运行时输入上报服务端（服务端权威模拟 + getUpdatePacket 广播；有操纵杆的联动台才需要）
        if (hasJoystick) {
            sendInput(seatPos, up, down, left, right);
        }

        lastDown.clear();
        lastDown.addAll(nowDown);
    }

    /** 上一 tick 该方向是否有键按下（档位模式「按下边沿」判定用）。 */
    private static boolean anyDirDown(List<Binding> bindings, JoyDir dir, Set<String> down) {
        for (Binding b : bindings) {
            if (b.dir() == dir && down.contains(b.keyName())) return true;
        }
        return false;
    }

    /** 发送坐垫操作输入到服务端（服务端校验后驱动 BE 轴状态，见 ControlDeskPacketHandlers）。 */
    private static void sendInput(BlockPos seatPos, boolean up, boolean down, boolean left, boolean right) {
        PacketDistributor.sendToServer(new SeatInputPayload(seatPos, up, down, left, right));
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
