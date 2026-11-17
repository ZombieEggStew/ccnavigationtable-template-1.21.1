local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\flight_logs\flight_overworld_bb4ffa2b_20261117-200843.csv]]
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
print("rows:", #rows)
print("first/last time_s:", col(1, "time_s"), col(#rows, "time_s"))

-- the tilt run is the only one with |propM| around 5 (differential up to D=0.33)
local seg1, seg2 = nil, nil
for i = 1, #rows do
  local m = math.sqrt(col(i,"propMx")^2 + col(i,"propMy")^2 + col(i,"propMz")^2)
  if m > 1.5 then
    if not seg1 then seg1 = i end
    seg2 = i
  end
end
print(string.format("propM>1.5 window: rows %s..%s", tostring(seg1), tostring(seg2)))
if not seg1 then return end
print(" max |propM| in window:", (function()
  local best = 0
  for i = seg1, seg2 do
    local m = math.sqrt(col(i,"propMx")^2 + col(i,"propMy")^2 + col(i,"propMz")^2)
    if m > best then best = m end
  end
  return best
end)())

-- print the whole window at 1-tick resolution but condensed: every 4th row plus
-- any row where |dwb| is large
print("\n tick   t_s      h       x       z      vY     wbX     wbY     wbZ    |propM|")
local prevX, prevZ, prevY = nil, nil, nil
for i = math.max(1, seg1 - 20), math.min(#rows, seg2 + 60) do
  local dw = 0
  if i > 1 then
    dw = math.max(math.abs(col(i,"wbX")-col(i-1,"wbX")), math.abs(col(i,"wbZ")-col(i-1,"wbZ")), math.abs(col(i,"wbY")-col(i-1,"wbY")))
  end
  local ts = col(i, "time_s")
  local show = (ts >= 24.0 and ts <= 34.0) and ((i % 2 == 0) or dw > 0.03)
  if show then
    local mx, my, mz = col(i,"propMx"), col(i,"propMy"), col(i,"propMz")
    local mm = math.sqrt(mx*mx+my*my+mz*mz)
    print(string.format("%5d %7.2f %7.2f %7.2f %7.2f %7.2f %7.3f %7.3f %7.3f %7.2f%s",
      col(i,"tick"), col(i,"time_s"), col(i,"y") - 93.047, col(i,"x"), col(i,"z"),
      col(i,"vY"), col(i,"wbX"), col(i,"wbY"), col(i,"wbZ"), mm, dw > 0.06 and "  <-- rate jump" or ""))
  end
end
