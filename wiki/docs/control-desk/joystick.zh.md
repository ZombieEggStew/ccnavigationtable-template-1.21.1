# 操纵杆

操纵杆安装在[控制台](overview.zh.md)上，可以向任意方向倾斜，最大 **15°**：**前推**（默认 `W`）、**后拉**（`S`）、**左摆**（`A`）、**右摆**（`D`）。松开按键后自动回到中心。

## 摆动模式

两个轴（前后 / 左右）**各自独立配置**：

- **自由模式**（默认）：操纵杆平滑摆动。**满偏时间**（tick）控制按住按键到满偏所需时间（默认 2，范围 1..100）；**回正时间**（tick）控制松开后回到中心所需时间（默认 2，范围 0..100；`0` = 不回正，操纵杆停在当前位置）。20 tick = 1 秒。
- **档位模式**：操纵杆不再平滑摆动，而是吸附到离散档位。**档位数**：1..31（默认 4）。档位从 -1 到 +1 均匀分布（如 4 档 → `{-1, -1/3, +1/3, +1}`；3 档 → `{-1, 0, +1}`；2 档 → `{-1, +1}`）。每按一次按键**进/退一档**（按住不连跳），且**没有自动回正** —— 离开坐垫后操纵杆仍保持所在档位，类似物理换挡杆。

## 按键绑定

| 方向 | 默认按键 |
|---|---|
| 前推 / 后拉 | `W` / `S` |
| 左摆 / 右摆 | `A` / `D` |

四个方向按键都**跟随控制台**，可在模块设置菜单中配置（打开[控制台配置菜单](overview.zh.md)，点击「操纵杆」行）。

## Lua API

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local joy = desk.getModule("joystick")   -- 未安装操纵杆返回 nil
```

### joy.isJoystickXActive() / joy.isJoystickYActive()

该轴任意方向键按住时返回 `true`（X = 左右，Y = 前后）。

### joy.getJoystickX() / joy.getJoystickY()

返回该轴的**幅度**（数值，**0..1**）—— 操纵杆在该轴上的偏转程度，不含方向。

```lua
print(joy.getJoystickX(), joy.getJoystickY())   -- 0..1
```

### joy.getJoystickXSigned() / joy.getJoystickYSigned()

返回**带符号**的轴值（数值，**-1..1**）：`+1` = 右摆（`D`）/ 前推（`W`），`-1` = 左摆（`A`）/ 后拉（`S`）。

```lua
print(joy.getJoystickXSigned(), joy.getJoystickYSigned())
```

所有方法都是 `mainThread = false`（跑在 CC worker 线程），可以高频轮询。

## 示例

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local joy = desk.getModule("joystick")

while true do
    local forward = joy.getJoystickYSigned()  -- -1..1，推力
    local steer   = joy.getJoystickXSigned()  -- -1..1，转向
    print(("forward %.2f  steer %.2f"):format(forward, steer))
    os.sleep(0.05)
end
```
