local SRC = [[D:\aMyMCMods\ccnavigationtable-template-1.21.1\run\flight_logs\flight_overworld_bb4ffa2b_20261117-193346.csv]]
local f = assert(io.open(SRC, "r"))
local hdr = f:read("*l")
local names = {}
local n = 0
for name in hdr:gmatch("[^,]+") do names[n] = name; n = n + 1 end
print("header fields:", n)
print("names[0..11]:", names[0], names[1], names[2], names[3], names[4], names[5], names[6], names[7], names[8], names[9], names[10], names[11])
local rows = {}
for line in f:lines() do
  rows[#rows + 1] = line
  if #rows > 20000 then break end
end
f:close()
print("total rows:", #rows)
local _, commas = rows[#rows]:gsub(",", ",")
print("commas in last row:", commas)
for k = #rows - 5, #rows do
  print(k .. " :: " .. rows[k]:sub(1, 200))
end
-- locate rows whose y is near 87 (h = -6): those are the att v2 run
local function field(row, i)
  local t, j, s = {}, 0, 1
  for pos = 1, #row + 1 do
    if pos > #row or row:sub(pos, pos) == "," then
      t[j] = row:sub(s, pos - 1); j = j + 1; s = pos + 1
    end
  end
  return t[i]
end
-- find the last row with vY < -5 (free fall) then the following rows
local lastFall = nil
for k = 1, #rows do
  local vy = tonumber(field(rows[k], 14) or "")
  if vy and vy < -5.0 then lastFall = k end
end
print("last free-fall row:", lastFall)
if lastFall then
  for k = lastFall - 3, math.min(#rows, lastFall + 14) do
    print(string.format("%5d :: y=%s vY=%s  propFy=%s  propMx=%s wbX=%s", k,
      field(rows[k], 4), field(rows[k], 14), field(rows[k], 51), field(rows[k], 53), field(rows[k], 20)))
  end
end
