local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\flight_logs\flight_overworld_bb4ffa2b_20261117-200843.csv]]
local f = assert(io.open(SRC, "r"))
local hdr = f:read("*l")
local idx, n = {}, 0
for name in hdr:gmatch("[^,]+") do idx[name] = n; n = n + 1 end
local rows = {}
for line in f:lines() do rows[#rows + 1] = line end
f:close()
print("rows:", #rows)
local function col(i, name)
  local s, j, st = {}, 0, 1
  local row = rows[i]
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

-- find the biggest jump in wbX/wbZ between consecutive ticks while the ship is
-- airborne below the dock (y between 80 and 90) -- that is the contact
local best, bestI = 0, nil
for i = 2, #rows do
  local y = col(i, "y")
  if y > 80 and y < 90 then
    local d1 = math.abs(col(i, "wbX") - col(i - 1, "wbX"))
    local d2 = math.abs(col(i, "wbZ") - col(i - 1, "wbZ"))
    local d3 = math.abs(col(i, "wbY") - col(i - 1, "wbY"))
    local d = math.max(d1, d2, d3)
    if d > best then best, bestI = d, i end
  end
end
print(string.format("biggest body-rate jump: %.3f rad/s at row %d (time_s=%.2f)", best, bestI or 0, bestI and col(bestI, "time_s") or 0))
if not bestI then return end
print("\n tick   t_s      y       x       z      vY     wbX     wbY     wbZ    |propM|")
for i = bestI - 12, bestI + 8 do
  if i >= 1 and i <= #rows then
    local mx, my, mz = col(i, "propMx"), col(i, "propMy"), col(i, "propMz")
    local mm = math.sqrt(mx*mx + my*my + mz*mz)
    print(string.format("%5d %7.2f %7.2f %7.2f %7.2f %7.2f %7.3f %7.3f %7.3f %7.2f",
      col(i, "tick"), col(i, "time_s"), col(i, "y"), col(i, "x"), col(i, "z"),
      col(i, "vY"), col(i, "wbX"), col(i, "wbY"), col(i, "wbZ"), mm))
  end
end
