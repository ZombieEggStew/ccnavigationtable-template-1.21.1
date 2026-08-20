# 监视器

![监视器](../img/my_monitor_item.png)

获得 Monitor 外设实例后（获取方式见 [概述](overview.md)），可以调用以下方法查询模块/屏幕、播放音效。

## monitor.getCellModule(x, y)

- **参数**：`x`（0..11）、`y`（0..9）——格子坐标
- **返回**：该格子上的模块实例（`ModuleHandle`）；若格子被屏幕占用则返回屏幕实例；空格/越界返回 `nil`

```lua
local mod = monitor.getCellModule(3, 4)
if mod then
    print(mod.getId(), mod.getType())  -- 例：7  toggle_switch
end
```

## monitor.getModule(id)

- **参数**：`id`——模块/屏幕 ID（模块与屏幕共用同一 ID 命名空间）
- **返回**：对应模块/屏幕实例；不存在返回 `nil`

```lua
local mod = monitor.getModule(7)
if mod then print(mod.getType()) end
```

## monitor.playNiceSound()

播放 Create 风格的下单音效 + WiFi 粒子（效果位置在方块中心，音效为 `create:stock_ticker_request`）。
音效在服务端广播给附近玩家；WiFi 粒子走自定义 clientbound 包（`ccpe:play_order_effect`）广播给 32 格内的客户端，由客户端本地生成（Create 的 `WiFiParticle` 数据无法走粒子网络通道编码）。

```lua
monitor.playNiceSound()
```

## monitor.playSound(sound)

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

## Monitor 自定义背景图片

- **目录**：将图片放入游戏根目录下的 `ccpe_res/monitor_bg/`。该目录与 `mods/`、`resourcepacks/` 同级；只扫描该目录的第一层，不扫描子目录。
- **支持格式**：支持 `.png`、`.jpg` 和 `.jpeg`，扩展名不区分大小写。
- **文件名规则**：文件名必须以字母或数字开头，只能包含小写/大写字母、数字、下划线、连字符和点号，例如 `test_bg.png`、`cockpit-01.jpg`。不符合规则的文件会被忽略。
- **选项名称**：客户端启动时扫描图片，并将文件名追加到 Monitor 右键菜单的背景切换选项中。菜单中的自定义背景名称显示为文件名，例如 `test_bg.png`。
- **持久化键**：文件名会转换为小写，并以 `custom/` 作为前缀保存。例如 `Test_BG.PNG` 会保存为 `custom/test_bg.png`。
- **加载时机**：图片在客户端启动时加载；添加、删除或替换图片后需要重启客户端才会重新扫描。
- **缺失处理**：如果 Monitor 保存的自定义背景文件已经不存在，渲染时会回退到默认背景。
