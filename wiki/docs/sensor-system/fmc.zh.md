# 飞行管理计算机 (FMC)

![fmc](../img/fmc_item.png)

>飞行管理系统（FMS）的一项核心能力，是利用飞行数据**实时估算**飞机当前的质量与重心，而不是只依赖机组预输入的数字。

>- **质量估算**：平飞时升力必须等于重力。FMS 通过迎角、俯仰配平与推力等实际飞行响应反推当前质量——飞机越重，维持同一航迹所需的迎角或推力就越大。
>- **重心估算**：重心位置决定飞机所需的配平状态，FMS 从升降舵/平尾的配平位置反推重心；两者结合即可估算全机质量与重心。
>- **持续修正**：起飞前机组输入起飞重量（燃油 + 业载），飞行中 FMS 用燃油流量与实测飞行特性不断修正估算值。

>空客会把实时估算的总重（GW）与重心（CG）显示在 MCDU 性能页上，用来动态更新最优巡航高度、燃油预测与进近速度，飞行员无需精确输入重量；波音的 FMS 也有类似的重量估算机制。

## FMC 门控
物理体上必须装有 **≥1 个 FMC**（`ccpe:fmc`），否则以下方法全部返回 `nil`。不在物理体上或底层物理数据不可用时同样返回 `nil`。

| 方法 | 返回 | 说明 |
|---|---|---|
| `getPhysicsCenterOfMassRel()` | table / nil | 重心**相对最后放置的 FMC 的方块中心**（AIC 等同 FMC）的机体局部系位置 `{x, y, z}` |
| `getPhysicsChainCenterOfMassRel()` | table / nil | 整条物理体链的**总质心**相对**最后放置的 FMC 的方块中心**（AIC 等同 FMC）的机体局部系位置 `{x, y, z}` |
| `getPhysicsMass()` | number / nil | 电脑所在物理体的质量（kg） |
| `getPhysicsChainMass()` | number / nil | 物理体**含全部约束链**的总质量（kg） |
| `getPhysicsGravityForce()` | number / nil | 所在物理体的重力（pN = 质量 × 11） |
| `getPhysicsChainGravityForce()` | number / nil | 整条物理体链的总重力（pN = 链总质量 × 11） |
| `getStressRemaining()` | number / nil | **最后放置的 FMC 的附着面方块**所在 Create 应力网络的**剩余应力**（su，过载时为负） |
| `getStressCapacity()` | number / nil | 该网络的总容量（su） |

## 重心语义

`getPhysicsCenterOfMassRel()` 返回重心相对**机体上最后放置的 FMC**（含约束链；多个 FMC/AIC 时取最后放置的那个）的**方块中心**的**机体局部系**（plot 帧）偏移：

```
重心相对 FMC 方块中心 = (重心相对物理体原点的偏移) − (FMC 方块中心相对物理体原点的偏移)
```

FMC 参考点 = 其 **`BlockPos`（角点）加半格（`+0.5`）**，即方块单元中心，不是方块角点。两个偏移都经过 Sable 转换 `plot − rotationPoint`（与 `getSensors()` 的 `pos` 同一坐标系）——结果**不随物理体移动/旋转变化**，适合稳定地识别重心装在机体的哪个位置（比如离 FMC 中心前后/上下多远）。

!!! note "物理体原点 = 质心"
    Sable 运行时会把物理体原点（`rotationPoint`）与质心保持同步，因此上式第一项 ≈ 0，该值 ≈ **FMC 方块中心相对物理体原点的偏移取反**（即重心相对 FMC 方块中心的方位）。

- 需要世界系时，用 `getOrientation()` 把该向量旋转到世界。

### 链质心

`getPhysicsChainCenterOfMassRel()` 返回整条物理体链（含约束连接，如轴承；始终含电脑所在物理体）的**总质心**相对**机体上最后放置的 FMC**（含约束链；多个 FMC/AIC 时取最后放置的那个）的**方块中心**的机体局部系偏移：

```
链质心相对 FMC 方块中心 = (链质心相对电脑所在物理体原点的偏移) − (FMC 方块中心相对电脑所在物理体原点的偏移)
```

其中第一项 = 世界系按质量加权平均链上各物理体质心 Σ(mᵢ·comᵢ)/Σmᵢ，再经电脑所在物理体的 pose 逆变换转回其 plot 帧 − rotationPoint；第二项与 `getPhysicsCenterOfMassRel()` 同一参考点（FMC 的 `BlockPos` 角点 + 半格）。结果与 `getSensors()` 的 `pos` 同帧，不随物理体移动/旋转变化。

Sable 没有现成的链质心 API（`MergedMassTracker` 只合并单个物理体自身 + 其 plot 内 contraptions），该值由本 mod 在服务端每 tick 计算。门控与 `getPhysicsChainMass()` 相同（机体含约束链上 ≥1 个 FMC）。

## 重力

重力是**标量**（大小，方向向下）：

```
重力 (pN) = 质量 (kg) × 11
```

`getPhysicsGravityForce()` 用电脑所在物理体自身的质量；`getPhysicsChainGravityForce()` 用链总质量（见 `getPhysicsChainMass()`）。

## 附着方块应力网络

`getStressRemaining()` 与 `getStressCapacity()` 读取**最后放置的 FMC**（AIC 等同 FMC）所**贴着的方块**所在 Create 应力网络：

- **附着方块** — FMC 支撑面方向上的方块（FMC：由 blockstate 的 `FACE`/`FACING` 决定的支撑面；AIC：`FACING` 背面的方块）。附着方块必须是 Create **动力方块**（`KineticBlockEntity`，如齿轮箱、传动轴、螺旋桨轴承），否则两个方法都返回 `nil`。
- **`getStressCapacity()`** — 网络总容量（su）。
- **`getStressRemaining()`** — 剩余应力 = 总容量 − 当前总应力（su），网络**过载**时为负。

两个方法与其余 FMC 方法同门控（机体含约束链上必须有 ≥1 个 FMC、电脑必须在物理体上）；读数每 tick 刷新。

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

-- 重心相对最后放置的 FMC（机体局部系，旋转时不变）
local com = ss.getPhysicsCenterOfMassRel()
if com then
    print("重心相对FMC:", string.format("x=%.2f y=%.2f z=%.2f", com.x, com.y, com.z))
end

-- 最后放置的 FMC 的附着面方块所在 Create 应力网络（su）
print("应力容量 (su):    ", ss.getStressCapacity())
print("剩余应力 (su):    ", ss.getStressRemaining())
```


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

## 高度-气压换算工具

同为 FMC 门控（因此装 AIC 也满足），传感器系统提供两个**世界高度 Y** 与**气压**之间的纯换算工具，用的是与游戏物理（以及静压孔读数 `getPressure()`）完全相同的空气模型：

| 方法 | 返回 | 说明 |
|---|---|---|
| `getPressureFromAltitude(Y)` | number / nil | 世界高度 `Y` 处的气压（大气压分数，海平面 = 1.0） |
| `getAltitudeFromPressure(P)` | number / nil | 气压为 `P` 的世界高度 `Y`（上面方法的反函数） |

门控与其余 FMC 方法相同（机体含约束链上必须有 ≥1 个 FMC，AIC 等同 FMC；电脑必须在物理体上），否则返回 `nil`。两者都是**纯数学**（`mainThread = false`）：只读一份缓存的空气曲线快照，不在电脑线程上碰世界。

### 计算公式

游戏大气模型为 `P(Y) = basePressure × 高度曲线(Y)`，其中曲线是对维度数据包 `dimension_physics` 加载的锚点做的**分段三次 Hermite** 插值：

```
getPressureFromAltitude(Y) = basePressure × Hermite(锚点, Y)
```

主世界默认锚点（`basePressure = 1.0`，海平面 63）：

| 高度 Y | 值 | 斜率 |
|---|---|---|
| −38.37 | 1.5（地下钳位） | −0.006 |
| 63 | 1.0 | −0.004 |
| 263 | 0.4493 | −0.001797 |
| 280 | 0.4198 | −0.001679 |
| 320 | **0**（建筑高度上限） | −0.02099 |

−38 m ~ 280 m 之间它与简单指数 `P ≈ e^(−0.004·(Y − 63))` 数值上几乎一致；280 m 以上曲线向下弯向**建筑高度上限（Y=320）处的 0**，320 m 以上保持 0——主世界默认上限以上**没有空气**（无升力/阻力/推力）。曲线**不是解析可逆的**（分段三次），所以反向用数值二分：

```
getAltitudeFromPressure(P) = 在 [维度 minY, minY+logicalHeight] 上二分  →  使 P(Y) ≈ P 的 Y
```

二分单调、精度到双精度，两个方法**严格互逆**：`getAltitudeFromPressure(getPressureFromAltitude(Y)) ≈ Y`。高度基准 = **世界 Y**，与 `getAltitude()` 一致。

### 缓存了什么

为满足 `mainThread = false` 的线程安全，曲线参数被一次性复制进一份**静态 volatile 快照**：

- `basePressure`
- 锚点 `{高度, 值, 斜率}`（默认 5 组）
- 二分区间 `[minY, minY + logicalHeight]`

快照在**进游戏（服务器启动，用主世界）**和**放置/加载 FMC 或 AIC（`onLoad`）时刷新一次**，与螺旋桨配置缓存同款策略，**不逐 tick 读取**；门控（当前机体上有没有 FMC/AIC）仍每 tick 判定。

!!! note "缓存时效"
    改了 `dimension_physics` 数据包（`/reload`）后，需重新放置/加载一次 FMC 或 AIC（或重进世界）才会刷新快照。缓存是全局的（所有电脑共用一份曲线）：多个机体在不同维度时，取最后加载的那个维度的曲线。
