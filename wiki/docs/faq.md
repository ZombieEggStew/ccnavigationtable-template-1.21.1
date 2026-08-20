# 常见问题

## 安装与配置

### Q: 需要哪些前置模组？

**必需：**
- Minecraft 1.21.1
- NeoForge 21.1.235+
- ComputerCraft: Tweaked
- Create

**可选：**
- Simulated (Create: Aeronautics) — 导航桌功能
- Sable — 物理数据功能

### Q: 如何确认模组已正确安装？

在创造模式物品栏搜索 `CCPE` 或 `外设扩展`，应该能看到：
- 微型外设扩展器
- 红石收发器
- 电子变速箱

---

## 外设扩展器

### Q: 为什么 pe.getAll() 返回 nil？

可能的原因：
1. **频道号错误** — 检查传感器界面的频道设置
2. **传感器未贴附** — 确保传感器贴在方块上（右键点击方块）
3. **方块无 NBT 数据** — 某些方块可能没有 NBT 数据

**调试方法：**
```lua
local pe = require("ccpe.pe")
local data = pe.getAll(1)
if data then
    print("数据读取成功")
else
    print("频道 1 无数据，检查传感器配置")
end
```

### Q: 路径语法返回 nil 但 getAll() 有数据？

检查：
1. **数组索引从 0 开始** — 使用 `Items[0]` 而非 `Items[1]`
2. **路径拼写** — 区分大小写，如 `CustomName` 不是 `customName`
3. **字段存在性** — 并非所有方块都有相同的 NBT 结构

**示例：**
```lua
-- ✅ 正确：数组从 0 开始
local first = pe.get(1, "Items[0].id")

-- ❌ 错误：数组不从 1 开始
local first = pe.get(1, "Items[1].id")
```

### Q: 数据不实时更新？

- 数据刷新频率为 **50ms (1 tick)**
- Lua 脚本中添加 `sleep()` 会降低刷新感知速度
- 检查配置文件 `config/ccpe/` 中的 `refresh_interval`

### Q: 传感器有距离限制吗？

默认配置下**无距离限制**。可在配置文件中设置：
```toml
[peripheral_extender]
max_distance = 0  # 0 = 无限制，其他值为米数
```

### Q: 可以在同一个频道上放多个传感器吗？

不建议。同一频道的多个传感器会互相覆盖数据，导致读取结果不可预测。每个传感器应使用唯一的频道号。

---

## 导航与物理数据

### Q: getNavTargetPos() 返回 nil？

检查：
1. **是否安装 Simulated** — 导航功能需要 Create: Aeronautics
2. **传感器是否贴在导航桌上** — 必须贴在 `simulated:navigation_table`
3. **导航桌是否已设置目标** — 打开导航桌界面设置目标位置

### Q: getPhysicsVelocity() 返回 nil？

检查：
1. **是否安装 Sable** — 物理数据需要 Sable 模组
2. **传感器是否贴在速度传感器上** — 速度数据必须从 `simulated:velocity_sensor` 读取
3. **方块是否在物理结构上** — 必须是 Sable 装配的物理体

**其他物理数据（位置、质量等）不需要速度传感器。**

---

## 红石收发器

### Q: setRedstoneSignal() 不工作？

检查：
1. **频道是否配置** — 在红石收发器界面用旗帜配置频道
2. **频率物品是否正确** — 需要一对 Create 红石频率物品
3. **Create 红石网络是否存在** — 确保有其他红石链接方块在同一频率上

### Q: 如何配置红石收发器的频道？

1. 右键打开红石收发器界面
2. 将**旗帜**放入槽位（旗帜数量 = 频道数量）
3. 为每面旗帜配置一对红石频率物品（接收 + 发送）

### Q: 最多支持多少个频道？

默认 16 个频道。可在配置文件中修改：
```toml
[redstone_transceiver]
max_channels = 16
```

---

## 电子变速箱

### Q: 与 Create 的转速控制器有什么区别？

Create 原版的 `RotationSpeedController` 在 CC:T 调用 `setTargetSpeed()` 时会触发 `RotationPropagator.handleRemoved()`，导致下游子网络的 source 被清空，可能影响其他设备（如 Aeroworks 的步进电机）。

**电子变速箱使用 `detachKinetics()` + `attachKinetics()` 实现温和的网络刷新，避免干扰下游设备。**

### Q: 如何从 Lua 控制电子变速箱？

```lua
local trans = peripheral.find("ccpe:electronic_transmission")
if trans then
    trans.setTargetSpeed(128)  -- 设置目标转速
    local speed = trans.getSpeed()  -- 读取当前转速
end
```

---

## 性能与优化

### Q: 监控大量传感器会影响性能吗？

CCPE 的性能开销非常小：
- 服务端每 tick 更新所有传感器数据（分摊到整个 tick）
- Lua 端单次读取约 0.02ms
- 即使监控 100 个传感器，性能影响也可忽略

**优化建议：**
- 使用路径读取（`pe.get()`）而非完整读取（`pe.getAll()`）
- 根据需求调整 Lua 脚本的刷新频率（`sleep()` 时间）

### Q: 如何减少网络流量？

1. **使用路径读取** — 只传输需要的字段
2. **缓存静态数据** — 方块类型、槽位数等不变数据只读取一次
3. **合理设置刷新间隔** — 不是所有数据都需要 50ms 刷新

```lua
-- ❌ 低效：每次传输完整 NBT
while true do
    local data = pe.getAll(1)
    print(data.Items[1].count)
    sleep(0.05)
end

-- ✅ 高效：只传输需要的字段
while true do
    local count = pe.get(1, "Items[0].count")
    print(count)
    sleep(0.5)  -- 降低刷新频率
end
```

---

## 错误与调试

### Q: 如何调试传感器连接问题？

```lua
local pe = require("ccpe.pe")

-- 测试传感器连接
function testChannel(channel)
    local data = pe.getAll(channel)
    if data then
        print("频道 " .. channel .. " 连接正常")
        return true
    else
        print("频道 " .. channel .. " 无数据")
        return false
    end
end

-- 测试频道 1-10
for i = 1, 10 do
    testChannel(i)
end
```

### Q: 游戏崩溃或报错？

1. **查看崩溃日志** — `logs/latest.log` 或 `crash-reports/`
2. **检查模组版本兼容性** — 确保所有模组版本匹配
3. **提交 Issue** — 附上完整日志到 [GitHub Issues](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/issues)

---

## 其他问题

### Q: 支持多人服务器吗？

支持。所有功能在单人和多人环境下均正常工作。

### Q: 能否在维度间使用？

可以。传感器和计算机可以在不同维度，只要频道号匹配即可通信（默认无距离限制）。

### Q: 如何获取帮助？

- [GitHub Issues](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/issues) — 报告 Bug 或请求功能
- [文档首页](index.md) — 查看完整文档
- [示例教程](examples/monitoring-system.md) — 参考实战案例
