# 屏幕模块

![屏幕模块](../img/screen.png)

屏幕与普通模块共用 ID 命名空间，`getType()` 返回 `"screen"`。屏幕支持**文本渲染**（格子模型）和**图形绘制**。

屏幕采用**格子模型**（LCD 帧缓冲语义）：

- **文本层是定长格子数组**：先 `setGrid(cols, rows)` 设定格子数，格子铺满屏幕内区；每格 = 字符 + 前景色 + 背景色，**写入即覆盖该格**（同位置永远只有一个值，无重叠面片），内容体积固定、不随运行时长增长。
- **光标制定位**：`setCursorPos(col, row)`（1 起，CC:T 风格），`write` 从光标处逐格写入。
- **背景色**：`fill` 批量设置格子背景色，与 `write` 叠加即「色块 + 文字」。
- **整屏批量传输**：`draw(batch)` 一次调用传整屏内容，**原子替换**（无 clear+write 中间态闪烁）。
- **图形层**（`drawRect`/`drawLine`/`drawCircle`/`drawPoint`）保持**自由定位**（1/128 块坐标）与 **z 层级**，不受格子约束，但仅在屏幕可绘制区域内绘制。

## 操作说明
- **放置屏幕**：右键一个空格子，作为锚点，再右键一个空格子，屏幕会占用这两个格子形成一个矩形区域（最小 2×2）。
- **配置模块**：手持扳手对准模块右键 或者 蹲下+右键 可以打开模块配置界面，配置模块 ID、tooltip等属性
- **拆卸模块**：手持扳手蹲下右键 可以拆卸模块


## 格子布局

### screen.setGrid(cols, rows) / screen.getGrid()

设定屏幕格子数（cols × rows，最大 128×128），格子铺满屏幕内区，字形尺寸由格子反推（`cellW = 内区宽 / cols`）。
**重设会清空文本层**（CC:T resize 语义），光标回到 `(1, 1)`。

用户未 `setGrid` 之前使用默认格子数（12 × 10）。

```lua
screen.setGrid(10, 6)
local cols, rows = screen.getGrid()   -- 10, 6
```

### screen.setTextScale(scale, lineSpacing?) / screen.getTextScale()

`setGrid` 的**别名**（旧 Lua 程序调用不报错）：按格子反推字号，等价于重设格子数——
`cols = 内区宽 / scale`，`rows = 内区高 / (scale × lineSpacing)`。重设同样会清空文本层。

- `scale`：字号（MC 像素，1px = 1/16 块）
- `lineSpacing`：可选，**格子高/格子宽比**（行距系数，默认 1.2；传 1.0 得到正方形格子）

```lua
screen.setTextScale(0.35)            -- 默认高宽比 1.2（格子竖长）
screen.setTextScale(0.35, 1.0)       -- 正方形格子
screen.setTextScale(0.35, 1.5)       -- 更扁的格子
local cols, rows = screen.getTextScale()   -- 返回格子数（同 getGrid）
```

### screen.getSize()

返回当前格子数，与 `getGrid()` 相同，返回 `cols, rows` 两个值。

```lua
local cols, rows = screen.getSize()
print(cols, rows)
```


## 文本渲染（格子模型）

### screen.write(text)

从光标处逐格写入文本（支持 `\n` 换行、忽略 `\r`）。每写入一个字符**覆盖该格**（字符 + 当前前景色），光标右移一格；
**背景色保持不变**（`fill` 设置的填充色不被 `write` 覆盖，支持「色块 + 文字」叠加）。
到达行尾时按 `setOverflowMode` 处理（默认 `"wrap"` 换行）；写满最后一行之后继续写入会被丢弃。

```lua
screen.write("Hello\nCCPE")
```

### screen.clear()

清空屏幕全部内容（格子 + 图形 + 光标），**保留格子数**。

### screen.setCursorPos(col, row) / screen.getCursorPos()

设置/读取光标位置（**格子坐标，1 起**，CC:T 风格；自动收拢到格子范围内）。
`getCursorPos` 返回 `col, row` 两个值。

```lua
screen.setCursorPos(1, 1)          -- 左上角第一格
local col, row = screen.getCursorPos()
```

### screen.setTextColour(colour) / screen.getTextColour()

设置/读取前景色（0xRRGGBB，默认 `0xFFFFFF`），影响之后 `write` 写入的字符颜色。

```lua
screen.setTextColour(0x00FF00)
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


## 填充（背景色）

### screen.fill(col, row, w, h, colour)

批量设置格子**背景色**（纯色填充，分段进度条用）。只改背景色，字符与前景色不变。

- `col, row`：起始格（1 起）
- `w, h`：宽高（格，超出自动裁剪）
- `colour`：颜色（0xRRGGBB）

```lua
screen.fill(1, 1, 10, 1, 0xFF0000)   -- 第一行前 10 格红色底
screen.write("Loading")              -- 字画在红色底上
```


## 整屏批量传输

### screen.draw(batch)

**一次调用传整屏所有需要绘制的格子与可选图形，整屏原子替换**（服务端清空后重建，客户端收到完整新画面，**无中间态闪烁**）。
解析失败会抛 Lua 错误，整屏保持不变（不会部分应用）。

`batch` 为 Lua table，两段式结构：

- **`cells`**：每格一个数组 `{col, row, char, fg?, bg?}`（col/row **1 起**；`fg` 省略沿用当前前景色，`bg` 省略为透明）
- **`shapes`**（可选）：图形数组，每项为带 `type` 字段的 table：
  - `{type = "rect", x, y, w, h, colour, solid?, lineWidth?, z?}`
  - `{type = "line", x1, y1, x2, y2, colour, lineWidth?, z?}`
  - `{type = "circle", cx, cy, radius, colour, solid?, lineWidth?, segments?, z?}`
  - `{type = "point", x, y, colour, z?}`
  - `z` 省略用当前默认层级（`setZIndex`）

```lua
screen.draw({
  cells = {
    {1, 1, "A", 0xFFFFFF, 0x000000},   -- 第 1 行第 1 格：白字黑底
    {2, 1, "B", 0xFF0000},             -- 第 2 格：红字，透明底
  },
  shapes = {
    {type = "rect", x = 0, y = 0, w = 8, h = 8, colour = 0x00FF00, solid = true},
  },
})
```

配合每 tick 调用一次 `draw`，即「每 tick 一帧」的整屏刷新模式，全链路无中间态。


## 图形绘制（自由定位 + z 层级）

图形层坐标使用「屏幕局部坐标」：原点在可绘制区域**左上角**，`x` 向右、`y` 向下，单位 = 1/128 块（`1px = 8 单位`）。
图形层不受格子约束，但**仅在屏幕可绘制区域内绘制**。

### screen.setZIndex(z) / screen.getZIndex()

设置/读取之后 `drawRect`/`drawLine`/`drawCircle`/`drawPoint` 未显式指定 z 时使用的默认层级（默认 0，越大越靠前）。
仅图形层有层级，文本层（格子）没有 z。

```lua
screen.setZIndex(2)
screen.drawRect(0, 0, 4, 4, 0xFF0000, true, 1)   -- 用默认层级 2
```

### screen.drawRect(x, y, width, height, colour, solid, lineWidth, z?)

在屏幕上画一个矩形。

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

画一条线段。

- `x1, y1` / `x2, y2`：起终点（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `lineWidth`：线宽（1/128 块）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
screen.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
```

### screen.drawCircle(cx, cy, radius, colour, solid, lineWidth, segments?, z?)

画一个圆（用正多边形逼近）。

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

画一个点（等价于 1×1 单位的实心矩形）。

- `x, y`：左上角坐标（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `z`：层级（越大越靠前，省略时用 `setZIndex` 默认层级）

```lua
screen.drawPoint(4, 4, 0xFF0000)
```

### screen.clearRects()

清空所有已画的矩形（不影响文本和其它图形）。

### screen.clearShapes()

清空所有图形（矩形 + 线段 + 圆 + 点），不影响文本层。

!!! tip "层级提醒"
    `z` 越大越靠前，但每 +1 前移约 1/2048 块；**建议 z 在 `[-1, 10]` 左右**；设太大侧面看会分层、有穿帮感。
