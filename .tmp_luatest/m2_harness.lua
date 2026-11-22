--===========================================================================
-- m2_harness.lua -- dry-run m2probe.lua outside Minecraft (Lua 5.4 stub world)
-- usage:  lua54 m2_harness.lua <prog.lua> [prog args...]
-- e.g.    lua54 m2_harness.lua "run/saves/New World/computercraft/computer/1/m2probe.lua" all 0 1
--
-- Stubs: ccpe.sensor_system, two advanced nozzles (ch0 upright, ch1 inverted),
-- an engine, fs (/log -> .tmp_luatest/m2out/log), virtual clock, 20 Hz physics.
-- Physics: thrust = scale*K*p per nozzle along the tilted body axis, torque from
-- r x F with r = (0,-h,+b) / (0,-h,-b), G=11, drag 0.09, ground at y0.
-- Purpose: catch nil / syntax / CSV / gate bugs BEFORE the in-game run.
--===========================================================================
local PROG = arg[1]
assert(PROG and PROG ~= "", "usage: lua54 m2_harness.lua <prog.lua> [args...]")
local PARGS = {}
for i = 2, #arg do PARGS[#PARGS + 1] = arg[i] end

local OUTDIR = ".tmp_luatest/m2out"
os.execute('if not exist "' .. OUTDIR:gsub("/", "\\") .. '" mkdir "' .. OUTDIR:gsub("/", "\\") .. '" >NUL 2>&1')
os.execute('if not exist "' .. OUTDIR:gsub("/", "\\") .. '\\log" mkdir "' .. OUTDIR:gsub("/", "\\") .. '\\log" >NUL 2>&1')

local VT = 1794910000.0            -- virtual epoch (s)
local DT = 0.05                    -- 20 Hz
local G = 11.0
local K = 256 * 2.05               -- pN per unit scale per nozzle at p=1
local MASS = 11.00
local IXX, IYY, IZZ = 63.0, 30.0, 15.0   -- in-game estimate: pitch bias 0.098 rad/s2 at 6.2 N*m => I_pitch~63
local BF = 2.05                    -- front nozzle 2.05 blocks AHEAD of the CG (-Z, nose side)
local BR = 1.95                    -- rear nozzle 1.95 blocks BEHIND the CG (+Z)
local HH = 1.32                    -- both nozzles below the CG (tandem, x=0)
local DRAG = 0.09
local GROUND = 64.0
local YMAX = 320.0

------------------------------------------------------------------ quaternion
local function qmul(a, b)
  return {
    x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
  }
end
local function qrot(q, v)
  local p = qmul(qmul(q, { x = v[1], y = v[2], z = v[3], w = 0 }),
                 { x = -q.x, y = -q.y, z = -q.z, w = q.w })
  return { p.x, p.y, p.z }
end
local function qnorm(q)
  local n = math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
  return { x = q.x / n, y = q.y / n, z = q.z / n, w = q.w / n }
end
local function cross(a, b)
  return { a[2] * b[3] - a[3] * b[2], a[3] * b[1] - a[1] * b[3], a[1] * b[2] - a[2] * b[1] }
end

------------------------------------------------------------------ world state
local W = {
  y = GROUND, vx = 0, vy = 0, vz = 0,
  om = { x = 0, y = 0, z = 0 },
  q = { x = 0, y = 0, z = 0, w = 1 },
  x = 0, z = 0,
}
local NZ = {
  [0] = { inv = false, axis =  1 },   -- mounted FACING up   -> thrust +Y
  [1] = { inv = true,  axis = -1 },   -- mounted FACING down -> inverted makes it +Y
}
for ch, n in pairs(NZ) do
  n.scale, n.tscale = 0, 0
  n.dx, n.dy, n.tdx, n.tdy = 0, 0, 0, 0
end

local function pressureAt(y)
  local p = math.exp(-0.004 * (y - 63.0))
  if p > 1.5 then p = 1.5 end
  if p < 0.0 then p = 0.0 end
  return p
end
local function pNow() return pressureAt(W.y) end

------------------------------------------------------------------ physics
local function step(dt)
  -- actuator: thrust scale ramps 0.25/tick = 5.0/s (memo propulsion 6.5)
  for _, n in pairs(NZ) do
    local d = n.tscale - n.scale
    local lim = 5.0 * dt
    if d > lim then d = lim elseif d < -lim then d = -lim end
    n.scale = n.scale + d
    -- deflection point walks 160 deg/s = 10.667 normalized units per second
    local tdx, tdy = n.tdx, n.tdy
    local ex, ey = tdx - n.dx, tdy - n.dy
    local e = math.sqrt(ex * ex + ey * ey)
    local lim2 = (160.0 / 15.0) * dt
    if e > lim2 then
      n.dx, n.dy = n.dx + ex / e * lim2, n.dy + ey / e * lim2
    else
      n.dx, n.dy = tdx, tdy
    end
  end

  local p = pNow()
  local Fb = { 0, 0, 0 }
  local Tb = { 0, 0, 0 }
  -- tandem layout measured 2026-11-21: ch0 = FRONT (-Z), ch1 = REAR (+Z), both x=0
  local r = { [0] = { 0, -HH, -BF }, [1] = { 0, -HH, BR } }   -- body frame
  for ch, n in pairs(NZ) do
    local len = math.sqrt(n.dx * n.dx + n.dy * n.dy)
    if len > 1.0 then n.dx, n.dy, len = n.dx / len, n.dy / len, 1.0 end
    local ux, uy = 0, 0
    if len > 1e-9 then ux, uy = n.dx / len, n.dy / len end
    local th = math.rad(15.0) * len
    local sign = (n.inv and -1.0 or 1.0) * (n.axis or 1.0)
    local mag = n.scale * K * p * sign
    local f = { math.sin(th) * ux * mag, math.cos(th) * mag, math.sin(th) * uy * mag }
    Fb[1], Fb[2], Fb[3] = Fb[1] + f[1], Fb[2] + f[2], Fb[3] + f[3]
    local t = cross(r[ch], f)
    Tb[1], Tb[2], Tb[3] = Tb[1] + t[1], Tb[2] + t[2], Tb[3] + t[3]
  end
  local Fw = qrot(W.q, Fb)
  local ax = Fw[1] / MASS - DRAG * W.vx
  local ay = Fw[2] / MASS - G - DRAG * W.vy
  local az = Fw[3] / MASS - DRAG * W.vz
  W.vx, W.vy, W.vz = W.vx + ax * dt, W.vy + ay * dt, W.vz + az * dt
  W.x, W.y, W.z = W.x + W.vx * dt, W.y + W.vy * dt, W.z + W.vz * dt
  if W.y < GROUND then
    W.y = GROUND
    if W.vy < 0 then W.vy = 0 end
  end
  if W.y > YMAX then
    W.y = YMAX
    if W.vy > 0 then W.vy = 0 end
  end
  W.om.x = W.om.x + Tb[1] / IXX * dt
  W.om.y = W.om.y + Tb[2] / IYY * dt
  W.om.z = W.om.z + Tb[3] / IZZ * dt
  local dq = qmul({ x = W.om.x, y = W.om.y, z = W.om.z, w = 0 }, W.q)
  W.q = qnorm({ x = W.q.x + 0.5 * dt * dq.x, y = W.q.y + 0.5 * dt * dq.y,
                z = W.q.z + 0.5 * dt * dq.z, w = W.q.w + 0.5 * dt * dq.w })
  VT = VT + dt
end

local function sleep(t)
  local acc = 0
  while acc < t - 1e-9 do
    local h = math.min(DT, t - acc)
    step(h)
    acc = acc + h
  end
end

------------------------------------------------------------------ fs stub
local function hostPath(p)
  return OUTDIR .. "/" .. p:gsub("^/", ""):gsub("\\", "/")
end
local fs = {
  exists = function(p) local f = io.open(hostPath(p), "r"); if f then f:close(); return true end return false end,
  isDir = function(p)
    local f = io.open(hostPath(p) .. "/.probe", "r"); if f then f:close(); return true end
    -- directory existence probe via opening a known child is not available; treat /log as a dir
    return p == "/" or p == "/log"
  end,
  makeDir = function(p)
    os.execute('if not exist "' .. hostPath(p):gsub("/", "\\") .. '" mkdir "' .. hostPath(p):gsub("/", "\\") .. '" >NUL 2>&1')
  end,
  getFreeSpace = function() return 900 * 1024 end,
  open = function(p, mode)
    local f = io.open(hostPath(p), mode == "w" and "w" or "a")
    if not f then return nil end
    local h = {}
    function h.writeLine(s) f:write(tostring(s), "\n") return h end
    function h.write(s) f:write(tostring(s)) return h end
    function h.flush() f:flush() return h end
    function h.close() f:close() return true end
    return h
  end,
}

------------------------------------------------------------------ sensor api
-- Mod convention, measured in game on 2026-11-21 (m2cone v10 CSV):
--   d(pitch)/dt = -wx  and  d(roll)/dt = +wz
-- so pitch is minus the body-X rotation angle and roll is the body-Z one.
-- (The old standard-decomposition stub disagreed with the mod and made every
--  attitude loop look divergent.)
local function eulerDeg(q)
  local pitch = math.deg(-2 * math.asin(math.max(-1, math.min(1, q.x))))
  local roll  = math.deg( 2 * math.asin(math.max(-1, math.min(1, q.z))))
  local yaw   = math.deg( 2 * math.asin(math.max(-1, math.min(1, q.y))))
  return pitch, roll, yaw
end

local noise = function(k) return 0.0015 * math.sin(VT * 37.0 + k) end

local ENGINE = { throttle = 1.0, load = 0.0, temp = 85.0 }
ENGINE.getThrottle = function() return ENGINE.throttle end
ENGINE.setThrottle = function(v) ENGINE.throttle = tonumber(v) or 0; return true end
ENGINE.isReversed = function() return false end
ENGINE.getTemperature = function() return ENGINE.temp end
ENGINE.isOverheated = function() return false end
ENGINE.isWarmingUp = function() return false end
ENGINE.getLoad = function() return ENGINE.load end
ENGINE.getStressUsed = function() return ENGINE.load * 8192 end
ENGINE.getStressCapacity = function() return 8192 end
ENGINE.getActiveFuel = function() return { type = "fluid", fluid = "minecraft:lava", optimalTemp = 155 } end
ENGINE.getMixture = function() return 1.0 end
ENGINE.getEffectiveMixture = function() return 1.02 end

local SS = {}
function SS.isOnBody() return true end
function SS.getBodyId() return "stubb0000-0000-0000-0000-000000000001" end
function SS.getPosition() return { x = W.x, y = W.y, z = W.z } end
function SS.getVelocity() return { x = W.vx, y = W.vy, z = W.vz } end
function SS.getAngularVelocity()
  return { x = W.om.x + noise(1), y = W.om.y + noise(2), z = W.om.z + noise(3) }
end
function SS.getOrientation()
  return { x = W.q.x, y = W.q.y, z = W.q.z, w = W.q.w }
end
function SS.getAngles()
  local pit, rol, yaw = eulerDeg(W.q)
  return { pitch = pit, roll = rol, yaw = yaw }
end
function SS.getAltitude() return W.y end
function SS.getPressure() return pNow() end
function SS.getPressureFromAltitude(y) return pressureAt(y) end
function SS.getPhysicsMass() return MASS end
function SS.getPhysicsCenterOfMassRel() return { x = 0, y = -1.68, z = -0.95 } end
function SS.getBodyPosition() return { x = W.x, y = W.y, z = W.z } end
function SS.hasSpeedSensor() return true end
function SS.hasPressureSensor() return true end
-- mirrors SensorSystemAPI.getSensors: the AIC registers TWICE (ins + fmc) at the
-- same block. pos is the block CORNER rel body origin, pos_rel is the corner diff
-- to this computer (so pos_rel is already a centre-to-centre offset). Values are
-- self-consistent with CGrel=(0,-1.68,-0.95): centre(AIC) = pos+0.5 = (0,1.68,0.95) = -CGrel.
function SS.getSensors()
  local pos = { x = -0.50, y = 1.18, z = 0.45 }
  local rel = { x = 0, y = 2, z = 0 }
  return { { type = 'ins', pos = pos, pos_rel = rel },
           { type = 'fmc', pos = pos, pos_rel = rel } }
end
function SS.getAngleRates()
  return { pitchRate = math.deg(W.om.x), rollRate = math.deg(W.om.z), yawRate = math.deg(W.om.y) }
end
function SS.getEngine() return ENGINE end
function SS.getStressRemaining() return 8192 - ENGINE.load * 8192 end
function SS.getStressCapacity() return 8192 end
function SS.getPeripheral(ch)
  local n = NZ[ch]
  if not n then return nil end
  if not n.dev then
    n.dev = {
      setThrustScale = function(v)
        v = tonumber(v) or 0
        if v < 0 then v = 0 elseif v > 1 then v = 1 end
        n.tscale = v
        return true
      end,
      getThrustScale = function() return n.scale end,
      setDirection = function(x, y)
        n.tdx, n.tdy = tonumber(x) or 0, tonumber(y) or 0
        return true
      end,
      getDeflection = function()
        return { x = n.dx * 15, y = n.dy * 15, tilt = math.sqrt(n.dx ^ 2 + n.dy ^ 2) * 15 }
      end,
      setInverted = function(v) n.inv = v and true or false; return true end,
      isInverted = function() return n.inv end,
      getChannel = function() return ch end,
      getMaxTilt = function() return 15 end,
      isMoving = function() return false end,
    }
  end
  return n.dev
end

_G.fs = fs
_G.parallel = {
  waitForAll = function(...)
    local fns = { ... }
    for i = 1, #fns do fns[i]() end
  end,
}
_G.sleep = sleep
_G.os.epoch = function() return math.floor(VT * 1000) end
_G.os.clock = function() return VT end

package.preload["ccpe.sensor_system"] = function() return SS end

------------------------------------------------------------------ run
local chunk, lerr = loadfile(PROG)
if not chunk then
  io.write("HARNESS load error: " .. tostring(lerr) .. "\n")
  os.exit(2)
end
local ok, err = pcall(chunk, table.unpack(PARGS))
if not ok then
  io.write("HARNESS runtime error: " .. tostring(err) .. "\n")
end
io.write(string.format("\nHARNESS state: y=%.2f vy=%.2f vx=%.2f vz=%.2f om=(%.3f,%.3f,%.3f)\n",
  W.y, W.vy, W.vx, W.vz, W.om.x, W.om.y, W.om.z))
local pit, rol, yaw = eulerDeg(W.q)
io.write(string.format("HARNESS att:   pit=%.1f rol=%.1f yaw=%.1f  scale0=%.3f scale1=%.3f\n",
  pit, rol, yaw, NZ[0].scale, NZ[1].scale))
if not ok then os.exit(3) end
os.exit(0)
