# [ WIP ]实战示例 - 简单监控系统

## 示例 1：监控箱子容量

```lua
local pe = require("ccpe.pe")

local function getChestFillPercentage(channel)
    local items = pe.get(channel, "Items")
    if not items then return 0 end

    local totalCount = 0
    for _, item in ipairs(items) do
        totalCount = totalCount + item.count
    end

    -- 假设箱子有 27 个槽位，每个槽位最多 64 个物品
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

## 示例 2：查找特定物品

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

-- 查找钻石的总数
local diamonds = findItem(2, "minecraft:diamond")
print("diamonds: " .. (diamonds or 0))
```