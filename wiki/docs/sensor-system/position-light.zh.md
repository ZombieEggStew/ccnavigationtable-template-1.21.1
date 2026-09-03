# 航行灯 （1.1.2加入）

![航行灯](../img/position_light.png)

>航行灯（Navigation Light），也叫导航灯或位置灯，核心作用是在夜间或能见度低时，让其他飞行员能判断你飞机的位置、运动方向和大概类型，以避免相撞。所有飞机都必须遵守一个共同的基本规则。

>- 左翼尖：红色航行灯
>- 右翼尖：绿色航行灯
>- 尾部：白色航行灯

>这个规则源于航海，便于飞行员快速判断对方飞行方向。例如，若看到对方飞机左侧是红光、右侧是绿光，说明你们同向飞行；若看到的正好相反（左绿右红），则意味着你们面对面相向飞行，需要立即警惕

**航行灯**是可安装在物理体（Sable sub-level）上的照明方块，共有三种颜色：

| 方块 | ID |
|---|---|
| 红色航行灯 | `ccpe:red_position_light` |
| 绿色航行灯 | `ccpe:green_position_light` |
| 白色航行灯 | `ccpe:white_position_light` |

## Lua 控制（FMC 门控）

电脑必须在物理体上，且物理体（含约束链）上必须装有 **≥1 个 FMC**（`ccpe:fmc`），否则以下方法返回 `0`。

| 方法 | 返回 | 说明 |
|---|---|---|
| `setLights(color, on)` | number | 按颜色开关全部航行灯。`color` = `"red"` / `"green"` / `"white"` / `"all"`（大小写不敏感）。返回实际改变状态的灯数。 |
| `setAllLights(on)` | number | 开关**全部**颜色的航行灯。等价于 `setLights("all", on)`。返回实际改变状态的灯数。 |

## 示例

```lua
local ss = require("ccpe.sensor_system")

local red = ss.setLights("red", true)  -- 打开全部红色航行灯
local all = ss.setAllLights(false)     -- 全部关闭
print("红色航行灯亮:", red, "关闭数:", all)
```
