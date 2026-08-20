# 方块 NBT 读取

外设扩展器的 NBT 读取功能允许你访问任何方块的内部数据，无需方块专门实现 CC:T 外设接口。

## 基础用法

### 读取完整 NBT 数据

```lua
local pe = require("ccpe.pe")

-- 读取频道 1 的方块的所有 NBT 数据
local data = pe.getAll(1)

-- 示例：箱子的 NBT 结构
-- {
--   Items = {
--     [1] = { id = "minecraft:diamond", count = 16, slot = 0 },
--     [2] = { id = "minecraft:iron_ingot", count = 64, slot = 1 }
--   },
--   CustomName = "钻石储存箱"
-- }
```

### 读取特定路径

使用路径语法只读取你需要的字段，提高效率：

```lua
local pe = require("ccpe.pe")

-- 读取第一个物品的 ID
local itemId = pe.get(1, "Items[0].id")
print(itemId)  -- "minecraft:diamond"

-- 读取第一个物品的数量
local count = pe.get(1, "Items[0].count")
print(count)  -- 16

-- 读取自定义名称
local name = pe.get(1, "CustomName")
print(name)  -- "钻石储存箱"
```

## 路径语法

NBT 路径使用类似 JSON 的语法：

| 语法 | 说明 | 示例 |
|---|---|---|
| `.field` | 访问对象字段 | `CustomName` |
| `[index]` | 访问数组元素（从 0 开始） | `Items[0]` |
| 组合 | 链式访问 | `Items[0].count` |

### 常见示例

```lua
-- 箱子/漏斗的物品列表
"Items"                    -- 所有物品
"Items[0]"                 -- 第一个物品
"Items[0].id"              -- 第一个物品的 ID
"Items[0].count"           -- 第一个物品的数量
"Items[0].components"      -- 第一个物品的组件数据

-- Create 设备
"Speed"                    -- 转速
"Stress"                   -- 应力值
"running"                  -- 是否运行中

-- 自定义数据
"ForgeData.CustomValue"    -- Mod 自定义字段
"CustomName"               -- 方块自定义名称
```

## 实战示例

### 示例 1：监控箱子容量

```lua
local pe = require("ccpe.pe")

function getChestFillPercentage(channel)
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
    local percentage = getChestFillPercentage(1)
    print(string.format("箱子容量: %.1f%%", percentage))
    
    if percentage > 90 then
        print("警告: 箱子快满了！")
    end
    
    sleep(1)
end
```

### 示例 2：查找特定物品

```lua
local pe = require("ccpe.pe")

function findItem(channel, itemId)
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
local diamonds = findItem(1, "minecraft:diamond")
print("钻石总数: " .. (diamonds or 0))
```

### 示例 3：多箱子库存统计

```lua
local pe = require("ccpe.pe")

-- 监控频道 1-10 的十个箱子
local channels = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}

function getTotalItems()
    local inventory = {}
    
    for _, ch in ipairs(channels) do
        local items = pe.get(ch, "Items")
        if items then
            for _, item in ipairs(items) do
                local id = item.id
                inventory[id] = (inventory[id] or 0) + item.count
            end
        end
    end
    
    return inventory
end

while true do
    local inventory = getTotalItems()
    
    term.clear()
    term.setCursorPos(1, 1)
    print("=== 总库存 ===")
    
    for itemId, count in pairs(inventory) do
        print(itemId .. ": " .. count)
    end
    
    sleep(2)
end
```

## 性能提示

!!! tip "优化建议"
    1. **使用路径读取** — 只读取需要的字段，避免传输整个 NBT 结构
    2. **合理设置刷新频率** — 根据实际需求调整 `sleep()` 时间
    3. **缓存不变数据** — 如方块类型、槽位数等固定信息

```lua
-- ❌ 低效：每次都读取完整 NBT
while true do
    local data = pe.getAll(1)
    print(data.Items[1].count)
    sleep(0.05)
end

-- ✅ 高效：只读取需要的字段
while true do
    local count = pe.get(1, "Items[0].count")
    print(count)
    sleep(0.05)
end
```

## 故障排查

### 返回 nil
- 检查频道号是否正确
- 确认传感器已贴在方块上
- 确认路径语法正确（注意数组从 0 开始）

### 数据不更新
- 数据刷新频率为 50ms（1 tick）
- 检查配置文件中的 `refresh_interval` 设置

## 下一步

- [外设代理](peripheral-proxy.md) — 调用方块的 CC:T 外设方法
- [Lua API 完整参考](api-reference.md)
- [示例：自动化监控系统](../examples/monitoring-system.md)
