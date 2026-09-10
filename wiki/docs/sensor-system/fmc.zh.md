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

**`θ`（气流角，度）**——缩放气流项 `V × sin(θ) / (S^0.5 × A)`：螺旋桨穿过自身所推空气所需的额外转速。按机型取用：

- **固定翼飞机平飞：`θ = 90`**——气流穿过桨盘，气流项全额生效（sin 90° = 1）；
- **直升机垂直起降：`θ = 0`**——无气流项。

默认 `0`。

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

## 风帆气动工具

同为 FMC 门控（因此装 AIC 也满足），传感器系统提供两个纯数学工具，围绕游戏气动模型的两条方程——**升力**方程与**无方向阻力**方程——做设计计算（配平、巡航速度估算、失速/最低气压分析），无需实时飞行数据：

```
升力：      L = k3 × P × |V| = 0.475 × P × |V|          （仅 Create 普通帆）
无方向阻力： D = k2 × P × |V| = 0.06888202261 × P × |V|  （普通帆与对称帆共用）
```

公式镜像 Sable 的 `BlockSubLevelLiftProvider.sable$contributeLiftAndDrag()`（每个物理子步、每块帆计算一次）。两个工具都固定 **n·v = 0** 条件——气流速度与帆面法向垂直，即**无法向速度分量**（平飞）。该条件下法向阻力恒为 0，输出只剩升力 + 无方向阻力：

| 方法 | 返回 | 说明 |
|---|---|---|
| `solveSailLift(P, V, L?)` | number / nil | 升力方程（Create `SailBlock`）：三个量传任意两个、缺失的传 `nil`，返回第三个 |
| `solveSailDirectionlessDrag(P, V, D?)` | number / nil | 无方向阻力方程（普通帆 / 对称帆通用）：同上 |

### 参数约定：三个量传任意两个，缺失的传 nil

两个方法都是「已知方程中任意两个量、求解剩下一个」的统一求解器。三个参数分别对应方程里的气压 P、速度 V 与输出量（升力 L / 阻力 D），**传任意两个，缺失的那个传 `nil`**，返回缺失量：

```lua
-- 升力方程 L = 0.475 × P × |V|
ss.solveSailLift(P, V, nil)   -- → 升力 L（正向）
ss.solveSailLift(P, nil, L)   -- → 速度 V = L/(0.475·P)     （平飞时 L = 重力 → 所需空速）
ss.solveSailLift(nil, V, L)   -- → 气压 P = L/(0.475·|V|)   （维持该升力的最低气压 → 最高可用高度）

-- 无方向阻力方程 D = 0.06888202261 × P × |V|（两种帆相同）
ss.solveSailDirectionlessDrag(P, V, nil)   -- → 阻力 D（正向）
ss.solveSailDirectionlessDrag(P, nil, D)   -- → 速度 V = D/(0.06888202261·P)
ss.solveSailDirectionlessDrag(nil, V, D)   -- → 气压 P = D/(0.06888202261·|V|)
```

- 只传 1 个量或 3 个量都传 → `nil`（欠定/超定）。
- **`P`** — 气压（大气压分数，海平面 = 1.0，与 `getPressure()` 同语义）；`P ≤ 0` → `nil`。
- **`V`** — 速度大小（m/s，与 `getSpeed()` 同单位）；负数按绝对值 `|V|` 处理；求解 P 时 `|V| = 0`（除零）→ `nil`。
- **`L` / `D`** — 力标量；负值无解 → `nil`。
- 门控与其余 FMC 工具相同（机体含约束链上必须有 ≥1 个 FMC，AIC 等同 FMC；电脑必须在物理体上），否则返回 `nil`。纯数学（`mainThread = false`），零主线程调度。

### 返回值

返回缺失的那个量，都是**每秒等效力标量**（力 / 速度 m/s / 气压分数）——与 substepsPerTick 配置无关，与游戏内图纸（冲量×60）同量纲，可与推力读数对比。不再返回每物理子步冲量。

### 计算公式

Create 普通帆（`SailBlock`，全默认参数）：

```
L = k3 × P × |V| = 0.475 × P × |V|
V = L / (0.475 × P)
P = L / (0.475 × |V|)
```

无方向阻力（普通帆与对称帆共用，k2 都未覆写）：

```
D = k2 × P × |V| = 0.06888202261 × P × |V|
V = D / (0.06888202261 × P)
P = D / (0.06888202261 × |V|)
```

Simulated 对称帆（`SymmetricSailBlock`：`k3 = 0`、`k1 = 1.75`）不产生升力，只有无方向阻力——由同一个 `solveSailDirectionlessDrag` 计算（k2 与普通帆相同）。

**k2 = 0.06888202261**（Sable 默认值，`(−0.75 + √(0.75² + 0.475²)) / 2`——恰好压住默认升力发散的最小阻尼）。法向阻力系数 **k1**（普通帆 0.75 / 对称帆 1.75）在本工具中不出现：它乘的是 `(n·v)`，而该工具条件 n·v = 0。n·v = 0 时升力也取该速度下的**最大值**（`|V − 法向阻力| = |V|`）；任何迎角/偏航分量都只会让它变小。

### 缓存了什么

系数（k2、k3）是写在 mod 里的常量，无静态缓存。门控仍每 tick 判定。

### 示例

```lua
local ss = require("ccpe.sensor_system")

-- 正向：P = 0.47、60 m/s 时普通帆的升力 + 无方向阻力（每秒力标量）
local lift = ss.solveSailLift(0.47, 60, nil)
local drag = ss.solveSailDirectionlessDrag(0.47, 60, nil)
print("lift (N): ", lift)   -- 0.475 × P × V
print("drag (N): ", drag)   -- 0.06888202261 × P × V

-- 反解：机翼需要 13.3 N 升力（≈ 一架小飞机的重力），P = 0.47 时所需空速
local v = ss.solveSailLift(0.47, nil, 13.3)
print("required speed (m/s): ", v)  -- 13.3 / (0.475 × 0.47)
```

### 多块帆的总升力：线性累加

Sable 对**每一块帆独立**计算同一条升力公式，逐帆累加进总冲量（`ServerSubLevel.prePhysicsTick()` 循环每块帆；`LiftProviderGroup` 分组只影响图纸/记录器的力箭头显示，不改变逐帆力）——**没有"帆越多、单帆越弱"的衰减**。因此在**无旋转（纯平移）、所有帆同朝向、同高度**的平飞下，总升力严格 = 帆数 × 单帆升力，工具乘以帆数成立。

偏离项（都来自"每块帆用自己的局部量"，而非帆间干扰）：

- **角速度**：每块帆用自己位置的局部气流 `v_local = V + ω×r`；旋转时离重心越远的帆局部气流越大、单帆升力越大，总升力 ≠ 帆数 ×（机体速度对应的单帆升力）。
- **帆朝向不一致**（上反角、舵面偏转）：升力是沿各自法线的矢量，方向不同则总矢量和 < 标量和；且 n·v ≠ 0 使单帆升力低于最大值。
- **气压逐帆取点**：P 在每块帆自己的方块中心取值，跨大高度差时略有差异（通常可忽略）。

## 通用阻力工具

### 乌云

![图纸力箭头](../img/diagram.png)

上图是 Contraption Diagram（图纸）的力箭头截图：**螺旋桨的推力明显远大于飞机整体受到的阻力**。若只有图纸上画出的这两个力，净力应为正、飞机应持续加速；但实测巡航速度却保持恒定（dv/dt ≈ 0），说明还存在一个**图纸未画出的未知力**在抵消多余的推力——它就是**通用阻力（universal drag）**：

- **方向**：与速度方向相反；
- **大小**：正比于质量与速度（F = −m·d·v）。

它由 Rapier 对每个 sublevel 刚体施加的恒定速度阻尼产生，**不经过任何力组**，所以图纸与飞行记录器 CSV 都看不到它——这正是"图纸净力 ≠ 实际净力"的原因，也是本工具存在的意义。

---

同为 FMC 门控（因此装 AIC 也满足），传感器系统提供一个纯数学工具：计算**通用阻力（速度阻尼）的等效力**——Rapier 对每个 sublevel 刚体施加的恒定速度阻尼。它不经过任何力组，图纸与飞行记录器 CSV 都看不到它；本工具让它在设计计算中可用（净力平衡：推力 − 帆阻力 − 通用阻力 ≈ 0）。

公式对应每子步阻尼 `v ← v/(1+d·Δt)` 的连续近似：

```
dv/dt = −d·v  →  等效力 F = −m·d·v   （大小 = m × d × |V|）
```

| 方法 | 返回 | 说明 |
|---|---|---|
| `getUniversalDragForce(m, V)` | number / nil | 通用阻力等效力标量 = `m × d × |V|` |

参数与约定：

- **`m`** — 质量（kg，与 `getPhysicsMass()`/`getPhysicsChainMass()` 同单位）；`m ≤ 0` → `nil`。
- **`V`** — 速度大小（m/s，与 `getSpeed()` 同单位）；负数按绝对值 `|V|` 处理。
- **`d`** — 通用阻力系数，**默认 0.09**（Sable `DimensionPhysics.DEFAULT_UNIVERSAL_DRAG`），可被维度数据包 `dimension_physics` 的 `"universal_drag"` 字段覆盖。
- 门控与其余 FMC 工具相同（机体含约束链上必须有 ≥1 个 FMC，AIC 等同 FMC；电脑必须在物理体上），否则返回 `nil`。纯数学（`mainThread = false`），零主线程调度。

与帆工具不同，返回值是单个"每秒力"（连续近似已按秒归一，不涉及子步 Δt）——它**不随气压缩放**，只与质量、速度成正比。

### 缓存了什么

系数 **`d`** 在**进游戏（服务器启动）**与**放置/加载 FMC 或 AIC（`onLoad`）时缓存一次**，与大气曲线快照同款策略；读不到时保留 Sable 默认（0.09）。门控仍每 tick 判定。

### 示例

```lua
local ss = require("ccpe.sensor_system")

-- 当前质量与速度下的通用阻力等效力（d 默认 0.09）
local drag = ss.getUniversalDragForce(ss.getPhysicsChainMass(), 60)
print("通用阻力 (N):", drag)   -- m × 0.09 × V
```

> `m` 代入 `getPhysicsMass()`/`getPhysicsChainMass()`、`V` 代入 `getSpeed()`（或 `|v|`）即可闭合全机力平衡：巡航时 `推力 − 帆阻力 − 通用阻力 ≈ 0`。可用记录值复核（如 m ≈ 45.25 kg、v ≈ 62.6 m/s → F ≈ 255 N）。

## 最高巡航高度求解工具

同为 FMC 门控（因此装 AIC 也满足），这个纯数学求解器反解巡航方程组：给定飞机质量、帆数与螺旋桨配置，求在给定**最大转速**下能达到的**最高稳态高度**与所需空速。

对未知量 `(v, P)` 求解两个稳态巡航方程（平飞、气流与帆面法向垂直故法向阻力为 0）：

```
(1) 升力 = 重力:   k3·P·v·N_w                          = m·g
                   → x = P·v = m·g/(k3·N_w)            （与高度无关，直接钉死）
(2) 推力 = 阻力:   P·S^1.5·N_p·T·R·(1 − v/(S^0.5·R·A))  = k2·P·v·N_s + m·d·v
```

| 方法 | 返回 | 说明 |
|---|---|---|
| `getMaxAltitude(m, wingSails, symmetricSails, propellerCount, sailsPerPropeller, maxRpm)` | table / nil | `{velocity=..., altitude=...}`——`maxRpm` 下的稳态巡航状态 |

代入 `v = x/P` 后方程 (2) 化为关于 **P 的一元二次**——无需矩阵（方程组对 `(v, P)` 双线性，消元后恰为一元二次）：

```
a·P² + b·P + c = 0
a = S^1.5·N_p·T·R
b = −(S·N_p·T·x/A + k2·x·N_s)      （S^1.5/S^0.5 = S、R 消去）
c = −m·d·x
P* = (−b + √(b²−4ac))/(2a)，v* = x/P*，高度 = 气压曲线反解(P*)（二分）
```

- **`N_w`** = `wingSails`——升力帆数（只有普通帆产生升力）。**`N_s`** = `wingSails + symmetricSails + propellerCount × sailsPerPropeller`——**总动力方块数**（风帆 + 对称风帆 + 螺旋桨动力方块），全部计入无方向阻力 `k2·P·v`。
- **推力模型**带气流削减系数 `(1 − v/(S^0.5·R·A))`——即 `getPropellerRPM` 模型在固定翼平飞（`θ = 90`）下的形式：桨面有最大有效速度 `S^0.5·R·A`，超过它推力转负。
- **系数来源**（与其余工具相同）：`k3 = 0.475`、`k2 = 0.06888202261`、`g = 11`、`d = 0.09`（通用阻力，数据包可覆盖）、`T = 0.2` / `A = 0.05`（aeronautics 配置）、维度大气曲线——均在进游戏与放置/加载 FMC/AIC 时缓存。
- **可行性**：解出的 `P*` 超出维度大气值域（贴地也升力不足 / 高于大气顶）、门控不满足（无 FMC）或参数非法（`m ≤ 0`、`wingSails < 1`、`propellerCount < 1`、`sailsPerPropeller < 1`、`maxRpm ≤ 0`）时返回 `nil`。`maxRpm = 256` 只是转速上限，实际还受应力网络容量（`getStressRemaining()`）约束。

### 示例

```lua
local ss = require("ccpe.sensor_system")

-- 给定装配在最大转速下的最高稳态巡航
local cruise = ss.getMaxAltitude(45.25, 43, 2, 2, 4, 256)
print("巡航空速 (m/s):", cruise.velocity)
print("最高高度 (Y):  ", cruise.altitude)
```

> `m` 用 `getPhysicsChainMass()`、帆数/桨数用你装配的真实方块数、转速上限用 256；结果与飞行记录器数据对照验证。
