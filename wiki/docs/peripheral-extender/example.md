# [ WIP ]Real-world Example - Simple Monitoring System

## Example 1: Monitor Chest Capacity

```lua
local pe = require("ccpe.pe")

local function getChestFillPercentage(channel)
    local items = pe.get(channel, "Items")
    if not items then return 0 end

    local totalCount = 0
    for _, item in ipairs(items) do
        totalCount = totalCount + item.count
    end

    -- Assume the chest has 27 slots, each holding up to 64 items
    local maxCapacity = 27 * 64
    return (totalCount / maxCapacity) * 100
end

while true do
    local percentage = getChestFillPercentage(2)
    print(string.format("capacity: %.1f%%", percentage))

    if percentage > 90 then
        print("warning: chest is almost full!")
    end

    sleep(1)
end
```

## Example 2: Find a Specific Item

```lua
local pe = require("ccpe.pe")

local function findItem(channel, itemId)
    local items = pe.get(channel, "Items")
    if not items then return nil end

    local totalCount = 0
    for _, item in ipairs(items) do
        if item.id == itemId then
            totalCount = totalCount + item.count
        end
    end

    return totalCount
end

-- Find the total number of diamonds
local diamonds = findItem(2, "minecraft:diamond")
print("diamonds: " .. (diamonds or 0))
```
