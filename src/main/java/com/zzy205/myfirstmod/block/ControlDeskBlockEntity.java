package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import com.zzy205.myfirstmod.client.ControlDeskClientRegistry;
import com.zzy205.myfirstmod.compat.cc.ControlDeskPeripheral;
import com.zzy205.myfirstmod.compat.cc.ControlDeskRegistry;
import com.zzy205.myfirstmod.compat.cc.GlobalChannelRegistry;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.monitor.ScreenText;
import com.zzy205.myfirstmod.network.SyncGridPayload;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 控制台方块实体 — 保存已安装控件（踏板一对 / 操纵杆）。
 * NBT 持久化 + 同步（兼容 Create 蓝图，参考 RedstoneTransceiverBlockEntity）。
 */
public class ControlDeskBlockEntity extends BlockEntity implements PartialSafeNBT, MonitorGridHost {

    /** 可安装到控制台的控件类型 */
    public enum ControlType {
        PEDAL, JOYSTICK, MONITOR_2, THROTTLE, JOYSTICK_2, THROTTLE_2, DOCK, BAFFLE
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

    /** 摇杆2 回正时间（tick）默认值（与 Joystick2ModuleScreen 滚轮条一致；配置独立于 joystick，范围复用 joystick 的 MIN/MAX）。 */
    public static final int DEFAULT_JOYSTICK2_RETURN_TIME = DEFAULT_JOYSTICK_RETURN_TIME;

    /** 摇杆2 档位模式（档位数）默认值（与 Joystick2ModuleScreen 滚轮条一致；范围复用 {@link #MIN_GEAR_COUNT}/{@link #MAX_GEAR_COUNT}）。 */
    public static final int DEFAULT_JOYSTICK2_GEAR_COUNT = DEFAULT_GEAR_COUNT;

    /** 摇杆2 自由模式累加速度（满偏所需 tick 数，速度 = 1/数值 每 tick）默认值（范围复用 joystick 的 MIN/MAX）。 */
    public static final int DEFAULT_JOYSTICK2_FREE_SPEED = DEFAULT_JOYSTICK_FREE_SPEED;

    /** 摇杆2 四向按键默认值（照抄 joystick：WASD；InputConstants.Key.getName() 格式，空串 = 未绑定）。 */
    public static final String DEFAULT_JOYSTICK2_KEY_UP = DEFAULT_JOYSTICK_KEY_UP;
    public static final String DEFAULT_JOYSTICK2_KEY_DOWN = DEFAULT_JOYSTICK_KEY_DOWN;
    public static final String DEFAULT_JOYSTICK2_KEY_LEFT = DEFAULT_JOYSTICK_KEY_LEFT;
    public static final String DEFAULT_JOYSTICK2_KEY_RIGHT = DEFAULT_JOYSTICK_KEY_RIGHT;

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

    /** 油门2 上抬/下拉按键默认值（InputConstants.Key.getName() 格式；空串 = 未绑定）：空格 = 上抬（角度 +）/ 左Ctrl = 下拉（角度 -），见 {@link Throttle2Motion}。 */
    public static final String DEFAULT_THROTTLE_2_KEY_UP = "key.keyboard.space";
    public static final String DEFAULT_THROTTLE_2_KEY_DOWN = "key.keyboard.left.control";

    /** 油门2 满偏时间（tick）默认值与范围（与 Throttle2ModuleScreen 滚轮条一致）：按住满 N tick 从最底端到满偏 +30°（速度 = 1/数值 每 tick）。 */
    public static final int DEFAULT_THROTTLE_2_FREE_SPEED = Throttle2Motion.FREE_SPEED_TICKS;
    public static final int MIN_THROTTLE_2_FREE_SPEED = 1;
    public static final int MAX_THROTTLE_2_FREE_SPEED = 100;

    /** 油门2 回正时间（tick）默认值与范围（与 Throttle2ModuleScreen 滚轮条一致）：回正开关开启时，松开按键按该时间线性回到中位 15°（0 = 关闭回正，与 joystick 回正时间同约定）。 */
    public static final int DEFAULT_THROTTLE_2_RETURN_TIME = 2;
    public static final int MIN_THROTTLE_2_RETURN_TIME = 0;
    public static final int MAX_THROTTLE_2_RETURN_TIME = 100;

    private static final String TAG_PEDAL = "PedalInstalled";
    private static final String TAG_JOYSTICK = "JoystickInstalled";
    private static final String TAG_MONITOR_2 = "Monitor2Installed";
    private static final String TAG_THROTTLE = "ThrottleInstalled";
    private static final String TAG_JOYSTICK_2 = "Joystick2Installed";
    private static final String TAG_THROTTLE_2 = "Throttle2Installed";
    private static final String TAG_DOCK = "DockInstalled";
    private static final String TAG_BAFFLE = "BaffleInstalled";
    private static final String TAG_JOYSTICK_2_PLACE_X = "Joystick2PlaceX";
    private static final String TAG_JOYSTICK_2_PLACE_Z = "Joystick2PlaceZ";
    private static final String TAG_THROTTLE_PLACE_X = "ThrottlePlaceX";
    private static final String TAG_THROTTLE_PLACE_Z = "ThrottlePlaceZ";
    private static final String TAG_THROTTLE_2_PLACE_X = "Throttle2PlaceX";
    private static final String TAG_THROTTLE_2_PLACE_Z = "Throttle2PlaceZ";
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
    private static final String TAG_THROTTLE_2_ANGLE = "Throttle2Angle"; // 运行时油门2 角度（不落盘，仅 getUpdateTag 同步）
    private static final String TAG_JOYSTICK_KEY_UP = "JoystickKeyUp";
    private static final String TAG_JOYSTICK_KEY_DOWN = "JoystickKeyDown";
    private static final String TAG_JOYSTICK_KEY_LEFT = "JoystickKeyLeft";
    private static final String TAG_JOYSTICK_KEY_RIGHT = "JoystickKeyRight";
    private static final String TAG_JOYSTICK2_RETURN_TIME = "Joystick2ReturnTime";            // 摇杆2 配置（独立于 joystick）
    private static final String TAG_JOYSTICK2_RETURN_TIME_YAW = "Joystick2ReturnTimeYaw";
    private static final String TAG_GEAR2_MODE_PITCH = "Gear2ModePitch";
    private static final String TAG_GEAR2_COUNT_PITCH = "Gear2CountPitch";
    private static final String TAG_GEAR2_MODE_YAW = "Gear2ModeYaw";
    private static final String TAG_GEAR2_COUNT_YAW = "Gear2CountYaw";
    private static final String TAG_JOYSTICK2_FREE_SPEED_PITCH = "Joystick2FreeSpeedPitch";
    private static final String TAG_JOYSTICK2_FREE_SPEED_YAW = "Joystick2FreeSpeedYaw";
    private static final String TAG_JOYSTICK2_KEY_UP = "Joystick2KeyUp";
    private static final String TAG_JOYSTICK2_KEY_DOWN = "Joystick2KeyDown";
    private static final String TAG_JOYSTICK2_KEY_LEFT = "Joystick2KeyLeft";
    private static final String TAG_JOYSTICK2_KEY_RIGHT = "Joystick2KeyRight";
    private static final String TAG_JOYSTICK2_AXIS_X = "Joystick2AxisX";                      // 摇杆2 运行时轴状态（不落盘，仅 getUpdateTag 同步）
    private static final String TAG_JOYSTICK2_AXIS_Y = "Joystick2AxisY";
    private static final String TAG_PEDAL_RETURN_TIME = "PedalReturnTime";
    private static final String TAG_PEDAL_FREE_SPEED = "PedalFreeSpeed";
    private static final String TAG_PEDAL_KEY_LEFT_UP = "PedalKeyLeftUp";
    private static final String TAG_PEDAL_KEY_LEFT_DOWN = "PedalKeyLeftDown";
    private static final String TAG_PEDAL_KEY_RIGHT_UP = "PedalKeyRightUp";
    private static final String TAG_PEDAL_KEY_RIGHT_DOWN = "PedalKeyRightDown";
    private static final String TAG_THROTTLE_KEY_FORWARD = "ThrottleKeyForward";
    private static final String TAG_THROTTLE_KEY_BACK = "ThrottleKeyBack";
    private static final String TAG_THROTTLE_TICKS_PER_GEAR = "ThrottleTicksPerGear";
    private static final String TAG_THROTTLE_2_KEY_UP = "Throttle2KeyUp";
    private static final String TAG_THROTTLE_2_KEY_DOWN = "Throttle2KeyDown";
    private static final String TAG_THROTTLE_2_FREE_SPEED = "Throttle2FreeSpeed";
    private static final String TAG_THROTTLE_2_RETURN_ENABLED = "Throttle2ReturnEnabled";
    private static final String TAG_THROTTLE_2_RETURN_TIME = "Throttle2ReturnTime";
    private static final String TAG_CHANNEL = "Channel";
    private static final String TAG_OCCUPIED_CHANNELS = "OccupiedChannels";
    private static final String TAG_MONITOR_2_GRID = "Monitor2Grid";

    private boolean pedalInstalled;
    private boolean joystickInstalled;
    private boolean monitor2Installed;   // monitor_2 / throttle / joystick_2 共用桌体后缘上方插槽，互斥安装
    private boolean throttleInstalled;
    private boolean joystick2Installed;
    private boolean throttle2Installed;
    private boolean dockInstalled;       // 拓展坞：装上后模型切换为 slab、禁装 PEDAL/JOYSTICK、桌顶网格变 14×14（blockstate DOCKED 同步）
    private boolean baffleInstalled;     // 挡板：装上后模型切换为 3/4 楼梯（北侧立墙）、与 PEDAL/JOYSTICK/DOCK 互斥（blockstate BAFFLED 同步）
    /** 后缘插槽模块（throttle / joystick_2）的安装朝向（度，0/90/180/270，北向基准；安装时按玩家朝向记录，默认 0 = 不额外旋转） */
    private int backSlotRotation;
    /** joystick_2 放置中心（北向模型空间 px，默认 8 = 模型中心）；安装时按预览盒位置（吸附 1px 网格）记录 */
    private int joystick2PlaceX = 8;
    private int joystick2PlaceZ = 8;
    /** throttle 放置中心（北向模型空间 px，默认 (8,12) = 桌顶网格唯一合法位置，14×6 全占）；安装时记录 */
    private int throttlePlaceX = 8;
    private int throttlePlaceZ = 12;
    /** throttle_2 放置中心（北向模型空间 px，默认 (8,12) = 桌顶网格唯一合法位置，14×6 全占）；安装时记录 */
    private int throttle2PlaceX = 8;
    private int throttle2PlaceZ = 12;
    /** monitor_2 放置中心（北向模型空间 px，默认 (8,12) = 桌顶网格唯一合法位置，14×6 全占）；安装时记录 */
    private int monitor2PlaceX = 8;
    private int monitor2PlaceZ = 12;
    /** monitor_2 表面小 Monitor 的网格状态（10×8，懒加载；仅安装 MONITOR_2 时有效，见 {@link #getMonitor2Grid()}） */
    @Nullable
    private GridState monitor2Grid;
    private int joystickReturnTime = DEFAULT_JOYSTICK_RETURN_TIME;      // 前后轴回正时间
    private int joystickReturnTimeYaw = DEFAULT_JOYSTICK_RETURN_TIME;   // 左右轴回正时间
    private boolean gearModePitch;                                      // 前后轴档位模式开关
    private int gearCountPitch = DEFAULT_GEAR_COUNT;                    // 前后轴档位数
    private boolean gearModeYaw;                                        // 左右轴档位模式开关
    private int gearCountYaw = DEFAULT_GEAR_COUNT;                      // 左右轴档位数
    private int freeSpeedPitch = DEFAULT_JOYSTICK_FREE_SPEED;           // 前后轴自由模式满偏 tick 数
    private int freeSpeedYaw = DEFAULT_JOYSTICK_FREE_SPEED;             // 左右轴自由模式满偏 tick 数
    private int joystick2ReturnTime = DEFAULT_JOYSTICK2_RETURN_TIME;    // 摇杆2 前后轴回正时间（独立于 joystick）
    private int joystick2ReturnTimeYaw = DEFAULT_JOYSTICK2_RETURN_TIME; // 摇杆2 左右轴回正时间
    private boolean gear2ModePitch;                                     // 摇杆2 前后轴档位模式开关
    private int gear2CountPitch = DEFAULT_JOYSTICK2_GEAR_COUNT;         // 摇杆2 前后轴档位数
    private boolean gear2ModeYaw;                                       // 摇杆2 左右轴档位模式开关
    private int gear2CountYaw = DEFAULT_JOYSTICK2_GEAR_COUNT;           // 摇杆2 左右轴档位数
    private int freeSpeed2Pitch = DEFAULT_JOYSTICK2_FREE_SPEED;         // 摇杆2 前后轴自由模式满偏 tick 数
    private int freeSpeed2Yaw = DEFAULT_JOYSTICK2_FREE_SPEED;           // 摇杆2 左右轴自由模式满偏 tick 数
    private String joystick2KeyUp = DEFAULT_JOYSTICK2_KEY_UP;           // 摇杆2 前推键（空串 = 未绑定）
    private String joystick2KeyDown = DEFAULT_JOYSTICK2_KEY_DOWN;       // 摇杆2 后拉键
    private String joystick2KeyLeft = DEFAULT_JOYSTICK2_KEY_LEFT;       // 摇杆2 左摆键
    private String joystick2KeyRight = DEFAULT_JOYSTICK2_KEY_RIGHT;     // 摇杆2 右摆键

    // ── 运行时轴状态（服务端权威，不持久化；经 getUpdateTag/getUpdatePacket 同步到客户端） ──
    private float joystickAxisX;   // 操纵杆轴 X（-1..1）：+1 = 右摆(D)，-1 = 左摆(A)
    private float joystickAxisY;   // 操纵杆轴 Y（-1..1）：+1 = 前推(W)，-1 = 后拉(S)
    private float joystick2AxisX;  // 摇杆2 轴 X（-1..1）：+1 = 右摆，-1 = 左摆（独立于 joystick）
    private float joystick2AxisY;  // 摇杆2 轴 Y（-1..1）：+1 = 前推，-1 = 后拉
    private float pedalLeftAxis;   // 左踏板轴（-1..1，运行时）：+1 = 踩下（+z 1px）/ -1 = 抬起（-z 1px），见 PedalMotion
    private float pedalRightAxis;  // 右踏板轴（-1..1，运行时）
    private int throttleGear;          // 油门档位（0..MAX_TRAVEL_PX，运行时）：0 = 最低档（底端，-x 端），1px = 1 档，锁存不回正
    private int throttleChargeTicks;   // 档位切换充电（0..throttleTicksPerGear，按住满 N tick 进/退一档，N 可配置）
    /** 油门2 角度（0..Throttle2Motion.MAX_DEG，运行时）：0 = 最底端（放置默认），+MAX = 上抬满偏（总距杆单边行程）；锁存不回正 */
    private float throttle2Angle;
    // 输入租约：最近一次操作输入（玩家 + 坐垫 + 操纵杆四方向 + 踏板四键 + 油门两键按住态）；服务端每 tick 校验租约并模拟动力学
    private UUID inputPlayer;
    private BlockPos inputSeatPos;
    private boolean inputUp, inputDown, inputLeft, inputRight;
    private boolean prevUp, prevDown, prevLeft, prevRight;
    // 摇杆2 输入租约（与操纵杆同一份方向输入，独立记录边沿历史——两控件可同时安装、各自模拟）
    private boolean input2Up, input2Down, input2Left, input2Right;
    private boolean prev2Up, prev2Down, prev2Left, prev2Right;
    private boolean inputPedalLeftDown, inputPedalLeftUp, inputPedalRightDown, inputPedalRightUp;
    private boolean inputThrottleForward, inputThrottleBack;
    // 油门2 输入租约（与油门独立：写死 空格=上抬 / 左Ctrl=下拉，见 Throttle2Motion；油门可配置键、油门2 写死键，两者可分别安装在不同控制台）
    private boolean inputThrottle2Up, inputThrottle2Down;
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
    private String throttle2KeyUp = DEFAULT_THROTTLE_2_KEY_UP;       // 油门2 上抬键（角度 +，空串 = 未绑定）
    private String throttle2KeyDown = DEFAULT_THROTTLE_2_KEY_DOWN;   // 油门2 下拉键（角度 -，空串 = 未绑定）
    private int throttle2FreeSpeed = DEFAULT_THROTTLE_2_FREE_SPEED;   // 油门2 满偏时间（tick）：按住满 N tick 从底端到满偏 +30°
    /** 油门2 回正开关（默认关闭 = 锁存不回正）：开启后松开按键按回正时间线性回到中位 15°（见 {@link Throttle2Motion#NEUTRAL_DEG}） */
    private boolean throttle2ReturnEnabled;
    /** 油门2 回正时间（tick，默认 2）：回正开关开启时，松开按键后从中位偏离处线性回到中位所需 tick 数（0 = 关闭回正） */
    private int throttle2ReturnTime = DEFAULT_THROTTLE_2_RETURN_TIME;

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
        if (this.level != null && this.level.isClientSide) {
            // 客户端独立命中检测（monitor_2 表面网格）依赖此注册表枚举候选控制台
            ControlDeskClientRegistry.add(this.getBlockPos());
        }
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
        if (this.level != null && this.level.isClientSide) {
            ControlDeskClientRegistry.remove(this.getBlockPos());
        }
        if (this.level != null && !this.level.isClientSide) {
            ControlDeskRegistry.unregister(this.channel, this);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (this.level != null && this.level.isClientSide) {
            ControlDeskClientRegistry.remove(this.getBlockPos());
        }
        super.onChunkUnloaded();
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
            case THROTTLE_2 -> throttle2Installed;
            case DOCK -> dockInstalled;
            case BAFFLE -> baffleInstalled;
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
            case DOCK -> {
                // 拓展坞占据桌体北侧空区（z0..8），与 PEDAL / JOYSTICK 互斥（装 dock 前需先拆踏板/操纵杆）；
                // blockstate DOCKED 由 ControlDeskBlock.useItemOn 同步切换（模型/slab 形态）
                if (pedalInstalled || joystickInstalled) return false;
                dockInstalled = true;
            }
            case BAFFLE -> {
                // 挡板占据桌体北侧全高区域（z0..8）：只与北侧控件 PEDAL / JOYSTICK 及同为形态安装的 DOCK 互斥
                // （装挡板前需先拆掉它们）；桌顶棋盘网格模块（joystick_2 / throttle / throttle_2 / monitor_2，
                // 均位于桌顶 z9..15）不受影响，可与挡板共存；
                // blockstate BAFFLED 由 ControlDeskBlock.useItemOn 同步切换（模型/3/4 楼梯形态）
                if (pedalInstalled || joystickInstalled || dockInstalled) {
                    return false;
                }
                baffleInstalled = true;
            }
            case MONITOR_2 -> {
                if (blocksPlacement(placeX, placeZ, MONITOR_2_FOOTPRINT_HALF_X, MONITOR_2_FOOTPRINT_HALF_Z)) return false; // 14×6 与已装模块占用重叠
                // 14×6 占位必须完全位于桌顶网格内（普通 x1..15/z9..15；docked x1..15/z1..15）
                if (!ControlDeskBlock.placementInGrid(
                        getBlockState().getValue(ControlDeskBlock.DOCKED), placeX, placeZ,
                        MONITOR_2_FOOTPRINT_HALF_X, MONITOR_2_FOOTPRINT_HALF_Z)) {
                    return false;
                }
                monitor2Installed = true;
                monitor2PlaceX = placeX;
                monitor2PlaceZ = placeZ;
                // 不记录安装朝向旋转：monitor_2 只随桌体 FACING（R_facing）旋转
            }
            case THROTTLE -> {
                if (blocksPlacement(placeX, placeZ, THROTTLE_FOOTPRINT_HALF_X, THROTTLE_FOOTPRINT_HALF_Z)) return false; // 14×6 与已装模块占用重叠
                // 14×6 占位必须完全位于桌顶网格内（普通 x1..15/z9..15；docked x1..15/z1..15）
                if (!ControlDeskBlock.placementInGrid(
                        getBlockState().getValue(ControlDeskBlock.DOCKED), placeX, placeZ,
                        THROTTLE_FOOTPRINT_HALF_X, THROTTLE_FOOTPRINT_HALF_Z)) {
                    return false;
                }
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
                // 4×4 占位必须完全位于桌顶网格内（普通 x1..15/z9..15；docked x1..15/z1..15），防止「差一格在网格外也能放」
                if (!ControlDeskBlock.joystick2PlacementInGrid(
                        getBlockState().getValue(ControlDeskBlock.DOCKED), placeX, placeZ)) {
                    return false;
                }
                joystick2Installed = true;
                joystick2PlaceX = placeX;
                joystick2PlaceZ = placeZ;
                // 让模型 -Z（Blockbench 北向正面）面向安装时的玩家（90° 间隔），另加基础 +90° 偏移（模型默认朝向差 90°，见 rotationToFace2）；toPlayer 为 null 时保持原值
                if (toPlayer != null) {
                    backSlotRotation = rotationToFace2(getBlockState().getValue(ControlDeskBlock.FACING), toPlayer);
                }
            }
            case THROTTLE_2 -> {
                if (blocksPlacement(placeX, placeZ, THROTTLE_2_FOOTPRINT_HALF_X, THROTTLE_2_FOOTPRINT_HALF_Z)) return false; // 14×6 与已装模块占用重叠
                // 14×6 占位必须完全位于桌顶网格内（普通 x1..15/z9..15；docked x1..15/z1..15）
                if (!ControlDeskBlock.placementInGrid(
                        getBlockState().getValue(ControlDeskBlock.DOCKED), placeX, placeZ,
                        THROTTLE_2_FOOTPRINT_HALF_X, THROTTLE_2_FOOTPRINT_HALF_Z)) {
                    return false;
                }
                throttle2Installed = true;
                throttle2PlaceX = placeX;
                throttle2PlaceZ = placeZ;
                // 只能 0°/180°：让模型 -Z 尽量面向安装时的玩家（90° 结果量化到最近 0/180，照抄 throttle）；toPlayer 为 null 时保持原值
                if (toPlayer != null) {
                    backSlotRotation = rotationToFace180(getBlockState().getValue(ControlDeskBlock.FACING), toPlayer);
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
        if (throttle2Installed) {
            int hx = THROTTLE_2_FOOTPRINT_HALF_X;
            int hz = THROTTLE_2_FOOTPRINT_HALF_Z;
            if (Math.abs(cx - throttle2PlaceX) < halfX + hx && Math.abs(cz - throttle2PlaceZ) < halfZ + hz) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在桌顶模块占位与「拓展坞多出来的区域」重叠（北向基准：普通 6×14 网格 z9..15，
     * 装 dock 后网格扩展为 14×14（z1..15），多出来的区域 = 北侧 z1..8）。
     * 判定口径：模块占地北缘 {@code placeZ - halfZ < 9} ⟺ 该模块占用了多余区域，
     * 拆除拓展坞前必须先把这些模块全部拆掉（否则拆 dock 后模块位置悬空/超出网格）。
     * 供服务端 {@code onSneakWrenched} 拦截拆 dock 与客户端拆除预览变红共用。
     */
    public boolean hasModuleOnDockExtension() {
        if (joystick2Installed && joystick2PlaceZ - JOYSTICK_2_FOOTPRINT_HALF < 9) return true;
        if (throttleInstalled && throttlePlaceZ - THROTTLE_FOOTPRINT_HALF_Z < 9) return true;
        if (throttle2Installed && throttle2PlaceZ - THROTTLE_2_FOOTPRINT_HALF_Z < 9) return true;
        if (monitor2Installed && monitor2PlaceZ - MONITOR_2_FOOTPRINT_HALF_Z < 9) return true;
        return false;
    }

    /**
     * monitor_2 表面是否还有模块或屏幕（10×8 网格非空）：拆除 monitor_2 前必须先拆完表面附着物
     * （对齐拆 dock 的拦截 {@link #hasModuleOnDockExtension()}）。
     * 供服务端 {@code onSneakWrenched} 拦截拆 monitor_2 与客户端拆除预览变红共用。
     */
    public boolean hasMonitor2Modules() {
        return monitor2Installed
                && (!getMonitor2Grid().isEmpty() || getMonitor2Grid().hasScreen());
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
                // 卸下摇杆2：放置位置、运行时轴状态与输入租约一并清除（重新安装后从中心开始）
                joystick2PlaceX = 8;
                joystick2PlaceZ = 8;
                joystick2AxisX = 0f;
                joystick2AxisY = 0f;
                clearInput();
            }
            case THROTTLE_2 -> {
                throttle2Installed = false;
                // 卸下油门2：放置位复位到唯一合法位置 (8,12)，运行时角度清零（重新安装后从最底端开始）
                throttle2PlaceX = THROTTLE_2_PLACE_X;
                throttle2PlaceZ = THROTTLE_2_PLACE_Z;
                throttle2Angle = 0f;
                clearInput();
            }
            case DOCK -> {
                dockInstalled = false;
                // blockstate DOCKED 由 ControlDeskBlock.onSneakWrenched 同步复位（模型回到 base 形态）
            }
            case BAFFLE -> {
                baffleInstalled = false;
                // blockstate BAFFLED 由 ControlDeskBlock.onSneakWrenched 同步复位（模型回到 base 形态）
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

    /** throttle_2 放置中心 X（北向模型空间 px，唯一合法位 8）。 */
    public int getThrottle2PlaceX() {
        return throttle2PlaceX;
    }

    /** throttle_2 放置中心 Z（北向模型空间 px，唯一合法位 12）。 */
    public int getThrottle2PlaceZ() {
        return throttle2PlaceZ;
    }

    /**
     * monitor_2 表面小 Monitor 的网格状态（10×8，懒加载）。
     * 仅安装 MONITOR_2 时有效；未安装时返回的空网格可用于预览/读取（不落盘）。
     */
    public GridState getMonitor2Grid() {
        if (monitor2Grid == null) {
            monitor2Grid = new GridState(MONITOR_2_GRID_WIDTH, MONITOR_2_GRID_HEIGHT);
        }
        return monitor2Grid;
    }

    // ═══════════════ monitor_2 表面小 Monitor（MonitorGridHost 实现） ═══════════════
    // 照抄 MonitorBlockEntity 的服务端方法，操作 getMonitor2Grid()；同步走 notifyChange +
    // SyncGridPayload（处理器按 BE 类型分发到本 BE 的 monitor_2 grid）。

    @Override
    public GridState getGridState() {
        return getMonitor2Grid();
    }

    /** 尝试放置 monitor_2 表面模块（服务端调用），成功返回 moduleId，失败返回 -1。 */
    @Override
    public int tryPlaceModule(int x, int y, ModuleType type) {
        int id = getMonitor2Grid().tryPlace(x, y, type);
        if (id >= 0) {
            monitor2Changed();
        }
        return id;
    }

    /** 移除 monitor_2 表面模块（服务端调用），成功返回被移除的模块类型名，失败返回 null。 */
    @Override
    public String tryRemoveModule(int moduleId) {
        var mod = getMonitor2Grid().tryRemove(moduleId);
        if (mod != null) {
            monitor2Changed();
            return mod.type().name;
        }
        return null;
    }

    /** 玩家点击按钮按下（服务端调用）：始终记录玩家点击；锁定时不改变按下状态。 */
    @Override
    public void pressModuleByPlayer(int id) {
        GridState grid = getMonitor2Grid();
        grid.recordPlayerClick(id);
        if (grid.isPlayerLocked(id)) return;
        grid.press(id);
        monitor2Changed();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
        }
    }

    /** 玩家释放按钮（服务端调用）：锁定时不改变状态。 */
    @Override
    public void releaseModuleByPlayer(int id) {
        if (getMonitor2Grid().isPlayerLocked(id)) return;
        releaseModule(id);
    }

    /** 应用 monitor_2 模块/屏幕的 ID 与配置（服务端调用）。 */
    @Override
    public void applyModuleConfig(String name, int oldId, int newId, CompoundTag config) {
        GridState grid = getMonitor2Grid();
        boolean changed;
        if (GridState.SCREEN_NAME.equals(name)) {
            changed = grid.updateScreen(oldId, newId, config.getString("text"));
        } else {
            changed = grid.trySetId(oldId, newId);
            if (changed) {
                grid.setModuleConfig(newId, config);
                if (ModuleType.KNOB == ModuleType.byName(name)) {
                    grid.setKnobAngle(newId, grid.getKnobAngle(newId));
                    grid.snapKnobToDetent(newId);
                }
            }
        }
        if (changed) {
            monitor2Changed();
        }
    }

    /** 新增一个 monitor_2 表面屏幕（服务端调用），自动分配最小空闲 ID；失败返回 -1。 */
    @Override
    public int addScreen(int x1, int y1, int x2, int y2) {
        int id = getMonitor2Grid().addScreen(x1, y1, x2, y2);
        if (id >= 0) {
            monitor2Changed();
        }
        return id;
    }

    /** 移除 monitor_2 表面指定格子的屏幕（服务端调用）。 */
    @Override
    public boolean removeScreenAt(int gx, int gy) {
        if (getMonitor2Grid().removeScreenAt(gx, gy)) {
            monitor2Changed();
            return true;
        }
        return false;
    }

    /** 同步 monitor_2 网格状态到所有追踪此区块的客户端（对齐 MonitorBlockEntity.syncGridToClients）。 */
    private void syncMonitor2GridToClients() {
        if (level instanceof ServerLevel serverLevel) {
            var payload = new SyncGridPayload(worldPosition, getMonitor2Grid().save(level.registryAccess()));
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition), payload);
        }
    }

    /** monitor_2 网格变更：本地标记 + 服务端推送 BE 更新与 grid 数据。 */
    private void monitor2Changed() {
        this.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            syncMonitor2GridToClients();
        }
    }

    @Override
    public void pressModule(int id) {
        getMonitor2Grid().press(id);
        monitor2Changed();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
        }
    }

    @Override
    public void releaseModule(int id) {
        getMonitor2Grid().release(id);
        monitor2Changed();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_OFF,
                    SoundSource.BLOCKS, 0.3f, 0.5f);
        }
    }

    @Override
    public void toggleModule(int id) {
        GridState grid = getMonitor2Grid();
        grid.toggle(id);
        monitor2Changed();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, 0.3f, grid.isPressed(id) ? 1.2f : 1.1f);
        }
    }

    @Override
    public void setToggleState(int id, boolean state) {
        if (getMonitor2Grid().getModule(id) == null) return;
        if (getMonitor2Grid().isPressed(id) == state) return;
        if (state) getMonitor2Grid().press(id); else getMonitor2Grid().release(id);
        monitor2Changed();
        if (level != null && !level.isClientSide) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK,
                    SoundSource.BLOCKS, 0.3f, state ? 1.2f : 1.1f);
        }
    }

    @Override
    public void rotateKnob(int id, float angle) {
        GridState grid = getMonitor2Grid();
        int step = grid.getDetentStep(id);
        if (step > 0) angle = GridState.snapToDetent(angle, step);
        grid.setKnobAngle(id, angle);
        monitor2Changed();
    }

    @Override
    public void setTooltip(int id, String text) {
        GridState grid = getMonitor2Grid();
        if (grid.getModule(id) != null) {
            CompoundTag config = grid.getModuleConfig(id).copy();
            config.putString("text", text);
            grid.setModuleConfig(id, config);
        } else if (grid.getScreenById(id) != null) {
            grid.updateScreen(id, id, text);
        } else {
            return;
        }
        monitor2Changed();
    }

    @Override
    public void setButtonPlayerControl(int id, boolean enabled) {
        getMonitor2Grid().setPlayerLocked(id, !enabled);
        monitor2Changed();
    }

    @Override
    public void setButtonLight(int id, float brightness) {
        getMonitor2Grid().setLightBrightness(id, brightness);
        getMonitor2Grid().setLightCodeControlled(id, true);
        monitor2Changed();
    }

    @Override
    public void setButtonLightControl(int id, boolean codeControlled) {
        getMonitor2Grid().setLightCodeControlled(id, codeControlled);
        monitor2Changed();
    }

    @Override
    public void setButtonLabelText(int id, String text) {
        if (getMonitor2Grid().getModule(id) == null) return;
        getMonitor2Grid().setButtonLabelText(id, text);
        monitor2Changed();
    }

    @Override
    public void setButtonLabelPosition(int id, double x, double y) {
        if (getMonitor2Grid().getModule(id) == null) return;
        getMonitor2Grid().setButtonLabelPosition(id, x, y);
        monitor2Changed();
    }

    @Override
    public void setButtonLabelScale(int id, double scale) {
        if (getMonitor2Grid().getModule(id) == null) return;
        getMonitor2Grid().setButtonLabelScale(id, scale);
        monitor2Changed();
    }

    @Override
    public void setButtonLabelColor(int id, int color) {
        if (getMonitor2Grid().getModule(id) == null) return;
        getMonitor2Grid().setButtonLabelColor(id, color);
        monitor2Changed();
    }

    @Override
    public void setButtonLabelDropShadow(int id, boolean dropShadow) {
        if (getMonitor2Grid().getModule(id) == null) return;
        getMonitor2Grid().setButtonLabelDropShadow(id, dropShadow);
        monitor2Changed();
    }

    // ── monitor_2 屏幕（格子模型） ──

    private boolean canMutateMonitor2Screen(int id) {
        if (level == null || level.isClientSide) return false;
        return getMonitor2Grid().getScreenById(id) != null;
    }

    private double monitor2ScreenInnerWidthPx(GridState.ScreenRegion scr) {
        return scr.width() - 2 * ScreenText.DRAWABLE_INSET * 16;
    }

    private double monitor2ScreenInnerHeightPx(GridState.ScreenRegion scr) {
        return scr.height() - 2 * ScreenText.DRAWABLE_INSET * 16;
    }

    @Override
    public void screenSetGrid(int id, int cols, int rows) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).setGrid(cols, rows);
        monitor2Changed();
    }

    @Override
    public int[] getScreenGrid(int id) {
        ScreenText t = getMonitor2Grid().getScreenText(id);
        if (t == null) return null;
        return new int[] { t.getCols(), t.getRows() };
    }

    @Override
    public void screenSetTextScale(int id, double scale, Double lineSpacing) {
        if (!canMutateMonitor2Screen(id)) return;
        GridState.ScreenRegion scr = getMonitor2Grid().getScreenById(id);
        ScreenText t = getMonitor2Grid().getOrCreateScreenText(id);
        t.setTextScale(scale, lineSpacing != null ? lineSpacing : ScreenText.LINE_SPACING,
                monitor2ScreenInnerWidthPx(scr), monitor2ScreenInnerHeightPx(scr));
        monitor2Changed();
    }

    @Override
    public void screenWrite(int id, String text) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).write(text);
        monitor2Changed();
    }

    @Override
    public void screenClear(int id) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).clear();
        monitor2Changed();
    }

    @Override
    public void screenSetCursor(int id, int col, int row) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).setCursorPos(col, row);
        monitor2Changed();
    }

    @Override
    public void screenSetTextColour(int id, int colour) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).setTextColour(colour);
        monitor2Changed();
    }

    @Override
    public void screenSetZIndex(int id, double z) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).setZIndex(z);
        monitor2Changed();
    }

    @Override
    public void screenSetOverflowMode(int id, String mode) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).setOverflowMode(ScreenText.OverflowMode.byName(mode));
        monitor2Changed();
    }

    @Override
    public void screenFill(int id, int col, int row, int w, int h, int colour) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).fill(col, row, w, h, colour);
        monitor2Changed();
    }

    @Override
    public void screenWriteField(int id, int col, int row, int width, String text, String align) {
        if (!canMutateMonitor2Screen(id)) return;
        ScreenText t = getMonitor2Grid().getOrCreateScreenText(id);
        t.writeField(col, row, width, text, ScreenText.Align.byName(align));
        monitor2Changed();
    }

    @Override
    public void screenFillField(int id, int col, int row, int width, int count, int colour, String align) {
        if (!canMutateMonitor2Screen(id)) return;
        ScreenText t = getMonitor2Grid().getOrCreateScreenText(id);
        t.fillField(col, row, width, count, colour, ScreenText.Align.byName(align));
        monitor2Changed();
    }

    @Override
    public void screenDraw(int id, List<int[]> cells,
                           List<ScreenText.Rect> rects, List<ScreenText.Line> lines, List<ScreenText.Circle> circles) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).replaceAll(cells, rects, lines, circles);
        monitor2Changed();
    }

    @Override
    public void screenReplaceCells(int id, List<int[]> cells) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).replaceCells(cells);
        monitor2Changed();
    }

    @Override
    public void screenReplaceShapes(int id, List<ScreenText.Rect> rects,
                                    List<ScreenText.Line> lines, List<ScreenText.Circle> circles) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).replaceShapes(rects, lines, circles);
        monitor2Changed();
    }

    @Override
    public void screenDrawRect(int id, double x, double y, double w, double h,
                               int colour, boolean solid, double lineWidth, Double z) {
        if (!canMutateMonitor2Screen(id)) return;
        ScreenText t = getMonitor2Grid().getOrCreateScreenText(id);
        t.addRect(x, y, w, h, colour, solid, lineWidth, z != null ? z : t.getZIndex());
        monitor2Changed();
    }

    @Override
    public void screenClearRects(int id) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).clearRects();
        monitor2Changed();
    }

    @Override
    public void screenDrawLine(int id, double x1, double y1, double x2, double y2,
                               int colour, double lineWidth, Double z) {
        if (!canMutateMonitor2Screen(id)) return;
        ScreenText t = getMonitor2Grid().getOrCreateScreenText(id);
        t.addLine(x1, y1, x2, y2, colour, lineWidth, z != null ? z : t.getZIndex());
        monitor2Changed();
    }

    @Override
    public void screenDrawCircle(int id, double cx, double cy, double radius, int colour,
                                 boolean solid, double lineWidth, int segments, Double z) {
        if (!canMutateMonitor2Screen(id)) return;
        ScreenText t = getMonitor2Grid().getOrCreateScreenText(id);
        t.addCircle(cx, cy, radius, colour, solid, lineWidth, segments, z != null ? z : t.getZIndex());
        monitor2Changed();
    }

    @Override
    public void screenClearShapes(int id) {
        if (!canMutateMonitor2Screen(id)) return;
        getMonitor2Grid().getOrCreateScreenText(id).clearShapes();
        monitor2Changed();
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
     * joystick_2 安装朝向：{@link #rotationToFace} 结果 + 基础 {@link #JOYSTICK_2_ROTATION_OFFSET}（+90°）偏移
     * （模型在 Blockbench 中的默认朝向与「-Z 面向玩家」相差 90°，用户定稿），仍为 90° 间隔（北/南/西/东）。
     * 预览（ghost）与实装（{@link #install}）共用同一实现，防偏差。
     */
    public static int rotationToFace2(Direction deskFacing, @Nullable Direction toPlayer) {
        return Math.floorMod(rotationToFace(deskFacing, toPlayer) + JOYSTICK_2_ROTATION_OFFSET, 360);
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
    /** joystick_2 安装旋转基础偏移（度）：模型默认朝向与「-Z 面向玩家」相差 90°（用户定稿），预览与实装统一加该偏移（见 {@link #rotationToFace2}）。 */
    public static final int JOYSTICK_2_ROTATION_OFFSET = 90;
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
    /** throttle_2 占地半宽（北向模型空间 px）：14×6 → x±7 / z±3；预览盒与占用阻挡共用（与 throttle 同尺寸）。 */
    public static final int THROTTLE_2_FOOTPRINT_HALF_X = 7;
    public static final int THROTTLE_2_FOOTPRINT_HALF_Z = 3;
    /** throttle_2 预览盒底 y（北向模型空间 px，下沉 1px 嵌入桌面示意）～ 顶 y13（高 6，与 throttle 相同）。 */
    public static final float THROTTLE_2_PLACE_Y_BOTTOM = 7f;
    public static final float THROTTLE_2_PLACE_Y_TOP = 13f;
    /** throttle_2 模型默认中心 x/z（Blockbench 旋转中心 (8,0,8) → 8）与底座底 y（0）：安装渲染时平移到放置位。 */
    public static final float THROTTLE_2_MODEL_CENTER = 8f;
    public static final float THROTTLE_2_MODEL_BOTTOM_Y = 0f;
    /** throttle_2 唯一合法放置中心（14×6 占地完全处于桌顶网格 x1..15 / z9..15 → 仅 (8,12)，全占）。 */
    public static final int THROTTLE_2_PLACE_X = 8;
    public static final int THROTTLE_2_PLACE_Z = 12;
    /** monitor_2 屏幕表面（case 前脸，北向基准模型空间 px）：case 元素 x2..14 / y1..11 / z2..6 的 z=2 前脸。 */
    public static final float MONITOR_2_SCREEN_Z = 2f;
    /** monitor_2 表面模块锚点相对屏幕面的凸出量（北向模型空间 px，模块背面本地 z=1px → 锚点 = 屏幕面 − 1px 使背面贴屏幕面、整体向外凸 1px；屏幕 9 宫格/文字仍贴屏幕面）。 */
    public static final float MONITOR_2_MODULE_PROTRUDE_PX = 1f;
    public static final float MONITOR_2_SCREEN_X_MIN = 2f;
    public static final float MONITOR_2_SCREEN_X_MAX = 14f;
    public static final float MONITOR_2_SCREEN_Y_MIN = 1f;
    public static final float MONITOR_2_SCREEN_Y_MAX = 11f;
    /** monitor_2 屏幕棋盘网格（格）：屏幕面 12×10 → 四周各内缩 1px → 10×8 格（用户定稿）。 */
    public static final int MONITOR_2_GRID_WIDTH = 10;
    public static final int MONITOR_2_GRID_HEIGHT = 8;
    /** monitor_2 case 前脸绕 x 轴旋转（Blockbench 模型内烘焙，monitor_2.json case 元素 rotation，px）：
     *  angle 22.5°、origin [14,4,3]（屏幕后仰 22.5°；方向符号待进游戏验证，反了翻转角度符号）。 */
    public static final float MONITOR_2_SCREEN_TILT_DEG = 22.5f;
    public static final float MONITOR_2_SCREEN_TILT_ORIGIN_X = 14f;
    public static final float MONITOR_2_SCREEN_TILT_ORIGIN_Y = 4f;
    public static final float MONITOR_2_SCREEN_TILT_ORIGIN_Z = 3f;

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

    /** 油门2 角度（0..{@link Throttle2Motion#MAX_DEG}，运行时）：0 = 最底端（放置默认），+MAX = 上抬满偏（总距杆单边行程，服务端权威）。 */
    public float getThrottle2Angle() {
        return throttle2Angle;
    }

    /** 设置油门2 角度（度），钳位到 [0, MAX_DEG]。服务端调用（Lua setAngle 走这里，服务端权威 + 广播）。 */
    public void setThrottle2Angle(float degrees) {
        float clamped = Math.max(0f, Math.min(Throttle2Motion.MAX_DEG, degrees));
        if (throttle2Angle == clamped) return;
        throttle2Angle = clamped;
        notifyChange();
    }

    /** 油门2 上抬键（空格）是否有按键动作（原始值，服务端输入租约）。 */
    public boolean isThrottle2UpActive() {
        return inputThrottle2Up;
    }

    /** 油门2 下拉键（左Ctrl）是否有按键动作（原始值，服务端输入租约）。 */
    public boolean isThrottle2DownActive() {
        return inputThrottle2Down;
    }

    /**
     * 写入坐垫操作输入（运行时，服务端调用；按玩家/坐垫租约记录，租约变化时重置边沿历史，
     * 避免换人/换坐垫后第一次按键不触发档位边沿）。
     * 参数 = 操纵杆四方向按住态 + 踏板四键按住态（踩下/抬起）+ 油门两键按住态（前进/后退）
     * + 油门2 两键按住态（上抬/下拉，写死键，独立于油门）；
     * 同一份方向输入同时写入摇杆2 租约（{@code input2*} / {@code prev2*}，两控件可同时安装、各自模拟）。
     */
    public void setSeatInput(UUID player, BlockPos seatPos,
                             boolean up, boolean down, boolean left, boolean right,
                             boolean pedalLeftDown, boolean pedalLeftUp,
                             boolean pedalRightDown, boolean pedalRightUp,
                             boolean throttleForward, boolean throttleBack,
                             boolean throttle2Up, boolean throttle2Down) {
        boolean leaseChanged = !Objects.equals(inputPlayer, player) || !Objects.equals(inputSeatPos, seatPos);
        inputPlayer = player;
        inputSeatPos = seatPos;
        inputUp = up;
        inputDown = down;
        inputLeft = left;
        inputRight = right;
        input2Up = up;
        input2Down = down;
        input2Left = left;
        input2Right = right;
        inputPedalLeftDown = pedalLeftDown;
        inputPedalLeftUp = pedalLeftUp;
        inputPedalRightDown = pedalRightDown;
        inputPedalRightUp = pedalRightUp;
        inputThrottleForward = throttleForward;
        inputThrottleBack = throttleBack;
        inputThrottle2Up = throttle2Up;
        inputThrottle2Down = throttle2Down;
        if (leaseChanged) {
            prevUp = prevDown = prevLeft = prevRight = false;
            prev2Up = prev2Down = prev2Left = prev2Right = false;
        }
    }

    /** 清除输入租约（操作者离开坐垫/断线时由服务端 tick 调用）。 */
    public void clearInput() {
        inputPlayer = null;
        inputSeatPos = null;
        inputUp = inputDown = inputLeft = inputRight = false;
        prevUp = prevDown = prevLeft = prevRight = false;
        input2Up = input2Down = input2Left = input2Right = false;
        prev2Up = prev2Down = prev2Left = prev2Right = false;
        inputPedalLeftDown = inputPedalLeftUp = inputPedalRightDown = inputPedalRightUp = false;
        inputThrottleForward = inputThrottleBack = false;
        inputThrottle2Up = inputThrottle2Down = false;
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
        boolean hasJoystick2 = be.joystick2Installed;
        boolean hasPedal = be.pedalInstalled;
        boolean hasThrottle = be.throttleInstalled;
        boolean hasThrottle2 = be.throttle2Installed;
        if (!hasJoystick && !hasJoystick2 && !hasPedal && !hasThrottle && !hasThrottle2) return;
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
        if (hasJoystick2) {
            simulateJoystick2(be);
        } else {
            be.prev2Up = be.prev2Down = be.prev2Left = be.prev2Right = false;
        }
        if (hasPedal) {
            simulatePedals(be);
        }
        if (hasThrottle) {
            simulateThrottle(be);
        }
        if (hasThrottle2) {
            simulateThrottle2(be);
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
     * 摇杆2 轴动力学（自由模式 / 档位模式，逻辑与 {@link #simulateJoystick} 相同，配置/轴值/边沿历史
     * 全部独立于 joystick——两控件可同时安装、各自模拟）：读 {@code input2*} / {@code prev2*} 租约，
     * 用 {@code joystick2} 系列配置模拟到 {@code joystick2AxisX/Y}（X 轴用 Yaw 系列、Y 轴用 Pitch 系列）。
     * 轴值变化时广播。
     */
    private static void simulateJoystick2(ControlDeskBlockEntity be) {
        boolean anyInput = be.input2Up || be.input2Down || be.input2Left || be.input2Right;
        if (!anyInput && be.joystick2AxisX == 0f && be.joystick2AxisY == 0f) {
            be.prev2Up = be.prev2Down = be.prev2Left = be.prev2Right = false;
            return;
        }
        // 按下边沿（相对上一 tick 输入）
        boolean upEdge = be.input2Up && !be.prev2Up;
        boolean downEdge = be.input2Down && !be.prev2Down;
        boolean leftEdge = be.input2Left && !be.prev2Left;
        boolean rightEdge = be.input2Right && !be.prev2Right;
        be.prev2Up = be.input2Up;
        be.prev2Down = be.input2Down;
        be.prev2Left = be.input2Left;
        be.prev2Right = be.input2Right;

        float targetX = (be.input2Right && !be.input2Left) ? 1f : ((be.input2Left && !be.input2Right) ? -1f : 0f);
        float targetY = (be.input2Up && !be.input2Down) ? 1f : ((be.input2Down && !be.input2Up) ? -1f : 0f);
        float newX = be.gear2ModeYaw
                ? JoystickTilt.stepGear(be.joystick2AxisX, rightEdge, leftEdge, be.gear2CountYaw)
                : JoystickTilt.stepAxis(be.joystick2AxisX, targetX,
                        JoystickTilt.pressStep(be.freeSpeed2Yaw), JoystickTilt.returnStep(be.joystick2ReturnTimeYaw));
        float newY = be.gear2ModePitch
                ? JoystickTilt.stepGear(be.joystick2AxisY, upEdge, downEdge, be.gear2CountPitch)
                : JoystickTilt.stepAxis(be.joystick2AxisY, targetY,
                        JoystickTilt.pressStep(be.freeSpeed2Pitch), JoystickTilt.returnStep(be.joystick2ReturnTime));
        if (newX != be.joystick2AxisX || newY != be.joystick2AxisY) {
            be.joystick2AxisX = newX;
            be.joystick2AxisY = newY;
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

    /**
     * 油门2（总距杆）角度推进（数值层，角度 0..{@link Throttle2Motion#MAX_DEG}）：
     * <ul>
     *   <li>上抬键按住按满偏时间（{@link #getThrottle2FreeSpeed}，默认 20 tick，可经 Throttle2ModuleScreen 配置）线性累加到满偏 +30°；下拉键按住同样步进回到底端 0°；</li>
     *   <li>无输入（或同时按）：<b>回正开关开启</b>（{@link #isThrottle2ReturnEnabled}）时按回正时间
     *       （{@link #getThrottle2ReturnTime}，默认 2 tick，0 = 关闭回正）线性回到<b>中位 15°</b>
     *       （{@link Throttle2Motion#NEUTRAL_DEG}，用户定稿）；回正开关关闭时<b>锁存</b>保持当前角度
     *       （总距杆机械锁存，与 throttle 档位一致）。</li>
     * </ul>
     * 角度变化时广播。
     */
    private static void simulateThrottle2(ControlDeskBlockEntity be) {
        boolean up = be.inputThrottle2Up;
        boolean down = be.inputThrottle2Down;
        float oldAngle = be.throttle2Angle;
        float newAngle = oldAngle;
        if (up != down) {
            float step = Throttle2Motion.MAX_DEG / Math.max(1, be.throttle2FreeSpeed); // 每 tick 角度步进
            newAngle = oldAngle + (up ? step : -step);
            newAngle = Math.max(0f, Math.min(Throttle2Motion.MAX_DEG, newAngle));
        } else if (be.throttle2ReturnEnabled && be.throttle2ReturnTime > 0) {
            // 回正开启：无输入 → 按回正时间线性回到中位 15°（从中位偏离处计步进）
            float step = Throttle2Motion.NEUTRAL_DEG / Math.max(1, be.throttle2ReturnTime);
            if (oldAngle > Throttle2Motion.NEUTRAL_DEG) {
                newAngle = Math.max(Throttle2Motion.NEUTRAL_DEG, oldAngle - step);
            } else if (oldAngle < Throttle2Motion.NEUTRAL_DEG) {
                newAngle = Math.min(Throttle2Motion.NEUTRAL_DEG, oldAngle + step);
            }
        }
        // 回正关闭且无输入 → 锁存（newAngle 保持原值）
        if (newAngle != oldAngle) {
            // 角度每转过 5°（SOUND_STEP_DEG）播放一次 LEVER_CLICK——音调随当前角度上升
            // （参考 throttle 档位音效：前进/回正/下拉任一方向转过边界均触发，角度越大音调越高）
            int oldStep = (int) (oldAngle / Throttle2Motion.SOUND_STEP_DEG);
            int newStep = (int) (newAngle / Throttle2Motion.SOUND_STEP_DEG);
            be.throttle2Angle = newAngle;
            be.notifyChange();
            if (oldStep != newStep && be.getLevel() != null) {
                be.getLevel().playSound(null, be.getBlockPos(), SoundEvents.LEVER_CLICK,
                        SoundSource.BLOCKS, ThrottleMotion.SOUND_VOLUME2,
                        Throttle2Motion.pitchForAngle(newAngle));
            }
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

    // ════════════════════ 摇杆2（joystick_2）配置与运行时状态（独立于 joystick） ════════════════════

    /** 摇杆2 轴 X（-1..1，运行时）：+1 = 右摆，-1 = 左摆（服务端权威，经 getUpdatePacket 同步）。 */
    public float getJoystick2AxisX() {
        return joystick2AxisX;
    }

    /** 摇杆2 轴 Y（-1..1，运行时）：+1 = 前推，-1 = 后拉（服务端权威，经 getUpdatePacket 同步）。 */
    public float getJoystick2AxisY() {
        return joystick2AxisY;
    }

    /** 摇杆2 X 轴是否有按键动作（原始值，服务端输入租约）：左/右方向键任一按住。 */
    public boolean isJoystick2XActive() {
        return input2Left || input2Right;
    }

    /** 摇杆2 Y 轴是否有按键动作（原始值，服务端输入租约）：前/后方向键任一按住。 */
    public boolean isJoystick2YActive() {
        return input2Up || input2Down;
    }

    public int getJoystick2ReturnTime() {
        return joystick2ReturnTime;
    }

    /** 设置摇杆2 前后轴回正时间（tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setJoystick2ReturnTime(int ticks) {
        int clamped = Math.max(MIN_JOYSTICK_RETURN_TIME, Math.min(MAX_JOYSTICK_RETURN_TIME, ticks));
        if (joystick2ReturnTime == clamped) return;
        joystick2ReturnTime = clamped;
        notifyChange();
    }

    public int getJoystick2ReturnTimeYaw() {
        return joystick2ReturnTimeYaw;
    }

    /** 设置摇杆2 左右轴回正时间（tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setJoystick2ReturnTimeYaw(int ticks) {
        int clamped = Math.max(MIN_JOYSTICK_RETURN_TIME, Math.min(MAX_JOYSTICK_RETURN_TIME, ticks));
        if (joystick2ReturnTimeYaw == clamped) return;
        joystick2ReturnTimeYaw = clamped;
        notifyChange();
    }

    public boolean isGear2ModePitch() {
        return gear2ModePitch;
    }

    public int getGear2CountPitch() {
        return gear2CountPitch;
    }

    public boolean isGear2ModeYaw() {
        return gear2ModeYaw;
    }

    public int getGear2CountYaw() {
        return gear2CountYaw;
    }

    public int getJoystick2FreeSpeedPitch() {
        return freeSpeed2Pitch;
    }

    public int getJoystick2FreeSpeedYaw() {
        return freeSpeed2Yaw;
    }

    /** 设置摇杆2 两轴档位模式（开关 + 档位数，档位数钳位到 [MIN, MAX]）。服务端调用。 */
    public void setGear2Config(boolean pitchMode, int pitchCount, boolean yawMode, int yawCount) {
        int pc = clampGearCount(pitchCount);
        int yc = clampGearCount(yawCount);
        if (gear2ModePitch == pitchMode && gear2CountPitch == pc
                && gear2ModeYaw == yawMode && gear2CountYaw == yc) {
            return;
        }
        gear2ModePitch = pitchMode;
        gear2CountPitch = pc;
        gear2ModeYaw = yawMode;
        gear2CountYaw = yc;
        notifyChange();
    }

    /** 设置摇杆2 两轴自由模式满偏 tick 数（累加速度 = 1/数值 每 tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setJoystick2FreeSpeed(int pitchTicks, int yawTicks) {
        int pt = clampFreeSpeed(pitchTicks);
        int yt = clampFreeSpeed(yawTicks);
        if (freeSpeed2Pitch == pt && freeSpeed2Yaw == yt) return;
        freeSpeed2Pitch = pt;
        freeSpeed2Yaw = yt;
        notifyChange();
    }

    public String getJoystick2KeyUp() {
        return joystick2KeyUp;
    }

    public String getJoystick2KeyDown() {
        return joystick2KeyDown;
    }

    public String getJoystick2KeyLeft() {
        return joystick2KeyLeft;
    }

    public String getJoystick2KeyRight() {
        return joystick2KeyRight;
    }

    /** 设置摇杆2 四向按键（InputConstants.Key.getName() 格式，空串 = 未绑定）。服务端调用。 */
    public void setJoystick2Keys(String up, String down, String left, String right) {
        String u = up == null ? "" : up;
        String d = down == null ? "" : down;
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        if (Objects.equals(joystick2KeyUp, u) && Objects.equals(joystick2KeyDown, d)
                && Objects.equals(joystick2KeyLeft, l) && Objects.equals(joystick2KeyRight, r)) {
            return;
        }
        joystick2KeyUp = u;
        joystick2KeyDown = d;
        joystick2KeyLeft = l;
        joystick2KeyRight = r;
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

    public String getThrottle2KeyUp() {
        return throttle2KeyUp;
    }

    public String getThrottle2KeyDown() {
        return throttle2KeyDown;
    }

    /** 设置油门2 上抬/下拉按键（InputConstants.Key.getName() 格式，空串 = 未绑定）。服务端调用。 */
    public void setThrottle2Keys(String up, String down) {
        String u = up == null ? "" : up;
        String d = down == null ? "" : down;
        if (Objects.equals(throttle2KeyUp, u) && Objects.equals(throttle2KeyDown, d)) {
            return;
        }
        throttle2KeyUp = u;
        throttle2KeyDown = d;
        notifyChange();
    }

    /** 油门2 满偏时间（tick）：按住满该 tick 数从最底端到满偏 +30°（速度 = 1/数值 每 tick）。 */
    public int getThrottle2FreeSpeed() {
        return throttle2FreeSpeed;
    }

    /** 设置油门2 满偏时间（tick），钳位到 [MIN, MAX]。服务端调用。 */
    public void setThrottle2FreeSpeed(int ticks) {
        int clamped = Math.max(MIN_THROTTLE_2_FREE_SPEED, Math.min(MAX_THROTTLE_2_FREE_SPEED, ticks));
        if (throttle2FreeSpeed == clamped) return;
        throttle2FreeSpeed = clamped;
        notifyChange();
    }

    /** 油门2 回正开关（默认关闭 = 锁存不回正；开启后松开按键回中位 15°）。 */
    public boolean isThrottle2ReturnEnabled() {
        return throttle2ReturnEnabled;
    }

    /** 油门2 回正时间（tick）：回正开关开启时，松开按键后线性回到中位 15° 所需 tick 数（0 = 关闭回正）。 */
    public int getThrottle2ReturnTime() {
        return throttle2ReturnTime;
    }

    /** 设置油门2 回正开关 + 回正时间（tick），回正时间钳位到 [MIN, MAX]。服务端调用。 */
    public void setThrottle2Return(boolean enabled, int ticks) {
        int clamped = Math.max(MIN_THROTTLE_2_RETURN_TIME, Math.min(MAX_THROTTLE_2_RETURN_TIME, ticks));
        if (throttle2ReturnEnabled == enabled && throttle2ReturnTime == clamped) return;
        throttle2ReturnEnabled = enabled;
        throttle2ReturnTime = clamped;
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
        tag.putBoolean(TAG_THROTTLE_2, throttle2Installed);
        tag.putBoolean(TAG_DOCK, dockInstalled);
        tag.putBoolean(TAG_BAFFLE, baffleInstalled);
        tag.putInt(TAG_JOYSTICK_2_PLACE_X, joystick2PlaceX);
        tag.putInt(TAG_JOYSTICK_2_PLACE_Z, joystick2PlaceZ);
        tag.putInt(TAG_THROTTLE_PLACE_X, throttlePlaceX);
        tag.putInt(TAG_THROTTLE_PLACE_Z, throttlePlaceZ);
        tag.putInt(TAG_THROTTLE_2_PLACE_X, throttle2PlaceX);
        tag.putInt(TAG_THROTTLE_2_PLACE_Z, throttle2PlaceZ);
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
        tag.putInt(TAG_JOYSTICK2_RETURN_TIME, joystick2ReturnTime);
        tag.putInt(TAG_JOYSTICK2_RETURN_TIME_YAW, joystick2ReturnTimeYaw);
        tag.putBoolean(TAG_GEAR2_MODE_PITCH, gear2ModePitch);
        tag.putInt(TAG_GEAR2_COUNT_PITCH, gear2CountPitch);
        tag.putBoolean(TAG_GEAR2_MODE_YAW, gear2ModeYaw);
        tag.putInt(TAG_GEAR2_COUNT_YAW, gear2CountYaw);
        tag.putInt(TAG_JOYSTICK2_FREE_SPEED_PITCH, freeSpeed2Pitch);
        tag.putInt(TAG_JOYSTICK2_FREE_SPEED_YAW, freeSpeed2Yaw);
        tag.putString(TAG_JOYSTICK2_KEY_UP, joystick2KeyUp);
        tag.putString(TAG_JOYSTICK2_KEY_DOWN, joystick2KeyDown);
        tag.putString(TAG_JOYSTICK2_KEY_LEFT, joystick2KeyLeft);
        tag.putString(TAG_JOYSTICK2_KEY_RIGHT, joystick2KeyRight);
        tag.putInt(TAG_PEDAL_RETURN_TIME, pedalReturnTime);
        tag.putInt(TAG_PEDAL_FREE_SPEED, pedalFreeSpeed);
        tag.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        tag.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        tag.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        tag.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        tag.putString(TAG_THROTTLE_KEY_FORWARD, throttleKeyForward);
        tag.putString(TAG_THROTTLE_KEY_BACK, throttleKeyBack);
        tag.putInt(TAG_THROTTLE_TICKS_PER_GEAR, throttleTicksPerGear);
        tag.putString(TAG_THROTTLE_2_KEY_UP, throttle2KeyUp);
        tag.putString(TAG_THROTTLE_2_KEY_DOWN, throttle2KeyDown);
        tag.putInt(TAG_THROTTLE_2_FREE_SPEED, throttle2FreeSpeed);
        tag.putBoolean(TAG_THROTTLE_2_RETURN_ENABLED, throttle2ReturnEnabled);
        tag.putInt(TAG_THROTTLE_2_RETURN_TIME, throttle2ReturnTime);
        tag.putInt(TAG_CHANNEL, channel);
        tag.putIntArray(TAG_OCCUPIED_CHANNELS, occupiedChannels);
        // monitor_2 表面网格（仅安装 MONITOR_2 时保存；蓝图/存档可携带表面模块）
        if (monitor2Grid != null) {
            tag.put(TAG_MONITOR_2_GRID, monitor2Grid.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pedalInstalled = tag.getBoolean(TAG_PEDAL);
        joystickInstalled = tag.getBoolean(TAG_JOYSTICK);
        monitor2Installed = tag.getBoolean(TAG_MONITOR_2);
        throttleInstalled = tag.getBoolean(TAG_THROTTLE);
        joystick2Installed = tag.getBoolean(TAG_JOYSTICK_2);
        throttle2Installed = tag.getBoolean(TAG_THROTTLE_2);
        dockInstalled = tag.getBoolean(TAG_DOCK);
        baffleInstalled = tag.getBoolean(TAG_BAFFLE);
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
        if (tag.contains(TAG_THROTTLE_2_PLACE_X)) {
            throttle2PlaceX = tag.getInt(TAG_THROTTLE_2_PLACE_X);
        }
        if (tag.contains(TAG_THROTTLE_2_PLACE_Z)) {
            throttle2PlaceZ = tag.getInt(TAG_THROTTLE_2_PLACE_Z);
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
        if (tag.contains(TAG_JOYSTICK2_AXIS_X)) {
            joystick2AxisX = tag.getFloat(TAG_JOYSTICK2_AXIS_X);
        }
        if (tag.contains(TAG_JOYSTICK2_AXIS_Y)) {
            joystick2AxisY = tag.getFloat(TAG_JOYSTICK2_AXIS_Y);
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
        if (tag.contains(TAG_THROTTLE_2_ANGLE)) {
            throttle2Angle = Math.max(0f, Math.min(Throttle2Motion.MAX_DEG, tag.getFloat(TAG_THROTTLE_2_ANGLE)));
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
        // 摇杆2 配置（独立于 joystick；旧存档无字段时保持默认）
        if (tag.contains(TAG_JOYSTICK2_RETURN_TIME)) {
            joystick2ReturnTime = tag.getInt(TAG_JOYSTICK2_RETURN_TIME);
        }
        if (tag.contains(TAG_JOYSTICK2_RETURN_TIME_YAW)) {
            joystick2ReturnTimeYaw = tag.getInt(TAG_JOYSTICK2_RETURN_TIME_YAW);
        }
        if (tag.contains(TAG_GEAR2_MODE_PITCH)) {
            gear2ModePitch = tag.getBoolean(TAG_GEAR2_MODE_PITCH);
        }
        if (tag.contains(TAG_GEAR2_COUNT_PITCH)) {
            gear2CountPitch = tag.getInt(TAG_GEAR2_COUNT_PITCH);
        }
        if (tag.contains(TAG_GEAR2_MODE_YAW)) {
            gear2ModeYaw = tag.getBoolean(TAG_GEAR2_MODE_YAW);
        }
        if (tag.contains(TAG_GEAR2_COUNT_YAW)) {
            gear2CountYaw = tag.getInt(TAG_GEAR2_COUNT_YAW);
        }
        if (tag.contains(TAG_JOYSTICK2_FREE_SPEED_PITCH)) {
            freeSpeed2Pitch = tag.getInt(TAG_JOYSTICK2_FREE_SPEED_PITCH);
        }
        if (tag.contains(TAG_JOYSTICK2_FREE_SPEED_YAW)) {
            freeSpeed2Yaw = tag.getInt(TAG_JOYSTICK2_FREE_SPEED_YAW);
        }
        if (tag.contains(TAG_JOYSTICK2_KEY_UP)) {
            joystick2KeyUp = tag.getString(TAG_JOYSTICK2_KEY_UP);
        }
        if (tag.contains(TAG_JOYSTICK2_KEY_DOWN)) {
            joystick2KeyDown = tag.getString(TAG_JOYSTICK2_KEY_DOWN);
        }
        if (tag.contains(TAG_JOYSTICK2_KEY_LEFT)) {
            joystick2KeyLeft = tag.getString(TAG_JOYSTICK2_KEY_LEFT);
        }
        if (tag.contains(TAG_JOYSTICK2_KEY_RIGHT)) {
            joystick2KeyRight = tag.getString(TAG_JOYSTICK2_KEY_RIGHT);
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
        if (tag.contains(TAG_THROTTLE_2_KEY_UP)) {
            throttle2KeyUp = tag.getString(TAG_THROTTLE_2_KEY_UP);
        }
        if (tag.contains(TAG_THROTTLE_2_KEY_DOWN)) {
            throttle2KeyDown = tag.getString(TAG_THROTTLE_2_KEY_DOWN);
        }
        if (tag.contains(TAG_THROTTLE_2_FREE_SPEED)) {
            throttle2FreeSpeed = Math.max(MIN_THROTTLE_2_FREE_SPEED,
                    Math.min(MAX_THROTTLE_2_FREE_SPEED, tag.getInt(TAG_THROTTLE_2_FREE_SPEED)));
        }
        if (tag.contains(TAG_THROTTLE_2_RETURN_ENABLED)) {
            throttle2ReturnEnabled = tag.getBoolean(TAG_THROTTLE_2_RETURN_ENABLED);
        }
        if (tag.contains(TAG_THROTTLE_2_RETURN_TIME)) {
            throttle2ReturnTime = Math.max(MIN_THROTTLE_2_RETURN_TIME,
                    Math.min(MAX_THROTTLE_2_RETURN_TIME, tag.getInt(TAG_THROTTLE_2_RETURN_TIME)));
        }
        if (tag.contains(TAG_CHANNEL)) {
            channel = tag.getInt(TAG_CHANNEL);
        }
        if (tag.contains(TAG_OCCUPIED_CHANNELS)) {
            occupiedChannels = tag.getIntArray(TAG_OCCUPIED_CHANNELS);
        }
        if (tag.contains(TAG_MONITOR_2_GRID)) {
            getMonitor2Grid().load(registries, tag.getCompound(TAG_MONITOR_2_GRID));
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
        compound.putBoolean(TAG_THROTTLE_2, throttle2Installed);
        compound.putBoolean(TAG_DOCK, dockInstalled);
        compound.putBoolean(TAG_BAFFLE, baffleInstalled);
        compound.putInt(TAG_JOYSTICK_2_PLACE_X, joystick2PlaceX);
        compound.putInt(TAG_JOYSTICK_2_PLACE_Z, joystick2PlaceZ);
        compound.putInt(TAG_THROTTLE_2_PLACE_X, throttle2PlaceX);
        compound.putInt(TAG_THROTTLE_2_PLACE_Z, throttle2PlaceZ);
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
        compound.putInt(TAG_JOYSTICK2_RETURN_TIME, joystick2ReturnTime);
        compound.putInt(TAG_JOYSTICK2_RETURN_TIME_YAW, joystick2ReturnTimeYaw);
        compound.putBoolean(TAG_GEAR2_MODE_PITCH, gear2ModePitch);
        compound.putInt(TAG_GEAR2_COUNT_PITCH, gear2CountPitch);
        compound.putBoolean(TAG_GEAR2_MODE_YAW, gear2ModeYaw);
        compound.putInt(TAG_GEAR2_COUNT_YAW, gear2CountYaw);
        compound.putInt(TAG_JOYSTICK2_FREE_SPEED_PITCH, freeSpeed2Pitch);
        compound.putInt(TAG_JOYSTICK2_FREE_SPEED_YAW, freeSpeed2Yaw);
        compound.putString(TAG_JOYSTICK2_KEY_UP, joystick2KeyUp);
        compound.putString(TAG_JOYSTICK2_KEY_DOWN, joystick2KeyDown);
        compound.putString(TAG_JOYSTICK2_KEY_LEFT, joystick2KeyLeft);
        compound.putString(TAG_JOYSTICK2_KEY_RIGHT, joystick2KeyRight);
        compound.putInt(TAG_PEDAL_RETURN_TIME, pedalReturnTime);
        compound.putInt(TAG_PEDAL_FREE_SPEED, pedalFreeSpeed);
        compound.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        compound.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        compound.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        compound.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        compound.putString(TAG_THROTTLE_KEY_FORWARD, throttleKeyForward);
        compound.putString(TAG_THROTTLE_KEY_BACK, throttleKeyBack);
        compound.putInt(TAG_THROTTLE_TICKS_PER_GEAR, throttleTicksPerGear);
        compound.putString(TAG_THROTTLE_2_KEY_UP, throttle2KeyUp);
        compound.putString(TAG_THROTTLE_2_KEY_DOWN, throttle2KeyDown);
        compound.putInt(TAG_THROTTLE_2_FREE_SPEED, throttle2FreeSpeed);
        compound.putBoolean(TAG_THROTTLE_2_RETURN_ENABLED, throttle2ReturnEnabled);
        compound.putInt(TAG_THROTTLE_2_RETURN_TIME, throttle2ReturnTime);
        // 频道是配置（蓝图可分享）；OccupiedChannels 是运行时快照，不写 Safe NBT
        compound.putInt(TAG_CHANNEL, channel);
        // monitor_2 表面网格：蓝图可携带表面模块（对齐 Monitor 的 GridState 走 saveAdditional）
        if (monitor2Grid != null) {
            compound.put(TAG_MONITOR_2_GRID, monitor2Grid.save(registries));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
        tag.putBoolean(TAG_MONITOR_2, monitor2Installed);
        tag.putBoolean(TAG_THROTTLE, throttleInstalled);
        tag.putBoolean(TAG_JOYSTICK_2, joystick2Installed);
        tag.putBoolean(TAG_THROTTLE_2, throttle2Installed);
        tag.putBoolean(TAG_DOCK, dockInstalled);
        tag.putBoolean(TAG_BAFFLE, baffleInstalled);
        tag.putInt(TAG_JOYSTICK_2_PLACE_X, joystick2PlaceX);
        tag.putInt(TAG_JOYSTICK_2_PLACE_Z, joystick2PlaceZ);
        tag.putInt(TAG_THROTTLE_PLACE_X, throttlePlaceX);
        tag.putInt(TAG_THROTTLE_PLACE_Z, throttlePlaceZ);
        tag.putInt(TAG_THROTTLE_2_PLACE_X, throttle2PlaceX);
        tag.putInt(TAG_THROTTLE_2_PLACE_Z, throttle2PlaceZ);
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
        tag.putFloat(TAG_JOYSTICK2_AXIS_X, joystick2AxisX);
        tag.putFloat(TAG_JOYSTICK2_AXIS_Y, joystick2AxisY);
        // 运行时踏板轴（服务端权威）：同上
        tag.putFloat(TAG_PEDAL_LEFT_AXIS, pedalLeftAxis);
        tag.putFloat(TAG_PEDAL_RIGHT_AXIS, pedalRightAxis);
        // 运行时油门轴（服务端权威）：同上（档位 / MAX_TRAVEL_PX，客户端 loadAdditional 换算回档位）
        tag.putFloat(TAG_THROTTLE_AXIS, getThrottleAxis());
        // 运行时油门2 角度（服务端权威）：同上（0..MAX_DEG，客户端 loadAdditional contains 守卫读）
        tag.putFloat(TAG_THROTTLE_2_ANGLE, throttle2Angle);
        tag.putString(TAG_JOYSTICK_KEY_UP, joystickKeyUp);
        tag.putString(TAG_JOYSTICK_KEY_DOWN, joystickKeyDown);
        tag.putString(TAG_JOYSTICK_KEY_LEFT, joystickKeyLeft);
        tag.putString(TAG_JOYSTICK_KEY_RIGHT, joystickKeyRight);
        tag.putInt(TAG_JOYSTICK2_RETURN_TIME, joystick2ReturnTime);
        tag.putInt(TAG_JOYSTICK2_RETURN_TIME_YAW, joystick2ReturnTimeYaw);
        tag.putBoolean(TAG_GEAR2_MODE_PITCH, gear2ModePitch);
        tag.putInt(TAG_GEAR2_COUNT_PITCH, gear2CountPitch);
        tag.putBoolean(TAG_GEAR2_MODE_YAW, gear2ModeYaw);
        tag.putInt(TAG_GEAR2_COUNT_YAW, gear2CountYaw);
        tag.putInt(TAG_JOYSTICK2_FREE_SPEED_PITCH, freeSpeed2Pitch);
        tag.putInt(TAG_JOYSTICK2_FREE_SPEED_YAW, freeSpeed2Yaw);
        tag.putString(TAG_JOYSTICK2_KEY_UP, joystick2KeyUp);
        tag.putString(TAG_JOYSTICK2_KEY_DOWN, joystick2KeyDown);
        tag.putString(TAG_JOYSTICK2_KEY_LEFT, joystick2KeyLeft);
        tag.putString(TAG_JOYSTICK2_KEY_RIGHT, joystick2KeyRight);
        tag.putInt(TAG_PEDAL_RETURN_TIME, pedalReturnTime);
        tag.putInt(TAG_PEDAL_FREE_SPEED, pedalFreeSpeed);
        tag.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        tag.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        tag.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        tag.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        tag.putString(TAG_THROTTLE_KEY_FORWARD, throttleKeyForward);
        tag.putString(TAG_THROTTLE_KEY_BACK, throttleKeyBack);
        tag.putInt(TAG_THROTTLE_TICKS_PER_GEAR, throttleTicksPerGear);
        tag.putString(TAG_THROTTLE_2_KEY_UP, throttle2KeyUp);
        tag.putString(TAG_THROTTLE_2_KEY_DOWN, throttle2KeyDown);
        tag.putInt(TAG_THROTTLE_2_FREE_SPEED, throttle2FreeSpeed);
        tag.putBoolean(TAG_THROTTLE_2_RETURN_ENABLED, throttle2ReturnEnabled);
        tag.putInt(TAG_THROTTLE_2_RETURN_TIME, throttle2ReturnTime);
        tag.putInt(TAG_CHANNEL, channel);
        tag.putIntArray(TAG_OCCUPIED_CHANNELS, occupiedChannels);
        // monitor_2 表面网格：随 BE 更新包同步（客户端读取后即可渲染表面模块）
        if (monitor2Grid != null) {
            tag.put(TAG_MONITOR_2_GRID, monitor2Grid.save(registries));
        }
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（quill 保存读的是客户端 BE，蓝图兼容必须）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
