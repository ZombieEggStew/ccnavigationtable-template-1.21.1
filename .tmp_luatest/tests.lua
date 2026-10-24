-- tests.lua : parameter identification bench (startup.lua untouched)
-- usage (in the computer terminal, press Ctrl+T first to stop startup):
--   tests           -> help
--   tests debug     -> live knob/pedal readout (SAFE, verifies actuators)
--   tests probe     -> static ground probe (SAFE, read only)
--   tests drop 0    -> freefall baseline
--   tests drop 1    -> all fans -100%
--   tests drop 2    -> all fans -50%
--   tests drop 3    -> left -100 / right +100 (roll torque A)
--   tests drop 4    -> left +100 / right -100 (roll torque B)
--   tests drop 5    -> front -100 / back +100 (pitch torque)
-- data goes to /log/*.csv

local okReq, vtel = pcall(require, "vtel")
if not okReq then vtel = dofile("/vtel.lua") end
local ss = require("ccpe.sensor_system")

local REST_Y = 65.79      -- ss.getBodyPosition().y when parked on flat ground y=64
local AIR_VY = -1.0       -- airborne when vy below this
local ALT_ABORT = 1.0     -- abort test when this close to rest height
local WAIT_TIMEOUT = 120  -- seconds to wait for takeoff

local args = { ... }
local mode = string.lower(tostring(args[1] or "help"))

local function fnum(v, n)
    if type(v) ~= "number" then return "nil" end
    return string.format("%." .. (n or 2) .. "f", v)
end

-- ---------------------------------------------------------------- debug
local function cmdDebug()
    local d = vtel.connect()
    vtel.setup(d)
    local pedal, knobThr, monitor = vtel.deskControls(d)

    print("=== debug (safe) ===")
    print("desk=" .. tostring(d.desk ~= nil) ..
          " monitor=" .. tostring(monitor ~= nil) ..
          " pedal=" .. tostring(pedal ~= nil) ..
          " knob=" .. tostring(knobThr ~= nil))
    print("trans=" .. tostring(d.trans ~= nil) ..
          " engine=" .. tostring(d.engine ~= nil) ..
          " fanfl=" .. tostring(d.fan.fl ~= nil))
    print("turn the throttle knob now; watch thr/cmd/flT change")
    print("")

    for i = 1, 300 do
        local steer, thr = vtel.driveManual(d, pedal, knobThr)
        local flT = vtel.try(d.fan.fl and d.fan.fl.getTargetSpeed)
        local r = vtel.read()
        if i % 10 == 0 then
            print(string.format("t=%4d thr=%+.2f steer=%+.2f cmd=%+4d flT=%s y=%.2f vy=%+.2f roll=%+.1f errs=%d %s",
                i, thr, steer, vtel.clamp256(thr * 256),
                flT and string.format("%+.0f", flT) or "nil",
                r.y, r.vy, r.roll, vtel.errorCount, tostring(vtel.lastError or "")))
        end
        sleep(0)
    end
    print("debug done")
end

-- ---------------------------------------------------------------- probe
local function cmdProbe()
    local d = vtel.connect()
    vtel.setup(d)
    print("=== probe (safe) ===")

    local r = vtel.read()
    print("mass = " .. fnum(r.mass, 1) .. " kg")
    print("body y = " .. fnum(r.y, 3) .. "   (rest reference " .. REST_Y .. ")")

    local com = ss.getPhysicsCenterOfMassRel()
    if com then
        print("com_rel = " .. fnum(com.x) .. ", " .. fnum(com.y) .. ", " .. fnum(com.z))
    else
        print("com_rel = nil (no FMC?)")
    end

    local sensors = ss.getSensors()
    if sensors then
        for _, s in ipairs(sensors) do
            local p = s.pos or {}
            local pr = s.pos_rel or {}
            print(string.format("sensor %-12s pos=(%s,%s,%s) rel=(%s,%s,%s)",
                tostring(s.type), fnum(p.x, 1), fnum(p.y, 1), fnum(p.z, 1),
                fnum(pr.x, 1), fnum(pr.y, 1), fnum(pr.z, 1)))
        end
    else
        print("sensors = nil")
    end

    local f = vtel.openLog("/log/probe.csv")
    local t0 = vtel.now()
    local n = 0
    local wmax = 0
    local ymin, ymax = 1e9, -1e9
    while n < 100 do
        local s = vtel.read()
        vtel.logRow(f, s, 0, 0, { 0, 0, 0, 0 })
        local w = math.sqrt(s.wx * s.wx + s.wy * s.wy + s.wz * s.wz)
        if w > wmax then wmax = w end
        if s.y < ymin then ymin = s.y end
        if s.y > ymax then ymax = s.y end
        n = n + 1
        sleep(0)
    end
    f.flush(); f.close()
    local dtm = (vtel.now() - t0) / n
    print("loop dt = " .. fnum(dtm, 4) .. " s  (" .. fnum(1 / math.max(dtm, 0.001), 1) .. " Hz)")
    print("|w| max = " .. fnum(wmax, 4) .. " rad/s (static noise floor)")
    print("y range = " .. fnum(ymin, 3) .. " .. " .. fnum(ymax, 3))
    print("wrote /log/probe.csv")
end

-- ---------------------------------------------------------------- drop
-- fn(tau) -> {fl, fr, bl, br} in RPM, tau = seconds since airborne detected
local PLANS = {
    [0] = {
        name = "freefall", desc = "all fans off, pure freefall",
        fn = function(tau) return { 0, 0, 0, 0 } end,
    },
    [1] = {
        name = "collective100", desc = "tau>0.3s: all fans -256",
        fn = function(tau)
            if tau < 0.3 then return { 0, 0, 0, 0 } end
            return { -256, -256, -256, -256 }
        end,
    },
    [2] = {
        name = "collective50", desc = "tau>0.3s: all fans -128",
        fn = function(tau)
            if tau < 0.3 then return { 0, 0, 0, 0 } end
            return { -128, -128, -128, -128 }
        end,
    },
    [3] = {
        name = "rollA", desc = "tau>0.3s: left -256 / right +256",
        fn = function(tau)
            if tau < 0.3 then return { 0, 0, 0, 0 } end
            return { -256, 256, -256, 256 }
        end,
    },
    [4] = {
        name = "rollB", desc = "tau>0.3s: left +256 / right -256",
        fn = function(tau)
            if tau < 0.3 then return { 0, 0, 0, 0 } end
            return { 256, -256, 256, -256 }
        end,
    },
    [5] = {
        name = "pitchA", desc = "tau>0.3s: front -256 / back +256",
        fn = function(tau)
            if tau < 0.3 then return { 0, 0, 0, 0 } end
            return { -256, -256, 256, 256 }
        end,
    },
}

local function cmdDrop(id)
    local plan = PLANS[id]
    if not plan then
        print("unknown test id: " .. tostring(id))
        return
    end

    local d = vtel.connect()
    vtel.setup(d)
    local pedal, knobThr = vtel.deskControls(d)

    print("pedal=" .. tostring(pedal ~= nil) .. " knob=" .. tostring(knobThr ~= nil) ..
          " trans=" .. tostring(d.trans ~= nil))

    local path = "/log/drop" .. id .. "_" .. plan.name .. ".csv"
    local f = vtel.openLog(path)

    local phase = 0
    local t0 = nil
    local tau = 0
    local cmd = { 0, 0, 0, 0 }
    local streak = 0
    local startT = vtel.now()
    local maxw = 0

    print("=== test " .. id .. " : " .. plan.name .. " ===")
    print(plan.desc)
    print("drive off the platform now; fans are taken over at airborne")

    while true do
        local r = vtel.read()

        if phase == 0 then
            vtel.driveManual(d, pedal, knobThr)

            if r.vy < AIR_VY and (r.y - REST_Y) > 1.5 then
                streak = streak + 1
            else
                streak = 0
            end
            if streak >= 2 then
                phase = 1
                t0 = r.t
                if d.trans then vtel.try(d.trans.setTargetSpeed, 0) end
                if d.wheel.fl then vtel.try(d.wheel.fl.setSteering, 0) end
                if d.wheel.fr then vtel.try(d.wheel.fr.setSteering, 0) end
                print(">>> AIRBORNE, taking over")
            end
            if r.t - startT > WAIT_TIMEOUT then
                print("timeout: no takeoff detected, exit")
                break
            end
        elseif phase == 1 then
            tau = r.t - t0
            cmd = plan.fn(tau) or { 0, 0, 0, 0 }
            vtel.setFans(d, cmd)
            if (r.y - REST_Y) < ALT_ABORT or tau > 2.5 then
                phase = 2
            end
        end

        vtel.logRow(f, r, tau, phase, cmd)

        local w = math.sqrt(r.wx * r.wx + r.wy * r.wy + r.wz * r.wz)
        if w > maxw then maxw = w end

        if phase == 2 then
            vtel.setFans(d, { 0, 0, 0, 0 })
            break
        end
        sleep(0)
    end

    vtel.setFans(d, { 0, 0, 0, 0 })
    f.flush(); f.close()
    print("|w| max = " .. fnum(maxw, 3) .. " rad/s")
    print("actuator errors = " .. vtel.errorCount .. " " .. tostring(vtel.lastError or ""))
    print("wrote " .. path)
end

-- ---------------------------------------------------------------- main
local function help()
    print("tests debug     -- live readout, verify knob/pedal (safe)")
    print("tests probe     -- static ground probe (safe)")
    print("tests drop 0    -- freefall baseline")
    print("tests drop 1    -- all fans -100%")
    print("tests drop 2    -- all fans -50%")
    print("tests drop 3    -- left-100/right+100 (roll A)")
    print("tests drop 4    -- left+100/right-100 (roll B)")
    print("tests drop 5    -- front-100/back+100 (pitch)")
    print("data in /log/")
end

if mode == "debug" then
    cmdDebug()
elseif mode == "probe" then
    cmdProbe()
elseif mode == "drop" then
    cmdDrop(tonumber(args[2]) or -1)
else
    help()
end
