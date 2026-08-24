package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * 控制台方块实体 — 保存已安装控件（踏板一对 / 操纵杆）。
 * NBT 持久化 + 同步（兼容 Create 蓝图，参考 RedstoneTransceiverBlockEntity）。
 */
public class ControlDeskBlockEntity extends BlockEntity implements PartialSafeNBT {

    /** 可安装到控制台的控件类型 */
    public enum ControlType {
        PEDAL, JOYSTICK
    }

    /** 操纵杆回正时间（tick）默认值与范围（与 JoystickModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_JOYSTICK_RETURN_TIME = 20;
    public static final int MIN_JOYSTICK_RETURN_TIME = 0;
    public static final int MAX_JOYSTICK_RETURN_TIME = 100;

    /** 操纵杆档位模式（档位数）默认值与范围（与 JoystickModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_GEAR_COUNT = 4;
    public static final int MIN_GEAR_COUNT = 1;
    public static final int MAX_GEAR_COUNT = 8;

    /** 操纵杆自由模式累加速度（满偏所需 tick 数，速度 = 1/数值 每 tick）默认值与范围（与 JoystickModuleScreen 滚轮条一致）。 */
    public static final int DEFAULT_JOYSTICK_FREE_SPEED = 20;
    public static final int MIN_JOYSTICK_FREE_SPEED = 1;
    public static final int MAX_JOYSTICK_FREE_SPEED = 100;

    /** 操纵杆四向按键默认值（InputConstants.Key.getName() 格式，如 "key.keyboard.w"；空串 = 未绑定）。 */
    public static final String DEFAULT_JOYSTICK_KEY_UP = "key.keyboard.w";
    public static final String DEFAULT_JOYSTICK_KEY_DOWN = "key.keyboard.s";
    public static final String DEFAULT_JOYSTICK_KEY_LEFT = "key.keyboard.a";
    public static final String DEFAULT_JOYSTICK_KEY_RIGHT = "key.keyboard.d";

    /** 脚踏板回正时间（tick）默认值与范围（与 PedalModuleScreen 滚轮条一致；左右两个踏板共用同一值）。 */
    public static final int DEFAULT_PEDAL_RETURN_TIME = 20;
    public static final int MIN_PEDAL_RETURN_TIME = 0;
    public static final int MAX_PEDAL_RETURN_TIME = 100;

    /** 脚踏板按键默认值（InputConstants.Key.getName() 格式）：左踏板 踩下=Q / 抬起=E，右踏板 踩下=E / 抬起=Q。 */
    public static final String DEFAULT_PEDAL_KEY_LEFT_UP = "key.keyboard.e";
    public static final String DEFAULT_PEDAL_KEY_LEFT_DOWN = "key.keyboard.q";
    public static final String DEFAULT_PEDAL_KEY_RIGHT_UP = "key.keyboard.q";
    public static final String DEFAULT_PEDAL_KEY_RIGHT_DOWN = "key.keyboard.e";

    private static final String TAG_PEDAL = "PedalInstalled";
    private static final String TAG_JOYSTICK = "JoystickInstalled";
    private static final String TAG_JOYSTICK_RETURN_TIME = "JoystickReturnTime";
    private static final String TAG_JOYSTICK_RETURN_TIME_YAW = "JoystickReturnTimeYaw";
    private static final String TAG_GEAR_MODE_PITCH = "GearModePitch";
    private static final String TAG_GEAR_COUNT_PITCH = "GearCountPitch";
    private static final String TAG_GEAR_MODE_YAW = "GearModeYaw";
    private static final String TAG_GEAR_COUNT_YAW = "GearCountYaw";
    private static final String TAG_JOYSTICK_FREE_SPEED_PITCH = "JoystickFreeSpeedPitch";
    private static final String TAG_JOYSTICK_FREE_SPEED_YAW = "JoystickFreeSpeedYaw";
    private static final String TAG_JOYSTICK_KEY_UP = "JoystickKeyUp";
    private static final String TAG_JOYSTICK_KEY_DOWN = "JoystickKeyDown";
    private static final String TAG_JOYSTICK_KEY_LEFT = "JoystickKeyLeft";
    private static final String TAG_JOYSTICK_KEY_RIGHT = "JoystickKeyRight";
    private static final String TAG_PEDAL_RETURN_TIME = "PedalReturnTime";
    private static final String TAG_PEDAL_KEY_LEFT_UP = "PedalKeyLeftUp";
    private static final String TAG_PEDAL_KEY_LEFT_DOWN = "PedalKeyLeftDown";
    private static final String TAG_PEDAL_KEY_RIGHT_UP = "PedalKeyRightUp";
    private static final String TAG_PEDAL_KEY_RIGHT_DOWN = "PedalKeyRightDown";

    private boolean pedalInstalled;
    private boolean joystickInstalled;
    private int joystickReturnTime = DEFAULT_JOYSTICK_RETURN_TIME;      // 前后轴回正时间
    private int joystickReturnTimeYaw = DEFAULT_JOYSTICK_RETURN_TIME;   // 左右轴回正时间
    private boolean gearModePitch;                                      // 前后轴档位模式开关
    private int gearCountPitch = DEFAULT_GEAR_COUNT;                    // 前后轴档位数
    private boolean gearModeYaw;                                        // 左右轴档位模式开关
    private int gearCountYaw = DEFAULT_GEAR_COUNT;                      // 左右轴档位数
    private int freeSpeedPitch = DEFAULT_JOYSTICK_FREE_SPEED;           // 前后轴自由模式满偏 tick 数
    private int freeSpeedYaw = DEFAULT_JOYSTICK_FREE_SPEED;             // 左右轴自由模式满偏 tick 数
    private String joystickKeyUp = DEFAULT_JOYSTICK_KEY_UP;
    private String joystickKeyDown = DEFAULT_JOYSTICK_KEY_DOWN;
    private String joystickKeyLeft = DEFAULT_JOYSTICK_KEY_LEFT;
    private String joystickKeyRight = DEFAULT_JOYSTICK_KEY_RIGHT;
    private int pedalReturnTime = DEFAULT_PEDAL_RETURN_TIME;   // 左右踏板共用的回正时间（tick）
    private String pedalKeyLeftUp = DEFAULT_PEDAL_KEY_LEFT_UP;    // 左踏板 抬起键（空串 = 未绑定）
    private String pedalKeyLeftDown = DEFAULT_PEDAL_KEY_LEFT_DOWN; // 左踏板 踩下键
    private String pedalKeyRightUp = DEFAULT_PEDAL_KEY_RIGHT_UP;   // 右踏板 抬起键
    private String pedalKeyRightDown = DEFAULT_PEDAL_KEY_RIGHT_DOWN; // 右踏板 踩下键

    public ControlDeskBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.control_desk_entity.get(), pos, state);
    }

    public boolean isInstalled(ControlType type) {
        return switch (type) {
            case PEDAL -> pedalInstalled;
            case JOYSTICK -> joystickInstalled;
        };
    }

    /** 安装控件；已安装返回 false（不覆盖）。服务端调用。 */
    public boolean install(ControlType type) {
        if (isInstalled(type)) return false;
        switch (type) {
            case PEDAL -> pedalInstalled = true;
            case JOYSTICK -> joystickInstalled = true;
        }
        notifyChange();
        return true;
    }

    /** 卸载控件；未安装返回 false。服务端调用。 */
    public boolean remove(ControlType type) {
        if (!isInstalled(type)) return false;
        switch (type) {
            case PEDAL -> pedalInstalled = false;
            case JOYSTICK -> joystickInstalled = false;
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

    // ════════════════════ NBT / 同步（Create 蓝图兼容） ════════════════════

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
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
        tag.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        tag.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        tag.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        tag.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pedalInstalled = tag.getBoolean(TAG_PEDAL);
        joystickInstalled = tag.getBoolean(TAG_JOYSTICK);
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
    }

    /** Create 原理图 / 装置搬运时的「安全 NBT」（Schematicannon 打印保留控件配置）。 */
    @Override
    public void writeSafe(CompoundTag compound, HolderLookup.Provider registries) {
        compound.putBoolean(TAG_PEDAL, pedalInstalled);
        compound.putBoolean(TAG_JOYSTICK, joystickInstalled);
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
        compound.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        compound.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        compound.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        compound.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putBoolean(TAG_PEDAL, pedalInstalled);
        tag.putBoolean(TAG_JOYSTICK, joystickInstalled);
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
        tag.putString(TAG_PEDAL_KEY_LEFT_UP, pedalKeyLeftUp);
        tag.putString(TAG_PEDAL_KEY_LEFT_DOWN, pedalKeyLeftDown);
        tag.putString(TAG_PEDAL_KEY_RIGHT_UP, pedalKeyRightUp);
        tag.putString(TAG_PEDAL_KEY_RIGHT_DOWN, pedalKeyRightDown);
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（quill 保存读的是客户端 BE，蓝图兼容必须）。 */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
