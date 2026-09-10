local ss = require("ccpe.sensor_system")
print()
local function reset_bearing(bearing)
    bearing.setControlMode(true)
    bearing.assemble()
    bearing.setTargetAngle(0)
end

local function reset_transmission(transmission)
    transmission.setServoMode(false)
    transmission.setTargetSpeed(0)
end

local function reset_screen(screen)
    screen.clear()
    screen.setTextScale(0.4, 1.0)
    screen.setTextColour(0x00FF00)
end

local N = 1
local S = 30
local ok = ss.initPropeller(N, S)
print("init propeller", ok)
local N_sail = 36
local N_symmetric_sail = 20

local CHANNEL_TM_PROPELLER = 0
local CHANNEL_ROLL_R = 1
local CHANNEL_ROLL_L = 2
local CHANNEL_PITCH_R = 3
local CHANNEL_PITCH_L = 4
local CHANNEL_YAW = 5


local CHANNEL_CONTROL_1 = 6
local CHANNEL_CONTROL_JOYSTICK = 7
local CHANNEL_CONTROL_THROTTLE = 8
local CHANNEL_FULE_TANK = 9
local FUEL_TANK = "back"
local ENGINE = "top"


local ID_SCREEN = 0
local ID_SCREEN_2 = 1
local ID_TOGGLE_ENGINE = 2
local ID_TOGGLE_SCREEN = 3
local ID_TOGGLE_LIGHT = 4
local ID_TOGGLE_AUTO_ROLL = 5
local ID_KNOB_ALT = 6
local ID_TOGGLE_AUTO_TH = 7
local ID_TOGGLE_PITCH_DAMP = 8

-- 控制/显示常量
local JOY_MAX_DEG = 30.0        -- 摇杆满偏舵角（°）
local PEDAL_MAX_DEG = 15.0      -- 脚踏满偏舵角（°）
local FUEL_OFFSET = 3000        -- 油箱读数为 0 时仍存的燃油（mB）
local FUEL_CAPACITY = 11000     -- 满油总量（mB）
local FUEL_WARN_PCT = 50        -- 油量警告阈值（%）
local FUEL_LOW_PCT = 20         -- 油量低阈值（%）
local STRESS_WARN_FRAC = 0.5    -- 应力警告阈值
local STRESS_DANGER_FRAC = 0.75 -- 应力危险阈值
local COLOR_OK = 0x54fc54
local COLOR_WARN = 0xfcfc54
local COLOR_DANGER = 0xfca800

local tm_propeller = ss.getPeripheral(CHANNEL_TM_PROPELLER)
local bearing_pitch_r = ss.getPeripheral(CHANNEL_PITCH_R)
local bearing_pitch_l = ss.getPeripheral(CHANNEL_PITCH_L)
local bearing_roll_r = ss.getPeripheral(CHANNEL_ROLL_R)
local bearing_roll_l = ss.getPeripheral(CHANNEL_ROLL_L)
local bearing_yaw = ss.getPeripheral(CHANNEL_YAW)

local control_1 = ss.getPeripheral(CHANNEL_CONTROL_1)
local control_1_joystick = ss.getPeripheral(CHANNEL_CONTROL_JOYSTICK)
local control_1_throttle = ss.getPeripheral(CHANNEL_CONTROL_THROTTLE)

local fuel_tank = peripheral.wrap(FUEL_TANK)

-- control desk
local module_throttle = control_1_throttle.getModule("throttle")
module_throttle.setFreeMode(false)
local module_monitor = control_1.getModule("monitor")
local module_joystick = control_1_joystick.getModule("joystick_2")
local module_pedal = control_1.getModule("pedal")
-- monitor
local module_toggle_engine = module_monitor.getModule(ID_TOGGLE_ENGINE)
local module_toggle_screen = module_monitor.getModule(ID_TOGGLE_SCREEN)
local module_screen = module_monitor.getModule(ID_SCREEN)
local module_toggle_light = module_monitor.getModule(ID_TOGGLE_LIGHT)
local module_toggle_auto_roll = module_monitor.getModule(ID_TOGGLE_AUTO_ROLL)
local module_screen_2 = module_monitor.getModule(ID_SCREEN_2)
local module_knob_alt = module_monitor.getModule(ID_KNOB_ALT)
local module_toggle_auto_th = module_monitor.getModule(ID_TOGGLE_AUTO_TH)
local module_toggle_pitch_damp = module_monitor.getModule(ID_TOGGLE_PITCH_DAMP)

-- init
redstone.setAnalogueOutput(ENGINE, 15)
reset_transmission(tm_propeller)
reset_bearing(bearing_pitch_r)
reset_bearing(bearing_pitch_l)
reset_bearing(bearing_roll_r)
reset_bearing(bearing_roll_l)
reset_bearing(bearing_yaw)

reset_screen(module_screen)
reset_screen(module_screen_2)

ss.enableNbtCache(CHANNEL_FULE_TANK)

local engine_gear = 0



-- 俯仰人工阻尼（phugoid 阻尼；memo .current_mission.md §9.2：ζ≈-0.02 发散，无自然阻尼）
-- 输入 = ss.getAngleRates().pitchRate（deg/s，姿态角差分+滤波；低头转>0，与 pitchDeg 同约定）
-- 理论推导：低头转（pitchRate>0）→ 应命令抬头 → target_pitch_angle 减小 → 阻尼项取 -pitchRate（SIGN=-1）
-- ⚠ 但用户实测 SIGN=+1 飞行表现更好（有意保留，勿改回 -1）。
-- 俯仰人工阻尼 GAIN 试飞记录：0.5（memo §10 验证 ζ +0.033）→ 1.0（τ=2 tick 实测后试，§6 待办 2）。
-- 若出现舵面高频抖动或新振荡模式，退回 0.75 / 0.5。
local PITCH_DAMP_GAIN = 0.5    -- 输出舵角(°) / 俯仰角速率(°/s)：先试 0.5，阻尼不足再升到 1~2
local PITCH_DAMP_SIGN = 1      -- 用户有意 =+1（实测更好）；理论符号应为 -1，勿自行改回

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
local ROLL_DEADBAND = 0.0     -- °：死区（只门控 P 项）

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

while true do
    local cells = {}
    local cells_alt_control = {}
    -- 传感器读数（nil 防护：缺传感器时用安全默认值，控制输出保持中立，不崩溃）
    local alt_current = ss.getAverageAltitude() or 0
    local pressure = ss.getAveragePressure() or 0
    local speed = ss.getSpeed() or 0

    local vel = ss.getVelocity() or { x = 0, y = 0, z = 0 }
    local vertical_speed = vel.y

    local target_alt = module_knob_alt.getAbsolutePercent() * cruise.altitude
    local is_auto_th = module_toggle_auto_th.getToggleState()
    local throttle_axis = module_throttle.getAxis()
    local target_rpm = throttle_axis * 256 or 0

    local target_pressure = ss.getPressureFromAltitude(target_alt)
    local target_speed = ss.solveSailLift(target_pressure, nil, G/N_sail)
    if not module_throttle.isActive() and is_auto_th then
        local sail_drag = (N_sail + N_symmetric_sail + S) * ss.solveSailDirectionlessDrag(target_pressure, target_speed, nil)
        local universal_drag = ss.getUniversalDragForce(m, target_speed)
        target_rpm = ss.getPropellerRPM(sail_drag + universal_drag, target_pressure, target_speed, 90)
        throttle_axis = target_rpm / 256
    end

    local fuel = ss.getNbt(CHANNEL_FULE_TANK,"TankContent.Fluid.amount") or 0
    local fuel_percent = (fuel + FUEL_OFFSET) * 100 / FUEL_CAPACITY
    local fuel_string = "nan%"
    local is_screen_enable = module_toggle_screen.getToggleState()
    local stress = 1 - (ss.getStressRemaining() or 0) / (ss.getStressCapacity() or 1)

    if fuel == 0 then
        fuel_string = "<16%"
    else
        fuel_string = string.format("%.0f%%", fuel_percent)
    end

    local stress_color = COLOR_OK
    if stress > STRESS_WARN_FRAC then
        stress_color = COLOR_WARN
    end
    if stress > STRESS_DANGER_FRAC then
        stress_color = COLOR_DANGER
    end

    local fuel_color = COLOR_OK
    if fuel_percent < FUEL_WARN_PCT then
        fuel_color = COLOR_WARN
    end
    if fuel_percent <= FUEL_LOW_PCT then
        fuel_color = COLOR_DANGER
    end

    local target_yaw_angle = 0
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

    target_yaw_angle = module_pedal.getPedalDifference()/2 * PEDAL_MAX_DEG

    local angle_roll = angle.roll

    -- 自动滚转（PD）：P 项门控死区，D 项（滚转阻尼）始终生效
    if not module_joystick.isAxisXActive() and module_toggle_auto_roll.getToggleState() then
        local roll_hold = 0.0
        if math.abs(angle_roll) > ROLL_DEADBAND then
            roll_hold = angle_roll * ROLL_KP
        end
        target_roll_angle = roll_hold + roll_rate_dps * ROLL_KD
    end

    if module_toggle_engine.getToggleState() then
        engine_gear = 0
    else
        engine_gear = 15
    end


    
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
            {1,3,"Press",align = "left"},
            {1,4,"Pitch",align = "left"},
            {1,5,"Roll",align = "left"},
            {1,6,"Yaw",align = "left"},
            {1,7,"Stress",align = "left"},
            {1,8,"Fuel",align = "left"},

            {1, 1 ,string.format("%.1f", alt_current),align = "right"},
            {1, 2 ,string.format("%.2f", speed),align = "right"},
            {1, 3 ,string.format("%.2f", pressure),align = "right"},
            {1, 4 ,string.format("%.1f", - angle.pitch),align = "right"},
            {1, 5 ,string.format("%.1f", angle_roll),align = "right"},
            {1, 6 ,string.format("%.1f", angle.yaw),align = "right"},
            {1, 7 ,string.format("%.0f%%", stress * 100),stress_color,align = "right", },
            {1, 8 ,fuel_string,fuel_color,align = "right"}
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
        function ()
            if module_throttle.isActive() then
                module_toggle_auto_th.setToggleState(false)
            end
        end,
        function () fuel_tank.pushFluid(ENGINE,1) end,
        function () redstone.setAnalogueOutput(ENGINE, engine_gear) end,
        function () tm_propeller.setTargetSpeed(target_rpm) end,
        function ()
            if module_throttle.isFreeMode() ~= is_auto_th then
                module_throttle.setFreeMode(is_auto_th)
            end
        end,
        function ()
            if is_auto_th then
                module_throttle.setAxis(throttle_axis)
            end
        end,
        function () bearing_pitch_r.setTargetAngle(target_pitch_angle) end,
        function () bearing_pitch_l.setTargetAngle(-target_pitch_angle) end,
        function () bearing_roll_l.setTargetAngle(target_roll_angle) end,
        function () bearing_roll_r.setTargetAngle(target_roll_angle) end,
        function () bearing_yaw.setTargetAngle(target_yaw_angle) end,
        function () module_screen.drawCells({cells = cells})end,
        function () module_screen_2.drawCells({cells = cells_alt_control})end,
        function () ss.enableNbtCache(CHANNEL_FULE_TANK,is_screen_enable and 20 or 0 )end,
        function () ss.setAllLights(module_toggle_light.getToggleState()) end


  )
  index = index + 1
  if index == 100 then
    print((os.clock() - clock)/100)
    index = 0
    clock = os.clock()
  end
end