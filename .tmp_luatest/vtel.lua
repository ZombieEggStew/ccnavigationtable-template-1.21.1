-- vtel.lua : vehicle telemetry / actuator wrapper (no dependency on startup.lua)
-- NOTE (CC calling convention): peripheral & module methods take NO implicit self.
--   correct : p.setTargetSpeed(128)
--   wrong   : p.setTargetSpeed(p, 128)   <-- silently fails inside pcall
local ss = require("ccpe.sensor_system")

local CH = {
    WHEEL_FL   = 0,
    WHEEL_FR   = 1,
    FAN_FL     = 2,
    FAN_FR     = 3,
    FAN_BL     = 4,
    FAN_BR     = 5,
    TRANS_BACK = 6,
    DESK       = 7,
    ENGINE     = 8,
}
local ID_KNOB_THROTTLE = 1

local vtel = {}
vtel.CH = CH
vtel.ID_KNOB_THROTTLE = ID_KNOB_THROTTLE
vtel.errorCount = 0
vtel.lastError = nil

function vtel.try(fn, ...)
    if type(fn) ~= "function" then
        vtel.lastError = "method missing"
        vtel.errorCount = vtel.errorCount + 1
        return nil
    end
    local ok, r = pcall(fn, ...)
    if ok then return r end
    vtel.lastError = tostring(r)
    vtel.errorCount = vtel.errorCount + 1
    return nil
end

function vtel.now()
    return os.epoch("utc") / 1000.0
end

function vtel.connect()
    local d = { fan = {}, wheel = {} }
    d.fan.fl = ss.getPeripheral(CH.FAN_FL)
    d.fan.fr = ss.getPeripheral(CH.FAN_FR)
    d.fan.bl = ss.getPeripheral(CH.FAN_BL)
    d.fan.br = ss.getPeripheral(CH.FAN_BR)
    d.wheel.fl = ss.getPeripheral(CH.WHEEL_FL)
    d.wheel.fr = ss.getPeripheral(CH.WHEEL_FR)
    d.trans = ss.getPeripheral(CH.TRANS_BACK)
    d.desk = ss.getPeripheral(CH.DESK)
    d.engine = ss.getPeripheral(CH.ENGINE)
    return d
end

local function clamp256(v)
    v = tonumber(v) or 0
    if v > 256 then return 256 elseif v < -256 then return -256 end
    return math.floor(v + 0.5)
end
vtel.clamp256 = clamp256

function vtel.setFan(p, rpm)
    if not p then return end
    vtel.try(p.setTargetSpeed, clamp256(rpm))
end

-- cmd = {fl, fr, bl, br} in RPM (-256..256, negative = opposite to input)
function vtel.setFans(d, cmd)
    if not cmd then return end
    vtel.setFan(d.fan.fl, cmd[1])
    vtel.setFan(d.fan.fr, cmd[2])
    vtel.setFan(d.fan.bl, cmd[3])
    vtel.setFan(d.fan.br, cmd[4])
end

function vtel.allFans(v)
    return { v, v, v, v }
end

-- power-up: transmission mode + all stopped + engine throttle (fans need upstream speed)
function vtel.setup(d)
    for _, p in pairs(d.fan) do
        if p then
            vtel.try(p.setServoMode, false)
            vtel.try(p.setTargetSpeed, 0)
        end
    end
    if d.trans then
        vtel.try(d.trans.setServoMode, false)
        vtel.try(d.trans.setTargetSpeed, 0)
    end
    if d.engine then
        vtel.try(d.engine.setThrottle, 1.0)
    end
end

-- one snapshot (all cache reads, mainThread=false, non-blocking)
function vtel.read()
    local r = { t = vtel.now() }

    local v = ss.getVelocity()
    r.vx = (v and v.x) or 0
    r.vy = (v and v.y) or 0
    r.vz = (v and v.z) or 0

    local p = ss.getBodyPosition()
    r.x = (p and p.x) or 0
    r.y = (p and p.y) or 0
    r.z = (p and p.z) or 0

    local q = ss.getOrientation()
    r.qx = (q and q.x) or 0
    r.qy = (q and q.y) or 0
    r.qz = (q and q.z) or 0
    r.qw = (q and q.w) or 1

    local w = ss.getAngularVelocity()
    r.wx = (w and w.x) or 0
    r.wy = (w and w.y) or 0
    r.wz = (w and w.z) or 0

    local a = ss.getAngles()
    r.pitch = (a and a.pitch) or 0
    r.roll  = (a and a.roll)  or 0
    r.yaw   = (a and a.yaw)   or 0

    r.mass = ss.getPhysicsMass() or 0
    return r
end

-- desk controls (pedal + throttle knob); returns nil-safe accessors
function vtel.deskControls(d)
    local monitor = d.desk and vtel.try(d.desk.getModule, "monitor") or nil
    local pedal = d.desk and vtel.try(d.desk.getModule, "pedal") or nil
    local knobThr = monitor and vtel.try(monitor.getModule, ID_KNOB_THROTTLE) or nil
    return pedal, knobThr, monitor
end

function vtel.readControls(pedal, knobThr)
    local steer = 0
    local thr = 0
    if pedal then steer = (vtel.try(pedal.getPedalDifference) or 0) / 2 end
    if knobThr then thr = vtel.try(knobThr.getAbsolutePercent) or 0 end
    return steer, thr
end

function vtel.driveManual(d, pedal, knobThr)
    local steer, thr = vtel.readControls(pedal, knobThr)
    if d.wheel.fl then vtel.try(d.wheel.fl.setSteering, steer) end
    if d.wheel.fr then vtel.try(d.wheel.fr.setSteering, steer) end
    if d.trans then vtel.try(d.trans.setTargetSpeed, thr * 256) end
    return steer, thr
end

function vtel.openLog(path)
    if not fs.exists("/log") then fs.makeDir("/log") end
    local f = fs.open(path, "w")
    f.writeLine("t,tau,phase,fl,fr,bl,br,x,y,vx,vy,vz,qx,qy,qz,qw,wx,wy,wz,pitch,roll,yaw,mass")
    f.flush()
    return f
end

function vtel.logRow(f, r, tau, phase, cmd)
    if not f then return end
    cmd = cmd or { 0, 0, 0, 0 }
    f.writeLine(string.format(
        "%.3f,%.3f,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.5f,%.5f,%.5f,%.5f,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%.2f",
        r.t, tau or 0, phase or 0,
        cmd[1], cmd[2], cmd[3], cmd[4],
        r.x, r.y, r.vx, r.vy, r.vz,
        r.qx, r.qy, r.qz, r.qw,
        r.wx, r.wy, r.wz,
        r.pitch, r.roll, r.yaw, r.mass))
end

return vtel
