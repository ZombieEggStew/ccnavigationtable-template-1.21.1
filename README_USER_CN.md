# CCPE — CC 外设扩展器

> 为 Create: Aeronautics 提供一些好用的 ComputerCraft: Tweaked 外设的 NeoForge 模组

## 包含内容

- **微型外设扩展器** — 无线读取任意方块的 NBT 数据；无线红石信号控制；远程外设连接
- **红石收发器** — 无线读取和发送 Create Redstone Link 信号
- **电子变速箱** — 专门为 CC:T 控制设计的转速控制器


## 可选联动内容
- **切换式油门台（需要Aeroworks）** — 四个切换式的拉杆


## 功能

### 📡 无线方块读数

CC:Tweaked 原版要求计算机紧贴目标方块才能访问外设。
虽然不少模组为各自的方块适配了外设接口，但并非所有方块都有适配。

CCPE 为 CC:Tweaked 添加了**外设扩展器**。将传感器贴在任意方块上，设置频道号，就能在 Lua 中无线读取该方块的数据——无需线缆，不限距离。

- 通过 `pe.get(频道, 路径)` 或 `pe.getAll(频道)` 读取 NBT 数据
- 路径语法：`"Items[0].Count"`、`"ForgeData.CustomName"` 等

数据在服务端每 tick（50ms）刷新一次，Lua 端读取约 0.02ms/次，适合高频监控场景。

```lua
local pe = require("ccpe.pe")
local data = pe.getAll(1)              -- 读取频道 1 方块的完整 NBT
local cnt  = pe.get(1, "Items[0].Count")  -- 读取指定路径的字段
```

### 🔌 外设代理
- `pe.getPeripheral(ch)` — 无线访问附着方块的 CC:T 外设方法

### 🧭 导航桌集成 （需要航空学的导航桌）
| 方法 | 返回值 | 说明 |
|---|---|---|
| `pe.getNavTargetPos(ch)` | `{x, y, z}` | 目标世界坐标 |
| `pe.getNavSelfPos(ch)` | `{x, y, z}` | 自身世界坐标 |
| `pe.getNavDistance(ch)` | `number` | 到目标距离（米） |
| `pe.getNavRelativeAngle(ch)` | `number` | 方位角（度，0~360） |

### 🚀 物理数据（需 Sable/物理结构）
| 方法 | 返回值 | 说明 |
|---|---|---|
| `getPhysicsPos(ch)` | `{x, y, z}` | 世界坐标（m）|
| `getPhysicsOrientation(ch)` | `{x, y, z, w}` | 旋转四元数 |
| `getPhysicsCenterOfMass(ch)` | `{x, y, z}` | 质心世界坐标 |
| `getPhysicsMass(ch)` | `number` | 质量（kg）|
| `getPhysicsChainMass(ch)` | `number` | 物理体链总质量（kg）|
| `getPhysicsGravityForce(ch)` | `number` | 重力（N）|
| `getPhysicsChainGravityForce(ch)` | `number` | 物理体链总重力（N）|

**速度类方法需传感器附着在 `速度传感器` 上**
| 方法 | 返回值 | 说明 |
|---|---|---|
| `getPhysicsVelocity(ch)` | `{x, y, z}` | 地面速度（m/s）|
| `getPhysicsAirVelocity(ch)` | `{x, y, z}` | 空速，已减风速（m/s）|
| `getPhysicsAngularVelocity(ch)` | `{x, y, z}` | 角速度（rad/s）|

### 📶 无线红石
- `pe.setRedstoneOutput(ch, 0-15)` — 无线红石发送 mainThread = true
- `pe.getRedstoneOutput(ch)` — 读取发送信号
- `pe.getRedstoneInput(ch)` — 读取输入红石信号


---


### 📻 红石收发器

使计算机可以直接读取和发送 Create Redstone Link 信号，不需要再在计算机旁边摆上一堆 create:redstone_link

每个收发器能够配置多个频道，每个频道绑定一个红石频率。Lua 端通过频道号操作：

```lua
local r = peripheral.find("ccpe:redstone_transceiver")

-- 读取频道 3 对应的 Create 红石网络信号
local signal = r.getRedstoneSignal(3)

-- 向频道 7 对应的 Create 网络发送满信号
r.setRedstoneSignal(7, 15)
```

| 方法 | 说明 |
|---|---|
| `getRedstoneSignal(频道)` | 读取指定频道绑定的 Create Redstone Link 信号（0-15） |
| `setRedstoneSignal(频道, 0-15)` | 向指定频道绑定的 Create 网络发送红石信号 `mainThread=true` |


---


### 🎛️ 电子变速箱

> **与 create:RotationSpeedController 有什么不同?**
使用机械动力的转速控制器作为外设执行 `getTargetSpeed()` 会触发 `RotationPropagator.handleRemoved()` 会级联清空整个下游子网络的 source，导致不符合预期的结果（比如在转速控制器的下游使用 aeroworks 的 stepper_servo，改变转速的同时激活步进电机，电机会乱转）。
而 simulated 的 analog_transmission 难以精细调节。

电子变速箱（type: `ccpe:transmission_peripheral`）是纯 CC:T 外设控制的 Create 动能变速器。**不接受红石信号**，只能通过 Lua 控制。可放置在动能网络中间，实时调节下游转速。

```lua
local t = peripheral.find("ccpe:transmission_peripheral")

-- 比率模式：下游 = 上游 × 比率
t.setRatio(0.5)   -- 下游降速至 50%
t.setRatio(3.0)   -- 下游加速至 3 倍（上限 256 RPM）

-- 目标模式：直接设定下游转速（0~256，保留两位小数）
t.setTargetSpeed(128.56)
print(t.getTargetSpeed())  -- 128.56

-- 查询当前状态
print(t.getRatio())
```

| 方法 | 说明 |
|---|---|
| `setRatio(ratio)` | 设置变速比（≥0），比例模式 `mainThread=true` |
| `getRatio()` | 获取当前变速比 |
| `setTargetSpeed(speed)` | 直接设定下游转速（0~256.00）`mainThread=true` |
| `getTargetSpeed()` | 获取目标转速 |


---


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


---


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
print(string.format("Target: %.0fm away, bearing %.1f°", dist, angle))

-- 物理数据
local mass = pe.getPhysicsMass(3)
local com  = pe.getPhysicsCenterOfMass(3)
print(string.format("Mass: %.1f kg, COM: %.1f, %.1f, %.1f", mass, com.x, com.y, com.z))

-- 无线红石
pe.setRedstoneOutput(4, 15)  -- 向频道 4 的 外设扩展器 充能

-- 机械动力 无线红石网络控制
local redstone = peripheral.find("ccpe:redstone_transceiver")

redstone.setRedstoneSignal(5, 15)  -- 向频道 5 的 Create 网络发送红石信号
redstone.getRedstoneSignal(3)  -- 读取频道 3 的 Create 网络红石信号

-- 电子变速箱 动能变速控制
local trans = peripheral.wrap("right")
trans.setTargetSpeed(200)  -- 设定下游转速 200 RPM
trans.setRatio(0.75)       -- 切换到比率模式：75% 输出
```

## 要求

- **NeoForge** 1.21.1
- **CC:Tweaked** 1.118.0+
- **Create** 6.0.10+
- **Simulated**（Create: Aeronautics）1.3.0+
- **Sable** 2.0.3+

## 性能

读取操作约 0.02ms/次，红石输出操作约 50ms/次。

