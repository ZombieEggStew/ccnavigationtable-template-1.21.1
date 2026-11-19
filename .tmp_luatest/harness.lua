--===========================================================================
-- att_harness.lua -- dry-run att.lua outside Minecraft (Lua 5.4 stub world)
-- Stubs: sensor system, peripherals, rednet fixture, fs, parallel, time.
-- Physics: crude but representative -- thrust sum on the FIRING nozzles, G=11,
-- body rate driven by differentials with the alpha measured in v1
-- (A: ch1/ch3 -> body X, sign - 1.25 rad/s^2 per unit D; B: ch2/ch4 -> body Z, sign +).
-- Purpose: catch nil / logic / reporting bugs before the in-game run.
--===========================================================================
local SRC = arg[1] or [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\saves\New World\computercraft\computer\0\att.lua]]
-- Optional scenario overrides (all backward compatible):
--   lua54 harness.lua <prog> <tau> <phase> <stride> <progArg> <y0> <att>
--   <progArg> is passed to the program as its first vararg (e.g. "dock")
--   <y0>      start world Y (default: the dock Y)
--   <att>     "belly" starts the body belly-flopped (cosVert 0)

local VT = 1794911300.000            -- virtual epoch seconds
local G, MASS, PN = 11.0, 61.0, 2.05
local DOCKY = 93.047
-- body state
local S = {
  y = DOCKY, vy = 0.0,
  q = { 0, 0, 0, 1 },                -- identity: upright
  wx = 0, wz = 0,
  lx = 0, lz = 0,                    -- world x/z drift
  vx = 0, vz = 0,
  scales = {}, tgt = {},
  chOut = {},                        -- redstone-linker output levels (ch -> 0..15)
  dir = { x = 0, y = 0 },            -- nozzle cone joystick (last written) -- v5
  throttle = 0,
  frozen = false,
}

-- tiny quaternion helpers (same convention as att.lua)
local function qmul(a, b)
  return {
    a[4]*b[1] + a[1]*b[4] + a[2]*b[3] - a[3]*b[2],
    a[4]*b[2] - a[1]*b[3] + a[2]*b[4] + a[3]*b[1],
    a[4]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[4],
    a[4]*b[4] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3],
  }
end
local function qconj(q) return { -q[1], -q[2], -q[3], q[4] } end
local function qrot(q, v)
  local p = qmul(qmul(q, { v[1], v[2], v[3], 0 }), qconj(q))
  return { p[1], p[2], p[3] }
end
local function qNorm(q)
  local n = math.sqrt(q[1]*q[1]+q[2]*q[2]+q[3]*q[3]+q[4]*q[4])
  return { q[1]/n, q[2]/n, q[3]/n, q[4]/n }
end
local function upVec() return qrot(S.q, { 0, 1, 0 }) end

local CHANNELS = { 0, 1, 2, 3, 4 }
-- v23: ch0 is part of the firing set now (F4).  It contributes 0 thrust while the program
-- keeps its scale at 0 (CFG.ch0On=false), so including it is backwards compatible; when F4
-- is on it adds the real +25% thrust AND the matching +25% cone torque (which the stub
-- derives from thrFrac).
local FIRING = { 0, 1, 2, 3, 4 }

-- REAL atmosphere instead of the old linear fit (which gave p(300)=0.12; the game gives
-- 0.306 there).  Piecewise cubic Hermite over the dimension_physics anchors, base 1.0,
-- sea level Y=63 -- same model as wiki/docs/sensor-system/fmc.zh.md L159-181.
local ATM = {
  { -38.37, 1.5000, -0.006000 },
  {  63.00, 1.0000, -0.004000 },
  { 263.00, 0.4493, -0.001797 },
  { 280.00, 0.4198, -0.001679 },
  { 320.00, 0.0000, -0.020990 },
}
local function pressureAt(y)
  if y <= ATM[1][1] then return ATM[1][2] end
  if y >= ATM[#ATM][1] then return ATM[#ATM][2] end
  local i = 1
  while i < #ATM - 1 and y > ATM[i + 1][1] do i = i + 1 end
  local a, b = ATM[i], ATM[i + 1]
  local dy = b[1] - a[1]
  local t = (y - a[1]) / dy
  local t2, t3 = t * t, t * t * t
  local h00 = 2 * t3 - 3 * t2 + 1
  local h10 = t3 - 2 * t2 + t
  local h01 = -2 * t3 + 3 * t2
  local h11 = t3 - t2
  return h00 * a[2] + h10 * (a[3] * dy) + h01 * b[2] + h11 * (b[3] * dy)
end

-- one physics tick (20 Hz)
local function physicsStep(dt)
  -- thrust-scale step response: first order (tau from arg[2], default 0.08 s;
  -- the real nozzle needs about 0.15 s to follow a setThrustScale step)
  local TAU = tonumber(arg[2]) or 0.08
  for _, ch in ipairs(CHANNELS) do
    local cur, tgt = S.scales[ch] or 0.0, S.tgt[ch] or 0.0
    S.scales[ch] = cur + (tgt - cur) * math.min(1.0, dt / TAU)
  end
  local p = pressureAt(S.y)
  local rpm = 256.0 * S.throttle
  local acc = 0.0
  local thrFrac = 0.0
  for _, ch in ipairs(FIRING) do
    acc = acc + (S.scales[ch] or 0) * PN * rpm * p / MASS
    thrFrac = thrFrac + (S.scales[ch] or 0)
  end
  thrFrac = thrFrac / #FIRING
  -- v5: CONE DEFLECTION (measured 2026-11-18): tilt length 0..1 => 0..15 deg cone,
  -- torque about body X at alpha ~2.0 rad/s^2 for ch1..4 at full thrust, sign as
  -- measured (y = -1 reduces theta), AND the plume itself rotates by that tilt
  -- (so a belly-flop body gets a sin(15)=0.26 vertical thrust share).
  local dx, dy = S.dir.x or 0, S.dir.y or 0
  local tiltFrac = math.sqrt(dx*dx + dy*dy)
  if tiltFrac > 1.0 then dx, dy, tiltFrac = dx / tiltFrac, dy / tiltFrac, 1.0 end
  local tiltRad = math.rad(15.0) * tiltFrac
  local ux, uy = 0, 0
  if tiltFrac > 1e-9 then ux, uy = dx / tiltFrac, dy / tiltFrac end
  local up = upVec()
  local bx = qrot(S.q, { 1, 0, 0 })
  local bz = qrot(S.q, { 0, 0, 1 })
  local ct, stt = math.cos(tiltRad), math.sin(tiltRad)
  local px = up[1]*ct + (bx[1]*ux + bz[1]*uy)*stt
  local py = up[2]*ct + (bx[2]*ux + bz[2]*uy)*stt
  local pz = up[3]*ct + (bx[3]*ux + bz[3]*uy)*stt
  S.vy = S.vy + (acc * py - G) * dt
  S.y = S.y + S.vy * dt
  -- lateral from the tilted thrust
  S.vx = S.vx + acc * px * dt
  S.vz = S.vz + acc * pz * dt
  S.lx = S.lx + S.vx * dt
  S.lz = S.lz + S.vz * dt
  -- torque: differentials, alpha = 1.0 rad/s^2 per unit D (att v2, signs as measured)
  local s1, s3 = S.scales[1] or 0, S.scales[3] or 0
  local s2, s4 = S.scales[2] or 0, S.scales[4] or 0
  local da = (s1 - s3) * 0.5
  local db = (s2 - s4) * 0.5
  S.wx = S.wx + (-1.0 * da + 2.0 * (-uy) * thrFrac * tiltRad / math.rad(15.0)) * dt
  S.wz = S.wz + ( 1.0 * db) * dt
  -- integrate orientation (body-frame rate -> small-angle multiply)
  local dq = { S.wx * dt * 0.5, 0, S.wz * dt * 0.5, 1.0 }
  S.q = qNorm(qmul(S.q, dq))
end

-- -------------------- package stubs --------------------
_G.VT = VT
os.epoch = function() return math.floor(VT * 1000) end
os.getComputerID = function() return 0 end
os.clock = function() return VT end

local LOGFILE = {}
local function mkFile(path)
  return {
    writeLine = function(...)
      local a = table.pack(...)
      LOGFILE[#LOGFILE+1] = { path = path, nargs = a.n, s = tostring(a[a.n]), first = tostring(a[1]) }
    end,
    flush = function() end,
    write = function(_, s) LOGFILE[#LOGFILE+1] = { path = path, s = s } end,
    readAll = function() return "return {x=11.5,y=93.047,z=-0.5}" end,
    close = function() end,
  }
end
fs = {
  exists = function(p) return true end,
  makeDir = function() end,
  getFreeSpace = function(p) return 600000 end,
  open = function(p, m)
    if p == "/log/target.txt" and m == "r" then return mkFile(p) end
    if m == "w" then return mkFile(p) end
    return nil
  end,
}
textutils = { unserialize = function() return { x = 11.5, y = DOCKY, z = -0.5 } end }
parallel = { waitForAll = function(...) for _, f in ipairs({...}) do f() end end }

local QUEUE = {}
rednet = {
  open = function() return true end,
  send = function(peer, msg, proto)
    if peer == 1 and type(msg) == "table" and msg.id then
      local reply = { replyTo = msg.id, ok = true, result = {} }
      if msg.cmd == "status" then
        reply.result = { bearings = { b10 = { input = 256 }, b11 = { input = -256 } } }
      end
      QUEUE[#QUEUE+1] = { peer = 1, msg = reply }
    end
    return true
  end,
  receive = function(proto, timeout)
    if #QUEUE == 0 then return nil, nil end
    local q = table.remove(QUEUE, 1)
    return q.peer, q.msg
  end,
}

local function nozzle(ch)
  return {
    getMaxTilt = function() return 15 end,
    setThrustScale = function(v) S.tgt[ch] = math.max(0, math.min(1, v)) end,
    getThrustScale = function() return S.scales[ch] or 0 end,
    -- v5: the vector nozzle's joystick deflection (0..1 length = 0..15 deg cone).
    -- Physics uses the LAST written value, like the real servo train.
    setDirection = function(dx, dy)
      S.dir.x = tonumber(dx) or 0
      S.dir.y = tonumber(dy) or 0
    end,
    getDirection = function() return S.dir.x, S.dir.y end,
    setDeflection = function(az, tilt)
      S.dir.x = (tilt or 0) * math.cos(math.rad(az or 0))
      S.dir.y = (tilt or 0) * math.sin(math.rad(az or 0))
    end,
    setInverted = function(b) end,
  }
end
peripheral = {
  getNames = function() return { "right" } end,
  getType = function() return "modem" end,
  find = function() return nil end,
}

package.preload["ccpe.sensor_system"] = function()
  return {
    getPeripheral = function(ch) if S.frozen then return nil end
                   if ch >= 0 and ch <= 4 then return nozzle(ch) end return nil end,
    getEngine = function() return { setThrottle = function(v) S.throttle = v end,
                                    getThrottle = function() return S.throttle end } end,
    getPhysicsMass = function() return MASS end,
    getPressure = function() return pressureAt(S.y) end,
    getPressureFromAltitude = function(y) return pressureAt(y) end,
    getVelocity = function() return { x = S.vx, y = S.vy, z = S.vz } end,
    getBodyPosition = function() return { x = 11.5 + S.lx, y = S.y, z = -0.5 + S.lz } end,
    getOrientation = function()
      local u = upVec()
      return { x = S.q[1], y = S.q[2], z = S.q[3], w = S.q[4] }
    end,
    setRedstoneOutput = function(ch, lvl)
      if S.frozen then return false end
      S.chOut[ch] = math.max(0, math.min(15, tonumber(lvl) or 0))
      return true
    end,
    getRedstoneOutput = function(ch) return S.chOut[ch] or 0 end,
    getRedstoneInput = function(ch) return S.chOut[ch] or 0 end,
  }
end

package.preload["_tick"] = function()
  -- the program has no hook into the clock, so we advance time from commit:
  return nil
end

-- Advance the virtual clock (and physics) whenever the program does a write block.
local realWaitAll = parallel.waitForAll
parallel.waitForAll = function(...)
  realWaitAll(...)
  physicsStep(0.05)
  VT = VT + 0.05
end

-- CC's `sleep` global is provided by the OS, not by the program: stub it as a clock+physics
-- wait, otherwise any `sleep(n)` in the tail turns into "attempt to call a nil value".
function sleep(sec)
  local n = math.max(0, math.floor((tonumber(sec) or 0) / 0.05))
  for _ = 1, n do physicsStep(0.05); VT = VT + 0.05 end
end

-- -------------------- run --------------------
args = nil
local Y0 = tonumber(arg[6])
if Y0 then S.y = Y0 end
local Z0 = tonumber(arg[8])
if Z0 then S.lz = Z0 end      -- initial z offset (real geometry: release ~+67 m from the clamp)
if (tostring(arg[7] or ""):lower() == "belly") then
  local s = math.sqrt(0.5)
  -- 90 deg about -X: the body's up-vector points at -Z, which is what the real ship does
  -- (the field CSV shows the drift going to -Z, i.e. the tilted thrust pushes that way).
  S.q = { -s, 0, 0, s }
end
local chunk, err = loadfile(SRC)
if not chunk then print("LOAD ERROR: " .. tostring(err)); os.exit(1) end
local PARG = {}
if arg[5] and tostring(arg[5]) ~= "" then PARG[1] = tostring(arg[5]) end
local ok, err2 = pcall(chunk, table.unpack(PARG))
if not ok then print("RUNTIME ERROR: " .. tostring(err2)) end

print("---- log files ----")
local perPath = {}
for i = 1, #LOGFILE do
  local p = tostring(LOGFILE[i].path)
  perPath[p] = (perPath[p] or 0) + 1
end
for p, c in pairs(perPath) do print("  " .. p .. " : " .. c .. " lines") end
print("---- CSV head ----")
for i = 1, math.min(6, #LOGFILE) do print(tostring(LOGFILE[i].path) .. ": " .. tostring(LOGFILE[i].s)) end
-- optional phase dump:  lua54 harness.lua <prog> [tau] [PHASE] [stride]
local ph = arg[3]
if ph then
  local stride = tonumber(arg[4]) or 4
  print("---- rows for phase " .. ph .. " (stride " .. stride .. ") ----")
  local k, shown = 0, 0
  for i = 1, #LOGFILE do
    local s = tostring(LOGFILE[i].s)
    if string.find(s, "^[^,]*," .. ph .. ",", 1) then
      k = k + 1
      if (k - 1) % stride == 0 and shown < 24 then
        print(s); shown = shown + 1
      end
    end
  end
  print("---- " .. ph .. " rows: " .. k .. " ----")
end
os.exit(ok and 0 or 1)
