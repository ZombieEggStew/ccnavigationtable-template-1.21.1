# 监视器

![监视器](../img/my_monitor_item.png)

获得 Monitor 外设实例后（获取方式见 [概述](overview.md)），可以调用以下三类方法：

| 类别 | 方法 |
|---|---|
| 模块 / 屏幕查询 | `getCellModule` / `getModule` |
| 背景平面绘制（文本 + 图形） | `write` / `clear` / `setCursorPos` / `getCursorPos` / `setTextScale` / `getTextScale` / `setTextColour` / `getTextColour` / `setZIndex` / `getZIndex` / `setOverflowMode` / `getOverflowMode` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `clearRects` / `clearShapes` / `getSize` |
| 音效 | `playNiceSound` / `playSound` |

---

## 操作说明

- **配置**：对准监视器底座 蹲下 + 右键 可以打开配置界面
    - 频道：设置 Monitor 的全局频道号，与 外设扩展器 公用一个频道系统
    - 背景：切换 Monitor 的背景图片
    - 旋转与偏移：自由设置旋转与偏移
- **拆卸**：手持扳手 **对准监视器底座** **蹲下右键** 可以拆卸，拆卸下来的监视器能够保持模块与设置。直接破坏会分离监视器与模块

---

## 模块 / 屏幕查询

### monitor.getCellModule(x, y)

- **参数**：`x`（0..11）、`y`（0..9）——格子坐标
- **返回**：该格子上的模块实例（`ModuleHandle`）；若格子被屏幕占用则返回屏幕实例；空格/越界返回 `nil`

```lua
local mod = monitor.getCellModule(3, 4)
if mod then
    print(mod.getId(), mod.getType())  -- 例：7  toggle_switch
end
```

### monitor.getModule(id)

- **参数**：`id`——模块/屏幕 ID（模块与屏幕共用同一 ID 命名空间）
- **返回**：对应模块/屏幕实例；不存在返回 `nil`

```lua
local mod = monitor.getModule(7)
if mod then print(mod.getType()) end
```

---


## 音效

### monitor.playNiceSound()

播放 Create 风格的下单音效 + WiFi 粒子（效果位置在方块中心，音效为 `create:stock_ticker_request`）。
音效在服务端广播给附近玩家；WiFi 粒子走自定义 clientbound 包（`ccpe:play_order_effect`）广播给 32 格内的客户端，由客户端本地生成（Create 的 `WiFiParticle` 数据无法走粒子网络通道编码）。

```lua
monitor.playNiceSound()
```

### monitor.playSound(sound)

播放指定的 Create 音效（在方块位置广播给附近玩家，音效由服务端播放，附近玩家都能听到）。

- **参数**：`sound`——音效名称字符串，当前支持：

| 名称 | Create 音效资源 | 说明 |
|---|---|---|
| `"bonk"` | `create:cardboard_bonk` | 纸板剑"梆" |
| `"bell"` | `create:desk_bell` | 前台铃 |
| `"confirm"` | `create:confirm_2` | 确认"叮" |
| `"fwoomp"` | `create:fwoomp` | 低沉"嗡" |
| `"trade"` | `create:stock_ticker_trade` | 收银 |
| `"request"` | `create:stock_ticker_request` | 下单 |

- **返回**：`boolean`——是否找到并播放了该音效；未知名称返回 `false`（不会抛 Lua 错误）

```lua
if monitor.playSound("bell") then
    print("响了")
end
```

!!! note "依赖 Create"
    音效类方法依赖 Create 模组（运行时存在 `create` 模组，音效资源来自 `create` 命名空间）。

---

## Monitor 背景平面绘制

Monitor 本身自带一块**背景平面**显示区，可以直接在面板上写字、画矩形/线段/圆，无需安装屏幕模块。它与屏幕模块共用同一套文本/图形引擎（`ScreenText`）与坐标系，具体渲染语义与 [屏幕模块](screen.md) 一致。

- **显示区**：Monitor 面板为 14×12px，去掉四周 1px 边框后，内区为 **12×10px**（正好对应 12×10 模块网格区域）。
- **坐标单位**：1/128 块（`1px = 8 单位`），原点在内区左上角，`x` 向右、`y` 向下——与 `drawRect`、`setCursorPos` 完全一致。
- **字号单位**：MC 像素（`1px = 1/16 块`），默认 `0.5`，范围 `0.05..8.0`，只影响之后写入的字符。
- **文本**：只有前景色（默认 `0xFFFFFF`），**没有背景色**，需要背景时自己 `drawRect`。
- **层级 z**：越大越靠前，默认 `0`；负值会被压到面板后面。
- **溢出**：文本写到右缘时按 `setOverflowMode` 处理，默认 `"wrap"` 换行。

!!! tip "层级提醒"
    `z` 越大越靠前，但每 +1 前移约 0.01px，**建议 z 在 `[-1, 10]` 左右**；设太大侧面看会分层。

### monitor.write(text, z?)

在光标处写入文本到背景平面（支持 `\n` 换行、忽略 `\r`）。每个字符的位置用 `drawRect` 坐标直接定位：
写一个字符光标向右推进一个字形宽（`字号 × 1.0 × 8`），`\n` 回行首并下移一行（`字号 × 1.2 × 8`）。
写到内区右缘时按 `setOverflowMode` 处理（默认 `"wrap"` 换行）。

- `text`：要写入的文本
- `z`：可选，本次写入字符的层级（越大越靠前）；省略时使用 `setZIndex` 设置的默认层级

```lua
monitor.write("Hello\nCCPE")
monitor.write("Top", 2)          -- 层级 2
```

### monitor.clear()

清空背景平面上的文本和所有图形（矩形/线段/圆），并把光标重置到 `(0, 0)`。

### monitor.setCursorPos(x, y) / monitor.getCursorPos()

设置/读取光标位置，坐标系统与 `drawRect` 的前两个参数完全一致：
以内区左上角为原点，X 向右、Y 向下，1 单位 = 1/128 块（负值收拢为 0）。
`getCursorPos` 返回 `x, y` 两个值。

```lua
monitor.setCursorPos(0, 0)          -- 内区左上角
local x, y = monitor.getCursorPos()
```

### monitor.setTextScale(scale) / monitor.getTextScale()

设置/读取整块背景平面的字号（字形高度，MC 像素，`1px = 1/16 块`，范围 `0.05..8.0`）。
字号只影响之后 `write` 写入的字形大小与推进量，不影响已写入文本的位置（旧字符仍按各自位置渲染）。

```lua
monitor.setTextScale(0.5)
print(monitor.getTextScale())  -- 0.5
```

### monitor.setTextColour(colour) / monitor.getTextColour()

设置/读取前景色（0xRRGGBB，默认 `0xFFFFFF`）。文本**没有背景色**，需要背景时自己用 `drawRect` 画。

```lua
monitor.setTextColour(0x00FF00)
```

### monitor.setZIndex(z) / monitor.getZIndex()

设置/读取之后 `write` / `drawRect` 未显式指定 z 时使用的默认层级（默认 `0`，越大越靠前，负值会被压进面板后面）。

```lua
monitor.setZIndex(2)
monitor.write("Hello")           -- 用默认层级 2
monitor.drawRect(0, 0, 4, 4, 0xFF0000, true, 1)   -- 也用层级 2
```

### monitor.setOverflowMode(mode) / monitor.getOverflowMode()

设置/读取文本超出一行宽度时的处理方式（未知名称会回退到 `"wrap"`）：

| mode | 含义 |
|---|---|
| `"truncate"` | 直接截断，丢弃超出部分 |
| `"ellipsis"` | 多截断一点，末尾补 `"..."` |
| `"wrap"` | 自动换到下一行（默认） |

```lua
monitor.setOverflowMode("ellipsis")
print(monitor.getOverflowMode())  -- ellipsis
```

### monitor.drawRect(x, y, width, height, colour, solid, lineWidth, z?)

在背景平面上画一个矩形。坐标与文本/光标共用同一套系统。

- `x, y`：左上角（1/128 块，0 = 内区左/上缘，向右/下增大）
- `width, height`：宽高（1/128 块，负值收拢为 0）
- `colour`：颜色（0xRRGGBB）
- `solid`：`true` = 实心，`false` = 只描边
- `lineWidth`：线宽（1/128 块，仅描边时生效）
- `z`：层级（越大越靠前，省略时使用 `setZIndex` 设置的默认层级）

```lua
monitor.drawRect(0, 0, 2, 2, 0xFF0000, true, 1)        -- 实心红块，默认层级
monitor.drawRect(1, 1, 1, 1, 0x00FF00, false, 0.2)     -- 绿色描边，默认层级
monitor.drawRect(0, 0, 8, 8, 0x0000FF, true, 1, 5)     -- 层级 5，盖在其它之上
```

### monitor.drawLine(x1, y1, x2, y2, colour, lineWidth, z?)

画一条线段。坐标与 `drawRect` 共用同一套系统。

- `x1, y1` / `x2, y2`：起终点（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `lineWidth`：线宽（1/128 块）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
monitor.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
```

### monitor.drawCircle(cx, cy, radius, colour, solid, lineWidth, segments?, z?)

画一个圆（用正多边形逼近）。坐标与 `drawRect` 共用同一套系统。

- `cx, cy`：圆心（1/128 块）
- `radius`：半径（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `solid`：`true` = 实心圆，`false` = 圆环
- `lineWidth`：线宽（1/128 块，仅 `solid=false` 时生效）
- `segments`：逼近段数（默认 32，最小 3，越大越圆）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
monitor.drawCircle(8, 8, 4, 0xFFFF00, true, 1)          -- 实心圆
monitor.drawCircle(8, 8, 4, 0x00FF00, false, 0.2, 48)   -- 48 段圆环
```

### monitor.drawPoint(x, y, colour, z?)

画一个点（等价于 1×1 单位的实心矩形）。坐标与 `drawRect` 共用同一套系统。

- `x, y`：左上角坐标（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
monitor.drawPoint(4, 4, 0xFF0000)
```

### monitor.clearRects()

清空所有已画的矩形（不影响文本和其它图形）。

### monitor.clearShapes()

清空所有图形（矩形 + 线段 + 圆 + 点），不影响文本。

### monitor.getSize()

返回当前字号下背景平面内区能容纳的整字行列数（参考值，文本实际按坐标定位，不受此限制），返回 `cols, rows` 两个值。
默认字号 `0.5` 时约为 `24 × 16`。

```lua
local cols, rows = monitor.getSize()
print(cols, rows)
```

---

## 线程模型（mainThread）

| 方法 | mainThread |
|---|---|
| `getCellModule` / `getModule` | ✅ `true`（查询也走服务器主线程） |
| `write` / `clear` / `setCursorPos` / `setTextScale` / `setTextColour` / `setZIndex` / `setOverflowMode` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `clearRects` / `clearShapes` / `playNiceSound` / `playSound` | ✅ `true` |
| `getCursorPos` / `getTextScale` / `getTextColour` / `getZIndex` / `getOverflowMode` / `getSize` | ❌ `false`（计算机线程直接读，低延迟） |

---

## Monitor 自定义背景图片

- **目录**：将图片放入游戏根目录下的 `ccpe_res/monitor_bg/`。该目录与 `mods/`、`resourcepacks/` 同级；只扫描该目录的第一层，不扫描子目录。
- **支持格式**：支持 `.png`、`.jpg` 和 `.jpeg`，扩展名不区分大小写。
- **文件名规则**：文件名必须以字母或数字开头，只能包含小写/大写字母、数字、下划线、连字符和点号，例如 `test_bg.png`、`cockpit-01.jpg`。不符合规则的文件会被忽略。
- **选项名称**：客户端启动时扫描图片，并将文件名追加到 Monitor 右键菜单的背景切换选项中。菜单中的自定义背景名称显示为文件名，例如 `test_bg.png`。
- **持久化键**：文件名会转换为小写，并以 `custom/` 作为前缀保存。例如 `Test_BG.PNG` 会保存为 `custom/test_bg.png`。
- **加载时机**：图片在客户端启动时加载；添加、删除或替换图片后需要重启客户端才会重新扫描。
- **缺失处理**：如果 Monitor 保存的自定义背景文件已经不存在，渲染时会回退到默认背景。
