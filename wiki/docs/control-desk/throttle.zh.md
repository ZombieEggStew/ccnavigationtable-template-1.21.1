# 油门杆

![throttle](../img/throttle.png)

油门杆安装在[控制台](overview.zh.md)上，在 **12 个离散档位（0..11）** 间切换。手柄沿**模型空间 X 轴**滑动：档位 0 在底端（−X 端），档位 11 为满前进（+X 端），1px = 1 档。按住**前进**键（默认 `空格`）升档，按住**后退**键（默认 `左Ctrl`）降档。

> 油门杆与**监视器 2** 共用控制台桌体后缘上方的插槽 —— 两者互斥安装。

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

## Lua API

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local th = desk.getModule("throttle")   -- 未安装油门杆返回 nil
```

### th.isThrottleForwardActive() / th.isThrottleBackActive()

前进 / 后退键按住时返回 `true`（原始输入，读服务端输入租约）。

### th.getThrottleGear()

返回当前档位（整数，**0..11**）：`0` = 最低档（底端，−X 端），`11` = 满前进（+X 端）。档位锁存 —— 不会自动回到 0。

```lua
print(th.getThrottleGear())   -- 0..11
```

### th.getThrottleAxis()

返回归一化的油门位置（数值，**0..1**）= 档位 / 最大行程：`0` = 最低档，`1` = 满前进。

```lua
print(th.getThrottleAxis())   -- 0..1
```

所有方法都是 `mainThread = false`（跑在 CC worker 线程），可以高频轮询。

## 示例

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local th = desk.getModule("throttle")

while true do
    local gear = th.getThrottleGear()        -- 0..11
    local throttle = th.getThrottleAxis()    -- 0..1
    print(("gear %d  throttle %.2f"):format(gear, throttle))
    os.sleep(0.05)
end
```
