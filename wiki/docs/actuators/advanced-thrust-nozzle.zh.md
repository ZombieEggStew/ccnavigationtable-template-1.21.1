# 矢量喷管

![advanced_thrust_nozzle](../img//advanced_thrust_nozzle.png)

> 装在电扇出风面上、可在 **15° 锥角内任意指向**的喷口

**矢量喷管**（`ccpe:advanced_thrust_nozzle`）是定向喷管的加强版。安装方式和定向喷管完全一样——装在 `create:encased_fan` 的出风面上，力仍然由风扇转速决定；区别在于**喷口本体可以绕底部枢轴倾斜**，于是推力沿**倾斜后的轴向**输出，一台喷管就能把飞行器往锥内的任意方向推。

倾斜由 CC:Tweaked Lua 驱动，提供两种写法：**极坐标**（方位角 + 锥角）和**摇杆**（`x`/`y` = `-1..1`）。两条命令都**不会瞬移**：喷口在偏转平面里**沿直线**恒速转到新方向，渲染、推力、排气/进气/尾迹粒子四者同时渐变。

## 与定向喷管的区别

| | 定向喷管 `ccpe:thrust_nozzle` | 矢量喷管 `ccpe:advanced_thrust_nozzle` |
|---|---|---|
| 推力方向 | 只能沿 FACING | FACING 周围 **15° 锥**内任意方向 |
| 每 RPM 出力 | 1 pN/RPM | **2 pN/RPM**（同一个风扇，出力翻倍） |
| Lua 控制 | 无（只受风扇转速影响） | `setDeflection` / `setDirection` / `setThrustScale` |
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
| `setThrustScale(系数)` | boolean | 出力系数 `0..1`（越界钳位），乘在「转速 × 气压」之上；**同时也决定排气 / 进气粒子**（见下方「出力与粒子」）；同样按运动曲线渐变 |
| `getThrustScale()` | number | 当前出力系数（`0..1`，渐变中是此刻实际值） |
| `getChannel()` | number | 本喷管在物理体内的频道（`-1` = 不在物理体上 / 未分配） |

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
```

方位角的 0° 是模型的 **+X** 方向，90° 是 **+Z** 方向（`(cos, sin)` 对应 `(x, y)`），整个坐标系随方块的 FACING 一起旋转。

## 推力模型

```
推力 = 风扇转速(RPM) × 气压 × 出力系数 × 2
```

- **矢量喷管满出力 = 2 pN/RPM**；气压为 `DimensionPhysicsData` 的海平面 1.0，随高度下降。
- 符号约定与定向喷管一致：**正转速 = 沿 FACING 方向喷气**（机体受反作用力），负转速 = 反向（吸气 / 反推）。
- 推力**不做遮挡判定**——喷口被堵照样出力（与定向喷管一致）。
- 出力系数**同时决定排气与进气粒子**（见下节），但**不影响高空尾迹**。

| 风扇转速 | 气压 | `setThrustScale` | 推力 |
|---|---|---|---|
| 256 RPM | 1.0 | 1.0 | **512 pN** |
| 256 RPM | 1.0 | 0.5 | 256 pN |
| 128 RPM | 1.0 | 1.0 | 256 pN |
| 256 RPM | 0.5 | 1.0 | 256 pN |

### 出力与粒子

出力系数直接充当「油门」的一部分：**有效油门 = 转速 × 出力系数**，所以排气与进气的**数量、排出速度、进气漏斗长度**会一起按系数缩放——手感和「把风扇转速调低一半」一致。

| 出力系数 | 推力 | 排气粒子 | 进气粒子 | 高空尾迹 |
|---|---|---|---|---|
| 1.0 | 2 pN/RPM × 转速 × 气压 | 满密度 / 满速度 | 满密度 / 漏斗 8 格 | 照旧 |
| 0.5 | 减半 | 数量与速度减半 | 数量减半 / 漏斗 4 格 | 照旧 |
| 0 | **0** | **完全不出** | **完全不出** | 照旧 |

- 粒子与推力走**同一条渐变曲线**（0.2s），不会出现「推力已经掉了还在满喷」的错位。
- **高空尾迹不受影响**——它按移动位移与高度发射，代表航迹上的冰晶，与出力强弱解耦；出力 0 时「没有排气但有尾迹」是有意为之。

## 可选：手感参数在哪

| 想改的东西 | 常量 | 现值 |
|---|---|---|
| 偏转快慢（动画 + 真实推力同时） | `AdvancedThrustNozzleBlockEntity.DEFLECTION_DEG_PER_SECOND` | 160°/s（= 8°/tick） |
| 矢量喷管出力 | `AdvancedThrustNozzleBlockEntity.THRUST_OUTPUT_MULTIPLIER` | 2.0（2 pN/RPM） |
| 出力系数渐变速度 | `AdvancedThrustNozzleBlockEntity.THRUST_SCALE_PER_TICK` | 0.25/tick |
| 最大锥角 | `AdvancedThrustNozzleBlockEntity.MAX_TILT_DEG` | 15° |
| 倾斜枢轴 | `AdvancedThrustNozzleRenderer.PIVOT` | (8, 5, 8)px |
| 选择框 / 碰撞箱 | `AdvancedThrustNozzleBlock.BASE_SHAPE` | `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)` |
