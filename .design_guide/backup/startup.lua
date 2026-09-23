sleep(1)

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

local COLOR_OK = 0x54fc54
local COLOR_WARN = 0xfcfc54
local COLOR_DANGER = 0xfca800

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

-- 引擎温度控制 v2（2026-09 用户定稿；2026-09 加 I 项）：
-- 混合比固定有效值 0.9（高效窗口下沿 0.8 + 0.1 裕度），不参与温度调节（风道应能压住拉稀发热）
-- 风道 = PI+D：cool = COOL_BASE + COOL_KP×温度误差 + COOL_KI×误差积分 + COOL_KD×温度变化速率（D 项阻尼防过冲/振荡）
-- I 项消除稳态误差（参考滚转环 +1.2° 残差加 ROLL_KI 的修法）：
--   实测 PD 平衡点钉在 145°C（需求曲线 dT/dcool≈−210°C/单位，155°C 只需 ~5% 风门，COOL_BASE 0.6 严重失配）
-- 实际混合比 = 杆 × autoRichness → 固定 eff 0.9 时杆 = 0.9/autoRichness（海拔补偿）
-- 紧急兜底（用户已确认）：T≥200°C 富油到杆 1.4（heatFactor→0.7）防 220°C 硬停
local ENGINE_TEMP_TARGET = 155.0  -- °C：温度目标（高效窗口中心 145~165）
local ENGINE_TEMP_EMERGENCY = 200.0 -- °C：紧急富油兜底阈值
local MIX_EFF_FIX = 0.9           -- 固定有效混合比（杆 = MIX_EFF_FIX / autoRichness）
local MIX_EFF_MIN = 0.8           -- 高效混合比窗口下沿（参考）
local MIX_EFF_MAX = 1.1           -- 高效混合比窗口上沿（参考）
local MIX_LEVER_ABS_MIN = 0.6     -- setMixture 硬下限
local MIX_LEVER_ABS_MAX = 1.4     -- setMixture 硬上限
local ENGINE_CTRL_EVERY = 10      -- 控制周期（tick，10 = 0.5s；温度是慢过程）
local COOL_BASE = 0.3             -- 风门基准工作点（P/I 项归零时；从 0.6 降到 0.3 减轻 I 项负担）
local COOL_KP = 0.05              -- 风门/°C：温度误差比例（过热增冷却、过冷减冷却）
local COOL_KI = 0.005             -- 1/s：温度误差积分增益（消除稳态误差；符号与 Kp 相同）
local COOL_INT_MAX = 100.0        -- °C·s：积分限幅（防 windup；最大修正 = 100×0.005 = 0.5 风门）
local COOL_KD = 0.02              -- 风门/(°C/s)：温度变化速率阻尼（0.05 引起 7~13% 抖动，降到 0.02）


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
local MIN_ALT = 65
local airspeed_int = 0   -- 空速保持积分状态（退出 auto_th 时清零）
local roll_int = 0       -- 滚转积分状态（auto_roll 关闭/摇杆接管时清零）
local cool_int = 0       -- 温度控制积分状态（风门工作点自动修正；无外部接管，不清零）
local engine_ctrl_tick = 0   -- 引擎温度控制周期计数
local e_temp_prev = engine.getTemperature() or 155   -- 上一控制周期温度（dT/dt 差分用，初始=启动温度防假速率）
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

    -- ===== 引擎温度控制 v2（混合比固定 0.9 + 风道 PD）=====
    -- 温度慢过程：每 ENGINE_CTRL_EVERY tick 才动作一次，避免 20Hz 抖动
    -- ⚠ 写方法（setMixture/setCooling）是 mainThread=true：此处只算目标值，真正调用放循环末尾
    --   parallel.waitForAll 中并行发出，避免阻塞电脑线程拖慢 20Hz 循环（读方法直读缓存零成本）
    local e_set_mix = nil   -- 本轮待写混合比（nil = 不写）
    local e_set_cool = nil  -- 本轮待写风门（nil = 不写）
    local e_temp = engine.getTemperature() or 155
    local e_auto_rich = engine.getAutoRichness() or 1.0

    engine_ctrl_tick = engine_ctrl_tick + 1
    if engine_ctrl_tick >= ENGINE_CTRL_EVERY then
        engine_ctrl_tick = 0

        -- 温度变化速率（°/s，控制周期差分；正 = 升温）
        local dTdt = (e_temp - e_temp_prev) / (ENGINE_CTRL_EVERY * DT)
        e_temp_prev = e_temp

        -- 混合比：固定有效混合比 0.9（海拔补偿杆值），不参与温度调节
        local lever_fixed = math.max(MIX_LEVER_ABS_MIN,
            math.min(MIX_LEVER_ABS_MAX, MIX_EFF_FIX / e_auto_rich))
        e_set_mix = lever_fixed

        -- 风道 PI+D：冷却效率 = 基准 + 温度误差比例 + 误差积分（消稳态误差）+ 温度变化速率阻尼
        -- 过冷（T 低 / 降温中）→ cool 减小（保热）；过热（T 高 / 升温中）→ cool 增大（散热）
        -- I 项与滚转环同款：cool_int += 误差×经过时间，限幅后 ×COOL_KI 输出（符号与 Kp 相同）
        local temp_err = e_temp - ENGINE_TEMP_TARGET
        cool_int = cool_int + temp_err * (ENGINE_CTRL_EVERY * DT)
        cool_int = math.max(-COOL_INT_MAX, math.min(COOL_INT_MAX, cool_int))
        local cool_cmd = COOL_BASE + COOL_KP * temp_err + cool_int * COOL_KI + COOL_KD * dTdt
        e_set_cool = cool_cmd

        -- 紧急兜底：T≥200°C 富油到杆 1.4（heatFactor→0.7）防 220°C 硬停
        if e_temp >= ENGINE_TEMP_EMERGENCY then
            e_set_mix = MIX_LEVER_ABS_MAX
        end

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

    local fluid_fuel = engine.getFluidTanks()
    local fluid_fuel_percent = 0

    if fluid_fuel and #fluid_fuel > 0 then
        for _,v in ipairs(fluid_fuel) do
            fluid_fuel_percent = fluid_fuel_percent + (v.amount or 0) / 80
        end
    end

    local fuel_color = COLOR_OK
    if fluid_fuel_percent < 50 then
        fuel_color = COLOR_WARN
    end
    if fluid_fuel_percent <= 20 then
        fuel_color = COLOR_DANGER
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
            {1,3,"Pitch",align = "left"},
            {1,4,"Roll",align = "left"},
            {1,5,"Yaw",align = "left"},
            {1,6,"Tmp",align = "left"},
            {1,7,"Fuel",align = "left"},

            {1, 1 ,string.format("%.1f", alt_current),align = "right"},
            {1, 2 ,string.format("%.2f", speed),align = "right"},
            {1, 3 ,string.format("%.1f", - angle.pitch),align = "right"},
            {1, 4 ,string.format("%.2f", angle_roll),align = "right"},
            {1, 5 ,string.format("%.1f", angle.yaw),align = "right"},
            {1, 6 ,string.format("%.0f", e_temp),align = "right"},
            {1, 7 ,string.format("%.0f%%", fluid_fuel_percent),fuel_color,align = "right"},
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
        function () if e_set_mix then engine.setMixture(e_set_mix) end end,
        function () if e_set_cool then engine.setCooling(e_set_cool) end end,
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