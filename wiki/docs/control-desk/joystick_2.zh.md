# 操纵杆 2

![操纵杆 2](../img/joystick_2.png)

操纵杆 2 安装在[控制台](overview.zh.md)上，可以向任意方向倾斜，最大 **15°**：**前推**（默认 `W`）、**后拉**（`S`）、**左摆**（`A`）、**右摆**（`D`）。松开按键后自动回到中心。

!!! tip "右手设计"
    该操纵杆为**右手**设计。推荐安装位置如上图所示，如果安装在其他位置，需要自行修改按键绑定。

## 摆动模式

两个轴（前后 / 左右）**各自独立配置**：

- **自由模式**（默认）：操纵杆平滑摆动。**满偏时间**（tick）控制按住按键到满偏所需时间（默认 2，范围 1..100）；**回正时间**（tick）控制松开后回到中心所需时间（默认 2，范围 0..100；`0` = 不回正，操纵杆停在当前位置）。20 tick = 1 秒。
- **档位模式**：操纵杆不再平滑摆动，而是吸附到离散档位。**档位数**：1..31（默认 4）。档位从 -1 到 +1 均匀分布（如 4 档 → `{-1, -1/3, +1/3, +1}`；3 档 → `{-1, 0, +1}`；2 档 → `{-1, +1}`）。每按一次按键**进/退一档**（按住不连跳），且**没有自动回正** —— 离开坐垫后操纵杆仍保持所在档位，类似物理换挡杆。

## 按键绑定

| 方向 | 默认按键 |
|---|---|
| 前推 / 后拉 | `W` / `S` |
| 左摆 / 右摆 | `A` / `D` |

四个方向按键都**跟随控制台**，可在模块设置菜单中配置（打开[控制台配置菜单](overview.zh.md)，点击「操纵杆 2」行）。按键绑定与普通[操纵杆](joystick.zh.md)**相互独立** —— 两个控件可以同时安装、各自配置。

## Lua API

```lua
local ss = require("ccpe.sensor_system")
local desk = ss.getPeripheral(4)
local joy2 = desk.getModule("joystick_2")   -- 未安装操纵杆 2 返回 nil
```

### joy2.isAxisXActive() / joy2.isAxisYActive()

该轴任意方向键按住时返回 `true`（X = 左右，Y = 前后）。

### joy2.getAxisX() / joy2.getAxisY()

返回该轴的**幅度**（数值，**0..1**）—— 操纵杆在该轴上的偏转程度，不含方向。

```lua
print(joy2.getAxisX(), joy2.getAxisY())   -- 0..1
```

### joy2.getAxisXSigned() / joy2.getAxisYSigned()

返回**带符号**的轴值（数值，**-1..1**）：`+1` = 右摆（`D`）/ 前推（`W`），`-1` = 左摆（`A`）/ 后拉（`S`）。

```lua
print(joy2.getAxisXSigned(), joy2.getAxisYSigned())
```

所有方法都是 `mainThread = false`（跑在 CC worker 线程），可以高频轮询。

## 示例

```lua
local ss = require("ccpe.sensor_system")
local desk = ss.getPeripheral(4)
local joy2 = desk.getModule("joystick_2")

while true do
    local forward = joy2.getAxisYSigned()  -- -1..1，推力
    local steer   = joy2.getAxisXSigned()  -- -1..1，转向
    print(("forward %.2f  steer %.2f"):format(forward, steer))
    os.sleep(0.05)
end
```
