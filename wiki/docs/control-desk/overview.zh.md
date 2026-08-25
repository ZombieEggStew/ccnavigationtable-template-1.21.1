# 控制台

控制台（Control Desk）是坐垫驱动的操作控制台。默认**没有安装任何控件** —— 需要玩家自己安装[脚踏板](pedal.zh.md)、[操纵杆](joystick.zh.md)和/或[油门杆](throttle.zh.md)。坐在控制台旁边的 Create 坐垫上即可自动进入**操作模式**：按键驱动所有联动控制台的已安装控件，控件状态通过 CC:T Lua API 暴露。

## 安装 / 拆除控件

- **安装**：手持控件物品（脚踏板 / 操纵杆 / 油门杆）右键控制台。控件安装在该控制台前缘的固定安装位；手持控件物品对准控制台时会显示安装预览框 —— **绿色** = 可安装，**红色** = 已安装。安装消耗 1 个物品（创造模式不消耗）。
- **拆除**：手持 Create 扳手**蹲下 + 右键**已安装的控件 —— 只拆除点击命中的那一个，并掉落为物品。
- **破坏控制台**：已安装的控件会随方块一起掉落。
- 未安装任何控件的控制台，扳手蹲下右键会走默认的拆方块行为。

## 坐垫操作模式

坐上去就行，无需手动交互：

1. 坐在**任意 Create 坐垫**上，且该坐垫正东/正南/正西/正北紧邻 1 格内存在至少一个控制台。
2. 坐垫四邻的所有控制台（最多 4 个）全部进入**联动**，按键会**同时广播**驱动它们 —— 没安装对应控件的联动控制台自动忽略输入。
3. 按**潜行键**下车（保留 Create 原版行为）。

联动判定以坐垫为中心，与控制台自身朝向无关。操作模式下，绑定为控件按键的键会在原版按键处理前被**提前清空（drain）**——例如按 `E` 驱动踏板而不是打开物品栏；按住态的行为（移动、潜行下车）不受影响。

!!! tip "坐垫判定"
    模组判定坐垫看的是**骑乘实体**而非方块：只要载具是 Create 的 `SeatEntity`（或其子类）即进入操作模式 —— 因此复用 Create 坐垫实体的其他模组座椅（如 **Create: Interiors** 的椅子）天然兼容。

### 默认按键

| 控件 | 动作 | 默认按键 |
|---|---|---|
| 操纵杆 | 前推 / 后拉 / 左摆 / 右摆 | `W` / `S` / `A` / `D` |
| 左踏板 | 踩下 / 抬起 | `Q` / `E` |
| 右踏板 | 踩下 / 抬起 | `E` / `Q` |
| 油门杆 | 前进（升档）/ 后退（降档） | `空格` / `左Ctrl` |

所有按键绑定都**跟随控制台**（存 BE NBT），可在模块设置菜单中配置（见下）。

## 配置菜单

打开控制台的配置菜单：

- **扳手右键**控制台，或
- **空手蹲下 + 右键**控制台

菜单内容：

- **频道**滚轮条 —— 控制台的全局频道号，与传感器 / 显示器 / 外设扩展器共享同一全局唯一命名空间（自动跳过已占用频道）。
- **已安装控件列表** —— 点击对应行打开该控件的模块设置菜单（按键绑定、回正时间、档位模式等）。

所有配置都存储在方块 NBT 中，兼容 **Create 蓝图 / 装置搬运**，可以批量制作配置好的控制台。

## CC:T 外设

控制台的外设类型为 `"ccpe:control_desk"`。

### 获取外设

```lua
-- 方式 A：经频道获取（任意距离）
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)   -- 4 = 控制台的频道号

-- 方式 B：直接 CC:T 外设（计算机紧邻放置）
local desk = peripheral.wrap("right")
-- 或
local desk = peripheral.find("ccpe:control_desk")
```

### 获取控件模块实例

```lua
local pedal = desk.getModule("pedal")     -- 未安装脚踏板返回 nil
local joy   = desk.getModule("joystick")  -- 未安装操纵杆返回 nil
local th    = desk.getModule("throttle")  -- 未安装油门杆返回 nil
```

`desk.getModule(name)` 接受 `"pedal"` / `"joystick"` / `"throttle"`（大小写不敏感），控件未安装时返回 `nil`。返回的模块实例直接读取**服务端权威的控件状态**：

| 实例 | 方法 |
|---|---|
| `pedal` | `getLeftPedal()`、`getRightPedal()`、`getPedalDifference()`、`isLeftPedalDown()`、`isRightPedalDown()`、`isLeftPedalUp()`、`isRightPedalUp()` |
| `joystick` | `isJoystickXActive()`、`isJoystickYActive()`、`getJoystickX()`、`getJoystickY()`、`getJoystickXSigned()`、`getJoystickYSigned()` |
| `throttle` | `isThrottleForwardActive()`、`isThrottleBackActive()`、`getThrottleGear()`、`getThrottleAxis()` |

完整的各实例 API 见[脚踏板](pedal.zh.md)、[操纵杆](joystick.zh.md)与[油门杆](throttle.zh.md)。所有状态读取方法都是 `mainThread = false`（跑在 CC worker 线程），可以高频轮询。

## 虚拟摇杆 HUD（可选）

客户端配置 `joystickOverlayEnabled`（默认**关闭**）可开启屏幕角落的虚拟摇杆 HUD overlay（调试/测试用）。
