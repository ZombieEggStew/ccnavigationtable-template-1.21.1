# 油门杆

![throttle](../img/throttle.png)

油门杆安装在[控制台](overview.zh.md)上，在 **12 个离散档位（0..11）** 间切换。手柄沿**模型空间 X 轴**滑动：档位 0 在底端（−X 端），档位 11 为满前进（+X 端），1px = 1 档。按住**前进**键（默认 `空格`）升档，按住**后退**键（默认 `左Ctrl`）降档。

> 油门杆全占桌顶 6×14 棋盘网格（唯一合法放置中心 `(8,12)`，只能 0°/180° 旋转）—— 与**油门杆 2**、**监视器 2** **互斥**（同一时刻只能安装其中一个）。

## 档位切换

- **档位切换节奏**（tick）：按住前进/后退键切换一个档位所需的时间 —— 默认 **4**，范围 1..100（20 tick = 1 秒）。持续按住时每满 N tick 切换一档。
- 油门杆**锁存不回正** —— 松开按键（或同时按住两个键）保持当前档位；离开坐垫后档位依然保持，类似物理油门。
- 每次档位切换播放一次拉杆音效，音调随档位位置上升（前进从低到高、后退从高到低，0.75 → 1.5）；最低档（0）不响。
- 指示灯随档位上升从**暗红**（档位 0）渐变为**亮红**（满前进）。

## 按键绑定

| 动作 | 默认按键 |
|---|---|
| 前进（升档） | `空格` |
| 后退（降档） | `左Ctrl` |

两个按键绑定与档位切换节奏都**跟随控制台**，可在模块设置菜单中配置（打开[控制台配置菜单](overview.zh.md)，点击「油门杆」行）。

## 自由模式

油门杆默认处于**档位模式**（卡位）—— 在 12 个离散档位间切换并逐档发出拉杆音效。切换到**自由模式**后，手柄改为**平滑连续滑动**：位置为连续值 **0..1**，可以停在档位之间，松开按键仍**锁存不回正**。

- 自由模式**没有 GUI** —— 完全由 Lua 控制（`setFreeMode`，见[下方 Lua API](#lua-api)）。
- 自由模式下按住按键，每 tick 位移 = `1/档位切换节奏` px（`档位切换节奏` 为档位切换配置），满行程（11px）共需 `11 × 节奏` tick。
- 切回档位模式时位置吸附到最近档位。
- 模式随控制台持久化（存档保留、Create 蓝图保留）。

```lua
local th = desk.getModule("throttle")
print(th.isFreeMode())   -- false（默认档位模式）
th.setFreeMode(true)     -- 切到自由模式（不卡位）
th.setAxis(0.37)         -- 手柄移到 37% 位置
```

## Lua API

```lua
local ss = require("ccpe.sensor_system")
local desk = ss.getPeripheral(4)
local th = desk.getModule("throttle")   -- 未安装油门杆返回 nil
```

### th.isForwardActive() / th.isBackActive()

前进 / 后退键按住时返回 `true`（原始输入，读服务端输入租约）。

### th.getThrottleGear()

返回当前档位（整数，**0..11**）：`0` = 最低档（底端，−X 端），`11` = 满前进（+X 端）。档位锁存 —— 不会自动回到 0。

```lua
print(th.getThrottleGear())   -- 0..11
```

### th.isActive()

有人正在操作这台油门时返回 `true`（前进或后退任一键按住，读服务端输入租约）。

### th.getAxis()

返回归一化的油门位置（数值，**0..1**）= 位置 / 最大行程：`0` = 底端，`1` = 满前进。档位模式下为离散值（档位/最大行程）；自由模式下为连续值（可停在档位之间）。

```lua
print(th.getAxis())   -- 0..1
```

### th.isFreeMode()

返回当前是否处于**自由模式**（`true`），`false` = 档位模式（默认）。模式开关无 GUI，由 Lua 切换并持久化（存档保留、蓝图保留）。

```lua
print(th.isFreeMode())   -- false（默认档位模式）
```

### th.setFreeMode(enabled)

在**自由模式**（`true`）与**档位模式**（`false`）之间切换。

- `true`（自由，不卡位）：按住前进/后退平滑连续移动（无卡位音效），松开锁存。
- `false`（档位，默认）：按住按键充电满档位切换节奏后进/退一档，逐档播放拉杆音效。

切回档位模式时位置吸附到最近档位。

```lua
th.setFreeMode(true)    -- 切到自由模式
th.setFreeMode(false)   -- 切回档位模式
```

### th.setAxis(axis)

直接设置油门位置（`axis` 为 **0..1**，越界自动钳位）：自由模式下连续写入；档位模式下吸附到最近档位。

> 注意：玩家坐在联动坐垫上操作（输入租约有效）时，服务端每 tick 模拟会推进位置并覆盖本设置；无玩家输入时设置保持到下次按键/模式切换。

```lua
th.setFreeMode(true)
th.setAxis(0.37)   -- 手柄移到 37% 位置
```

### 线程模型

所有**状态读取**方法（`isForwardActive()`、`isBackActive()`、`isActive()`、`getThrottleGear()`、`getAxis()`、`isFreeMode()`）都在 CC worker 线程运行（`mainThread = false`），可以高频轮询。**控制**方法（`setFreeMode()`、`setAxis()`）在服务端主线程运行（`mainThread = true`）。

## 示例

```lua
local ss = require("ccpe.sensor_system")
local desk = ss.getPeripheral(4)
local th = desk.getModule("throttle")

while true do
    local gear = th.getThrottleGear()        -- 0..11
    local throttle = th.getAxis()    -- 0..1
    print(("gear %d  throttle %.2f"):format(gear, throttle))
    os.sleep(0.05)
end
```
