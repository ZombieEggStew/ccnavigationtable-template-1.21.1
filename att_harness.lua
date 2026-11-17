--===========================================================================
-- att_harness.lua -- dry-run att.lua outside Minecraft (Lua 5.4 stub world)
-- Stubs: sensor system, peripherals, rednet fixture, fs, parallel, time.
-- Physics: crude but representative -- thrust sum on the FIRING nozzles, G=11,
-- body rate driven by differentials with the alpha measured in v1
-- (A: ch1/ch3 -> body X, sign - 1.25 rad/s^2 per unit D; B: ch2/ch4 -> body Z, sign +).
-- Purpose: catch nil / logic / reporting bugs before the in-game run.
--===========================================================================
local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\saves\New World\computercraft\computer\0\att.lua]]

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
local FIRING = { 1, 2, 3, 4 }

local function pressureAt(y)          -- same anchors as the mod: 1.0 at sea, ~0.887 at dock
  local p = 1.0 - 0.0110 * (y - 63.0)
  return math.max(0.05, math.min(1.05, p))
end

-- one physics tick (20 Hz)
local function physicsStep(dt)
  -- thrust-scale ramp: 0.25 per tick, like AdvancedThrustNozzle THRUST_SCALE_PER_TICK
  for _, ch in ipairs(CHANNELS) do
    local cur, tgt = S.scales[ch] or 0.0, S.tgt[ch] or 0.0
    local stepv = 0.25
    if cur < tgt then cur = math.min(tgt, cur + stepv) else cur = math.max(tgt, cur - stepv) end
    S.scales[ch] = cur
  end
  local p = pressureAt(S.y)
  local rpm = 256.0 * S.throttle
  local acc = 0.0
  for _, ch in ipairs(FIRING) do acc = acc + (S.scales[ch] or 0) * PN * rpm * p / MASS end
  local up = upVec()
  S.vy = S.vy + (acc * up[2] - G) * dt
  S.y = S.y + S.vy * dt
  -- lateral from the tilted thrust
  S.vx = S.vx + acc * up[1] * dt
  S.vz = S.vz + acc * up[3] * dt
  S.lx = S.lx + S.vx * dt
  S.lz = S.lz + S.vz * dt
  -- torque: differentials (v1 measured map)
  local s1, s3 = S.scales[1] or 0, S.scales[3] or 0
  local s2, s4 = S.scales[2] or 0, S.scales[4] or 0
  local da = (s1 - s3) * 0.5
  local db = (s2 - s4) * 0.5
  S.wx = S.wx + (-1.25 * da) * dt
  S.wz = S.wz + ( 1.25 * db) * dt
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
    writeLine = function(_, s) LOGFILE[#LOGFILE+1] = { path = path, s = s } end,
    flush = function() end,
    write = function(_, s) LOGFILE[#LOGFILE+1] = { path = path, s = s } end,
    readAll = function() return "return {x=11.5,y=93.047,z=-0.5}" end,
    close = function() end,
  }
end
fs = {
  exists = function(p) return true end,
  makeDir = function() end,
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
    setDirection = function(dx, dz) end,
    getDirection = function() return 0, 0 end,
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

-- -------------------- run --------------------
args = nil
local chunk, err = loadfile(SRC)
if not chunk then print("LOAD ERROR: " .. tostring(err)); os.exit(1) end
local ok, err2 = pcall(chunk)
if not ok then print("RUNTIME ERROR: " .. tostring(err2)) end

print("---- CSV head ----")
for i = 1, math.min(6, #LOGFILE) do print(tostring(LOGFILE[i].path) .. ": " .. tostring(LOGFILE[i].s)) end
os.exit(ok and 0 or 1)
