local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\flight_logs\flight_overworld_bb4ffa2b_20261117-214433.csv]]
local f = assert(io.open(SRC, "r"))
local hdr = f:read("*l")
local idx, n = {}, 0
for name in hdr:gmatch("[^,]+") do idx[name] = n; n = n + 1 end
local rows = {}
for line in f:lines() do rows[#rows + 1] = line end
f:close()
local function col(i, name)
  local row = rows[i]
  local s, j, st = {}, 0, 1
  for pos = 1, #row + 1 do
    if pos > #row or row:sub(pos, pos) == "," then
      s[j] = row:sub(st, pos - 1); j = j + 1; st = pos + 1
    end
  end
  local v = s[idx[name]]
  if not v then return 0 end
  v = v:gsub("%s", "")
  if v == "" or v:lower():find("nan") then return 0 end
  return tonumber(v) or 0
end
print("rows:", #rows, " time:", col(1,"time_s"), "..", col(#rows,"time_s"))

-- guide run window: |propM| between 1.5 and 6 (differential), altitude near 85
local lo, hi = nil, nil
for i = 1, #rows do
  local y = col(i, "y")
  local m = math.sqrt(col(i,"propMx")^2 + col(i,"propMy")^2 + col(i,"propMz")^2)
  if y > 80 and y < 92 and m > 1.0 then lo = lo or i; hi = i end
end
print("window rows:", lo, hi)
if not lo then return end
print("\n  t_s     y      x      z     vX     vZ    pitch  roll  |Fh|   Fv   atan(Fh/Fv)  cosVert  |propM|")
local step = math.max(1, math.floor((hi - lo) / 26))
for i = lo, hi, step do
  local fx, fy, fz = col(i,"propFx"), col(i,"propFy"), col(i,"propFz")
  local fh = math.sqrt(fx*fx + fz*fz)
  local tiltF = math.deg(math.atan2(fh, math.max(fy, 1e-6)))
  local qx, qy, qz, qw = col(i,"qx"), col(i,"qy"), col(i,"qz"), col(i,"qw")
  -- up vector = q * (0,1,0) * q^-1
  local ux = 2*(qx*qy + qw*qz)
  local uy = qw*qw - qx*qx + qy*qy - qz*qz
  local uz = 2*(qy*qz - qw*qx)
  local m = math.sqrt(col(i,"propMx")^2 + col(i,"propMy")^2 + col(i,"propMz")^2)
  print(string.format("%7.2f %7.2f %6.2f %7.2f %6.2f %6.2f %6.2f %6.2f %6.2f %6.2f %8.2f %8.3f %7.2f",
    col(i,"time_s"), col(i,"y"), col(i,"x"), col(i,"z"), col(i,"vX"), col(i,"vZ"),
    col(i,"pitchDeg"), col(i,"rollDeg"), fh, fy, tiltF, uy, m))
end
