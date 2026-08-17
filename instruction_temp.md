# CC:T 联动 — Lua API 记录（Monitor + 模块）

> 记录到目前为止已实现的 Lua 方法。
> 入口有两种：
> 1. 通过 Peripheral Extender 的频道系统：`local pe = require("ccpe.pe")` → `pe.getPeripheral(ch)`
> 2. 计算机紧贴 Monitor 时：`peripheral.wrap("right")`（或 `peripheral.find("ccpe:monitor")`）

---

## 0. 获取 Monitor 外设

Monitor 的 CC:T 外设类型名是 `"ccpe:monitor"`。

```lua
-- 方式 A：通过频道拿到 Monitor 自己（推荐，跨距离）
local pe = require("ccpe.pe")
local monitor = pe.getPeripheral(3)   -- 3 是 Monitor 的全局频道号

-- 方式 B：计算机紧贴 Monitor 时直接 wrap
local monitor = peripheral.wrap("right")
```

`monitor` 本身提供模块/屏幕的查询方法（见下）。查到的「模块实例」再提供各自的 get/set 方法。

---

## 1. Monitor 外设方法

### `monitor.getCellModule(x, y)`

- **参数**：`x`（0..11）、`y`（0..9）——格子坐标
- **返回**：该格子上的模块实例（`ModuleHandle`）；若格子被屏幕占用则返回屏幕实例；空格/越界返回 `nil`

```lua
local mod = monitor.getCellModule(3, 4)
if mod then
    print(mod.getId(), mod.getType())  -- 例：7  toggle_switch
end
```

### `monitor.getModule(id)`

- **参数**：`id`——模块/屏幕 ID（模块与屏幕共用同一 ID 命名空间）
- **返回**：对应模块/屏幕实例；不存在返回 `nil`

```lua
local mod = monitor.getModule(7)
if mod then print(mod.getType()) end
```

### `monitor.playNiceSound()`

播放 Create 风格的下单音效 + WiFi 粒子（效果位置在方块中心，音效为 `create:stock_ticker_request`）。
音效在服务端广播给附近玩家；WiFi 粒子走自定义 clientbound 包（`ccpe:play_order_effect`）广播给 32 格内的客户端，由客户端本地生成（Create 的 `WiFiParticle` 数据无法走粒子网络通道编码）。

```lua
monitor.playNiceSound()
```

### `monitor.playSound(sound)`

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

> 音效类方法依赖 Create 模组（运行时存在 `create` 模组，音效资源来自 `create` 命名空间）。

---

## 2. 模块实例通用方法（所有 handle 都有）

以下方法对所有模块类型（button_1 / toggle_switch / knob）以及屏幕（screen）都可用。

### `handle.getId()`

返回该模块/屏幕在本 Monitor 内的唯一 ID（数字）。

```lua
print(mod.getId())   -- 7
```

### `handle.getType()`

返回类型名字符串：`"button_1"`、`"toggle_switch"`、`"knob"` 或 `"screen"`。

```lua
print(mod.getType()) -- toggle_switch
```

### `handle.getX()` / `handle.getY()`

返回模块/屏幕左上角所在格子的坐标（数字）。

```lua
print(mod.getX(), mod.getY())  -- 3  4
```

### `handle.getWidth()` / `handle.getHeight()`

返回占用尺寸（格数，数字）。例如旋钮（knob）为 2×2。

```lua
print(mod.getWidth(), mod.getHeight())  -- 2  2
```

### `handle.setTooltip(tooltip)`

设置该模块在配置界面/悬停时显示的 tooltip 文本。

- 普通模块：写入配置的 `"text"` 键（悬停与配置界面显示）
- 屏幕（screen）：写入屏幕的悬停说明文字（`tooltipText`）

```lua
mod.setTooltip("喂料阀门")
screen.setTooltip("压力表")
```

---

## 3. 按钮（button_1）

按钮本身为**瞬时型**：按下/弹起是分开的，`isPressed` 读取当前按下状态。
此外还提供「玩家点击检测」「玩家互动锁」「灯带控制」三组能力，可组合出锁存按钮等自定义行为。
`press()` / `release()` 会播放对应的按键音效——**音效由按钮的实际动作（运动）触发**，而不是玩家的原始鼠标输入。

### 基础：按下 / 弹起 / 状态

#### `btn.press()`

按下按钮，并播放按下音效（灯带在「自动模式」下会随按下点亮）。

```lua
btn.press()
```

#### `btn.release()`

弹起按钮，并播放弹起音效（灯带在「自动模式」下会随弹起熄灭）。

```lua
btn.release()
```

#### `btn.isPressed()`

返回当前是否处于按下状态（布尔）。

```lua
btn.press()
print(btn.isPressed())  -- true
btn.release()
print(btn.isPressed())  -- false
```

### 玩家点击检测

> 用于区分「玩家点击」与「Lua 调用 press/release」。只有玩家实际点击（客户端→服务端的交互包）才会更新下列状态，`btn.press()` 不计数。

#### `btn.wasClicked()`

返回自上次读取以来按钮是否被玩家**按下**过（只在按下边沿 0→1 触发，松开鼠标不触发；读取后自动清除标志，适合边沿检测）。

```lua
while true do
    if btn.wasClicked() then
        print("玩家点击了按钮")
    end
    os.sleep(0.05)
end
```

#### `btn.getClickCount()`

返回玩家累计点击次数（每次玩家按下 +1；Lua 的 `press()` 不计入）。

```lua
local last = btn.getClickCount()
while true do
    local now = btn.getClickCount()
    if now ~= last then
        print("新增点击", now - last, "次")
        last = now
    end
    os.sleep(0.05)
end
```

#### `btn.clearClicked()`

清除「未读点击」标志（不读取）。

```lua
btn.clearClicked()
```

### 玩家互动锁（Lua 完全控制按钮）

#### `btn.setPlayerControl(enabled)`

设置玩家互动开关。

- `true`（默认）：玩家可按下/弹起按钮，行为照常
- `false`：按钮由 Lua 完全控制——玩家点击**不会**改变按下状态、也**不会**直接播放音效，但仍会更新 `wasClicked()` / `getClickCount()`（音效由 `press()` / `release()` 触发，跟随按钮实际动作）

```lua
btn.setPlayerControl(false)
```

#### `btn.getPlayerControl()`

返回当前是否允许玩家互动（布尔，默认 `true`）。

```lua
print(btn.getPlayerControl())  -- true
```

### 灯带控制

#### `btn.setLight(level)`

设置灯带亮度（0..1），并自动切换到「代码控制」模式（此后玩家互动不再改变灯带）。

- `0` = 熄灭，`1` = 最亮

```lua
btn.setLight(1)   -- 点亮
btn.setLight(0)   -- 熄灭
```

#### `btn.getLight()`

返回 Lua 设定的灯带亮度（0..1，默认 0）。

```lua
print(btn.getLight())  -- 1.0
```

#### `btn.setLightControl(codeControlled)`

设置灯带是否由代码控制。

- `true`：灯带亮度只随 `setLight` 改变（玩家互动不影响灯带）
- `false`（默认）：自动模式，灯带随按下状态点亮/熄灭

```lua
btn.setLightControl(true)
btn.setLightControl(false)
```

#### `btn.isLightControlled()`

返回灯带当前是否由代码控制（布尔）。

```lua
print(btn.isLightControlled())  -- false
```

### 示例 1：Lua 控制的锁存按钮（灯带自动跟随）

不控制灯带（保持默认「自动模式」），只用玩家互动锁接管按钮行为，把瞬时按钮变成「点击翻转」的锁存按钮：灯带自动跟随按下状态点亮。

```lua
local pe = require("ccpe.pe")
local monitor = pe.getPeripheral(3)
local btn = monitor.getModule(0)

-- 锁住玩家互动：玩家点击不再直接改变按钮状态，脚本全权决定按下/弹起
btn.setPlayerControl(false)

local latched = false

while true do
    if btn.wasClicked() then
        latched = not latched
        if latched then
            btn.press()    -- 按下：播放按下音效，灯带自动点亮
        else
            btn.release()  -- 弹起：播放弹起音效，灯带自动熄灭
        end
    end
    os.sleep(0.05)
end
```

### 示例 2：灯带表示状态的锁存按钮（按钮保持瞬时）

不控制按钮按下/弹起行为（保持玩家可互动的瞬时按钮），只用灯带表示锁存状态：每次点击翻转灯带，灯带不再跟随瞬时按压。

```lua
local pe = require("ccpe.pe")
local monitor = pe.getPeripheral(3)
local btn = monitor.getModule(0)

-- 灯带交给 Lua（setLight 会自动切到代码控制模式，不再跟随按下）
btn.setLight(0)

local latched = false

while true do
    if btn.wasClicked() then
        latched = not latched
        if latched then
            btn.setLight(1)   -- 灯带点亮 = 开
        else
            btn.setLight(0)   -- 灯带熄灭 = 关
        end
    end
    os.sleep(0.05)
end
```

---

## 4. 钮子开关（toggle_switch）

钮子开关为锁存型：状态保持，直到再次改变。状态变化会同步客户端渲染。

### `sw.getToggleState()`

返回当前锁存状态（布尔）。

```lua
print(sw.getToggleState())  -- false
```

### `sw.setToggleState(state)`

设置锁存状态。`true` = 打开（按下），`false` = 关闭（弹起）。

```lua
sw.setToggleState(true)
sw.setToggleState(false)
```

### `sw.toggle()`

反转锁存状态（等价于玩家点击拉杆）。

```lua
sw.toggle()
```

---

## 5. 旋钮（knob）

角度单位为**度**，范围 0..360。

### `knob.getAngle()`

返回当前角度（数字，0..360）。

```lua
print(knob.getAngle())  -- 45.0
```

### `knob.setAngle(angle)`

设置角度（数字，度）。自动归一化到 0..360；开启卡位（detent）时会吸附到最近档位。

```lua
knob.setAngle(180)
knob.setAngle(90)   -- 若开了 45° 卡位，会吸附到 90
```

---

## 6. 屏幕（screen）

屏幕与普通模块共用 ID 命名空间，`getType()` 返回 `"screen"`。屏幕支持**文本渲染**和**矩形绘制**。

- 字号（`setTextScale`）决定每屏能显示的行列数，行列数随字号自动重算（见 `getSize`）。
- 文本有溢出模式（`setOverflowMode`），控制超出一行时的行为。
- 矩形坐标使用「屏幕局部像素」：原点在屏幕**左上角**，`x` 向右、`y` 向下，单位 = 1 像素 = 1 格 = 1/16 块。

### `screen.getTooltip()` / `screen.setTooltip(tooltip)`

屏幕的悬停说明文字（继承自通用方法 `setTooltip`）。

```lua
print(screen.getTooltip())  -- 压力表
screen.setTooltip("压力表")
```

### `screen.write(text, z?)`

在光标处写入文本（支持 `\n` 换行、忽略 `\r`）。每个字符占据的位置用 [`drawRect`](#screendrawrectx-y-width-height-colour-solid-linewidth) 坐标直接定位：
写一个字符光标向右推进一个字形宽（`字号 × 1.0 × 8`），`\n` 回行首并下移一行（`字号 × 1.2 × 8`）。
写到屏幕右缘时按 [`setOverflowMode`](#screensetoverflowmodemode--screengetoverflowmode) 处理（默认 `"wrap"` 换行）。

可选参数 `z` 指定本次写入字符的层级（越大越靠前），省略时使用 [`setZIndex`](#screensetzindexz--screengetzindex) 设置的默认层级。

```lua
screen.write("Hello\nCCPE")
screen.write("Top", 2)          -- 层级 2
```

### `screen.clear()`

清空屏幕文本和所有图形（矩形/线段/圆），并把光标重置到 `(0, 0)`。

### `screen.setCursorPos(x, y)` / `screen.getCursorPos()`

设置/读取光标位置，坐标系统与 [`drawRect`](#screendrawrectx-y-width-height-colour-solid-linewidth) 的前两个参数完全一致：
以屏幕内区左上角为原点，X 向右、Y 向下，1 单位 = 1/128 块。
`getCursorPos` 返回 `x, y` 两个值。

```lua
screen.setCursorPos(0, 0)          -- 内区左上角
local x, y = screen.getCursorPos()
```

### `screen.setTextScale(scale)` / `screen.getTextScale()`

设置/读取整块屏幕的字号（字形高度，MC 像素，1px = 1/16 块）。字号只影响之后 `write` 写入的字形大小与推进量，
不影响已写入文本的位置（旧字符仍按各自位置渲染）。

```lua
screen.setTextScale(0.35)
print(screen.getTextScale())  -- 0.35
```

### `screen.setTextColour(colour)` / `screen.getTextColour()`

设置/读取前景色（0xRRGGBB）。文本**没有背景色**，需要背景时自己用 [`drawRect`](#screendrawrectx-y-width-height-colour-solid-linewidth) 画。

```lua
screen.setTextColour(0x00FF00)
```

### `screen.setZIndex(z)` / `screen.getZIndex()`

设置/读取之后 `write` / `drawRect` 未显式指定 z 时使用的默认层级（默认 0，越大越靠前，负值会被压进面板后面）。

```lua
screen.setZIndex(2)
screen.write("Hello")           -- 用默认层级 2
screen.drawRect(0, 0, 4, 4, 0xFF0000, true, 1)   -- 也用层级 2
```

### `screen.setOverflowMode(mode)` / `screen.getOverflowMode()`

设置/读取文本超出一行宽度时的处理方式：

| mode | 含义 |
|---|---|
| `"truncate"` | 直接截断，丢弃超出部分 |
| `"ellipsis"` | 多截断一点，末尾补 `"..."` |
| `"wrap"` | 自动换到下一行（默认） |

```lua
screen.setOverflowMode("ellipsis")
```

### `screen.drawRect(x, y, width, height, colour, solid, lineWidth, z?)`

在屏幕上画一个矩形。坐标与文本/光标共用同一套系统。

- `x, y`：左上角（1/128 块，0 = 内区左/上缘，向右/下增大）
- `width, height`：宽高（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `solid`：`true` = 实心，`false` = 只描边
- `lineWidth`：线宽（1/128 块，仅描边时生效）
- `z`：层级（越大越靠前，省略时使用 [`setZIndex`](#screensetzindexz--screengetzindex) 设置的默认层级）

```lua
screen.drawRect(0, 0, 2, 2, 0xFF0000, true, 1)        -- 实心红块，默认层级
screen.drawRect(1, 1, 1, 1, 0x00FF00, false, 0.2)     -- 绿色描边，默认层级
screen.drawRect(0, 0, 8, 8, 0x0000FF, true, 1, 5)     -- 层级 5，盖在其它之上
```

### `screen.drawLine(x1, y1, x2, y2, colour, lineWidth, z?)`

画一条线段。坐标与 `drawRect` 共用同一套系统。

- `x1, y1` / `x2, y2`：起终点（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `lineWidth`：线宽（1/128 块）
- `z`：层级（越大越靠前，省略时用 [`setZIndex`](#screensetzindexz--screengetzindex) 默认层级）

```lua
screen.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
```

### `screen.drawCircle(cx, cy, radius, colour, solid, lineWidth, segments?, z?)`

画一个圆（用正多边形逼近）。坐标与 `drawRect` 共用同一套系统。

- `cx, cy`：圆心（1/128 块）
- `radius`：半径（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `solid`：`true` = 实心圆，`false` = 圆环
- `lineWidth`：线宽（1/128 块，仅 `solid=false` 时生效）
- `segments`：逼近段数（默认 32，最小 3，越大越圆）
- `z`：层级（越大越靠前，省略时用 [`setZIndex`](#screensetzindexz--screengetzindex) 默认层级）

```lua
screen.drawCircle(8, 8, 4, 0xFFFF00, true, 1)          -- 实心圆
screen.drawCircle(8, 8, 4, 0x00FF00, false, 0.2, 48)   -- 48 段圆环
```

### `screen.drawPoint(x, y, colour, z?)`

画一个点（等价于 1×1 单位的实心矩形）。坐标与 `drawRect` 共用同一套系统。

- `x, y`：左上角坐标（1/128 块）
- `colour`：颜色（0xRRGGBB）
- `z`：层级（越大越靠前，省略时用 [`setZIndex`](#screensetzindexz--screengetzindex) 默认层级）

```lua
screen.drawPoint(4, 4, 0xFF0000)
```

### `screen.clearRects()`

清空所有已画的矩形（不影响文本和其它图形）。

### `screen.clearShapes()`

清空所有图形（矩形 + 线段 + 圆 + 点），不影响文本。

> 层级提醒：`z` 越大越靠前，但每 +1 前移约 0.01px，**建议 z 在 `[-1, 10]` 左右**；设太大侧面看会分层、有穿帮感。

### `screen.getSize()`

返回当前字号下屏幕内区能容纳的整字行列数（参考值，文本实际按坐标定位，不受此限制），返回 `cols, rows` 两个值。

```lua
local cols, rows = screen.getSize()
print(cols, rows)
```

---

## 附：约定与说明

- **mainThread 规则**：所有纯 **get** 方法 `mainThread = false`（计算机线程直接读，低延迟）；所有 **set / 动作** 方法 `mainThread = true`（服务器主线程写，安全）。注意 `wasClicked()` / `clearClicked()` 属于「读取并清除」，也算动作，走 `mainThread = true`。
- **格子坐标**：`x` 0..11，`y` 0..9（12×10 网格）。
- **模块 ID**：同一 Monitor 内唯一，模块与屏幕共用命名空间。
- **返回 nil**：查询不到（空格 / 无效 ID）时返回 `nil`。

### 完整示例

```lua
local pe = require("ccpe.pe")
local monitor = pe.getPeripheral(3)

-- 遍历一个格子上的模块
local mod = monitor.getCellModule(3, 4)
if mod then
    print("type:", mod.getType(), "id:", mod.getId())
    mod.setTooltip("由 Lua 设置的说明")

    if mod.getType() == "toggle_switch" then
        mod.setToggleState(true)
    elseif mod.getType() == "knob" then
        mod.setAngle(135)
    elseif mod.getType() == "button_1" then
        mod.press()
    end
end
```
