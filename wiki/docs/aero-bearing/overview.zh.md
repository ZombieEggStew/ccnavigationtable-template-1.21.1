# 航空轴承

![aero_bearing](../img/my_bearing_item.png)
> 1.0.9 加入

**航空轴承** 是一个 Create 动能方块 + Sable 物理体的组合。动力从**背面半截传动杆**（轴向输入，同 `create:mechanical_bearing`）接入，装配后的结构成为**独立的物理体**（sub-level）；传动杆**贯通**——输入转速经顶盘继续传给 sub-level 内部的机械（输入转速 = 输出转速）。

## 驱动策略

| 项 | 行为 |
|---|---|
| **动力（背面输入转速）** | 按 impact **4.0**（同 `simulated:swivel_bearing`）计费应力；把转速与应力原样传给 sub-level 内部机械；**同时是顶盘能否转动的唯一前提**。输入转速为 0 时，顶盘即使收到 Lua 角度命令也**不会转**。 |
| **角度命令** | 只能由 Lua `setTargetAngle()` 设定终点角。顶盘**不瞬移**，而是以「顶盘旋转速度」逐 tick 走向终点，到位后停住并锁死（气流吹不动）。 |
| **锁定模式** | 默认**已锁定**（上面那条「锁死」）。Lua `setLocked(false)` 切到**未锁定**：关节只剩被动摩擦阻尼（`swivelBearingFriction`），顶盘可被气流/碰撞等外力自由带动；此模式下不推进角度命令。切回锁定时以当前物理朝向为起点，不跳变。 |
| **顶盘旋转速度** | 默认 = **输入转速**，但**上限 96 RPM**（输入 256 RPM 时顶盘仍按 96 RPM 走）。可用 Lua `setRotationSpeed(rpm)` 固定为 0~96 RPM；设过非 0 值就一直沿用，再次传 0 恢复自动。 |

> 序列化角度输入（`create:sequenced_gearshift`、曲柄、阀门手柄等 `TURN_ANGLE`）**不会**驱动顶盘。

## Lua API

外设类型为 `aero_bearing`（按方向 wrap，如 `peripheral.wrap("right")`，或 `peripheral.find("aero_bearing")`）。

| 方法 | 说明 |
|---|---|
| `setTargetAngle(度)` | 设定命令终点角（唯一的角度来源）；需要先装配、且要有动力才会真正转过去。`mainThread=true` |
| `getTargetAngle()` | 当前伺服参考角（度，正在朝终点走） |
| `getTargetAngleRad()` | 当前伺服参考角（弧度） |
| `getCommandedAngle()` | 命令终点角（度） |
| `setRotationSpeed(rpm)` | 设定顶盘旋转速度（**0~96** RPM，自动钳制、取绝对值）；`0` = 自动（`min(输入转速, 96)`）。返回生效值。`mainThread=true` |
| `getRotationSpeed()` | 配置的顶盘旋转速度（RPM）；`0` 表示自动 |
| `getInputSpeed()` | 输入转速（RPM）——顶盘转动的动力前提 |
| `isControlMode()` | 恒为 `true`（角度恒由 Lua 控制） |
| `setControlMode(开关)` | 兼容旧脚本的空实现，恒返回 `true` |
| `setLocked(开关)` | 切换锁定 / 未锁定模式（行为同 `simulated:swivel_bearing`）：`true` = PD 伺服锁在目标角；`false` = 只剩摩擦阻尼、可被外力自由带动。返回切换后的状态。`mainThread=true` |
| `isLocked()` | 是否处于锁定模式（默认 `true`） |
| `isAssembled()` | 是否已装配（有从动物理体） |
| `assemble()` | 把 FACING 方向的结构装配成 sub-level；返回是否成功 |
| `disassemble()` | 把从动物理体拆回世界方块；返回是否成功 |

```lua
local b = peripheral.wrap("right")

-- 装配轴承前方的结构（背面要接动力，否则顶盘不会动）
print(b.assemble())            -- 有结构可装配时返回 true

-- 默认：顶盘以「输入转速」走向目标角
b.setTargetAngle(90)

-- 固定顶盘旋转速度（这个轴承此后一直按 32 RPM 走向目标角）
b.setRotationSpeed(32)
b.setTargetAngle(-45)

-- 恢复「顶盘转速 = 输入转速」
b.setRotationSpeed(0)

-- 锁定 / 未锁定（未锁定时顶盘可被气流自由带动，像一块被风推着的配平片）
b.setLocked(false)
b.setTargetAngle(-45)          -- 未锁定时命令被挂起，不会推进
b.setLocked(true)              -- 切回锁定：以当前朝向为起点，不跳变

-- 查询
print(b.getInputSpeed())       -- 例如 64.0
print(b.getRotationSpeed())    -- 0.0（自动）
print(b.getTargetAngle())      -- 伺服参考角
print(b.getCommandedAngle())   -- 命令终点角
print(b.isLocked())            -- true
```

## 与 `simulated:swivel_bearing` 的差异

| | swivel_bearing | 航空轴承 |
|---|---|---|
| 动力输入 | 中间齿轮，从侧面齿轮啮合 | **直接轴向输入**：传动轴/应力网络直接接在轴承轴上 |
| 应力网络 | 贯通传动杆（应力穿过） | **同样贯通**：顶盘是同一应力网络的成员，sub-level 内机械的转速与应力都过这条杆 |
| 角度来源 | 经网络转速（含序列化输入，连续旋转） | **只由 Lua 设定终点角**，且按可配置的「顶盘旋转速度」限速到位；无动力时不转 |
| 锁定 | 红石信号 + 数值面板 `LockingSetting` | 物理行为相同（锁定 = PD 伺服，未锁定 = 摩擦阻尼），但改用 Lua `setLocked` 切换，不用红石 |
