# 监视器 2

![monitor_2](../img/monitor_2.png)

监视器 2 安装在[控制台](overview.zh.md)桌顶，是**内置的小型监视器**：屏幕面上是一个 **10×8** 的模块槽网格，可安装与正式[监视器](../monitor/overview.zh.md)相同的模块 —— 按钮、钮子开关、旋钮与屏幕 —— 全部可通过 Lua 自由控制。

> 它全占桌顶 6×14 棋盘网格（唯一合法放置中心 `(8,12)`）——与[油门杆](throttle.zh.md)、[油门杆 2](throttle_2.zh.md)**互斥**（同一时刻只能安装其中一个）。与那些控件不同，监视器 2 **不面向玩家**：只随桌体 FACING 旋转。

## 安装 / 拆除

- **安装**：手持监视器 2 物品右键控制台。它安装在桌顶网格唯一合法位置（中心 `(8,12)`）；14×6×12 预览盒显示安装位置（绿 = 可装，红 = 被占用）。
- **拆除**：手持 Create 扳手蹲下右键拆除；破坏控制台方块时随掉落。

## 10×8 网格

屏幕面是 **10×8** 网格（正式监视器为 12×10）：`x` 0..9，`y` 0..7。模块的放置、交互、渲染与配置和监视器完全一致 —— 见[监视器总览](../monitor/overview.zh.md)。

| 类型 | 说明 | 文档 |
|---|---|---|
| `button_1` | 按钮（瞬时；玩家点击 / 交互锁 / 灯带控制） | [按钮模块](../monitor/button.zh.md) |
| `toggle_switch` | 钮子开关（锁存） | [开关模块](../monitor/switch.zh.md) |
| `knob` | 旋钮（角度 0..360） | [旋钮模块](../monitor/knob.zh.md) |
| `screen` | 屏幕（格子模型文本 + 自由图形绘制） | [屏幕模块](../monitor/screen.zh.md) |

## Lua API

监视器 2 的 Lua API 与**正式监视器完全相同** —— 没有需要新学的方法，唯一区别是网格尺寸（10×8 vs 12×10）。

### 获取监视器 2 外设

从控制台外设经 `getModule("monitor")` 获取（外设类型 `"ccpe:monitor_2"`）；未安装监视器 2 时返回 `nil`：

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local m = desk.getModule("monitor")   -- 未安装监视器 2 返回 nil
```

`m` 具有与**监视器外设完全相同的方法** —— `getCellModule(x, y)` / `getModule(id)` / `playNiceSound()` / `playSound(name)`，完整说明见[监视器](../monitor/monitor.zh.md)。这里所有查询都作用在 10×8 网格上。

### 快捷入口（无需先取外设）

控制台外设还直接在 10×8 网格上暴露同样的查询：

| 方法 | 说明 |
|---|---|
| `desk.getMonitor2CellModule(x, y)` | 读取格子上的模块/屏幕实例（`x` 0..9，`y` 0..7）；空格 / 未安装监视器 2 返回 `nil` |
| `desk.getMonitor2Module(id)` | 按 ID 读取模块/屏幕实例；不存在 / 未安装监视器 2 返回 `nil` |

### 模块实例

返回的模块/屏幕实例与**监视器的完全相同** —— 通用方法（`getId()`、`getType()`、`getX()`、`getY()`、`getWidth()`、`getHeight()`、`setTooltip()`）与各类型的专属方法都见监视器文档：

- 通用实例方法 —— 见[监视器总览](../monitor/overview.zh.md)的「通用模块实例方法」一节
- 各类型 —— [按钮](../monitor/button.zh.md) / [开关](../monitor/switch.zh.md) / [旋钮](../monitor/knob.zh.md) / [屏幕](../monitor/screen.zh.md)

### 与监视器的差异

只有网格尺寸不同（10×8 vs 12×10），其余行为完全一致，包括 `mainThread` 规则与 `nil` 语义。

## 示例

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)

-- 直接从 10×8 网格读取模块
local mod = desk.getMonitor2CellModule(3, 4)
if mod then
    print("type:", mod.getType(), "id:", mod.getId())
    mod.setTooltip("压力表")
end

-- 或经监视器 2 外设
local m = desk.getModule("monitor")
if m then
    local scr = m.getCellModule(1, 1)
    if scr and scr.getType() == "screen" then
        scr.write("你好，监视器 2")
    end
end
```
