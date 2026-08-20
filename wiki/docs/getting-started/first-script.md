# 第一个脚本

本教程将带你快速上手使用外设扩展器读取箱子的物品数量。

## 准备工作

你需要准备：

1. 一台 CC:T 计算机
2. 一个外设扩展器（传感器）
3. 一个箱子

## 步骤 1：放置设备

1. 在世界中放置一个箱子
2. 将**外设扩展器**贴在箱子的任意面上（右键点击箱子）
3. 在附近任意位置放置一台 CC:T 计算机（不需要紧贴）

## 步骤 2：配置频道

右键打开外设扩展器的界面，设置**频道号**为 `1`。

!!! tip "频道号"
    频道号用于区分不同的传感器。一个传感器占用一个频道，范围是 1 到 9999。

## 步骤 3：编写脚本

在计算机中创建一个新文件：

```lua
edit chest_monitor.lua
```

输入以下代码：

```lua
local pe = require("ccpe.pe")

while true do
    -- 读取频道 1 的箱子数据
    local data = pe.getAll(1)
    
    -- 清屏并显示
    term.clear()
    term.setCursorPos(1, 1)
    
    print("=== 箱子监控 ===")
    print("频道: 1")
    print("")
    
    if data and data.Items then
        print("物品数量: " .. #data.Items)
        print("")
        
        -- 列出前 5 个物品
        for i = 1, math.min(5, #data.Items) do
            local item = data.Items[i]
            print(i .. ". " .. item.id .. " x" .. item.count)
        end
    else
        print("箱子为空或无数据")
    end
    
    sleep(0.5)
end
```

保存并退出（Ctrl+S，然后 Ctrl+X）。

## 步骤 4：运行

```lua
chest_monitor.lua
```

现在你应该能看到实时显示的箱子内容！尝试往箱子里放入或取出物品，脚本会自动更新显示。

## 进阶：读取特定字段

如果你只想读取某个特定数据（比如第一个物品的数量），可以使用路径语法：

```lua
local pe = require("ccpe.pe")

-- 只读取第一个物品的数量
local count = pe.get(1, "Items[0].count")
print("第一个物品数量: " .. tostring(count))
```

!!! note "路径语法"
    NBT 路径使用类似 JSON 的语法：
    
    - `Items[0].id` — 第一个物品的 ID
    - `Items[0].count` — 第一个物品的数量
    - `CustomName` — 自定义名称

## 下一步

- [NBT 读取详细文档](../peripheral-extender/nbt-reading.md)
- [外设代理](../peripheral-extender/peripheral-proxy.md) — 调用方块的 CC:T 外设方法
- [示例：自动化监控系统](../examples/monitoring-system.md)
