local ss = require("ccpe.sensor_system")
print()
local function reset_bearing(bearing)
    bearing.assemble()
    bearing.setTargetAngle(0)
end

local function reset_screen(screen)
    screen.clear()
    screen.setTextScale(0.4, 1.0)
    screen.setTextColour(0x00FF00)
end

local N = 1
local S = 14
local ok = ss.initPropeller(N, S)
print("init propeller", ok)
local N_sail = 26
local N_symmetric_sail = 8

local CHANNEL_ENGINE = 0
local CHANNEL_SERVO_R = 1
local CHANNEL_SERVO_L = 2
local CHANNEL_CONTROL = 3


local ID_SCREEN_1 = 0
local ID_SCREEN_2 = 1
local ID_TOGGLE_SCREEN = 2
local ID_TOGGLE_LIGHT = 3
local ID_KNOB_THROTTLE = 4
local ID_KNOB_ALT = 5
local ID_TOGGLE_AUTO_TH = 6
local ID_TOGGLE_AUTO_ROLL = 7
local ID_TOGGLE_PITCH_DAMP = 8
local ID_TOGGLE_DC = 9


-- 控制/显示常量
local JOY_MAX_DEG = 15.0        -- 摇杆满偏舵角（°）

local engine = ss.getPeripheral(CHANNEL_ENGINE)
local servo_r = ss.getPeripheral(CHANNEL_SERVO_R)
local servo_l = ss.getPeripheral(CHANNEL_SERVO_L)

local control_desk = ss.getPeripheral(CHANNEL_CONTROL)

-- control desk
local module_monitor = control_desk.getModule("monitor")
local module_joystick = control_desk.getModule("joystick_3")

-- monitor
local module_toggle_screen = module_monitor.getModule(ID_TOGGLE_SCREEN)
local module_screen_1 = module_monitor.getModule(ID_SCREEN_1)
local module_screen_2 = module_monitor.getModule(ID_SCREEN_2)
local module_toggle_light = module_monitor.getModule(ID_TOGGLE_LIGHT)
local module_toggle_auto_roll = module_monitor.getModule(ID_TOGGLE_AUTO_ROLL)
local module_knob_alt = module_monitor.getModule(ID_KNOB_ALT)
local module_toggle_auto_th = module_monitor.getModule(ID_TOGGLE_AUTO_TH)
local module_toggle_pitch_damp = module_monitor.getModule(ID_TOGGLE_PITCH_DAMP)
local module_knob_throttle = module_monitor.getModule(ID_KNOB_THROTTLE)
local module_toggle_dc = module_monitor.getModule(ID_TOGGLE_DC)

-- init

reset_bearing(servo_r)
reset_bearing(servo_l)

reset_screen(module_screen_1)
reset_screen(module_screen_2)



-- 俯仰人工阻尼（phugoid 阻尼；memo .current_mission.md §9.2：ζ≈-0.02 发散，无自然阻尼）
-- 输入 = ss.getAngleRates().pitchRate（deg/s，姿态角差分+滤波；低头转>0，与 pitchDeg 同约定）
-- 理论推导：低头转（pitchRate>0）→ 应命令抬头 → target_pitch_angle 减小 → 阻尼项取 -pitchRate（SIGN=-1）
-- ⚠ 但用户实测 SIGN=+1 飞行表现更好（有意保留，勿改回 -1）。
-- 俯仰人工阻尼 GAIN 试飞记录：0.5（memo §10 验证 ζ +0.033）→ 1.0（τ=2 tick 实测后试，§6 待办 2）。
-- 若出现舵面高频抖动或新振荡模式，退回 0.75 / 0.5。
local PITCH_DAMP_GAIN = 0.5    -- 输出舵角(°) / 俯仰角速率(°/s)：先试 0.5，阻尼不足再升到 1~2
local PITCH_DAMP_SIGN = -1      -- 用户有意 =+1（实测更好）；理论符号应为 -1，勿自行改回

-- 高度保持（auto_th 开启且摇杆 Y 未用时生效；memo §13.2.3 方案 3 外环：高度差→目标垂速，内环：垂速误差→尾翼）
-- 符号：target_pitch_angle 正=低头（与阻尼环同约定，memo §11.4）；vertical_speed = ss.getVelocity().y 世界系，正=上升
-- 低于目标（err>0）→ 目标垂速>0 → 实际垂速-目标<0 → alt_pitch<0=抬头 → 爬升 ✓
-- 若舵面高频抖动：降 ALT_HOLD_KP_PITCH；若高度捕获太慢/冲过头：升/降 ALT_HOLD_KP_VS、ALT_HOLD_MAX_VS
local ALT_HOLD_KP_VS = 0.3       -- (m/s)/m：高度差 → 目标垂直速度
local ALT_HOLD_MAX_VS = 5.0      -- m/s：目标垂速限幅（防爬升/下降指令过激）
local ALT_HOLD_KP_PITCH = 1.0    -- °/(m/s)：垂速误差 → 尾翼舵角（正=低头）
local ALT_HOLD_MAX_PITCH = 15.0  -- °：高度保持输出限幅（摇杆满偏 30° 的一半）

-- 自动滚转（PD）：P 项沿用已验证 -0.5；D 项 = 「真实滚转速率」（滚转阻尼）
-- ⚠ 不用 av.z（getAngularVelocity）：实测 CSV 显示俯仰机动时 av.z 有 ±45°/s 假尖峰，
--   且符号与 d(roll)/dt 相反——用它当 D 项会把俯仰机动当成滚转指令（bug 根因）。
-- 改用 ss.getAngleRates().rollRate（Java 侧由姿态角差分+滤波算出，免疫该问题）。
-- 死区只门控 P 项，D 项始终生效（防止扰动在死区内积累）。
local ROLL_KP = -0.5          -- °/°：滚转角比例增益（原 P 控制，已验证）
local ROLL_KD = -0.5          -- °/(°/s)：滚转角速率阻尼增益（作用于 d(roll)/dt）
local ROLL_KI = -0.08         -- 1/s：积分增益（消除 PD 稳态误差；符号与 Kp 相同，负反馈）
local ROLL_INT_MAX = 12.0     -- °·s：积分限幅（防 windup；2026-09 实测扰动需 ~0.6° 偏置，6 太小被顶到饱和 → 12）
local ROLL_DEADBAND = 0.0     -- °：死区（只门控 P 项）

-- 自动油门闭环（空速保持）：模型 RPM 前馈 + 皮托管空速 PI 修正
-- 2026-09 实测：皮托管精确（airSpeed/vNorm = 0.9994），模型目标速度比真实平衡低 ~4%，
-- 开环会以 ~4% 偏快巡航；闭环把实际空速拉到目标速度，免疫风/质量/模型常数漂移。
-- 油门振荡 → 降 AIRSPEED_KP / AIRSPEED_KI；收敛太慢 → 升 AIRSPEED_KI。
local DT = 0.05              -- 控制周期（s），CC 默认 20Hz
local AIRSPEED_KP = 0.002     -- 油门比例：每 m/s 空速误差的油门修正（0.01 ≈ 2.6rpm/256）
local AIRSPEED_KI = 0.001    -- 积分增益（1/s）：稳态归零，消除模型偏移
local AIRSPEED_INT_MAX = 0.1 -- 积分限幅（油门 ±0.1，防 windup）


print("ready")

local index = 0
local clock = os.clock()
local m = ss.getPhysicsChainMass()
print("mass",m)
local G = ss.getPhysicsChainGravityForce()
print("gravity",G)
local cruise = ss.solveMaxCruise(m, N_sail, N_symmetric_sail, 1, S, 256)
print("max alt",cruise.altitude)
print("max speed",cruise.velocity)
local MIN_ALT = 66
local airspeed_int = 0   -- 空速保持积分状态（退出 auto_th 时清零）
local roll_int = 0       -- 滚转积分状态（auto_roll 关闭/摇杆接管时清零）
module_monitor.playNiceSound()

while true do
    local cells = {}
    local cells_alt_control = {}
    -- 传感器读数（nil 防护：缺传感器时用安全默认值，控制输出保持中立，不崩溃）
    local alt_current = ss.getAverageAltitude() or 0
    local pressure = ss.getAveragePressure() or 0
    local speed = ss.getSpeed() or 0
    local air_speed = ss.getAirSpeed() or 0   -- 皮托管空速（闭环反馈；已验证精确）

    local vel = ss.getVelocity() or { x = 0, y = 0, z = 0 }
    local vertical_speed = vel.y

    local target_alt = module_knob_alt.getAbsolutePercent() * (cruise.altitude - MIN_ALT) + MIN_ALT
    local is_auto_th = module_toggle_auto_th.getToggleState()
    local throttle_axis = module_knob_throttle.getAbsolutePercent()
    local target_rpm = throttle_axis * 256

    local target_pressure = ss.getPressureFromAltitude(target_alt)
    local target_speed = ss.solveSailLift(target_pressure, nil, G/N_sail)
    if is_auto_th then
        local sail_drag = (N_sail + N_symmetric_sail + S) * ss.solveSailDirectionlessDrag(target_pressure, target_speed, nil)
        local universal_drag = ss.getUniversalDragForce(m, target_speed)
        target_rpm = ss.getPropellerRPM(sail_drag + universal_drag, target_pressure, target_speed, 90) or 0
        throttle_axis = target_rpm / 256

        -- 空速闭环（PI）：模型 RPM 前馈 + 皮托管空速误差修正，把实际空速拉到目标速度
        local airspeed_err = target_speed - air_speed
        airspeed_int = math.max(-AIRSPEED_INT_MAX, math.min(AIRSPEED_INT_MAX,
            airspeed_int + AIRSPEED_KI * airspeed_err * DT))
        throttle_axis = math.max(0, math.min(1, throttle_axis + AIRSPEED_KP * airspeed_err + airspeed_int))
    else
        airspeed_int = 0   -- 非自动油门时清积分（防 windup 与重入跳变）
    end


    local is_screen_enable = module_toggle_screen.getToggleState()

    local target_roll_angle = 0
    local target_pitch_angle = 0
    local angle = ss.getAngles() or { pitch = 0, roll = 0, yaw = 0 }

    -- 姿态角速率（deg/s，Java 侧姿态角差分+滤波；俯仰/滚转阻尼共用，免疫 av 的投影/测量问题）
    local rates = ss.getAngleRates()
    local pitch_rate_dps = rates and rates.pitchRate or 0
    local roll_rate_dps = rates and rates.rollRate or 0

    -- 俯仰人工阻尼：低头转（pitchRate>0）→ 命令抬头（target 减小），符号 PITCH_DAMP_SIGN 见常量区
    local damp_pitch = 0.0
    if module_toggle_pitch_damp.getToggleState() then
        damp_pitch = PITCH_DAMP_SIGN * PITCH_DAMP_GAIN * pitch_rate_dps
    end

    -- 高度保持（仅 auto_th 开启且摇杆 Y 未用时生效）：
    -- 外环 高度差→目标垂速（限幅），内环 垂速误差→尾翼舵角；正=低头、负=抬头
    local alt_pitch = 0.0
    if is_auto_th and not module_joystick.isAxisYActive() then
        local target_vs = ALT_HOLD_KP_VS * (target_alt - alt_current)
        target_vs = math.max(-ALT_HOLD_MAX_VS, math.min(ALT_HOLD_MAX_VS, target_vs))
        alt_pitch = ALT_HOLD_KP_PITCH * (vertical_speed - target_vs)
        alt_pitch = math.max(-ALT_HOLD_MAX_PITCH, math.min(ALT_HOLD_MAX_PITCH, alt_pitch))
    end

    target_pitch_angle = module_joystick.getAxisYSigned() * JOY_MAX_DEG + damp_pitch + alt_pitch

    target_roll_angle  = module_joystick.getAxisXSigned() * -JOY_MAX_DEG

    local angle_roll = angle.roll

    -- 自动滚转（PID）：P 项门控死区，D 项（滚转阻尼）始终生效，I 项消除稳态误差
    -- 2026-09 实测：PD 下 roll 恒卡 +1.2°（wbZ=0，恒定扰动力矩靠 P 项偏置顶着，误差消不掉）→ 加 I 项
    if not module_joystick.isAxisXActive() and module_toggle_auto_roll.getToggleState() then
        local roll_hold = 0.0
        if math.abs(angle_roll) > ROLL_DEADBAND then
            roll_int = roll_int + angle_roll * DT
            roll_int = math.max(-ROLL_INT_MAX, math.min(ROLL_INT_MAX, roll_int))
            roll_hold = angle_roll * ROLL_KP + roll_int * ROLL_KI
            target_roll_angle = roll_hold + roll_rate_dps * ROLL_KD
        end
    else
        roll_int = 0   -- 摇杆接管/关闭 auto_roll 时清积分（防重入跳变）
    end

    local servo_r_angle = - target_pitch_angle - target_roll_angle
    local servo_l_angle = - target_pitch_angle + target_roll_angle


    if math.abs(speed) < 0.001 then
        speed = 0
    end
    if math.abs(angle_roll) < 0.01 then
        angle_roll = 0
    end
    if math.abs(vertical_speed) < 0.001 then
        vertical_speed = 0
    end
    if is_screen_enable == true then
        cells = 
        {
            {1,1,"Alt",align = "left"},
            {1,2,"Speed",align = "left"},
            {1,3,"Pitch",align = "left"},
            {1,4,"Roll",align = "left"},
            {1,5,"Yaw",align = "left"},

            {1, 1 ,string.format("%.1f", alt_current),align = "right"},
            {1, 2 ,string.format("%.2f", speed),align = "right"},
            {1, 3 ,string.format("%.1f", - angle.pitch),align = "right"},
            {1, 4 ,string.format("%.2f", angle_roll),align = "right"},
            {1, 5 ,string.format("%.1f", angle.yaw),align = "right"},
        }

        cells_alt_control =
        {

            {1,1,"Tgt",align = "left"},
            {1,2,"Tgt",align = "left"},
            {1,3,"VS",align = "left"},

            {1, 1 ,string.format("%.1f", target_alt),align = "right"},
            {1, 2 ,string.format("%.2f", target_speed),align = "right"},
            {1, 3 ,string.format("%.2f", vertical_speed),align = "right"},

        }

    end

    parallel.waitForAll(
        function ()
            if module_joystick.isAxisXActive() then
                module_toggle_auto_roll.setToggleState(false)
            end
        end,
        function () engine.setThrottle(throttle_axis) end,
        function () servo_r.setTargetAngle(servo_r_angle) end,
        function () servo_l.setTargetAngle(servo_l_angle) end,
        function () module_screen_1.drawCells({cells = cells})end,
        function () module_screen_2.drawCells({cells = cells_alt_control})end,
        function () ss.setAllLights(module_toggle_light.getToggleState()) end,
        function ()
            redstone.setOutput("back", module_toggle_dc.getToggleState())
        end


  )
  index = index + 1
  if index == 100 then
    print((os.clock() - clock)/100)
    index = 0
    clock = os.clock()
  end
end