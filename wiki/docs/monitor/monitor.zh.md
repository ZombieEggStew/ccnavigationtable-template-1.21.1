# 监视器

![监视器](../img/my_monitor_item.png)

获得 Monitor 外设实例后（获取方式见 [概述](overview.md)），可以调用以下三类方法：

| 类别 | 方法 |
|---|---|
| 模块 / 屏幕查询 | `getCellModule` / `getModule` |
| 音效 | `playNiceSound` / `playSound` |

!!! warning "文本与图形只能在屏幕模块上绘制"
    Monitor 本体**不再提供背景平面绘制 API**（旧版的 `write` / `clear` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `setCursorPos` / `setTextScale` / `setTextColour` / `setZIndex` / `setOverflowMode` / `clearRects` / `clearShapes` / `getSize` 已移除）。
    要在 Monitor 上显示内容，请安装**屏幕模块**（screen），所有文本/图形 API 见 [屏幕模块](screen.md)。Monitor 的网格线与背景贴图保留不变。

---

## 操作说明

- **配置**：对准监视器底座 蹲下 + 右键 可以打开配置界面，可以配置以下选项：
    - 频道：设置 Monitor 的全局频道号，与 外设扩展器 共用一个频道系统
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

---

## 线程模型（mainThread）

| 方法 | mainThread |
|---|---|
| `getCellModule` / `getModule` | ✅ `true`（查询也走服务器主线程） |
| `playNiceSound` / `playSound` | ✅ `true` |

---

## Monitor 自定义背景图片

- **目录**：将图片放入游戏根目录下的 `ccpe_res/monitor_bg/`。该目录与 `mods/`、`resourcepacks/` 同级；只扫描该目录的第一层，不扫描子目录。
- **支持格式**：支持 `.png`、`.jpg` 和 `.jpeg`，扩展名不区分大小写。
- **文件名规则**：文件名必须以字母或数字开头，只能包含小写/大写字母、数字、下划线、连字符和点号，例如 `test_bg.png`、`cockpit-01.jpg`。不符合规则的文件会被忽略。
- **选项名称**：客户端启动时扫描图片，并将文件名追加到 Monitor 右键菜单的背景切换选项中。菜单中的自定义背景名称显示为文件名，例如 `test_bg.png`。
- **持久化键**：文件名会转换为小写，并以 `custom/` 作为前缀保存。例如 `Test_BG.PNG` 会保存为 `custom/test_bg.png`。
- **加载时机**：图片在客户端启动时加载；添加、删除或替换图片后需要重启客户端才会重新扫描。
- **缺失处理**：如果 Monitor 保存的自定义背景文件已经不存在，渲染时会回退到默认背景。
