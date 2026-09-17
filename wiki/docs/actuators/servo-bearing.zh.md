# 舵机轴承

![servo_bearing](../img/servo_bearing.png)
> 1.1.4 加入

真实的小型飞机没有助力系统：飞行员是**用手掰钢索**来控制副翼、升降舵和方向舵的。每个舵面都通过钢索与驾驶舱的操纵杆/脚蹬直接相连，你感受到的每一个操纵力都是直接拉在那根钢丝上。**舵机轴承**用机器替代了这些钢索：由 CC:Tweaked 计算机把连接的舵面精确地摆到指定角度——纯电气控制。

## 为什么要新的轴承？

本模组里已有两种轴承，各有各的设计思路——**舵机轴承**是第三种，专为「精确的 Lua 控制」而生。

| | `create:mechanical_bearing` | `simulated:swivel_bearing` | `ccpe:servo_bearing` |
|---|---|---|---|
| 动力输入 | Create 应力网络（转速） | 侧面齿轮啮合 | **无**——纯 Lua 目标角 |
| 从动结构 | 运动学 contraption 实体 | 真实物理体（Sable sub-level） | 运动学 contraption 实体 |
| 动力学 | 刚性、运动学、无惯性 | PD 伺服、有惯性、气动反馈 | 刚性、运动学、无惯性 |
| 角度来源 | `angle += speed` 累计 | 网络转速 / Lua 控制模式 | `setTargetAngle()` 最短路径 |
| 客户端渲染 | 指数追赶 → **末端爬行** | 物理驱动（精确） | 服务端角度 + 帧间插值 |
| 红石 | POWERED / 扳手 / movementMode | 锁定（powered） | 无 |

### `mechanical_bearing` 的渲染问题

`create:mechanical_bearing` 的**客户端**并不直接显示服务端角度。它维护一个每 tick 减半的 `clientAngleDiff`，指数**追赶**服务端值——于是每次转动到末尾时，最后几度会在视觉上**爬行**到位。这纯粹是**客户端视觉瑕疵**：服务端角度和物理都是精确的，渲染延迟**完全不影响物理计算**。

舵机轴承砍掉了整条追赶链（细节见下），视觉平滑、一步到位、无爬行。

## 舵机轴承

`ccpe:servo_bearing` —— 一个 Create 风格的轴承方块，**无动力输入**（`hasShaftTowards=false`，不会接入应力网络）、**无红石**。旋转角度完全由 CC:Tweaked Lua 以目标角舵机的方式驱动。

它复刻了 `create:mechanical_bearing` 的旋转逻辑（每 tick 推进角度、`applyRotation()` → `movedContraption.setAngle(angle)`），只是动力从应力网络换成了 Lua。与机械轴承一样，装配后的结构是运动学 **contraption 实体**——刚性、指哪打哪、无惯性；Sable 会将其识别为物理体内的运动学 contraption（质量并入母机）。

## Lua API

外设类型：`servo_bearing`（按方向 wrap，如 `peripheral.wrap("right")`，或 `peripheral.find("servo_bearing")`）。

| 方法 | 说明 |
|---|---|
| `assemble()` | 装配 FACING 方向的结构；返回是否成功 |
| `disassemble()` | 把结构拆回世界方块；返回是否成功 |
| `isAssembled()` | 当前是否已装配 |
| `setTargetAngle(角度)` | 沿**最短路径**定位到 `角度`（0–360）。需要先装配；未装配返回 `false` |
| `getTargetAngle()` | 当前目标角度（0–360） |
| `getAngle()` | 当前**实际**角度（0–360，服务端权威） |

```lua
local s = peripheral.wrap("right")

print(s.assemble())        -- 装配轴承前方的结构
s.setTargetAngle(90)       -- 转到 90°（最短路径）
s.setTargetAngle(-45)      -- 转回来
print(s.getAngle())        -- 45.0（正在朝 -45° 转）
```

## 行为说明

- **速度上限**：角度每 tick 至多推进 **18°（360°/s）**，并按剩余度数钳制，保证精确到位。
- **最短路径**：`setTargetAngle` 总是走较短的方向（如 350°→10° 走 +20°）。多圈目标（如 450°）会解析为 −270°。
- **服务端权威 + 平滑客户端**：客户端每 tick 同步服务端角度（`lazyTickRate=1`，滞后 ≤ 1 tick），用**帧间插值**渲染（`angleLerp(prevAngle, angle, partialTicks)`）而非原版的外推式——匀速平滑、约 50ms 内到位、**无爬行**。
- **装配**：空手右键切换装配；Lua `assemble()`/`disassemble()` 行为等价（同步调用）。在 Sable sub-level 内装配已验证可行。
- **无 plate 方块**：与 `aero_bearing` 不同，不需要连接 plate——contraption 实体直接承载结构。

## 与 `aero_bearing` 的对比

| | `aero_bearing` | 舵机轴承 |
|---|---|---|
| 驱动 | Sable 物理（RotaryConstraint PD 伺服） | Create contraption 实体（运动学） |
| 动力学 | 有惯性、气动反馈 | 刚性、角度精确、无惯性 |
| 动力 | 轴向应力输入（或 Lua 控制模式） | 无 |
| 适合 | 物理舵面 / 旋翼 | 精确角度控制 |

两者互补：需要气动真实反馈（风帆、旋翼）用**航空轴承**；需要舵面精确到达计算机指定角度时用**舵机轴承**。

## 已知限制

- `MAX_ANGULAR_SPEED`（18°/tick）目前硬编码，计划后续加 Config 选项。
- 未装配时调用 `setTargetAngle` 返回 `false`。
- 装配失败原因已内部记录，但尚未暴露到 Lua。
- 连续转动模式（`setAngularSpeed(rpm)`）尚未实现。
