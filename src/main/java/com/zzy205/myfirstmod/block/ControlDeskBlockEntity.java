package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import com.zzy205.myfirstmod.compat.cc.ControlDeskPeripheral;
import com.zzy205.myfirstmod.compat.cc.ControlDeskRegistry;
import com.zzy205.myfirstmod.compat.cc.GlobalChannelRegistry;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 控制台方块实体 — 保存已安装控件（踏板一对 / 操纵杆）。
 * NBT 持久化 + 同步（兼容 Create 蓝图，参考 RedstoneTransceiverBlockEntity）。
 */
public class ControlDeskBlockEntity extends BlockEntity implements PartialSafeNBT {

    /** 可安装到控制台的控件类型 */
    public enum ControlType {
        PEDAL, JOYSTICK, MONITOR_2, THROTTLE, JOYSTICK_2
    }

    /** 操纵杆回正时间（tick）默认值与范围（与 JoystickModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_JOYSTICK_RETURN_TIME = 2;
    public static final int MIN_JOYSTICK_RETURN_TIME = 0;
    public static final int MAX_JOYSTICK_RETURN_TIME = 100;

    /** 操纵杆档位模式（档位数）默认值与范围（与 JoystickModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_GEAR_COUNT = 4;
    public static final int MIN_GEAR_COUNT = 1;
    public static final int MAX_GEAR_COUNT = 31;

    /** 操纵杆自由模式累加速度（满偏所需 tick 数，速度 = 1/数值 每 tick）默认值与范围（与 JoystickModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_JOYSTICK_FREE_SPEED = 2;
    public static final int MIN_JOYSTICK_FREE_SPEED = 1;
    public static final int MAX_JOYSTICK_FREE_SPEED = 100;

    /** 操纵杆四向按键默认值（InputConstants.Key.getName() 格式，如 "key.keyboard.w"；空串 = 未绑定）。 */
    public static final String DEFAULT_JOYSTICK_KEY_UP = "key.keyboard.w";
    public static final String DEFAULT_JOYSTICK_KEY_DOWN = "key.keyboard.s";
    public static final String DEFAULT_JOYSTICK_KEY_LEFT = "key.keyboard.a";
    public static final String DEFAULT_JOYSTICK_KEY_RIGHT = "key.keyboard.d";

    /** 脚踏板回正时间（tick）默认值与范围（与 PedalModuleScreen 滚轮条一致；左右两个踏板共用同一值）。 */
    public static final int DEFAULT_PEDAL_RETURN_TIME = 2;
    public static final int MIN_PEDAL_RETURN_TIME = 0;
    public static final int MAX_PEDAL_RETURN_TIME = 100;

    /** 脚踏板满偏时间（tick，踩下/抬起按住到满偏所需 tick 数，速度 = 1/数值 每 tick）默认值与范围（与 PedalModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_PEDAL_FREE_SPEED = 2;
    public static final int MIN_PEDAL_FREE_SPEED = 1;
    public static final int MAX_PEDAL_FREE_SPEED = 100;

    /** 脚踏板按键默认值（InputConstants.Key.getName() 格式）：左踏板 踩下=Q / 抬起=E，右踏板 踩下=E / 抬起=Q。 */
    public static final String DEFAULT_PEDAL_KEY_LEFT_UP = "key.keyboard.e";
    public static final String DEFAULT_PEDAL_KEY_LEFT_DOWN = "key.keyboard.q";
    public static final String DEFAULT_PEDAL_KEY_RIGHT_UP = "key.keyboard.q";
    public static final String DEFAULT_PEDAL_KEY_RIGHT_DOWN = "key.keyboard.e";

    /** 油门杆档位切换节奏（tick）默认值与范围（与 ThrottleModuleScreen 滚轮条一致）：按住满 N tick 进/退一档。 */
    public static final int DEFAULT_THROTTLE_TICKS_PER_GEAR = ThrottleMotion.TICKS_PER_GEAR;
    public static final int MIN_THROTTLE_TICKS_PER_GEAR = 1;
    public static final int MAX_THROTTLE_TICKS_PER_GEAR = 100;

    /** 油门杆前进/后退按键默认值（InputConstants.Key.getName() 格式；空串 = 未绑定）：空格 = 前进（模型空间 +x）/ 左Ctrl = 后退（-x）。 */
    public static final String DEFAULT_THROTTLE_KEY_FORWARD = "key.keyboard.space";
    public static final String DEFAULT_THROTTLE_KEY_BACK = "key.keyboard.left.control";

    private static final String TAG_PEDAL = "PedalInstalled";
    private static final String TAG_JOYSTICK = "JoystickInstalled";
    private static final String TAG_MONITOR_2 = "Monitor2Installed";
    private static final String TAG_THROTTLE = "ThrottleInstalled";
    private static final String TAG_JOYSTICK_2 = "Joystick2Installed";
    private static final String TAG_JOYSTICK_2_PLACE_X = "Joystick2PlaceX";
    private static final String TAG_JOYSTICK_2_PLACE_Z = "Joystick2PlaceZ";
    private static final String TAG_THROTTLE_PLACE_X = "ThrottlePlaceX";
    private static final String TAG_THROTTLE_PLACE_Z = "ThrottlePlaceZ";
    private static final String TAG_MONITOR_2_PLACE_X = "Monitor2PlaceX";
    private static final String TAG_MONITOR_2_PLACE_Z = "Monitor2PlaceZ";
    private static final String TAG_BACK_SLOT_ROTATION = "BackSlotRotation";
    private static final String TAG_JOYSTICK_RETURN_TIME = "JoystickReturnTime";
    private static final String TAG_JOYSTICK_RETURN_TIME_YAW = "JoystickReturnTimeYaw";
    private static final String TAG_GEAR_MODE_PITCH = "GearModePitch";
    private static final String TAG_GEAR_COUNT_PITCH = "GearCountPitch";
    private static final String TAG_GEAR_MODE_YAW = "GearModeYaw";
    private static final String TAG_GEAR_COUNT_YAW = "GearCountYaw";
    private static final String TAG_JOYSTICK_FREE_SPEED_PITCH = "JoystickFreeSpeedPitch";
    private static final String TAG_JOYSTICK_FREE_SPEED_YAW = "JoystickFreeSpeedYaw";
    private static final String TAG_JOYSTICK_AXIS_X = "JoystickAxisX";   // 运行时轴状态（不落盘，仅 getUpdateTag 同步）
    private static final String TAG_JOYSTICK_AXIS_Y = "JoystickAxisY";
    private static final String TAG_PEDAL_LEFT_AXIS = "PedalLeftAxis";   // 运行时踏板轴（不落盘，仅 getUpdateTag 同步）
    private static final String TAG_PEDAL_RIGHT_AXIS = "PedalRightAxis";
    private static final String TAG_THROTTLE_AXIS = "ThrottleAxis";      // 运行时油门轴（不落盘，仅 getUpdateTag 同步）
    private static final String TAG_JOYSTICK_KEY_UP = "JoystickKeyUp";
    private static final String TAG_JOYSTICK_KEY_DOWN = "JoystickKeyDown";
    private static final String TAG_JOYSTICK_KEY_LEFT = "JoystickKeyLeft";
    private static final String TAG_JOYSTICK_KEY_RIGHT = "JoystickKeyRight";
    private static final String TAG_PEDAL_RETURN_TIME = "PedalReturnTime";
    private static final String TAG_PEDAL_FREE_SPEED = "PedalFreeSpeed";
    private static final String TAG_PEDAL_KEY_LEFT_UP = "PedalKeyLeftUp";
    private static final String TAG_PEDAL_KEY_LEFT_DOWN = "PedalKeyLeftDown";
    private static final String TAG_PEDAL_KEY_RIGHT_UP = "PedalKeyRightUp";
    private static final String TAG_PEDAL_KEY_RIGHT_DOWN = "PedalKeyRightDown";
    private static final String TAG_THROTTLE_KEY_FORWARD = "ThrottleKeyForward";
    private static final String TAG_THROTTLE_KEY_BACK = "ThrottleKeyBack";
    private static final String TAG_THROTTLE_TICKS_PER_GEAR = "ThrottleTicksPerGear";
    private static final String TAG_CHANNEL = "Channel";
    private static final String TAG_OCCUPIED_CHANNELS = "OccupiedChannels";

    private boolean pedalInstalled;
    private boolean joystickInstalled;
    private boolean monitor2Installed;   // monitor_2 / throttle / joystick_2 共用桌体后缘上方插槽，互斥安装
    private boolean throttleInstalled;
    private boolean joystick2Installed;
    /** 后缘插槽模块（throttle / joystick_2）的安装朝向（度，0/90/180/270，北向基准；安装时按玩家朝向记录，默认 0 = 不额外旋转） */
    private int backSlotRotation;
    /** joystick_2 放置中心（北向模型空间 px，默认 8 = 模型中心）；安装时按预览盒位置（吸附 1px 网格）记录 */
    private int joystick2PlaceX = 8;
    private int joystick2PlaceZ = 8;
    /** throttle 放置中心（北向模型空间 px，默认 (8,12) = 桌顶网格唯一合法位置，14×6 全占）；安装时记录 */
    private int throttlePlaceX = 8;
    private int throttlePlaceZ = 12;
    /** monitor_2 放置中心（北向模型空间 px，默认 (8,12) = 桌顶网格唯一合法位置，14×6 全占）；安装时记录 */
    private int monitor2PlaceX = 8;
    private int monitor2PlaceZ = 12;
    private int joystickReturnTime = DEFAULT_JOYSTICK_RETURN_TIME;      // 前后轴回正时间
    private int joystickReturnTimeYaw = DEFAULT_JOYSTICK_RETURN_TIME;   // 左右轴回正时间
    private boolean gearModePitch;                                      // 前后轴档位模式开关
    private int gearCountPitch = DEFAULT_GEAR_COUNT;                    // 前后轴档位数
    private boolean gearModeYaw;                                        // 左右轴档位模式开关
    private int gearCountYaw = DEFAULT_GEAR_COUNT;                      // 左右轴档位数
    private int freeSpeedPitch = DEFAULT_JOYSTICK_FREE_SPEED;           // 前后轴自由模式满偏 tick 数
    private int freeSpeedYaw = DEFAULT_JOYSTICK_FREE_SPEED;             // 左右轴自由模式满偏 tick 数

    // ── 运行时轴状态（服务端权威，不持久化；经 getUpdateTag/getUpdatePacket 同步到客户端） ──
    private float joystickAxisX;   // 操纵杆轴 X（-1..1）：+1 = 右摆(D)，-1 = 左摆(A)
    private float joystickAxisY;   // 操纵杆轴 Y（-1..1）：+1 = 前推(W)，-1 = 后拉(S)
    private float pedalLeftAxis;   // 左踏板轴（-1..1，运行时）：+1 = 踩下（+z 1px）/ -1 = 抬起（-z 1px），见 PedalMotion
    private float pedalRightAxis;  // 右踏板轴（-1..1，运行时）
    private int throttleGear;          // 油门档位（0..MAX_TRAVEL_PX，运行时）：0 = 最低档（底端，-x 端），1px = 1 档，锁存不回正
    private int throttleChargeTicks;   // 档位切换充电（0..throttleTicksPerGear，按住满 N tick 进/退一档，N 可配置）
    // 输入租约：最近一次操作输入（玩家 + 坐垫 + 操纵杆四方向 + 踏板四键 + 油门两键按住态）；服务端每 tick 校验租约并模拟动力学
    private UUID inputPlayer;
    private BlockPos inputSeatPos;
    private boolean inputUp, inputDown, inputLeft, inputRight;
    private boolean prevUp, prevDown, prevLeft, prevRight;
    private boolean inputPedalLeftDown, inputPedalLeftUp, inputPedalRightDown, inputPedalRightUp;
    private boolean inputThrottleForward, inputThrottleBack;
    private String joystickKeyUp = DEFAULT_JOYSTICK_KEY_UP;
    private String joystickKeyDown = DEFAULT_JOYSTICK_KEY_DOWN;
    private String joystickKeyLeft = DEFAULT_JOYSTICK_KEY_LEFT;
    private String joystickKeyRight = DEFAULT_JOYSTICK_KEY_RIGHT;
    private int pedalReturnTime = DEFAULT_PEDAL_RETURN_TIME;   // 左右踏板共用的回正时间（tick）
    private int pedalFreeSpeed = DEFAULT_PEDAL_FREE_SPEED;     // 左右踏板共用的满偏时间（tick，踩下/抬起按住到满偏）
    private String pedalKeyLeftUp = DEFAULT_PEDAL_KEY_LEFT_UP;    // 左踏板 抬起键（空串 = 未绑定）
    private String pedalKeyLeftDown = DEFAULT_PEDAL_KEY_LEFT_DOWN; // 左踏板 踩下键
    private String pedalKeyRightUp = DEFAULT_PEDAL_KEY_RIGHT_UP;   // 右踏板 抬起键
    private String pedalKeyRightDown = DEFAULT_PEDAL_KEY_RIGHT_DOWN; // 右踏板 踩下键
    private String throttleKeyForward = DEFAULT_THROTTLE_KEY_FORWARD; // 油门杆 前进键（模型空间 +x，空串 = 未绑定）
    private String throttleKeyBack = DEFAULT_THROTTLE_KEY_BACK;      // 油门杆 后退键（模型空间 -x，空串 = 未绑定）
    private int throttleTicksPerGear = DEFAULT_THROTTLE_TICKS_PER_GEAR; // 档位切换节奏（tick）：按住满 N tick 进/退一档

    // ── 全局频道（与传感器/显示器共享 GlobalChannelRegistry 命名空间，频道全局唯一） ──
    /** 全局频道号（-1 表示尚未注册，加载时自动分配） */
    private int channel = -1;
    /** 所有已被占用的频道号快照（服务端设置，客户端通过 updateTag 同步，菜单用它跳过已占用频道） */
    private int[] occupiedChannels = new int[0];
    /** CC:T 外设实例（懒加载），避免直接在 BE 上实现 IPeripheral 导致 getType() 冲突（对齐 MonitorBlockEntity） */
    @Nullable
    private IPeripheral peripheral;

    public ControlDeskBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.control_desk_entity.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            int assigned = ControlDeskRegistry.register(this.channel, this);
            if (assigned != this.channel) {
                this.channel = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            ControlDeskRegistry.unregister(this.channel, this);
        }
        super.setRemoved();
    }

    /** 全局频道号。 */
    public int getChannel() {
        return channel;
    }

    /** 获取 CC:T 外设实例（懒加载；经 pe.getPeripheral(ch) / peripheral.wrap 获取，Lua API 待实施）。 */
    public IPeripheral getPeripheral() {
        if (peripheral == null) {
            peripheral = new ControlDeskPeripheral(this);
        }
        return peripheral;
    }

    /** 获取已占用频道号数组（客户端配置菜单用它跳过已占用频道）。 */
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    /** 更新全局频道号（服务端调用）：重新注册（冲突顺延）并同步客户端。 */
    public void setChannel(int newChannel) {
        if (level == null || level.isClientSide) return;
        // -1 表示客户端尚未同步到真实频道，直接忽略，避免误触发自动重分配
        if (newChannel < 0) return;
        if (newChannel == this.channel) return;
        int assigned = ControlDeskRegistry.register(newChannel, this);
        this.channel = assigned;
        notifyChange();
    }

    /** 从全局注册表同步 occupiedChannels 快照到本 BE，并通知客户端。 */
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        this.occupiedChannels = GlobalChannelRegistry.occupiedChannelsArray();
        notifyChange();
    }

    public boolean isInstalled(ControlType type) {
        return switch (type) {
            case PEDAL -> pedalInstalled;
            case JOYSTICK -> joystickInstalled;
            case MONITOR_2 -> monitor2Installed;
            case THROTTLE -> throttleInstalled;
            case JOYSTICK_2 -> joystick2Installed;
        };
    }

    /**
     * 安装控件；已安装/位置被占用返回 false。MONITOR_2 / THROTTLE / JOYSTICK_2 均为棋盘自由放置模块，
     * 全部走<b>纯占地矩形判定</b>（{@link #blocksPlacement}，monitor_2 / throttle 同为 14×6 全占网格 → 天然互斥）；
     * PEDAL / JOYSTICK 固定安装位（忽略位置）。
     * {@code toPlayer} = 桌体→玩家的水平方向（THROTTLE / JOYSTICK_2 记录到 {@link #backSlotRotation}，
     * 让模型正面面向玩家；null 时保持原值；<b>monitor_2 不面向玩家</b>，仅随桌体 FACING 旋转）；
     * {@code placeX/placeZ} = 放置中心（北向模型空间 px；throttle / monitor_2 恒为唯一合法位 (8,12)，joystick_2 按预览盒吸附）。
     */
    public boolean install(ControlType type, int placeX, int placeZ, @Nullable Direction toPlayer) {
        if (isInstalled(type)) return false;
        switch (type) {
            case PEDAL -> pedalInstalled = true;
            case JOYSTICK -> joystickInstalled = true;
            case MONITOR_2 -> {
                if (blocksPlacement(placeX, placeZ, MONITOR_2_FOOTPRINT_HALF_X, MONITOR_2_FOOTPRINT_HALF_Z)) return false; // 14×6 与已装模块占用重叠
                monitor2Installed = true;
                monitor2PlaceX = placeX;
                monitor2PlaceZ = placeZ;
                // 不记录安装朝向旋转：monitor_2 只随桌体 FACING（R_facing）旋转
            }
            case THROTTLE -> {
                if (blocksPlacement(placeX, placeZ, THROTTLE_FOOTPRINT_HALF_X, THROTTLE_FOOTPRINT_HALF_Z)) return false; // 14×6 与已装模块占用重叠
                throttleInstalled = true;
                throttlePlaceX = placeX;
                throttlePlaceZ = placeZ;
                // 只能 0°/180°：让模型 -Z 尽量面向安装时的玩家（90° 结果量化到最近 0/180）；toPlayer 为 null 时保持原值
                if (toPlayer != null) {
                    backSlotRotation = rotationToFace180(getBlockState().getValue(ControlDeskBlock.FACING), toPlayer);
                }
            }
            case JOYSTICK_2 -> {
                if (blocksPlacement(placeX, placeZ, JOYSTICK_2_FOOTPRINT_HALF, JOYSTICK_2_FOOTPRINT_HALF)) return false; // 4×4 与已装模块占用重叠
                joystick2Installed = true;
                joystick2PlaceX = placeX;
                joystick2PlaceZ = placeZ;
                // 让模型 -Z（Blockbench 北向正面）面向安装时的玩家（90° 间隔）；toPlayer 为 null 时保持原值
                if (toPlayer != null) {
                    backSlotRotation = rotationToFace(getBlockState().getValue(ControlDeskBlock.FACING), toPlayer);
                }
            }
        }
        notifyChange();
        return true;
    }

    /**
     * 候选放置（中心 cx,cz、半宽 halfX×halfZ，北向模型空间 px）是否与已安装模块的占地矩形重叠。
     * 记录 joystick_2 的 4×4（half 2×2）、throttle 的 14×6 与 monitor_2 的 14×6（half 7×3）。
     */
    public boolean blocksPlacement(int cx, int cz, int halfX, int halfZ) {
        if (joystick2Installed) {
            int hx = JOYSTICK_2_FOOTPRINT_HALF;
            int hz = JOYSTICK_2_FOOTPRINT_HALF;
            if (Math.abs(cx - joystick2PlaceX) < halfX + hx && Math.abs(cz - joystick2PlaceZ) < halfZ + hz) {
                return true;
            }
        }
        if (throttleInstalled) {
            int hx = THROTTLE_FOOTPRINT_HALF_X;
            int hz = THROTTLE_FOOTPRINT_HALF_Z;
            if (Math.abs(cx - throttlePlaceX) < halfX + hx && Math.abs(cz - throttlePlaceZ) < halfZ + hz) {
                return true;
            }
        }
        if (monitor2Installed) {
            int hx = MONITOR_2_FOOTPRINT_HALF_X;
            int hz = MONITOR_2_FOOTPRINT_HALF_Z;
            if (Math.abs(cx - monitor2PlaceX) < halfX + hx && Math.abs(cz - monitor2PlaceZ) < halfZ + hz) {
                return true;
            }
        }
        return false;
    }

    /** 卸载控件；未安装返回 false。服务端调用。 */
    public boolean remove(ControlType type) {
        if (!isInstalled(type)) return false;
        switch (type) {
            case PEDAL -> {
                pedalInstalled = false;
                // 卸下踏板：运行时轴值与输入租约一并清除（重新安装后从中间位置开始）
                pedalLeftAxis = 0f;
                pedalRightAxis = 0f;
                clearInput();
            }
            case JOYSTICK -> {
                joystickInstalled = false;
                // 卸下操纵杆：运行时轴状态与输入租约一并清除（重新安装后从中心开始）
                joystickAxisX = 0f;
                joystickAxisY = 0f;
                clearInput();
            }
            case MONITOR_2 -> {
                monitor2Installed = false;
                // 卸下监视器2：放置位复位到唯一合法位置 (8,12)
                monitor2PlaceX = MONITOR_2_PLACE_X;
                monitor2PlaceZ = MONITOR_2_PLACE_Z;
            }
            case THROTTLE -> {
                throttleInstalled = false;
                // 卸下油门杆：放置位复位到唯一合法位置 (8,12)，运行时档位与充电清零（重新安装后从最低档开始）
                throttlePlaceX = THROTTLE_PLACE_X;
                throttlePlaceZ = THROTTLE_PLACE_Z;
                throttleGear = 0;
                throttleChargeTicks = 0;
                clearInput();
            }
            case JOYSTICK_2 -> {
                joystick2Installed = false;
                // 卸下摇杆2：放置位置与输入租约一并清除（重新安装后按新预览位置放置）
                joystick2PlaceX = 8;
                joystick2PlaceZ = 8;
                clearInput();
            }
        }
        notifyChange();
        return true;
    }

    private void notifyChange() {
        this.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 后缘插槽模块（throttle / joystick_2）的安装朝向（度，0/90/180/270，北向基准；默认 0 = 不额外旋转）。 */
    public int getBackSlotRotation() {
        return backSlotRotation;
    }

    /** joystick_2 放置中心 X（北向模型空间 px，默认 8 = 模型中心）；安装时按预览盒位置（吸附 1px 网格）记录。 */
    public int getJoystick2PlaceX() {
        return joystick2PlaceX;
    }

    /** joystick_2 放置中心 Z（北向模型空间 px，默认 8 = 模型中心）。 */
    public int getJoystick2PlaceZ() {
        return joystick2PlaceZ;
    }

    /** throttle 放置中心 X（北向模型空间 px，唯一合法位 8）。 */
    public int getThrottlePlaceX() {
        return throttlePlaceX;
    }

    /** throttle 放置中心 Z（北向模型空间 px，唯一合法位 12）。 */
    public int getThrottlePlaceZ() {
        return throttlePlaceZ;
    }

    /** monitor_2 放置中心 X（北向模型空间 px，唯一合法位 8）。 */
    public int getMonitor2PlaceX() {
        return monitor2PlaceX;
    }

    /** monitor_2 放置中心 Z（北向模型空间 px，唯一合法位 12）。 */
    public int getMonitor2PlaceZ() {
        return monitor2PlaceZ;
    }

    /**
     * joystick_2 安装朝向：让模型 -Z（北向，Blockbench 中的正面）面向玩家，90° 间隔（北/南/西/东）。
     * {@code deskFacing} = 桌体 FACING；{@code toPlayer} = 桌体→玩家的水平方向（调用方经
     * {@link ControlDeskBlock#directionFromDeskTo} 取最近基本方向）；null 返回 0。
     * <p>
     * 推导：R_facing（{@code rotateCenteredDegrees(-facing.getOpposite().toYRot())}）已把模型 -Z 转到桌体
     * FACING 方向（操作者所在侧），额外绕 Y 旋转 θ（正角度 = 俯视逆时针，yaw 减 θ）使 -Z 指向 toPlayer：
     * {@code θ = toYRot(deskFacing) - toYRot(toPlayer)}（mod 360）。
     */
    public static int rotationToFace(Direction deskFacing, @Nullable Direction toPlayer) {
        if (toPlayer == null) return 0;
        return Math.floorMod(Math.round(deskFacing.toYRot()) - Math.round(toPlayer.toYRot()), 360);
    }

    /**
     * throttle 安装朝向：只能 0°/180°——把 {@link #rotationToFace} 的结果量化到最近 0/180
     * （玩家在桌体 FACING 侧 → 0°，对面 → 180°，侧向就近取）。
     */
    public static int rotationToFace180(Direction deskFacing, @Nullable Direction toPlayer) {
        int theta = rotationToFace(deskFacing, toPlayer);
        return Math.floorMod(Math.round(theta / 180f) * 180, 360);
    }

    /**
     * 模型放置底 y（北向模型空间 px，三个自由放置模块共用）：桌顶面 y8 —— 模型<b>坐于桌面不下沉</b>；
     * 仅<b>预览盒</b>下沉 1px（各模块 {@code *_PLACE_Y_BOTTOM = 7}，嵌入桌面示意）。
     */
    public static final float MODEL_PLACE_Y = 8f;

    /** joystick_2 占地半宽（北向模型空间 px）：4×4 → ±2px；预览盒与占用阻挡共用。 */
    public static final int JOYSTICK_2_FOOTPRINT_HALF = 2;
    /** joystick_2 预览盒底 y（北向模型空间 px，下沉 1px 嵌入桌面示意；模型坐桌面 y8 见 {@link #MODEL_PLACE_Y}）～ 顶 y16（高 9）。 */
    public static final float JOYSTICK_2_PLACE_Y_BOTTOM = 7f;
    public static final float JOYSTICK_2_PLACE_Y_TOP = 16f;
    /** joystick_2 模型默认中心 x/z（模型 x6..10 / z6..10 → 8）与底座底 y（0）：安装渲染时平移到放置位。 */
    public static final float JOYSTICK_2_MODEL_CENTER = 8f;
    public static final float JOYSTICK_2_MODEL_BOTTOM_Y = 0f;
    /** throttle 占地半宽（北向模型空间 px）：14×6 → x±7 / z±3；预览盒与占用阻挡共用。 */
    public static final int THROTTLE_FOOTPRINT_HALF_X = 7;
    public static final int THROTTLE_FOOTPRINT_HALF_Z = 3;
    /** throttle 预览盒底 y（北向模型空间 px，下沉 1px 嵌入桌面示意）～ 顶 y13（高 6）。 */
    public static final float THROTTLE_PLACE_Y_BOTTOM = 7f;
    public static final float THROTTLE_PLACE_Y_TOP = 13f;
    /** throttle 模型默认中心 x/z（模型 x0.99..15.01 / z4.99..11.01 → 8）与底座底 y（0）：安装渲染时平移到放置位。 */
    public static final float THROTTLE_MODEL_CENTER = 8f;
    public static final float THROTTLE_MODEL_BOTTOM_Y = 0f;
    /** throttle 唯一合法放置中心（14×6 占地完全处于桌顶网格 x1..15 / z9..15 → 仅 (8,12)，全占）。 */
    public static final int THROTTLE_PLACE_X = 8;
    public static final int THROTTLE_PLACE_Z = 12;
    /** monitor_2 占地半宽（北向模型空间 px）：14×6 → x±7 / z±3；预览盒与占用阻挡共用。 */
    public static final int MONITOR_2_FOOTPRINT_HALF_X = 7;
    public static final int MONITOR_2_FOOTPRINT_HALF_Z = 3;
    /** monitor_2 预览盒底 y（北向模型空间 px，下沉 1px 嵌入桌面示意）～ 顶 y19（高 12）。 */
    public static final float MONITOR_2_PLACE_Y_BOTTOM = 7f;
    public static final float MONITOR_2_PLACE_Y_TOP = 19f;
    /** monitor_2 模型默认中心 x/z（Blockbench 中模型 14×6 居中 → 8，用户会同步调整模型）与底座底 y（0）。 */
    public static final float MONITOR_2_MODEL_CENTER = 8f;
    public static final float MONITOR_2_MODEL_BOTTOM_Y = 0f;
    /** monitor_2 唯一合法放置中心（14×6 占地完全处于桌顶网格 x1..15 / z9..15 → 仅 (8,12)，全占）。 */
    public static final int MONITOR_2_PLACE_X = 8;
    public static final int MONITOR_2_PLACE_Z = 12;

    public int getJoystickReturnTime() {
        return joystickReturnTime;
    }

    /** 设置操纵杆回正时间（tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setJoystickReturnTime(int ticks) {
        int clamped = Math.max(MIN_JOYSTICK_RETURN_TIME, Math.min(MAX_JOYSTICK_RETURN_TIME, ticks));
        if (joystickReturnTime == clamped) return;
        joystickReturnTime = clamped;
        notifyChange();
    }

    public int getJoystickReturnTimeYaw() {
        return joystickReturnTimeYaw;
    }

    /** 设置操纵杆左右轴回正时间（tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setJoystickReturnTimeYaw(int ticks) {
        int clamped = Math.max(MIN_JOYSTICK_RETURN_TIME, Math.min(MAX_JOYSTICK_RETURN_TIME, ticks));
        if (joystickReturnTimeYaw == clamped) return;
        joystickReturnTimeYaw = clamped;
        notifyChange();
    }

    public boolean isGearModePitch() {
        return gearModePitch;
    }

    public int getGearCountPitch() {
        return gearCountPitch;
    }

    public boolean isGearModeYaw() {
        return gearModeYaw;
    }

    public int getGearCountYaw() {
        return gearCountYaw;
    }

    public int getJoystickFreeSpeedPitch() {
        return freeSpeedPitch;
    }

    public int getJoystickFreeSpeedYaw() {
        return freeSpeedYaw;
    }

    /** 设置两轴档位模式（开关 + 档位数，档位数钳位到 [MIN, MAX]）。服务端调用。 */
    public void setGearConfig(boolean pitchMode, int pitchCount, boolean yawMode, int yawCount) {
        int pc = clampGearCount(pitchCount);
        int yc = clampGearCount(yawCount);
        if (gearModePitch == pitchMode && gearCountPitch == pc
                && gearModeYaw == yawMode && gearCountYaw == yc) {
            return;
        }
        gearModePitch = pitchMode;
        gearCountPitch = pc;
        gearModeYaw = yawMode;
        gearCountYaw = yc;
        notifyChange();
    }

    private static int clampGearCount(int count) {
        return Math.max(MIN_GEAR_COUNT, Math.min(MAX_GEAR_COUNT, count));
    }

    /** 设置两轴自由模式满偏 tick 数（累加速度 = 1/数值 每 tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setJoystickFreeSpeed(int pitchTicks, int yawTicks) {
        int pt = clampFreeSpeed(pitchTicks);
        int yt = clampFreeSpeed(yawTicks);
        if (freeSpeedPitch == pt && freeSpeedYaw == yt) return;
        freeSpeedPitch = pt;
        freeSpeedYaw = yt;
        notifyChange();
    }

    private static int clampFreeSpeed(int ticks) {
        return Math.max(MIN_JOYSTICK_FREE_SPEED, Math.min(MAX_JOYSTICK_FREE_SPEED, ticks));
    }

    public float getJoystickAxisX() {
        return joystickAxisX;
    }

    public float getJoystickAxisY() {
        return joystickAxisY;
    }

    /** X 轴（A/D）是否有按键动作（原始值，服务端输入租约）：左/右方向键任一按住。 */
    public boolean isJoystickXActive() {
        return inputLeft || inputRight;
    }

    /** Y 轴（W/S）是否有按键动作（原始值，服务端输入租约）：前/后方向键任一按住。 */
    public boolean isJoystickYActive() {
        return inputUp || inputDown;
    }

    /** 左踏板轴（-1..1，运行时）：+1 = 踩下（动画 +z 1px）/ -1 = 抬起（动画 -z 1px），见 {@link PedalMotion}。 */
    public float getPedalLeftAxis() {
        return pedalLeftAxis;
    }

    /** 右踏板轴（-1..1，运行时）：+1 = 踩下（动画 +z 1px）/ -1 = 抬起（动画 -z 1px），见 {@link PedalMotion}。 */
    public float getPedalRightAxis() {
        return pedalRightAxis;
    }

    /** 油门轴（0..1，运行时）：档位 / MAX_TRAVEL_PX（0 = 最低档/底端，1 = 满前进），见 {@link ThrottleMotion}。 */
    public float getThrottleAxis() {
        return throttleGear / (float) ThrottleMotion.MAX_TRAVEL_PX;
    }

    /** 油门档位（0..MAX_TRAVEL_PX，运行时）：渲染层指示灯/动画直接读它。 */
    public int getThrottleGear() {
        return throttleGear;
    }

    /** 油门杆前进键是否有按键动作（原始值，服务端输入租约）。 */
    public boolean isThrottleForwardActive() {
        return inputThrottleForward;
    }

    /** 油门杆后退键是否有按键动作（原始值，服务端输入租约）。 */
    public boolean isThrottleBackActive() {
        return inputThrottleBack;
    }

    /**
     * 写入坐垫操作输入（运行时，服务端调用；按玩家/坐垫租约记录，租约变化时重置边沿历史，
     * 避免换人/换坐垫后第一次按键不触发档位边沿）。
     * 参数 = 操纵杆四方向按住态 + 踏板四键按住态（踩下/抬起）+ 油门两键按住态（前进/后退）。
     */
    public void setSeatInput(UUID player, BlockPos seatPos,
                             boolean up, boolean down, boolean left, boolean right,
                             boolean pedalLeftDown, boolean pedalLeftUp,
                             boolean pedalRightDown, boolean pedalRightUp,
                             boolean throttleForward, boolean throttleBack) {
        boolean leaseChanged = !Objects.equals(inputPlayer, player) || !Objects.equals(inputSeatPos, seatPos);
        inputPlayer = player;
        inputSeatPos = seatPos;
        inputUp = up;
        inputDown = down;
        inputLeft = left;
        inputRight = right;
        inputPedalLeftDown = pedalLeftDown;
        inputPedalLeftUp = pedalLeftUp;
        inputPedalRightDown = pedalRightDown;
        inputPedalRightUp = pedalRightUp;
        inputThrottleForward = throttleForward;
        inputThrottleBack = throttleBack;
        if (leaseChanged) {
            prevUp = prevDown = prevLeft = prevRight = false;
        }
    }

    /** 清除输入租约（操作者离开坐垫/断线时由服务端 tick 调用）。 */
    public void clearInput() {
        inputPlayer = null;
        inputSeatPos = null;
        inputUp = inputDown = inputLeft = inputRight = false;
        prevUp = prevDown = prevLeft = prevRight = false;
        inputPedalLeftDown = inputPedalLeftUp = inputPedalRightDown = inputPedalRightUp = false;
        inputThrottleForward = inputThrottleBack = false;
    }

    /**
     * 服务端每 tick 模拟控件动力学：
     * <ul>
     *   <li>操纵杆（已安装时）：自由模式按下线性累加/松开按回正时间归零；档位模式无回正、按下边沿进/退一档；</li>
     *   <li>踏板（已安装时）：踩下键按住 → 轴向 +1 累加（+z 1px）；抬起键按住 → 轴向 -1 累加（-z 1px）；都不按 → 按回正时间向 0 归零；</li>
     * </ul>
     * 轴/压下值变化时广播到客户端。
     * 配置取本 BE 自己的设置（操纵杆 X 轴用 Yaw 系列、Y 轴用 Pitch 系列；踏板左右共用回正时间）。
     */
    public static void tickServer(Level level, BlockPos pos, BlockState state, ControlDeskBlockEntity be) {
        if (level == null || level.isClientSide) return;
        boolean hasJoystick = be.joystickInstalled;
        boolean hasPedal = be.pedalInstalled;
        boolean hasThrottle = be.throttleInstalled;
        if (!hasJoystick && !hasPedal && !hasThrottle) return;
        // 输入租约校验：操作者不再坐在输入坐垫上（离开/换坐垫/断线）→ 清除输入
        // （档位模式轴值保持、自由模式/踏板自然回正）
        if (be.inputPlayer != null) {
            Player p = ((ServerLevel) level).getPlayerByUUID(be.inputPlayer);
            if (p == null || !Objects.equals(ControlDeskSeatLink.seatPosOf(p), be.inputSeatPos)) {
                be.clearInput();
            }
        }
        if (hasJoystick) {
            simulateJoystick(be);
        } else {
            be.prevUp = be.prevDown = be.prevLeft = be.prevRight = false;
        }
        if (hasPedal) {
            simulatePedals(be);
        }
        if (hasThrottle) {
            simulateThrottle(be);
        }
    }

    /** 操纵杆轴动力学（自由模式 / 档位模式），轴值变化时广播。 */
    private static void simulateJoystick(ControlDeskBlockEntity be) {
        boolean anyInput = be.inputUp || be.inputDown || be.inputLeft || be.inputRight;
        if (!anyInput && be.joystickAxisX == 0f && be.joystickAxisY == 0f) {
            be.prevUp = be.prevDown = be.prevLeft = be.prevRight = false;
            return;
        }
        // 按下边沿（相对上一 tick 输入）
        boolean upEdge = be.inputUp && !be.prevUp;
        boolean downEdge = be.inputDown && !be.prevDown;
        boolean leftEdge = be.inputLeft && !be.prevLeft;
        boolean rightEdge = be.inputRight && !be.prevRight;
        be.prevUp = be.inputUp;
        be.prevDown = be.inputDown;
        be.prevLeft = be.inputLeft;
        be.prevRight = be.inputRight;

        float targetX = (be.inputRight && !be.inputLeft) ? 1f : ((be.inputLeft && !be.inputRight) ? -1f : 0f);
        float targetY = (be.inputUp && !be.inputDown) ? 1f : ((be.inputDown && !be.inputUp) ? -1f : 0f);
        float newX = be.gearModeYaw
                ? JoystickTilt.stepGear(be.joystickAxisX, rightEdge, leftEdge, be.gearCountYaw)
                : JoystickTilt.stepAxis(be.joystickAxisX, targetX,
                        JoystickTilt.pressStep(be.freeSpeedYaw), JoystickTilt.returnStep(be.joystickReturnTimeYaw));
        float newY = be.gearModePitch
                ? JoystickTilt.stepGear(be.joystickAxisY, upEdge, downEdge, be.gearCountPitch)
                : JoystickTilt.stepAxis(be.joystickAxisY, targetY,
                        JoystickTilt.pressStep(be.freeSpeedPitch), JoystickTilt.returnStep(be.joystickReturnTime));
        if (newX != be.joystickAxisX || newY != be.joystickAxisY) {
            be.joystickAxisX = newX;
            be.joystickAxisY = newY;
            be.notifyChange();
        }
    }

    /**
     * 踏板动力学（数值层，左右独立，轴 -1..1）：踩下键按住 → 按满偏时间向 +1 线性累加
     * （{@link JoystickTilt#pressStep}，满偏 tick 数可配置，默认 2）；抬起键按住 → 向 -1 累加；
     * 都不按 → 按回正时间向 0 线性归零（{@link JoystickTilt#returnStep}）。轴值变化时广播。
     */
    private static void simulatePedals(ControlDeskBlockEntity be) {
        boolean anyInput = be.inputPedalLeftDown || be.inputPedalLeftUp
                || be.inputPedalRightDown || be.inputPedalRightUp;
        if (!anyInput && be.pedalLeftAxis == 0f && be.pedalRightAxis == 0f) {
            return;
        }
        float leftTarget = (be.inputPedalLeftDown && !be.inputPedalLeftUp) ? 1f
                : ((be.inputPedalLeftUp && !be.inputPedalLeftDown) ? -1f : 0f);
        float rightTarget = (be.inputPedalRightDown && !be.inputPedalRightUp) ? 1f
                : ((be.inputPedalRightUp && !be.inputPedalRightDown) ? -1f : 0f);
        float pressStep = JoystickTilt.pressStep(be.pedalFreeSpeed);
        float returnStep = JoystickTilt.returnStep(be.pedalReturnTime);
        float newLeft = JoystickTilt.stepAxis(be.pedalLeftAxis, leftTarget, pressStep, returnStep);
        float newRight = JoystickTilt.stepAxis(be.pedalRightAxis, rightTarget, pressStep, returnStep);
        if (newLeft != be.pedalLeftAxis || newRight != be.pedalRightAxis) {
            be.pedalLeftAxis = newLeft;
            be.pedalRightAxis = newRight;
            be.notifyChange();
        }
    }

    /**
     * 油门档位推进（数值层，档位 0..{@link ThrottleMotion#MAX_TRAVEL_PX}）：前进键按住充电，
     * 满 {@link #throttleTicksPerGear}（默认 4）tick 进一档（+1px）；后退键按住同样充电后退一档（-1px）；
     * 无输入（或同时按）**锁存**保持当前档位并清零充电；已到顶/底时充电清零不动作。
     * 每个档位切换播放一次 {@code LEVER_CLICK} 音效——音调随档位位置单调上升
     * （前进从低到高、后退从高到低，见 {@link ThrottleMotion#pitchForGear}），最低档不响。
     * 档位变化时广播。
     */
    private static void simulateThrottle(ControlDeskBlockEntity be) {
        boolean forward = be.inputThrottleForward;
        boolean back = be.inputThrottleBack;
        if (forward == back) { // 无输入或同时按：锁存（保持档位），充电清零
            be.throttleChargeTicks = 0;
            return;
        }
        int dir = forward ? 1 : -1;
        if ((dir > 0 && be.throttleGear >= ThrottleMotion.MAX_TRAVEL_PX)
                || (dir < 0 && be.throttleGear <= 0)) {
            be.throttleChargeTicks = 0; // 已到顶/底：充电清零，按住不动作
            return;
        }
        be.throttleChargeTicks++;
        if (be.throttleChargeTicks < be.throttleTicksPerGear) {
            return; // 充电中（未满配置的 tick 数不步进）
        }
        be.throttleChargeTicks = 0;
        be.throttleGear += dir;
        be.notifyChange();
        if (be.throttleGear >= 1 && be.getLevel() != null) {
            be.getLevel().playSound(null, be.getBlockPos(), SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, ThrottleMotion.SOUND_VOLUME, ThrottleMotion.pitchForGear(be.throttleGear));
        }
    }

    public String getJoystickKeyUp() {
        return joystickKeyUp;
    }

    public String getJoystickKeyDown() {
        return joystickKeyDown;
    }

    public String getJoystickKeyLeft() {
        return joystickKeyLeft;
    }

    public String getJoystickKeyRight() {
        return joystickKeyRight;
    }

    /** 设置操纵杆四向按键（InputConstants.Key.getName() 格式，空串 = 未绑定）。服务端调用。 */
    public void setJoystickKeys(String up, String down, String left, String right) {
        String u = up == null ? "" : up;
        String d = down == null ? "" : down;
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        if (Objects.equals(joystickKeyUp, u) && Objects.equals(joystickKeyDown, d)
                && Objects.equals(joystickKeyLeft, l) && Objects.equals(joystickKeyRight, r)) {
            return;
        }
        joystickKeyUp = u;
        joystickKeyDown = d;
        joystickKeyLeft = l;
        joystickKeyRight = r;
        notifyChange();
    }

    public int getPedalReturnTime() {
        return pedalReturnTime;
    }

    /** 设置脚踏板回正时间（tick），钳位到 [MIN, MAX]；左右两个踏板共用。服务端调用。 */
    public void setPedalReturnTime(int ticks) {
        int clamped = Math.max(MIN_PEDAL_RETURN_TIME, Math.min(MAX_PEDAL_RETURN_TIME, ticks));
        if (pedalReturnTime == clamped) return;
        pedalReturnTime = clamped;
        notifyChange();
    }

    public int getPedalFreeSpeed() {
        return pedalFreeSpeed;
    }

    /** 设置脚踏板满偏时间（tick，速度 = 1/数值 每 tick），钳位到 [MIN, MAX]；左右两个踏板共用。服务端调用。 */
    public void setPedalFreeSpeed(int ticks) {
        int clamped = Math.max(MIN_PEDAL_FREE_SPEED, Math.min(MAX_PEDAL_FREE_SPEED, ticks));
        if (pedalFreeSpeed == clamped) return;
        pedalFreeSpeed = clamped;
        notifyChange();
    }

    public String getPedalKeyLeftUp() {
        return pedalKeyLeftUp;
    }

    public String getPedalKeyLeftDown() {
        return pedalKeyLeftDown;
    }

    public String getPedalKeyRightUp() {
        return pedalKeyRightUp;
    }

    public String getPedalKeyRightDown() {
        return pedalKeyRightDown;
    }

    /** 设置脚踏板按键绑定（InputConstants.Key.getName() 格式，空串 = 未绑定）。服务端调用。 */
    public void setPedalKeys(String leftUp, String leftDown, String rightUp, String rightDown) {
        String lu = leftUp == null ? "" : leftUp;
        String ld = leftDown == null ? "" : leftDown;
        String ru = rightUp == null ? "" : rightUp;
        String rd = rightDown == null ? "" : rightDown;
        if (Objects.equals(pedalKeyLeftUp, lu) && Objects.equals(pedalKeyLeftDown, ld)
                && Objects.equals(pedalKeyRightUp, ru) && Objects.equals(pedalKeyRightDown, rd)) {
            return;
        }
        pedalKeyLeftUp = lu;
        pedalKeyLeftDown = ld;
        pedalKeyRightUp = ru;
        pedalKeyRightDown = rd;
        notifyChange();
    }

    public String getThrottleKeyForward() {
        return throttleKeyForward;
    }

    public String getThrottleKeyBack() {
        return throttleKeyBack;
    }

    /** 设置油门杆前进/后退按键（InputConstants.Key.getName() 格式，空串 = 未绑定）。服务端调用。 */
    public void setThrottleKeys(String forward, String back) {
        String f = forward == null ? "" : forward;
        String b = back == null ? "" : back;
        if (Objects.equals(throttleKeyForward, f) && Objects.equals(throttleKeyBack, b)) {
            return;
        }
        throttleKeyForward = f;
        throttleKeyBack = b;
        notifyChange();
    }

    /** 油门杆档位切换节奏（tick）：按住满该 tick 数进/退一档（速度 = 1/数值 每 tick）。 */
    public int getThrottleTicksPerGear() {
        return throttleTicksPerGear;
    }

    /** 设置油门杆档位切换节奏（tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setThrottleTicksPerGear(int ticks) {
        int clamped = Math.max(MIN_THROTTLE_TICKS_PER_GEAR, Math.min(MAX_THROTTLE_TICKS_PER_GEAR, ticks));
        if (throttleTicksPerGear == clamped) return;
        throttleTicksPerGear = clamped;
        notifyChange();
    }

    // ════════════════════ NBT / 同步（Create 蓝图兼容） ════════════════════

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
        tag.putBoolean(TAG_MONITOR_2, monitor2Installed);
        tag.putBoolean(TAG_THROTTLE, throttleInstalled);
        tag.putBoolean(TAG_JOYSTICK_2, joystick2Installed);
        tag.putInt(TAG_JOYSTICK_2_PLACE_X, joystick2PlaceX);
        tag.putInt(TAG_JOYSTICK_2_PLACE_Z, joystick2PlaceZ);
        tag.putInt(TAG_THROTTLE_PLACE_X, throttlePlaceX);
        tag.putInt(TAG_THROTTLE_PLACE_Z, throttlePlaceZ);
        tag.putInt(TAG_MONITOR_2_PLACE_X, monitor2PlaceX);
        tag.putInt(TAG_MONITOR_2_PLACE_Z, monitor2PlaceZ);
        tag.putInt(TAG_BACK_SLOT_ROTATION, backSlotRotation);
        tag.putInt(TAG_JOYSTICK_RETURN_TIME, joystickReturnTime);
        tag.putInt(TAG_JOYSTICK_RETURN_TIME_YAW, joystickReturnTimeYaw);
        tag.putBoolean(TAG_GEAR_MODE_PITCH, gearModePitch);
        tag.putInt(TAG_GEAR_COUNT_PITCH, gearCountPitch);
        tag.putBoolean(TAG_GEAR_MODE_YAW, gearModeYaw);
        tag.putInt(TAG_GEAR_COUNT_YAW, gearCountYaw);
        tag.putInt(TAG_JOYSTICK_FREE_SPEED_PITCH, freeSpeedPitch);
        tag.putInt(TAG_JOYSTICK_FREE_SPEED_YAW, freeSpeedYaw);
        tag.putString(TAG_JOYSTICK_KEY_UP, joystickKeyUp);
        tag.putString(TAG_JOYSTICK_KEY_DOWN, joystickKeyDown);
        tag.putString(TAG_JOYSTICK_KEY_LEFT, joystickKeyLeft);
        tag.putString(TAG_JOYSTICK_KEY_RIGHT, joystickKeyRight);
        tag.putInt(TAG_PEDAL_RETURN_TIME, pedalReturnTime);
        tag.putInt(TAG_PEDAL_FREE_SPEED, pedalFreeSpeed);
        tag.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        tag.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        tag.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        tag.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        tag.putString(TAG_THROTTLE_KEY_FORWARD, throttleKeyForward);
        tag.putString(TAG_THROTTLE_KEY_BACK, throttleKeyBack);
        tag.putInt(TAG_THROTTLE_TICKS_PER_GEAR, throttleTicksPerGear);
        tag.putInt(TAG_CHANNEL, channel);
        tag.putIntArray(TAG_OCCUPIED_CHANNELS, occupiedChannels);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pedalInstalled = tag.getBoolean(TAG_PEDAL);
        joystickInstalled = tag.getBoolean(TAG_JOYSTICK);
        monitor2Installed = tag.getBoolean(TAG_MONITOR_2);
        throttleInstalled = tag.getBoolean(TAG_THROTTLE);
        joystick2Installed = tag.getBoolean(TAG_JOYSTICK_2);
        if (tag.contains(TAG_JOYSTICK_2_PLACE_X)) {
            joystick2PlaceX = tag.getInt(TAG_JOYSTICK_2_PLACE_X);
        }
        if (tag.contains(TAG_JOYSTICK_2_PLACE_Z)) {
            joystick2PlaceZ = tag.getInt(TAG_JOYSTICK_2_PLACE_Z);
        }
        if (tag.contains(TAG_THROTTLE_PLACE_X)) {
            throttlePlaceX = tag.getInt(TAG_THROTTLE_PLACE_X);
        }
        if (tag.contains(TAG_THROTTLE_PLACE_Z)) {
            throttlePlaceZ = tag.getInt(TAG_THROTTLE_PLACE_Z);
        }
        if (tag.contains(TAG_MONITOR_2_PLACE_X)) {
            monitor2PlaceX = tag.getInt(TAG_MONITOR_2_PLACE_X);
        }
        if (tag.contains(TAG_MONITOR_2_PLACE_Z)) {
            monitor2PlaceZ = tag.getInt(TAG_MONITOR_2_PLACE_Z);
        }
        if (tag.contains(TAG_BACK_SLOT_ROTATION)) {
            backSlotRotation = tag.getInt(TAG_BACK_SLOT_ROTATION);
        }
        // 旧存档无对应字段时保持默认（getInt 缺失返回 0 / getString 缺失返回 ""，需显式判断）
        if (tag.contains(TAG_JOYSTICK_RETURN_TIME)) {
            joystickReturnTime = tag.getInt(TAG_JOYSTICK_RETURN_TIME);
        }
        if (tag.contains(TAG_JOYSTICK_RETURN_TIME_YAW)) {
            joystickReturnTimeYaw = tag.getInt(TAG_JOYSTICK_RETURN_TIME_YAW);
        }
        if (tag.contains(TAG_GEAR_MODE_PITCH)) {
            gearModePitch = tag.getBoolean(TAG_GEAR_MODE_PITCH);
        }
        if (tag.contains(TAG_GEAR_COUNT_PITCH)) {
            gearCountPitch = tag.getInt(TAG_GEAR_COUNT_PITCH);
        }
        if (tag.contains(TAG_GEAR_MODE_YAW)) {
            gearModeYaw = tag.getBoolean(TAG_GEAR_MODE_YAW);
        }
        if (tag.contains(TAG_GEAR_COUNT_YAW)) {
            gearCountYaw = tag.getInt(TAG_GEAR_COUNT_YAW);
        }
        if (tag.contains(TAG_JOYSTICK_FREE_SPEED_PITCH)) {
            freeSpeedPitch = tag.getInt(TAG_JOYSTICK_FREE_SPEED_PITCH);
        }
        if (tag.contains(TAG_JOYSTICK_FREE_SPEED_YAW)) {
            freeSpeedYaw = tag.getInt(TAG_JOYSTICK_FREE_SPEED_YAW);
        }
        // 运行时轴状态：getUpdatePacket / 区块加载同步读这里（不落盘，saveAdditional 不含 → 服务端读盘恒为 0）
        if (tag.contains(TAG_JOYSTICK_AXIS_X)) {
            joystickAxisX = tag.getFloat(TAG_JOYSTICK_AXIS_X);
        }
        if (tag.contains(TAG_JOYSTICK_AXIS_Y)) {
            joystickAxisY = tag.getFloat(TAG_JOYSTICK_AXIS_Y);
        }
        if (tag.contains(TAG_PEDAL_LEFT_AXIS)) {
            pedalLeftAxis = tag.getFloat(TAG_PEDAL_LEFT_AXIS);
        }
        if (tag.contains(TAG_PEDAL_RIGHT_AXIS)) {
            pedalRightAxis = tag.getFloat(TAG_PEDAL_RIGHT_AXIS);
        }
        if (tag.contains(TAG_THROTTLE_AXIS)) {
            throttleGear = Math.max(0, Math.min(ThrottleMotion.MAX_TRAVEL_PX,
                    Math.round(tag.getFloat(TAG_THROTTLE_AXIS) * ThrottleMotion.MAX_TRAVEL_PX)));
        }
        if (tag.contains(TAG_JOYSTICK_KEY_UP)) {
            joystickKeyUp = tag.getString(TAG_JOYSTICK_KEY_UP);
        }
        if (tag.contains(TAG_JOYSTICK_KEY_DOWN)) {
            joystickKeyDown = tag.getString(TAG_JOYSTICK_KEY_DOWN);
        }
        if (tag.contains(TAG_JOYSTICK_KEY_LEFT)) {
            joystickKeyLeft = tag.getString(TAG_JOYSTICK_KEY_LEFT);
        }
        if (tag.contains(TAG_JOYSTICK_KEY_RIGHT)) {
            joystickKeyRight = tag.getString(TAG_JOYSTICK_KEY_RIGHT);
        }
        if (tag.contains(TAG_PEDAL_RETURN_TIME)) {
            pedalReturnTime = tag.getInt(TAG_PEDAL_RETURN_TIME);
        }
        if (tag.contains(TAG_PEDAL_FREE_SPEED)) {
            pedalFreeSpeed = tag.getInt(TAG_PEDAL_FREE_SPEED);
        }
        if (tag.contains(TAG_PEDAL_KEY_LEFT_UP)) {
            pedalKeyLeftUp = tag.getString(TAG_PEDAL_KEY_LEFT_UP);
        }
        if (tag.contains(TAG_PEDAL_KEY_LEFT_DOWN)) {
            pedalKeyLeftDown = tag.getString(TAG_PEDAL_KEY_LEFT_DOWN);
        }
        if (tag.contains(TAG_PEDAL_KEY_RIGHT_UP)) {
            pedalKeyRightUp = tag.getString(TAG_PEDAL_KEY_RIGHT_UP);
        }
        if (tag.contains(TAG_PEDAL_KEY_RIGHT_DOWN)) {
            pedalKeyRightDown = tag.getString(TAG_PEDAL_KEY_RIGHT_DOWN);
        }
        if (tag.contains(TAG_THROTTLE_KEY_FORWARD)) {
            throttleKeyForward = tag.getString(TAG_THROTTLE_KEY_FORWARD);
        }
        if (tag.contains(TAG_THROTTLE_KEY_BACK)) {
            throttleKeyBack = tag.getString(TAG_THROTTLE_KEY_BACK);
        }
        if (tag.contains(TAG_THROTTLE_TICKS_PER_GEAR)) {
            throttleTicksPerGear = Math.max(MIN_THROTTLE_TICKS_PER_GEAR,
                    Math.min(MAX_THROTTLE_TICKS_PER_GEAR, tag.getInt(TAG_THROTTLE_TICKS_PER_GEAR)));
        }
        if (tag.contains(TAG_CHANNEL)) {
            channel = tag.getInt(TAG_CHANNEL);
        }
        if (tag.contains(TAG_OCCUPIED_CHANNELS)) {
            occupiedChannels = tag.getIntArray(TAG_OCCUPIED_CHANNELS);
        }
    }

    /** Create 原理图 / 装置搬运时的「安全 NBT」（Schematicannon 打印保留控件配置）。 */
    @Override
    public void writeSafe(CompoundTag compound, HolderLookup.Provider registries) {
        compound.putBoolean(TAG_PEDAL, pedalInstalled);
        compound.putBoolean(TAG_JOYSTICK, joystickInstalled);
        compound.putBoolean(TAG_MONITOR_2, monitor2Installed);
        compound.putBoolean(TAG_THROTTLE, throttleInstalled);
        compound.putBoolean(TAG_JOYSTICK_2, joystick2Installed);
        compound.putInt(TAG_JOYSTICK_2_PLACE_X, joystick2PlaceX);
        compound.putInt(TAG_JOYSTICK_2_PLACE_Z, joystick2PlaceZ);
        compound.putInt(TAG_THROTTLE_PLACE_X, throttlePlaceX);
        compound.putInt(TAG_THROTTLE_PLACE_Z, throttlePlaceZ);
        compound.putInt(TAG_MONITOR_2_PLACE_X, monitor2PlaceX);
        compound.putInt(TAG_MONITOR_2_PLACE_Z, monitor2PlaceZ);
        compound.putInt(TAG_BACK_SLOT_ROTATION, backSlotRotation);
        compound.putInt(TAG_JOYSTICK_RETURN_TIME, joystickReturnTime);
        compound.putInt(TAG_JOYSTICK_RETURN_TIME_YAW, joystickReturnTimeYaw);
        compound.putBoolean(TAG_GEAR_MODE_PITCH, gearModePitch);
        compound.putInt(TAG_GEAR_COUNT_PITCH, gearCountPitch);
        compound.putBoolean(TAG_GEAR_MODE_YAW, gearModeYaw);
        compound.putInt(TAG_GEAR_COUNT_YAW, gearCountYaw);
        compound.putInt(TAG_JOYSTICK_FREE_SPEED_PITCH, freeSpeedPitch);
        compound.putInt(TAG_JOYSTICK_FREE_SPEED_YAW, freeSpeedYaw);
        compound.putString(TAG_JOYSTICK_KEY_UP, joystickKeyUp);
        compound.putString(TAG_JOYSTICK_KEY_DOWN, joystickKeyDown);
        compound.putString(TAG_JOYSTICK_KEY_LEFT, joystickKeyLeft);
        compound.putString(TAG_JOYSTICK_KEY_RIGHT, joystickKeyRight);
        compound.putInt(TAG_PEDAL_RETURN_TIME, pedalReturnTime);
        compound.putInt(TAG_PEDAL_FREE_SPEED, pedalFreeSpeed);
        compound.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        compound.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        compound.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        compound.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        compound.putString(TAG_THROTTLE_KEY_FORWARD, throttleKeyForward);
        compound.putString(TAG_THROTTLE_KEY_BACK, throttleKeyBack);
        compound.putInt(TAG_THROTTLE_TICKS_PER_GEAR, throttleTicksPerGear);
        // 频道是配置（蓝图可分享）；OccupiedChannels 是运行时快照，不写 Safe NBT
        compound.putInt(TAG_CHANNEL, channel);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
        tag.putBoolean(TAG_MONITOR_2, monitor2Installed);
        tag.putBoolean(TAG_THROTTLE, throttleInstalled);
        tag.putBoolean(TAG_JOYSTICK_2, joystick2Installed);
        tag.putInt(TAG_JOYSTICK_2_PLACE_X, joystick2PlaceX);
        tag.putInt(TAG_JOYSTICK_2_PLACE_Z, joystick2PlaceZ);
        tag.putInt(TAG_THROTTLE_PLACE_X, throttlePlaceX);
        tag.putInt(TAG_THROTTLE_PLACE_Z, throttlePlaceZ);
        tag.putInt(TAG_MONITOR_2_PLACE_X, monitor2PlaceX);
        tag.putInt(TAG_MONITOR_2_PLACE_Z, monitor2PlaceZ);
        tag.putInt(TAG_BACK_SLOT_ROTATION, backSlotRotation);
        tag.putInt(TAG_JOYSTICK_RETURN_TIME, joystickReturnTime);
        tag.putInt(TAG_JOYSTICK_RETURN_TIME_YAW, joystickReturnTimeYaw);
        tag.putBoolean(TAG_GEAR_MODE_PITCH, gearModePitch);
        tag.putInt(TAG_GEAR_COUNT_PITCH, gearCountPitch);
        tag.putBoolean(TAG_GEAR_MODE_YAW, gearModeYaw);
        tag.putInt(TAG_GEAR_COUNT_YAW, gearCountYaw);
        tag.putInt(TAG_JOYSTICK_FREE_SPEED_PITCH, freeSpeedPitch);
        tag.putInt(TAG_JOYSTICK_FREE_SPEED_YAW, freeSpeedYaw);
        // 运行时轴状态（服务端权威）：随 getUpdatePacket / 区块加载同步，客户端渲染直接读它
        tag.putFloat(TAG_JOYSTICK_AXIS_X, joystickAxisX);
        tag.putFloat(TAG_JOYSTICK_AXIS_Y, joystickAxisY);
        // 运行时踏板轴（服务端权威）：同上
        tag.putFloat(TAG_PEDAL_LEFT_AXIS, pedalLeftAxis);
        tag.putFloat(TAG_PEDAL_RIGHT_AXIS, pedalRightAxis);
        // 运行时油门轴（服务端权威）：同上（档位 / MAX_TRAVEL_PX，客户端 loadAdditional 换算回档位）
        tag.putFloat(TAG_THROTTLE_AXIS, getThrottleAxis());
        tag.putString(TAG_JOYSTICK_KEY_UP, joystickKeyUp);
        tag.putString(TAG_JOYSTICK_KEY_DOWN, joystickKeyDown);
        tag.putString(TAG_JOYSTICK_KEY_LEFT, joystickKeyLeft);
        tag.putString(TAG_JOYSTICK_KEY_RIGHT, joystickKeyRight);
        tag.putInt(TAG_PEDAL_RETURN_TIME, pedalReturnTime);
        tag.putInt(TAG_PEDAL_FREE_SPEED, pedalFreeSpeed);
        tag.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        tag.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        tag.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        tag.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        tag.putString(TAG_THROTTLE_KEY_FORWARD, throttleKeyForward);
        tag.putString(TAG_THROTTLE_KEY_BACK, throttleKeyBack);
        tag.putInt(TAG_THROTTLE_TICKS_PER_GEAR, throttleTicksPerGear);
        tag.putInt(TAG_CHANNEL, channel);
        tag.putIntArray(TAG_OCCUPIED_CHANNELS, occupiedChannels);
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（quill 保存读的是客户端 BE，蓝图兼容必须）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
