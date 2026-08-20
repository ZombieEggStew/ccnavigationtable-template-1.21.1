# 方块 NBT 读取

外设扩展器的 NBT 读取功能允许你访问任何方块的内部数据，无需方块专门实现 CC:T 外设接口。

## 基础用法

### 读取完整 NBT 数据

```lua
local pe = require("ccpe.pe")

-- 读取频道 1 的外设扩展器附着方块的所有 NBT 数据
local data = pe.getAll(1)

-- 示例：箱子的 NBT 结构
-- {
--   Items = {
--     [1] = { count = 16, slot = 0, id = "minecraft:diamond" },
--     [2] = { count = 32, slot = 1, id = "minecraft:iron_ingot" }
--   },
--   x = 8,
--   z = 9,
--   id = "minecraft:chest",
--   y = 63,
-- }
```

---

### 读取特定路径

!!! info "快速获取路径"
    在 pe 的右键菜单中，点击一行 NBT 数据即可复制该字段的路径。

    如果有▶符号，表示该字段是table，左键点击展开/收起，右键点击复制路径。


使用路径语法只读取你需要的字段，提高效率：

```lua
local pe = require("ccpe.pe")

-- 读取第一个物品的 ID
local itemId = pe.get(1, "Items[0].id")
print(itemId)  -- "minecraft:diamond"

-- 读取第一个物品的数量
local count = pe.get(1, "Items[0].count")
print(count)  -- 16

```

#### 路径语法

NBT 路径使用类似 JSON 的语法：

| 语法 | 说明 | 示例 |
|---|---|---|
| `.field` | 访问对象字段 | `id` |
| `[index]` | 访问数组元素（从 0 开始） | `Items[0]` |
| 组合 | 链式访问 | `Items[0].count` |

---

## 实战示例

### 示例 1：监控箱子容量

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

### 示例 2：查找特定物品

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

- [外设代理](peripheral-proxy.md) — 获取的 CC:T 外设
- [Lua API 完整参考](api-reference.md)
- [示例：自动化监控系统](../examples/monitoring-system.md)
