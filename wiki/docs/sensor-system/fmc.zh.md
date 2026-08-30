# 飞行管理计算机 (FMC)

![fmc](../img/fmc_item.png)

## FMC 门控
物理体上必须装有 **≥1 个 FMC**（`ccpe:fmc`），否则以下方法全部返回 `nil`。不在物理体上或底层物理数据不可用时同样返回 `nil`。

| 方法 | 返回 | 说明 |
|---|---|---|
| `getPhysicsCenterOfMassRel()` | table / nil | 重心**相对当前电脑**的机体局部系位置 `{x, y, z}` |
| `getPhysicsMass()` | number / nil | 电脑所在物理体的质量（kg） |
| `getPhysicsChainMass()` | number / nil | 物理体**含全部约束链**的总质量（kg） |
| `getPhysicsGravityForce()` | number / nil | 所在物理体的重力（pN = 质量 × 11） |
| `getPhysicsChainGravityForce()` | number / nil | 整条物理体链的总重力（pN = 链总质量 × 11） |

## 重心语义

`getPhysicsCenterOfMassRel()` 返回重心相对电脑的**机体局部系**（plot 帧）偏移：

```
重心相对电脑 = (重心相对物理体原点的偏移) − (电脑相对物理体原点的偏移)
```

- 与 `getSensors()` 的 `pos_rel` 同一坐标系——**不随物理体移动/旋转变化**，适合稳定地识别重心装在机体的哪个位置（比如离驾驶舱前后/上下多远）。
- 需要世界系时，用 `getOrientation()` 把该向量旋转到世界。

## 重力

重力是**标量**（大小，方向向下）：

```
重力 (pN) = 质量 (kg) × 11
```

`getPhysicsGravityForce()` 用电脑所在物理体自身的质量；`getPhysicsChainGravityForce()` 用链总质量（见 `getPhysicsChainMass()`）。

## 示例

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("电脑不在物理体上")
end

print("质量 (kg):        ", ss.getPhysicsMass())
print("链总质量 (kg):    ", ss.getPhysicsChainMass())
print("重力 (pN):        ", ss.getPhysicsGravityForce())
print("链总重力 (pN):    ", ss.getPhysicsChainGravityForce())

-- 重心相对当前电脑（机体局部系，旋转时不变）
local com = ss.getPhysicsCenterOfMassRel()
if com then
    print("重心相对电脑:", string.format("x=%.2f y=%.2f z=%.2f", com.x, com.y, com.z))
end
```

这些方法**需要物理体上装有 FMC（`ccpe:fmc`）** 才能读取；需要 INS 门控的姿态/物理方法见[惯性导航系统](ins.zh.md)页面。

## 螺旋桨转速工具

FMC 还提供螺旋桨转速求解工具（同为 FMC 门控）：根据期望推力与当前飞行状态，反解出螺旋桨（Propeller Bearing）应输出的转速。该工具依赖 aeronautics 的螺旋桨物理配置。

### initPropeller(N, S)

使用前必须先初始化一次：

```lua
-- N = 螺旋桨（Propeller Bearing）数量
-- S = 每个螺旋桨上动力方块的数量（风帆 / 对称风帆 / 羊毛方块）
local ok = ss.initPropeller(N, S)
```

| 参数 | 说明 |
|---|---|
| `N` | 螺旋桨数量（≥ 1） |
| `S` | 每个螺旋桨上的动力方块数量（≥ 1） |

返回 `true` 表示成功；**机体（含约束链）上没有 FMC（门控不满足）或参数非法时返回 `false`**。

### getPropellerRPM(F, P, V, θ?)

```lua
-- F = 期望推力；P = 气压（海平面 = 1.0）；V = 速度（m/s）
-- θ = 螺旋桨平面与速度方向的夹角（度，可选，默认 0）
local rpm = ss.getPropellerRPM(F, P, V, thetaDeg)
```

公式（由 aeronautics 推力/气流模型反解）：

```
R = F / (P × S^1.5 × N × T) + V × sin(θ) / (S^0.5 × A)
```

其中 **T**（Propeller Bearing Thrust，默认 0.2）与 **A**（Propeller Bearing Airflow，默认 0.05）来自 aeronautics 配置（`aeronautics > server > Physics`）。配置在**进游戏（服务器启动）时与放置/加载 FMC 时缓存一次**（静态缓存，不逐 tick 读取）——游戏中修改配置后，需要重进世界或重新放置一次 FMC 才生效。

返回所需转速 R；未 init、门控不满足（无 FMC）或参数非法（如 `P ≤ 0`）返回 `nil`。

> 气压可用 `getPressure()`（静压孔读数）、速度可用 `getSpeed()`/`getAverageSpeed()`（皮托管读数）直接代入，组合成推力闭环控制。
