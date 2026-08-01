# CCPE — CC 外设扩展器

> 为 ComputerCraft: Tweaked 提供无线传感器访问的 NeoForge 模组

## 这是什么？

CCPE 为 CC:Tweaked 添加了**外设扩展器**。将传感器贴在任意方块上，设置频道号，就能在 Lua 中无线读取该方块的数据——无需线缆，不限距离。

```lua
local pe = require("ccpe.pe")
local data = pe.getAll(1)              -- 读取频道 1 方块的完整 NBT
local cnt  = pe.get(1, "Items[0].Count")  -- 读取指定路径的字段
```

## 特点

CC:Tweaked 原版要求计算机紧贴目标方块才能访问外设。
虽然不少模组为各自的方块适配了外设接口，但并非所有方块都有适配。

CCPE 的传感器通过 NBT 缓存，**对任意方块都能读取数据**，不需要等待模组作者逐个适配。

数据在服务端每 tick（50ms）刷新一次，Lua 端读取约 0.02ms/次，适合高频监控场景。

## 功能

### 📡 无线方块读数
- 传感器可附着到**任意**方块
- 通过 `pe.get(频道, 路径)` 或 `pe.getAll(频道)` 读取 NBT 数据
- 路径语法：`"Items[0].Count"`、`"ForgeData.CustomName"` 等

### 🧭 导航桌集成
- `pe.getNavTargetPos(ch)` → `{x, y, z}` — 目标世界坐标
- `pe.getNavSelfPos(ch)` → `{x, y, z}` — 自身世界坐标
- `pe.getNavDistance(ch)` → `number` — 到目标距离（米）
- `pe.getNavRelativeAngle(ch)` → `number` — 方位角（度，0~360）

### 🚀 物理数据（需 Sable/物理结构）
| 方法 | 返回值 | 说明 |
|---|---|---|
| `getPhysicsPos(ch)` | `{x, y, z}` | 世界坐标（m）|
| `getPhysicsOrientation(ch)` | `{x, y, z, w}` | 旋转四元数 |
| `getPhysicsMass(ch)` | `number` | 质量（kg）|
| `getPhysicsCenterOfMass(ch)` | `{x, y, z}` | 质心世界坐标 |
| `getPhysicsGravityForce(ch)` | `number` | 重力（N）|

> 速度类方法需传感器附着在 `simulated:velocity_sensor` 上
| 方法 | 返回值 | 说明 |
|---|---|---|
| `getPhysicsVelocity(ch)` | `{x, y, z}` | 地面速度（m/s）|
| `getPhysicsAirVelocity(ch)` | `{x, y, z}` | 空速，已减风速（m/s）|
| `getPhysicsAngularVelocity(ch)` | `{x, y, z}` | 角速度（rad/s）|

### 📶 无线红石
- `pe.setRedstoneOutput(ch, 0-15)` — 无线红石发送 mainThread = true
- `pe.getRedstoneOutput(ch)` — 读取发送信号
- `pe.getRedstoneInput(ch)` — 读取输入红石信号

### 🔌 外设代理
- `pe.getPeripheral(ch)` — 无线访问附着方块的 CC:T 外设方法

### 📻 红石收发器
- 基于频道的 Create Redstone Link 集成
- `receiver.setRedstoneSignal(频道, 0-15)` — 向 Create 网络发送信号 mainThread = true
- `receiver.getRedstoneSignal(频道)` — 读取 Create 网络信号

### 🏗️ 区块加载与物理体加载

传感器支持保持目标方块所在区域不被卸载：

| 模式 | 说明 | 适用场景 |
|---|---|---|
| 关闭 | 不加载 | 近距离使用 |
| **加载区块** | 通过原版 `setChunkForced` 加载传感器所在区块 | 防止方块所在区块被卸载 |
| **加载物理体** | 向 Sable 注册 force-load ticket + PORTAL ticket，随物理结构移动 | 防止飞行器/物理结构被 Sable 距离优化卸载 |

> 加载物理体模式会自动追踪物理结构的移动，将 PORTAL ticket 动态移动到物理体当前所在区块。每 5 秒刷新一次轴承连接链。

配置项（`config/ccpe-common.toml`）：
- `sensorChunkLoadEnabled` — 是否允许区块加载
- `sensorMaxForceLoad` — 最大同时加载传感器数量
- `sensorPortalTicketRadius` — PORTAL ticket 半径

## 快速上手

```lua
local pe = require("ccpe.pe")

-- 读取箱子物品
local items = pe.getAll(1)
for k, v in pairs(items) do print(k, v) end

-- 导航制导 需要外设扩展器附着在导航台上
local target = pe.getNavTargetPos(2)
local dist   = pe.getNavDistance(2)
local angle  = pe.getNavRelativeAngle(2)
print(string.format("目标：%.0f 米外，方位 %.1f°", dist, angle))

-- 物理数据
local mass = pe.getPhysicsMass(3)
local com  = pe.getPhysicsCenterOfMass(3)
print(string.format("质量：%.1f kg，质心：%.1f, %.1f, %.1f", mass, com.x, com.y, com.z))

-- 无线红石
pe.setRedstoneOutput(4, 15)  -- 向频道 4 的 外设扩展器 充能
```

## 要求

- **NeoForge** 1.21.1
- **CC:Tweaked** 1.118.0+
- **Create** 6.0.10+
- **Simulated**（Create: Aeronautics）1.3.0+
- **Sable** 2.0.3+

## 性能

读取操作约 0.02ms/次，红石输出操作约 50ms/次。

