local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\flight_logs\flight_overworld_bb4ffa2b_20261117-193346.csv]]
local f = assert(io.open(SRC, "r"))
local hdr = f:read("*l")
local idx = {}
local n = 0
for name in hdr:gmatch("[^,]+") do idx[name] = n; n = n + 1 end
local function split(row)
  local t, j, s = {}, 0, 1
  for pos = 1, #row + 1 do
    if pos > #row or row:sub(pos, pos) == "," then
      t[j] = row:sub(s, pos - 1); j = j + 1; s = pos + 1
    end
  end
  return t
end
local function num(t, name)
  local v = t[idx[name]]
  if not v then return nil end
  v = v:gsub("%s", "")
  if v == "" or v:lower():find("nan") then return nil end
  return tonumber(v)
end
local rows = {}
for line in f:lines() do rows[#rows + 1] = line end
f:close()
print("rows:", #rows)

-- find the att v2 window: y deep below the dock (h < -4) with thrust present
local lo, hi = nil, nil
for i = 2, #rows do
  local t = split(rows[i])
  local y = num(t, "y")
  if y and y < 89.0 then
    local fx, fy, fz = num(t, "propFx"), num(t, "propFy"), num(t, "propFz")
    if (fx and math.abs(fx) > 1) or (fy and math.abs(fy) > 1) or (fz and math.abs(fz) > 1) then
      if not lo then lo = i end
      hi = i
    end
  end
end
print(string.format("att-like window: rows %d..%d", lo or -1, hi or -1))
if not lo then return end
print("sample row:", rows[lo + 5]:sub(1, 200))
print("\n t_s     y       vY      wbX      wbZ     propFx  propFy  propMx  propMy  propMz")
for i = lo, hi, 4 do
  local t = split(rows[i])
  print(string.format("%6.2f %8.2f %7.2f %8.4f %8.4f %7.1f %7.1f %7.1f %7.1f %7.1f",
    num(t, "time_s") or 0, num(t, "y") or 0, num(t, "vY") or 0,
    num(t, "wbX") or 0, num(t, "wbZ") or 0,
    num(t, "propFx") or 0, num(t, "propFy") or 0,
    num(t, "propMx") or 0, num(t, "propMy") or 0, num(t, "propMz") or 0))
end
