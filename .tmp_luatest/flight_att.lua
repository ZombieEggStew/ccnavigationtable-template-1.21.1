--==========================================================================
-- flight_att.lua -- independent cross-check of the att v2 run using the
-- mod's own FlightDataRecorder (run/flight_logs/*.csv):
--   * body rates wbX/wbY/wbZ are the MOD's own body angular velocity
--   * propMx/My/Mz is the propulsion moment actually applied by the nozzles
-- So we can (a) confirm the axis/sign the Lua saw, (b) get the torque, and
-- (c) get the body inertia I = M / alpha from two independent numbers.
--==========================================================================
local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\flight_logs\flight_overworld_bb4ffa2b_20261117-193346.csv]]

local f = assert(io.open(SRC, "r"))
local hdr = f:read("*l")
local idx = {}
local n = 0
for name in hdr:gmatch("[^,]+") do idx[name] = n; n = n + 1 end

local function number(s)
  if s == nil then return nil end
  s = s:gsub("%s", "")
  if s == "" or s:lower():find("nan") or s:lower():find("inf") then return nil end
  local v = tonumber(s)
  return v
end

local cols = {
  "tick", "time_s", "x", "y", "z", "vX", "vY", "vZ",
  "qx", "qy", "qz", "qw",
  "wX", "wY", "wZ", "wbX", "wbY", "wbZ",
  "propFx", "propFy", "propFz", "propMx", "propMy", "propMz",
  "massKg", "altitude", "pitchDeg", "rollDeg", "yawDeg",
}
local rec = {}
for _, name in ipairs(cols) do rec[name] = {} end
local total = 0
for line in f:lines() do
  if line ~= "" then
    total = total + 1
    local a, i = {}, 0
    for field in (line .. ","):gmatch("([^,]*),") do a[i] = field; i = i + 1 end
    for _, name in ipairs(cols) do
      rec[name][total] = number(a[idx[name]] or "")
    end
  end
end
f:close()

-- last contiguous thrust segment = the att v2 run (capture -> hold)
local first, last, seg = nil, nil, {}
for i = 1, total do
  local fx, fy, fz = rec.propFx[i], rec.propFy[i], rec.propFz[i]
  local mag = (fx and math.abs(fx) or 0) + (fy and math.abs(fy) or 0) + (fz and math.abs(fz) or 0)
  if mag > 1 then
    seg[#seg + 1] = i
    last = i
    if not first then first = i end
  end
end
print(string.format("rows=%d  thrust rows=%d  (rows %d..%d)", total, #seg, first or -1, last or -1))

-- keep only the final burst (att v2): walk from `last` backwards while rows are contiguous in tick
local i0 = last
while i0 > 1 do
  local a = rec.propFx[i0 - 1] or 0
  local b = rec.propFy[i0 - 1] or 0
  local c = rec.propFz[i0 - 1] or 0
  if (math.abs(a) + math.abs(b) + math.abs(c)) <= 1 then break end
  i0 = i0 - 1
end
local i1 = last
print(string.format("final burst: rows %d..%d  (%d ticks, ~%.2f s)",
  i0, i1, i1 - i0 + 1, (rec.time_s[i1] or 0) - (rec.time_s[i0] or 0)))

-- moment magnitude profile: find the differential pulses (propM spikes)
local mTot = {}
for i = i0, i1 do
  local mx, my, mz = rec.propMx[i] or 0, rec.propMy[i] or 0, rec.propMz[i] or 0
  mTot[#mTot + 1] = { i = i, mx = mx, my = my, mz = mz, m = math.sqrt(mx*mx + my*my + mz*mz) }
end
table.sort(mTot, function(a, b) return a.m > b.m end)
print("\n-- top 12 propulsion-moment ticks (world frame) --")
print("row  tick   |M|      Mx      My      Mz     wbX     wbY     wbZ")
local shown = 0
local lastShown = -99
for _, r in ipairs(mTot) do
  if r.m > 1 and math.abs(r.i - lastShown) > 6 then
    shown = shown + 1
    if shown > 12 then break end
    lastShown = r.i
    print(string.format("%4d %5.0f %8.1f %8.1f %8.1f %8.1f %8.4f %8.4f %8.4f",
      r.i, rec.tick[r.i] or 0, r.m, r.mx, r.my, r.mz,
      rec.wbX[r.i] or 0, rec.wbY[r.i] or 0, rec.wbZ[r.i] or 0))
  end
end
