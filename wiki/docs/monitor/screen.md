# 屏幕模块

![屏幕模块](../img/screen.png)

屏幕与普通模块共用 ID 命名空间，`getType()` 返回 `"screen"`。屏幕支持**文本渲染**和**图形绘制**。

- 字号（`setTextScale`）决定每屏能显示的行列数，行列数随字号自动重算（见 `getSize`）。
- 文本有溢出模式（`setOverflowMode`），控制超出一行时的行为。
- 图形坐标使用「屏幕局部像素」：原点在屏幕**左上角**，`x` 向右、`y` 向下，单位 = 1 像素 = 1 格 = 1/16 块。


## 操作说明
- **放置屏幕**：右键一个空格子，作为锚点，再右键一个空格子，屏幕会占用这两个格子形成一个矩形区域（最小 2×2）。
- **配置模块**：手持扳手对准模块右键 或者 蹲下+右键 可以打开模块配置界面，配置模块 ID、tooltip等属性
- **拆卸模块**：手持扳手蹲下右键 可以拆卸模块


## 文本渲染

### screen.write(text, z?)

在光标处写入文本（支持 `\n` 换行、忽略 `\r`）。每个字符占据的位置用 `drawRect` 坐标直接定位：
写一个字符光标向右推进一个字形宽（`字号 × 1.0 × 8`），`\n` 回行首并下移一行（`字号 × 1.2 × 8`）。
写到屏幕右缘时按 `setOverflowMode` 处理（默认 `"wrap"` 换行）。

可选参数 `z` 指定本次写入字符的层级（越大越靠前），省略时使用 `setZIndex` 设置的默认层级。

```lua
screen.write("Hello\nCCPE")
screen.write("Top", 2)          -- 层级 2
```

### screen.clear()

清空屏幕文本和所有图形（矩形/线段/圆），并把光标重置到 `(0, 0)`。

### screen.setCursorPos(x, y) / screen.getCursorPos()

设置/读取光标位置，坐标系统与 `drawRect` 的前两个参数完全一致：
以屏幕内区左上角为原点，X 向右、Y 向下，1 单位 = 1/128 块。
`getCursorPos` 返回 `x, y` 两个值。

```lua
screen.setCursorPos(0, 0)          -- 内区左上角
local x, y = screen.getCursorPos()
```

### screen.setTextScale(scale) / screen.getTextScale()

设置/读取整块屏幕的字号（字形高度，MC 像素，1px = 1/16 块）。字号只影响之后 `write` 写入的字形大小与推进量，
不影响已写入文本的位置（旧字符仍按各自位置渲染）。

```lua
screen.setTextScale(0.35)
print(screen.getTextScale())  -- 0.35
```

### screen.setTextColour(colour) / screen.getTextColour()

设置/读取前景色（0xRRGGBB）。文本**没有背景色**，需要背景时自己用 `drawRect` 画。

```lua
screen.setTextColour(0x00FF00)
```

### screen.setZIndex(z) / screen.getZIndex()

设置/读取之后 `write` / `drawRect` 未显式指定 z 时使用的默认层级（默认 0，越大越靠前，负值会被压进面板后面）。

```lua
screen.setZIndex(2)
screen.write("Hello")           -- 用默认层级 2
screen.drawRect(0, 0, 4, 4, 0xFF0000, true, 1)   -- 也用层级 2
```

### screen.setOverflowMode(mode) / screen.getOverflowMode()

设置/读取文本超出一行宽度时的处理方式：

| mode | 含义 |
|---|---|
| `"truncate"` | 直接截断，丢弃超出部分 |
| `"ellipsis"` | 多截断一点，末尾补 `"..."` |
| `"wrap"` | 自动换到下一行（默认） |

```lua
screen.setOverflowMode("ellipsis")
```

## 图形绘制

### screen.drawRect(x, y, width, height, colour, solid, lineWidth, z?)

在屏幕上画一个矩形。坐标与文本/光标共用同一套系统。

- `x, y`：左上角（1/128 块，0 = 内区左/上缘，向右/下增大）
- `width, height`：宽高（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `solid`：`true` = 实心，`false` = 只描边
- `lineWidth`：线宽（1/128 块，仅描边时生效）
- `z`：层级（越大越靠前，省略时使用 `setZIndex` 设置的默认层级）

```lua
screen.drawRect(0, 0, 2, 2, 0xFF0000, true, 1)        -- 实心红块，默认层级
screen.drawRect(1, 1, 1, 1, 0x00FF00, false, 0.2)     -- 绿色描边，默认层级
screen.drawRect(0, 0, 8, 8, 0x0000FF, true, 1, 5)     -- 层级 5，盖在其它之上
```

### screen.drawLine(x1, y1, x2, y2, colour, lineWidth, z?)

画一条线段。坐标与 `drawRect` 共用同一套系统。

- `x1, y1` / `x2, y2`：起终点（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `lineWidth`：线宽（1/128 块）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
screen.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
```

### screen.drawCircle(cx, cy, radius, colour, solid, lineWidth, segments?, z?)

画一个圆（用正多边形逼近）。坐标与 `drawRect` 共用同一套系统。

- `cx, cy`：圆心（1/128 块）
- `radius`：半径（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `solid`：`true` = 实心圆，`false` = 圆环
- `lineWidth`：线宽（1/128 块，仅 `solid=false` 时生效）
- `segments`：逼近段数（默认 32，最小 3，越大越圆）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
screen.drawCircle(8, 8, 4, 0xFFFF00, true, 1)          -- 实心圆
screen.drawCircle(8, 8, 4, 0x00FF00, false, 0.2, 48)   -- 48 段圆环
```

### screen.drawPoint(x, y, colour, z?)

画一个点（等价于 1×1 单位的实心矩形）。坐标与 `drawRect` 共用同一套系统。

- `x, y`：左上角坐标（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
screen.drawPoint(4, 4, 0xFF0000)
```

### screen.clearRects()

清空所有已画的矩形（不影响文本和其它图形）。

### screen.clearShapes()

清空所有图形（矩形 + 线段 + 圆 + 点），不影响文本。

!!! tip "层级提醒"
    `z` 越大越靠前，但每 +1 前移约 0.01px，**建议 z 在 `[-1, 10]` 左右**；设太大侧面看会分层、有穿帮感。

## 尺寸

### screen.getSize()

返回当前字号下屏幕内区能容纳的整字行列数（参考值，文本实际按坐标定位，不受此限制），返回 `cols, rows` 两个值。

```lua
local cols, rows = screen.getSize()
print(cols, rows)
```
