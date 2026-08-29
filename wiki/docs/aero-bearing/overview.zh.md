# 航空轴承

![aero_bearing](../img/my_bearing_item.png)
> 1.0.9 加入

**航空轴承** 是一个 Create 动能方块 + Sable 物理体的组合。动力从**背面半截传动杆**（轴向输入，同 `create:mechanical_bearing`）接入，装配后的结构成为**独立的物理体**（sub-level），由旋转约束驱动旋转——**不贯通应力**。

## 两种模式

| 模式 | 行为 |
|---|---|
| **应力驱动**（默认） | 输入转速按 tick 推进目标角（`convertToAngular`），从动物理体连续旋转。支持序列化角度输入（`create:sequenced_gearshift`、曲柄、阀门手柄等 `TURN_ANGLE`），**精确**转到指令角度。 |
| **Lua 控制** | 跳过「转速 × 时间 = 角度」的累计过程：旋转角度由 `setTargetAngle()` **直接设定**。应力网络仅保留应力消耗（impact 4.0，同 `simulated:swivel_bearing`）。通过 `setControlMode(true)` 进入，或首次调用 `setTargetAngle()` 自动进入。 |

## Lua API

外设类型为 `aero_bearing`（按方向 wrap，如 `peripheral.wrap("right")`，或 `peripheral.find("aero_bearing")`）。

| 方法 | 说明 |
|---|---|
| `setTargetAngle(度)` | 从动物理体绝对定位到指定角度；需要先装配；自动进入 Lua 控制模式。`mainThread=true` |
| `getTargetAngle()` | 当前目标角（度，服务端权威） |
| `getTargetAngleRad()` | 当前目标角（弧度） |
| `setControlMode(开关)` | 进入/退出 Lua 控制模式（保持当前朝向，不跳变） |
| `isControlMode()` | 是否处于 Lua 控制模式 |
| `isAssembled()` | 是否已装配（有从动物理体） |
| `assemble()` | 把 FACING 方向的结构装配成 sub-level；返回是否成功 |
| `disassemble()` | 把从动物理体拆回世界方块；返回是否成功 |

```lua
local b = peripheral.wrap("right")

-- 装配轴承前方的结构
print(b.assemble())            -- 有结构可装配时返回 true

-- Lua 控制模式：把从动物理体绝对定位到指定角度
b.setTargetAngle(90)           -- 转到 90°（自动进入 Lua 控制模式）
b.setTargetAngle(-45)          -- 转回来

-- 查询状态
print(b.getTargetAngle())      -- -45.0
print(b.isControlMode())       -- true

-- 退出 Lua 控制模式，恢复应力驱动旋转
b.setControlMode(false)
```

## 与 `simulated:swivel_bearing` 的差异

| | swivel_bearing | 航空轴承 |
|---|---|---|
| 动力输入 | 中间齿轮，从侧面齿轮啮合 | **直接轴向输入**：传动轴/应力网络直接接在轴承轴上 |
| 应力网络 | 贯通传动杆（应力穿过） | **不贯通**：从动物理体由物理约束驱动，应力到轴承为止 |
| 角度控制 | 经网络转速（序列化输入） | 应力模式：相同；**Lua 控制模式：角度由 Lua 直接设定，跳过应力网络角度累计** |
