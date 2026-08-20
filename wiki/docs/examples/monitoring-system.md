# 自动化监控系统

本教程将教你构建一个完整的多箱子监控系统，实时追踪仓库库存并发出预警。

## 系统功能

- 监控多个箱子的库存
- 统计每种物品的总数
- 库存低于阈值时自动预警
- 图形化界面显示

## 准备工作

### 硬件需求
- 1 台 CC:T 高级计算机（支持彩色显示）
- 6 个外设扩展器（传感器）
- 6 个箱子

### 布局建议
将 6 个箱子排成一排，每个箱子上贴一个传感器，设置频道 1-6。计算机可以放在任意位置。

## 完整代码

创建文件 `monitor.lua`：

```lua
local pe = require("ccpe.pe")

-- 配置
local CHANNELS = {1, 2, 3, 4, 5, 6}  -- 监控的频道号
local REFRESH_RATE = 2  -- 刷新间隔（秒）

-- 预警阈值（物品 ID -> 最小数量）
local THRESHOLDS = {
    ["minecraft:diamond"] = 64,
    ["minecraft:iron_ingot"] = 256,
    ["minecraft:gold_ingot"] = 128,
    ["minecraft:redstone"] = 512,
}

-- 颜色配置
local COLOR_TITLE = colors.cyan
local COLOR_NORMAL = colors.white
local COLOR_WARNING = colors.yellow
local COLOR_CRITICAL = colors.red

-- 清屏并显示标题
function drawHeader()
    term.setBackgroundColor(colors.black)
    term.clear()
    term.setCursorPos(1, 1)
    
    term.setTextColor(COLOR_TITLE)
    print("=== 仓储监控系统 ===")
    print("")
end

-- 收集所有箱子的库存
function collectInventory()
    local inventory = {}
    local totalSlots = 0
    local usedSlots = 0
    
    for _, channel in ipairs(CHANNELS) do
        local items = pe.get(channel, "Items")
        if items then
            usedSlots = usedSlots + #items
            totalSlots = totalSlots + 27  -- 假设每个箱子 27 格
            
            for _, item in ipairs(items) do
                local id = item.id
                inventory[id] = (inventory[id] or 0) + item.count
            end
        end
    end
    
    return inventory, usedSlots, totalSlots
end

-- 检查预警
function checkAlerts(inventory)
    local alerts = {}
    
    for itemId, threshold in pairs(THRESHOLDS) do
        local count = inventory[itemId] or 0
        if count < threshold then
            table.insert(alerts, {
                item = itemId,
                count = count,
                threshold = threshold,
                critical = count < threshold * 0.5
            })
        end
    end
    
    return alerts
end

-- 显示库存列表
function displayInventory(inventory)
    term.setTextColor(COLOR_NORMAL)
    print("库存清单:")
    print(string.rep("-", 40))
    
    -- 按物品 ID 排序
    local sorted = {}
    for itemId, count in pairs(inventory) do
        table.insert(sorted, {id = itemId, count = count})
    end
    table.sort(sorted, function(a, b) return a.count > b.count end)
    
    -- 显示前 15 项
    for i = 1, math.min(15, #sorted) do
        local item = sorted[i]
        local shortId = item.id:match("^.*:(.*)$") or item.id
        
        -- 检查是否有阈值设定
        local threshold = THRESHOLDS[item.id]
        if threshold then
            if item.count < threshold then
                term.setTextColor(COLOR_WARNING)
            else
                term.setTextColor(colors.lime)
            end
            print(string.format("%-20s %6d / %d", 
                shortId, item.count, threshold))
        else
            term.setTextColor(COLOR_NORMAL)
            print(string.format("%-20s %6d", shortId, item.count))
        end
    end
    
    if #sorted > 15 then
        term.setTextColor(colors.gray)
        print(string.format("... 还有 %d 种物品", #sorted - 15))
    end
end

-- 显示预警信息
function displayAlerts(alerts)
    if #alerts == 0 then
        return
    end
    
    print("")
    print("⚠ 库存预警:")
    print(string.rep("-", 40))
    
    for _, alert in ipairs(alerts) do
        local shortId = alert.item:match("^.*:(.*)$") or alert.item
        local color = alert.critical and COLOR_CRITICAL or COLOR_WARNING
        local prefix = alert.critical and "[严重]" or "[警告]"
        
        term.setTextColor(color)
        print(string.format("%s %s: %d / %d",
            prefix, shortId, alert.count, alert.threshold))
    end
end

-- 显示统计信息
function displayStats(usedSlots, totalSlots)
    print("")
    term.setTextColor(colors.gray)
    
    local fillPercentage = (usedSlots / totalSlots) * 100
    print(string.format("容量: %d / %d (%.1f%%)", 
        usedSlots, totalSlots, fillPercentage))
    print(string.format("监控箱子: %d 个", #CHANNELS))
end

-- 主循环
function main()
    while true do
        drawHeader()
        
        -- 收集数据
        local inventory, usedSlots, totalSlots = collectInventory()
        local alerts = checkAlerts(inventory)
        
        -- 显示界面
        displayInventory(inventory)
        displayAlerts(alerts)
        displayStats(usedSlots, totalSlots)
        
        -- 更新时间戳
        term.setCursorPos(1, term.getSize())
        term.setTextColor(colors.gray)
        term.write("更新时间: " .. os.date("%H:%M:%S"))
        
        sleep(REFRESH_RATE)
    end
end

-- 启动
main()
```

## 使用步骤

### 1. 放置设备
按照准备工作的布局放置箱子、传感器和计算机。

### 2. 配置频道
依次打开 6 个传感器，设置频道为 1, 2, 3, 4, 5, 6。

### 3. 上传代码
在计算机中创建并编辑 `monitor.lua`，粘贴上面的完整代码。

### 4. 自定义配置
根据需求修改代码顶部的配置：

```lua
-- 修改监控的频道
local CHANNELS = {1, 2, 3}  -- 只监控 3 个箱子

-- 修改预警阈值
local THRESHOLDS = {
    ["minecraft:coal"] = 1024,  -- 煤炭低于 1024 时预警
    ["create:brass_ingot"] = 64,
}

-- 修改刷新频率
local REFRESH_RATE = 1  -- 每秒刷新
```

### 5. 运行
```bash
monitor.lua
```

## 界面效果

运行后你会看到类似这样的界面：

```
=== 仓储监控系统 ===

库存清单:
----------------------------------------
iron_ingot           512 / 256
diamond               48 / 64
redstone            1024 / 512
gold_ingot           200 / 128
coal                 300
...

⚠ 库存预警:
----------------------------------------
[警告] diamond: 48 / 64

容量: 89 / 162 (54.9%)
监控箱子: 6 个
更新时间: 14:23:45
```

## 扩展功能

### 添加声音预警

```lua
-- 在 main() 函数的 displayAlerts() 后添加
if #alerts > 0 then
    -- 发出蜂鸣声
    for i = 1, 3 do
        os.queueEvent("speaker_sound")
        sleep(0.1)
    end
end
```

### 远程通知

结合 CC:T 的 Modem，可以向其他计算机发送预警消息：

```lua
local modem = peripheral.find("modem")

function sendAlert(alert)
    if modem then
        modem.transmit(100, 100, {
            type = "inventory_alert",
            item = alert.item,
            count = alert.count,
            threshold = alert.threshold
        })
    end
end
```

### 网页监控

使用 CC:T 的 HTTP API，可以将数据上传到 Web 服务器，实现远程监控。

## 故障排查

### 数据不显示
- 检查传感器是否正确贴在箱子上
- 确认频道号配置正确
- 检查代码中的 `CHANNELS` 数组

### 预警不工作
- 检查 `THRESHOLDS` 表中的物品 ID 是否正确
- 确认物品 ID 使用完整格式，如 `minecraft:diamond`

## 下一步

- [外设代理](../peripheral-extender/peripheral-proxy.md) — 访问更多方块的功能
- [物理数据读取](../peripheral-extender/physics-data.md) — 监控飞行器状态
- [飞船姿态控制](ship-attitude-control.md) — 构建自动驾驶系统
