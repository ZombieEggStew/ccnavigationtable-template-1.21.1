# 矢量喷管

![advanced_thrust_nozzle](../img//advanced_thrust_nozzle.png)

> 装在电扇出风面上、可在 **15° 锥角内任意指向**的喷口

**矢量喷管**（`ccpe:advanced_thrust_nozzle`）是定向喷管的加强版。安装方式和定向喷管完全一样——装在 `create:encased_fan` 的出风面上，力仍然由风扇转速决定；区别在于**喷口本体可以绕底部枢轴倾斜**，于是推力沿**倾斜后的轴向**输出，一台喷管就能把飞行器往锥内的任意方向推。

倾斜由 CC:Tweaked Lua 驱动，提供**两套坐标系、三种写法**：**极坐标**（方位角 + 锥角）、**摇杆**（`x`/`y` = `-1..1`），以及只关心「往哪推」时最省事的**推力语义**（`x`/`y`/`z` = 期望推力方向）。三种写法都**不会瞬移**：喷口在偏转平面里**沿直线**恒速转到新方向，渲染、推力、排气/进气/尾迹粒子四者同时渐变。

> ⚠️ 极坐标与摇杆的参数描述的是**模型帧里喷口往哪偏**（也就是排气往哪偏），**不是推力方向**——推力是它的反向，而且两个横向分量的符号**不同**。控制程序请直接用 `setThrustDirection(x, y, z)`，原因见下方「坐标系与摇杆 ≠ 推力」一节。

## 与定向喷管的区别

| | 定向喷管 `ccpe:thrust_nozzle` | 矢量喷管 `ccpe:advanced_thrust_nozzle` |
|---|---|---|
| 推力方向 | 只能沿 FACING | FACING 周围 **15° 锥**内任意方向 |
| 每 RPM 出力 | 1 pN/RPM | **2 pN/RPM**（同一个风扇，出力翻倍） |
| Lua 控制 | 无（只受风扇转速影响） | `setDeflection` / `setDirection` / `setThrustDirection` / `setThrustScale` |
| 频道 | 无 | **物理体作用域频道**，Lua 可寻址 |
| 右键 GUI | 无 | 频道滚轮 |
| 排气 / 尾迹 / 染料 / 扳手 | ✅ | ✅ 全部继承，并且会跟着倾斜轴走 |

## 放置与安装

- 必须装在 `create:encased_fan` 的**出风面**那一格（与 `create:nozzle` / 定向喷管相同的 `canSurvive` 检查），朝向由背后电扇的出风面唯一决定。
- 风扇那一路的推力在装上喷管后**恒为 0**，总推力只来自喷管本身，不会叠加。
- **空手右键**打开频道 GUI；**手持染料右键**只把尾迹粒子染成该染料的颜色（护目镜 tooltip 会显示当前颜色）。
- **扳手潜行右键**快速拆除（掉落直接进背包）；普通右键扳手**不旋转**——喷管朝向只由电扇决定，转 90° 基本不会是合法朝向。

## 频道与寻址

频道号是**喷管在物理体内的地址**，与[短程信号链接器](../sensor-system/short-range-linker.md)、控制台**共享同一套频道空间**（同一物理体内一个频道号 1:1 对应一台设备）。

- 放置时**自动分配**链内最小空闲号；同链抢同一频道时**后注册者顺延**。
- **只在物理体（Sable 子次元，含约束链）上注册**：放在地上的喷管不注册，`getChannel()` 返回 `-1`，GUI 显示「需放置在物理体上」。
- 每 20 tick 复核一次——轴承连/断导致约束链变化（频道空间合并/拆分）时自动顺延。
- 离开物理体（拆到地上）后频道复位为 `-1`，重新装上会重新分配。
- 与链接器不同，喷管 GUI **没有「加载物理体」开关**（喷管不持有 force-load ticket）。

Lua 侧用 `ccpe.sensor_system` 同一模块寻址：

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)        -- 频道 0 的喷管
print(n.getChannel(), n.getMaxTilt()) -- 0  15
```

## Lua API

外设类型：`advanced_thrust_nozzle`（用 `ccpe.sensor_system.getPeripheral(频道)` 按频道取）。

| 方法 | 返回 | 说明 |
|---|---|---|
| `setDeflection(方位角, 锥角)` | boolean | **极坐标写法**：方位角绕 FACING 轴（0..360，负值自动归正），锥角自动钳到 `0..15`。只写目标，喷口沿直线渐变过去。非有限值返回 `false` |
| `setDirection(x, y)` | boolean | **摇杆写法**：`x`/`y` ∈ `-1..1`，**方向**=方位角、**长度**按圆形映射=锥角（长度 0 = 直出、1 = 满锥角 15°）。输入先各自钳 `-1..1`、再整体钳进单位圆（斜推 `(1,1)` 等价 45° 方向满锥角）。同样只写目标、走直线 |
| `getDeflection()` | table | `{azimuth, tilt, targetAzimuth, targetTilt, x, y, targetX, targetY}`：前者是此刻**实际**值（渐变中与目标不同），后者是目标值；方位已归正 0..360（点落在直出中心时方位无意义、报 0）；`x`/`y` 为实际偏转点（度，`锥角·(cos, sin) 方位`），可直接和 `setDirection` 对照 |
| `getMaxTilt()` | number | 锥角上限（当前 `15`），脚本做归一化用 |
| `isMoving()` | boolean | 是否还在渐变中（偏转或出力尚未到位） |
| `getThrustDirection(x, y)` | table / nil | **推力语义只读对照**：参数与 `setDirection` 完全同义，返回**等喷口走到位后**的推力方向（plot / 机体系单位向量 `{x, y, z}`）。结果已乘上当前出力符号（`setInverted(true)` 后同一摇杆报出来的方向整体反向）。用来**自检 / 标定**：把自己的期望方向和这里的真值对一下即可。报的是按**目标**偏转算的稳态值；非有限值 / 不在服务端返回 `nil` |
| `setThrustDirection(x, y, z)` | boolean | **推力语义写入（控制程序推荐用这个）**：传入「我想让机体被推向哪个方向」（plot / 机体系，不必归一化），内部反解出偏转。只用**垂直于 FACING 轴**的分量（对朝下的喷管：`(1,0,0)` = 向东、`(0,0,1)` = 向南），并自动吸收 FACING 的横向镜像、风扇转向与 `setInverted` ⇒ **换载具 / 换朝向不用重标定**。长度钳到 `0..1` 后映射锥角（1 = 满 15°）；轴向分量被忽略（锥角不能反转轴向推力，那要用 `setInverted`）；零向量返回 `false` |
| `getProfile()` | table / nil | **装配与符号快照**（排障 / 记录用）：`{facing, inverted, speed, axisStep, thrustSign, maxTilt}`。`facing` = 朝向字符串，`speed` = 背后电扇**带符号**转速（无气流源 / 未转 = 0），`axisStep` = FACING 轴向步进（`±1`），`thrustSign` = `κ = sign(axisStep · speed · inverted)`（冲量 = `−轴 · κ`，`speed = 0` 时按 `+1` 计） |
| `setThrustScale(系数)` | boolean | 出力系数 `0..1`（越界钳位），乘在「转速 × 气压」之上；**同时也决定排气 / 进气粒子**（见下方「出力与粒子」）；同样按运动曲线渐变 |
| `getThrustScale()` | number | 当前出力系数（`0..1`，渐变中是此刻实际值） |
| `setInverted(enabled)` | boolean | **出力反转（推力 ⇄ 吸力）**：`true` ⇒ 同一转速、同一出力系数下力沿轴的**符号整体翻过来**（原推力变吸力，负转速的吸力也反向）。**瞬间生效**、不做渐变；只翻符号，不动幅值与指向（大小仍由转速/气压/出力系数决定）；粒子同步换向（**排气 ⇄ 进气**）。状态进 NBT / 蓝图 |
| `isInverted()` | boolean | 出力是否已反转（`true` = 沿轴的力反向，推力变吸力方向） |
| `getChannel()` | number | 本喷管在物理体内的频道（`-1` = 不在物理体上 / 未分配） |
| `getForcePoint()` | table | 本喷嘴**施力点**相对物理体原点（质心枢轴）的局部坐标 `{x, y, z}`，用于力矩 `τ = r × F`。取点 = 方块中心（= Sable 实际施力点，与飞行记录仪 `propM*` 同源）；喷口外端 = 本点 + 倾斜轴 × 0.55 格，但位移与力共线 ⇒ 对力矩等价。坐标系与 `getSensors().pos` 同帧（那边是方块角点，本方法已 +0.5 取中心）；物理体移动/旋转时不变；不在物理体上返回 `nil` |

### 坐标系与摇杆 ≠ 推力

同一台喷管被两套坐标描述，**混用是这块方块最容易踩的坑**：

| 概念 | 帧 | 说明 |
|---|---|---|
| `setDeflection` 的方位角 / `setDirection` 的 `x, y` / `getDeflection().azimuth` | **模型帧** | 描述**喷口 / 排气**往哪偏。`FACING=down` 时模型 `+X` → plot `+X`、模型 `+Z` → plot **`−Z`**、模型 `+Y` → plot `−Y` |
| `setThrustDirection` 的 `x, y, z` / `getThrustDirection` 的返回值 | **plot / 机体系** | 描述**机体被推向哪**，与 `getForcePoint()` / `getSensors().pos` 同一帧 |

三件事叠在一起，使「摇杆」和「推力」不是简单取反：

1. **推力 = 排气方向的反向**——喷口把排气往东摆，机体被推向西；
2. `FACING=down` 的模型帧相对 plot 系**镜像**了一次：`x` 恰好同向、`z` 反向。**`x` 的文档碰巧在两个帧里都成立，这正是最容易误导人的地方**——以为 `y` 也一样就错了；
3. 出力符号 `κ = sign(axisStep · 风扇转速 · inverted)` 再整体翻一次。

净结果（`FACING=down`、负转速、未 inverted，即 `κ = +1`）下，plot 系的推力是：

```
推力 = (−摇杆 x, +摇杆 y)        -- 两个分量符号不同
```

这不是笔误：把轴翻过来必须用 180° 旋转，而它在垂直平面上的行列式恒为 −1（是**反射**而非旋转），所以「摇杆」与「推力」的横向映射**永远**含一个镜像，两个分量不可能同号。

> 因此手工反号**只在「四台全部朝下 + 同一套接线」时成立**，换载具 / 换朝向就会失效。`setThrustDirection(x, y, z)` 把这些符号全部搬进了 Java，控制代码不必再硬编码；拿不准时用 `getProfile()` 现场读真实符号，不要靠推理。

### 示例

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)

-- 极坐标写法：方位 90° + 锥角 15°（渐进去，约 0.19s）
n.setDeflection(90, 15)
n.setThrustScale(0.5)                  -- 出力减半（推力与排气/进气粒子同时减半，同样渐变）
print(n.getDeflection().tilt, n.getThrustScale())

-- 等它转到位
while n.isMoving() do sleep(0.05) end
print(n.getDeflection().azimuth, n.getDeflection().targetAzimuth) -- 两个相等

-- 摇杆写法：方向 = 方位、长度 = 锥角（走直线）
n.setDirection(1, 0)                   -- 朝模型 +X 满锥角（= setDeflection(0, 15)）
n.setDirection(0, -1)                  -- 朝模型 −Z 满锥角（= setDeflection(270, 15)）
n.setDirection(0.5, 0)                 -- 朝 +X、半锥角 7.5°
n.setDirection(0, 0)                   -- 回直出

-- 【推荐】推力语义：x / y / z = 我想被推向哪个方向（plot / 机体系，长度 0..1 映射锥角）
n.setThrustDirection(1, 0, 0)          -- 向东满推力（朝下喷管；不用管 FACING / inv / 转速）
n.setThrustDirection(0, 0, 1)          -- 向南满推力
n.setThrustDirection(0.1, 0, 0)        -- 向东 1.5°（0.1 = 1.5 / 15，小角度标定方便）
n.setThrustDirection(0, 1, 0)          -- 朝下喷管的轴向 ⇒ 直出（轴向分量被忽略）

-- 自检 / 标定：给定摇杆，看真实推力朝哪
local d = n.getThrustDirection(1, 0)   -- 朝下喷管 κ=+1 时应得 {x≈-0.259, y≈0.966, z≈0}
print(d.x, d.y, d.z)

-- 装配快照：排障先打这一行
local prof = n.getProfile()            -- {facing="down", inverted=false, speed=-256, axisStep=-1, thrustSign=1, maxTilt=15}
print(prof.facing, prof.speed, prof.thrustSign)

-- 出力反转（推力 ⇄ 吸力）：瞬间生效，排气同步切成进气
n.setInverted(true)                    -- 力沿轴反向
print(n.isInverted())                  -- true
n.setInverted(false)                   -- 恢复正方向

-- 施力点（机体系，力矩用）：物理体原点≈质心 ⇒ 可直接当力臂 r
local p = n.getForcePoint()            -- {x=…, y=…, z=…}；不在物理体上是 nil
-- 力矩 τ = r × F：F 是该喷管的推力向量（机体系，沿倾斜轴，符号由转速 / setInverted 决定）
```

`setDeflection` / `setDirection` 的方位角 0° 是**模型**的 **+X** 方向、90° 是 **+Z**（`(cos, sin)` 对应 `(x, y)`），整个坐标系随方块的 FACING 一起旋转；`setThrustDirection` / `getThrustDirection` 用的是 **plot / 机体系**（见上面「坐标系与摇杆 ≠ 推力」一节）。


## 推力模型

```
推力 = 风扇转速(RPM) × 气压 × 出力系数 × 2
```

- **矢量喷管满出力 = 2 pN/RPM**；气压为 `DimensionPhysicsData` 的海平面 1.0，随高度下降。
- 符号约定（六向统一）：**推力恒与气流方向相反**，而气流方向 = 倾斜轴 × `κ`（`κ = axisStep · 转速符号 · inverted`）。因为有 `axisStep` 补偿，所有装法都收敛到同一条规则：**竖直喷管恒为「正转速向下推、负转速向上推」**，与朝上装还是朝下装无关；水平喷管同理，正转速把机体推向该轴的负方向。`κ > 0` 出排气、`κ < 0` 出进气。拿不准时用 `getProfile()` 的 `axisStep` / `speed` / `thrustSign` 现场确认。
- 推力**不做遮挡判定**——喷口被堵照样出力（与定向喷管一致）。
- 出力系数**同时决定排气与进气粒子**（见下节），但**不影响高空尾迹**。
- `setInverted(true)` 只翻**符号**（推力 ⇄ 吸力）：幅值与其他参数一律不变，粒子同一步从排气切进气。

| 风扇转速 | 气压 | `setThrustScale` | 推力 |
|---|---|---|---|
| 256 RPM | 1.0 | 1.0 | **512 pN** |
| 256 RPM | 1.0 | 0.5 | 256 pN |
| 128 RPM | 1.0 | 1.0 | 256 pN |
| 256 RPM | 0.5 | 1.0 | 256 pN |


## 可选：手感参数在哪

| 想改的东西 | 常量 | 现值 |
|---|---|---|
| 偏转快慢（动画 + 真实推力同时） | `AdvancedThrustNozzleBlockEntity.DEFLECTION_DEG_PER_SECOND` | 160°/s（= 8°/tick） |
| 矢量喷管出力 | `AdvancedThrustNozzleBlockEntity.THRUST_OUTPUT_MULTIPLIER` | 2.0（2 pN/RPM） |
| 出力系数渐变速度 | `AdvancedThrustNozzleBlockEntity.THRUST_SCALE_PER_TICK` | 0.25/tick |
| 最大锥角 | `AdvancedThrustNozzleBlockEntity.MAX_TILT_DEG` | 15° |
| 倾斜枢轴 | `AdvancedThrustNozzleRenderer.PIVOT` | (8, 5, 8)px |
| 选择框 / 碰撞箱 | `AdvancedThrustNozzleBlock.BASE_SHAPE` | `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)` |
