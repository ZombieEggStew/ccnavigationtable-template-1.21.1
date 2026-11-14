# 航空轴承

![aero_bearing](../img/my_bearing_item.png)
> 1.0.9 加入

**航空轴承** 是一个 Create 动能方块 + Sable 物理体的组合。动力从**背面半截传动杆**（轴向输入，同 `create:mechanical_bearing`）接入，装配后的结构成为**独立的物理体**（sub-level）；传动杆**贯通**——输入转速经顶盘继续传给 sub-level 内部的机械（输入转速 = 输出转速），但**不驱动**定盘/从动物理体的物理角。

## 驱动策略

| 输入 | 行为 |
|---|---|
| **输入动力有转速** | 只做两件事：按 impact **4.0**（同 `simulated:swivel_bearing`）计费应力；经顶盘把转速与应力原样传给 sub-level 内部机械。**定盘不跟着转**。 |
| **物理角** | **只能**由 Lua `setTargetAngle()` 设定。PD 伺服把定盘/从动物理体锁在该目标角（防风帆被气流吹动也不动）。 |

> 序列化角度输入（`create:sequenced_gearshift`、曲柄、阀门手柄等 `TURN_ANGLE`）**不再**驱动定盘。

## Lua API

外设类型为 `aero_bearing`（按方向 wrap，如 `peripheral.wrap("right")`，或 `peripheral.find("aero_bearing")`）。

| 方法 | 说明 |
|---|---|
| `setTargetAngle(度)` | 定盘/从动物理体绝对定位到指定角度（唯一的角度来源）；需要先装配。`mainThread=true` |
| `getTargetAngle()` | 当前目标角（度，服务端权威） |
| `getTargetAngleRad()` | 当前目标角（弧度） |
| `isControlMode()` | 恒为 `true`（角度恒由 Lua 控制） |
| `setControlMode(开关)` | 兼容旧脚本的空实现，恒返回 `true`（「应力驱动模式」已取消） |
| `isAssembled()` | 是否已装配（有从动物理体） |
| `assemble()` | 把 FACING 方向的结构装配成 sub-level；返回是否成功 |
| `disassemble()` | 把从动物理体拆回世界方块；返回是否成功 |

```lua
local b = peripheral.wrap("right")

-- 装配轴承前方的结构（动力可从背面接入，但不会让定盘转）
print(b.assemble())            -- 有结构可装配时返回 true

-- 角度只能由 Lua 设定
b.setTargetAngle(90)           -- 定盘转到 90°
b.setTargetAngle(-45)          -- 转回来

-- 查询状态
print(b.getTargetAngle())      -- -45.0
print(b.isControlMode())       -- true（恒为 true）
```

## 与 `simulated:swivel_bearing` 的差异

| | swivel_bearing | 航空轴承 |
|---|---|---|
| 动力输入 | 中间齿轮，从侧面齿轮啮合 | **直接轴向输入**：传动轴/应力网络直接接在轴承轴上 |
| 应力网络 | 贯通传动杆（应力穿过） | **同样贯通**：顶盘是同一应力网络的成员，sub-level 内机械的转速与应力都过这条杆 |
| 角度来源 | 经网络转速（含序列化输入） | **只由 Lua `setTargetAngle` 设定**：输入动力只计费并传递应力，定盘不转 |
