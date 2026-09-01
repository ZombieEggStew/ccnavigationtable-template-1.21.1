# 传感器系统

> 面向物理体的航空传感器

**传感器系统**是一组可安装在物理体（Sable sub-level）上的航电方块。装到物理体上之后，**同一物理体**（含约束链）上的 CC:Tweaked 电脑即可通过同一个 Lua 模块读取传感器数据：`require("ccpe.sensor_system")`。

所有数据都是**物理体作用域**的：电脑只能看到**自己所在物理体**（含约束链）上安装的传感器，多架飞行器可以各自独立安装仪表而互不干扰。

## 传感器方块

| 方块 | ID | 测量内容 | 门控条件 |
|---|---|---|---|
| [静压孔](static-port.zh.md) | `ccpe:static_port` | 静压孔自身位置的气压与高度 | ≥ 1 个静压孔 |
| [皮托管](pitot-tube.zh.md) | `ccpe:pitot_tube` | 沿管口轴线的**带符号**地速与空速 | ≥ 1 个皮托管 **且** ≥ 1 个静压孔（皮托静压门控） |
| [惯性导航系统](ins.zh.md) | `ccpe:ins` | 姿态（俯仰/滚转/偏航）、位置、姿态四元数、角速度 | ≥ 1 个 INS |
| [飞行管理计算机](fmc.zh.md) | `ccpe:fmc` | 质量、重力、重心、附着方块的 Create 应力网络、螺旋桨转速求解器 | ≥ 1 个 FMC |
| [航空集成计算机](aic.zh.md) | `ccpe:aic` | 同时充当 **INS 与 FMC** | — |
| [短程信号链接器](short-range-linker.zh.md) | `ccpe:short_range_linker` | 物理体作用域外设频道 + 红石输入/输出 | 处于物理体上 |

## 门控机制

每类传感器都要求物理体（含约束链）上装有 **≥ 1 个**对应方块。门控不满足时，相关方法返回 `nil`（`getSensors()` 中对应条目的读数也为 `nil`）：

- 静压读数需要 ≥ 1 个**静压孔**。
- 速度读数需要完整的**皮托静压系统**——≥ 1 个**皮托管且 ≥ 1 个静压孔**。
- 姿态读数需要 ≥ 1 个 **INS**。
- 物理数据读数需要 ≥ 1 个 **FMC**。
- **AIC** 同时充当 INS **和** FMC，一个方块即可解锁两类读数。

## Lua 模块

```lua
local ss = require("ccpe.sensor_system")
```

所有传感器方块共用：

| 方法 | 返回 | 说明 |
|---|---|---|
| `isOnBody()` | boolean | 电脑是否位于物理体上 |
| `getBodyId()` | string / nil | 所在物理体的 UUID |
| `getSensors()` | table | 物理体上所有传感器的同 tick 快照：`{type, pos={x,y,z}, pos_rel={x,y,z}, ...}`——`pos` 相对物理体原点，`pos_rel` 相对当前电脑 |

以及各方块专属方法（详见各页面）：

- **静压孔** — `getAltitude()`、`getPressure()`、`getAverageAltitude()`、`getAveragePressure()`、`getWeightedAltitude()`、`getWeightedPressure()`
- **皮托管** — `getSpeed()`、`getAirSpeed()`、`getAverageSpeed()`、`getAverageAirSpeed()`
- **INS** — `getAngles()`、`getPosition()`、`getBodyPosition()`、`getOrientation()`、`getAngularVelocity()`
- **FMC** — `getPhysicsCenterOfMassRel()`、`getPhysicsMass()`、`getPhysicsChainMass()`、`getPhysicsGravityForce()`、`getPhysicsChainGravityForce()`、`getStressRemaining()`、`getStressCapacity()`、`initPropeller(N, S)`、`getPropellerRPM(F, P, V, θ?)`
- **短程信号链接器** — `getPeripheral(channel)`、`getRedstoneOutput(channel)`、`getRedstoneInput(channel)`、`setRedstoneOutput(channel, signal)`

## 读数语义

- **逐传感器取点** — 读数取自**每个传感器方块自身的位置**，而非物理体原点（例如每个静压孔/皮托管都有各自独立的读数）。
- **每 tick 刷新** — 读数每 tick 最多刷新一次（最多滞后 1 tick）；Lua 读取**零主线程调度**，高频轮询几乎零成本。
- **`getSensors()` 快照** — 所有传感器在同一个 tick 读取，多传感器计算（如压差）保持一致。
- **最近放置** — `getSpeed()` / `getAltitude()` 等便捷方法返回**最后放置**的同类型方块的数据（注册顺序 = 放置顺序，仅当前会话有效）。服务器重启后目标可能变化——如需稳定读取**指定**传感器，请用 `getSensors()` 并按 `pos_rel` 识别。

## 快速示例

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("电脑不在物理体上")
end

print("bodyId:", ss.getBodyId())
print("alt:   ", ss.getAltitude(), "  pressure:", ss.getPressure())
print("speed: ", ss.getSpeed(), "  airspeed:", ss.getAirSpeed())

local a = ss.getAngles()
if a then
    print(string.format("pitch=%.1f roll=%.1f yaw=%.1f", a.pitch, a.roll, a.yaw))
end

-- 全部传感器，同一 tick 的一致快照
for i, s in ipairs(ss.getSensors()) do
    print(i, s.type, s.pos_rel.x, s.pos_rel.y, s.pos_rel.z)
end
```

## 页面索引

- [静压孔](static-port.zh.md) — 气压与高度
- [皮托管](pitot-tube.zh.md) — 速度与空速（皮托静压门控）
- [惯性导航系统](ins.zh.md) — 姿态与运动
- [飞行管理计算机](fmc.zh.md) — 物理数据、应力与螺旋桨求解器
- [航空集成计算机](aic.zh.md) — 一个方块同时充当 INS 与 FMC
- [短程信号链接器](short-range-linker.zh.md) — 物理体作用域频道与红石输入/输出
