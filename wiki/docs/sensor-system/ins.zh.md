# 惯性导航系统（INS）

![ins](../img/ins_item.png)

>惯性导航（Inertial Navigation）完全自主——不依赖 GPS、地面站或任何外部信号：陀螺仪感知姿态与角速度，加速度计感知加速度，系统对加速度积分得到速度、再积分得到位置。

>正因为无需外部参考，惯导无法被干扰或欺骗，是核潜艇、导弹与航天器的核心导航手段；智能手机里的计步、手势识别用的也是同一原理（IMU）。

**惯性导航系统**（`ccpe:ins`）是装在物理体（Sable sub-level）上的姿态指示器方块。中心方块的红色一端永远指向北方。

## 姿态读数门控

以下方法都要求物理体（含约束链）上装有 **≥1 个 INS**（`ccpe:ins`），否则全部返回 `nil`：

| 方法 | 返回 | 说明 |
|---|---|---|
| `getAngles()` | table / nil | 机体姿态 `{pitch, roll, yaw}`（**度**，约定见下） |
| `getPosition()` | table / nil | **最后放置的 INS 方块**的世界坐标 `{x, y, z}` |
| `getBodyPosition()` | table / nil | **物理体原点**（枢轴/质心轴）的世界坐标 `{x, y, z}` |
| `getOrientation()` | table / nil | 机体姿态四元数 `{x, y, z, w}`（世界系） |
| `getAngularVelocity()` | table / nil | **机体局部系**角速率 `{x, y, z}`（rad/s，绕机体自身 X/Y/Z 轴；机体姿态恒等时 = 世界系） |
| `getAngleRates()` | table / nil | **姿态角速率** `{pitchRate, rollRate, yawRate}`（**deg/s**，pitch/roll/yaw 的导数；见下方「为什么用它」） |
| `getVelocity()` | table / nil | **世界系**线速度 `{x, y, z}`（m/s，**机体原点**平移速度，静止时严格 = 0） |

INS 也会出现在 `getSensors()` 中，条目为 `{type="ins", pos={x,y,z}, pos_rel={x,y,z}}`（无逐传感器读数——姿态用上面的专用方法读取）。

!!! note "角速度参考系"
    `getAngularVelocity()` 返回绕**机体自身轴**的角速率分量（滚转/俯仰/偏航率风格）：**世界系角速度**（Sable 每 tick 的 pose 姿态差分 `latestAngularVelocity`，×20 转 rad/s——静止时严格为 0，不同于会报幻影值的裸读物理 handle）经与 `getOrientation()` 同一 tick 的姿态四元数旋转到机体系得到。需要世界系角速度时，用该四元数对本结果做正向旋转（`q * ω_body`）即可恢复。

!!! note "为什么姿态角速率要用 getAngleRates() 而不是 getAngularVelocity()"
    `getAngularVelocity()` 的机体轴分量（尤其体 Z）**不能直接当滚转/俯仰速率用**。实测飞行日志（2026-09）发现两类问题：

    1. **机体轴分量会被世界旋转轴投影污染**：俯仰+偏航机动时，世界里的旋转轴会投影到已经抬头的体 Z 轴上，`av.z` 报出 ±30~45 °/s 的「假滚转」，而真实滚转只有 ~5 °/s（`av.z ≈ −偏航率·sin(俯仰角)`）。用它做滚转阻尼，会把「俯仰机动」误当成「滚转指令」，导致滚转大幅摆动。
    2. **Sable 的角速度源在剧烈机动时不精确**：`latestAngularVelocity`（世界 pose 姿态差分）与四元数序列的真实角速度在快速俯仰时偏差可达 0.5+ rad/s（验证：世界下向量在机体系 `ld` 的有限差分 ≠ `ld × ω_body`）。

    而 `getAngles()` 的姿态角本身与四元数**严格一致**（重算误差 0.00）。`getAngleRates()` 直接对姿态角做数值差分（最小角差处理 ±180° 回绕 + EMA 低通，时间常数 ~0.09 s @20Hz），得到的就是控制器真正需要的俯仰/滚转/偏航角速率——与 `getAngles()` 同符号体系，PD 控制里 **P 项用角度、D 项用它**，天然配对。**返回即用，无需在 Lua 侧再滤波。**

!!! note "线速度参考系与数据源"
    `getVelocity()` 返回**机体原点的世界系平移速度**（m/s）——Sable 每 tick 用世界 pose 位置差分 ×20 计算（`ServerSubLevel.latestLinearVelocity`），**机体静止时严格为 0**。它不是裸读物理 handle 的速度，也不是 `Sable.HELPER.getVelocity` 的点速度：后两者混入了物理 handle 的非世界系幻影值（世界静止的机体上仍报 ≈ −0.03 m/s、≈ 0.008 rad/s，飞行日志诊断列已证实）。需要机体局部系线速度（沿机体自身 X/Y/Z 轴）时，用 `getOrientation()` 姿态四元数的逆对本结果做旋转（`q⁻¹ * v`）即可。

## 角度约定

- **pitch** 俯仰——绕机体局部 X 轴，**正 = 抬头**。
- **roll** 滚转——绕机体局部 Z 轴，**正 = 右翼下压**（右倾）。
- **yaw** 航向——**0 = 机体局部 −Z 指向世界北**；**正 = 右转**（从上往下看顺时针）；范围 −180..180。稳态下等于 INS 指北标记的读数。

!!! note "万向锁局限"
    pitch/roll 由重力向量投影得出（与 `simulated:gimbal_sensor` 同算法）。接近垂直姿态（±90° 俯仰）时分解退化——与真实姿态指示器的局限相同。

## 位置语义

- `getPosition()` —— **INS 方块本身**在世界中的位置（plot 坐标经 Sable 物理体变换投影到世界）。随物理体移动/旋转实时变化。
- `getBodyPosition()` —— **整个物理体原点**的位置（物理系统使用的枢轴）。两者通常接近但不相等，因为 INS 方块一般装在偏离原点的地方。

## 示例

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("电脑不在物理体上")
end

-- 姿态（度）
local a = ss.getAngles()
if a then
    print(string.format("pitch=%.1f roll=%.1f yaw=%.1f", a.pitch, a.roll, a.yaw))
end

-- INS 方块位置 vs 物理体原点（世界坐标）
print("ins pos:   ", textutils.serialize(ss.getPosition()))
print("body origin:", textutils.serialize(ss.getBodyPosition()))

-- 姿态四元数 {x,y,z,w}
print("quaternion:", textutils.serialize(ss.getOrientation()))

-- 角速率（rad/s，机体局部系，见上方说明）
print("ang vel:   ", textutils.serialize(ss.getAngularVelocity()))

-- 姿态角速率（deg/s，已滤波，PD 控制的 D 项用；见上方「为什么用它」）
print("angle rates:", textutils.serialize(ss.getAngleRates()))

-- 线速度（m/s，世界系，见上方说明）
print("velocity:  ", textutils.serialize(ss.getVelocity()))
```

共享方法（`isOnBody()`、`getBodyId()`、`getSensors()` 等）的行为见[静压孔](static-port.zh.md)页面。**需要 FMC 门控**的物理数据见[飞行管理计算机](fmc.zh.md)页面。
