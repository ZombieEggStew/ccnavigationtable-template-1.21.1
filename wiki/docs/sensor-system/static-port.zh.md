# 静压孔

![static_port](../img/static_port.png)

>静压孔（Static Port）是测量大气静压的开口，通常开在机身侧面平整、远离机头气流扰动的位置——它只感受大气压力，不感受迎面气流带来的动压。

>静压孔与皮托管共同构成「皮托管-静压系统」——这套系统结冰或堵塞曾是多起空难的诱因，所以现代客机对它的冗余与防冰设计格外重视。

**静压孔**（Static Port，`ccpe:static_port`）是一个贴附式气压传感器方块。装在物理体（Sable sub-level）上时，物理体上的 CC:Tweaked 电脑可以通过 `ccpe.sensor_system` 读取该静压孔位置的气压与高度。

## 读数基准

`ccpe.sensor_system` 的读数基准是**静压孔自身的位置**（不是物理体原点）：

- 每个静压孔：plot 坐标投影到世界 → 该点高度（世界 Y）与气压（Sable `DimensionPhysicsData`，与 `simulated:altitude_sensor` 同源公式；海平面 = 1.0）。
- 一个物理体可以放多个静压孔，每个静压孔都有自己独立的读数。
 
## Lua API

电脑（需与静压孔在同一物理体，含约束链）通过 `require("ccpe.sensor_system")` 使用：

| 方法 | 返回 | 说明 |
|---|---|---|
| `isOnBody()` | boolean | 电脑是否在物理体上 |
| `getBodyId()` | string / nil | 所在物理体 UUID |
| `getSensors()` | table | 全部传感器快照（同一 tick）：`{type, pos={x,y,z}, pos_rel={x,y,z}, altitude, pressure}`；`pos` 相对**物理体原点**，`pos_rel` 相对**当前电脑**（推荐用于区分不同静压孔） |
| `getAltitude()` | number / nil | **最后放置的静压孔**的高度（世界 Y） |
| `getPressure()` | number / nil | **最后放置的静压孔**的气压（大气压分数，海平面 = 1.0） |
| `getAverageAltitude()` | number / nil | 全部静压孔高度的**简单平均值** |
| `getAveragePressure()` | number / nil | 全部静压孔气压的**简单平均值** |
| `getWeightedAltitude()` | number / nil | 全部静压孔高度的**距离加权平均值**（权重 = 1/距物理体原点距离，IDW） |
| `getWeightedPressure()` | number / nil | 全部静压孔气压的**距离加权平均值**（权重 = 1/距物理体原点距离，IDW） |

- 物理体上没有静压孔时，`getAltitude()/getPressure()` 返回 `nil`，`getSensors()` 返回空数组；平均值/加权平均值同样返回 `nil`。
- 读数每 tick 刷新（最多滞后 1 tick）；Lua 读取零主线程调度，高频调用开销可忽略。
- 加权平均的边界情况：静压孔恰在物理体原点（距离 ≈ 0）时权重无穷大，直接返回该孔读数；只有一个静压孔时平均值/加权平均值等于该孔读数。

```lua
local ss = require("ccpe.sensor_system")

print("onBody:", ss.isOnBody())
print("bodyId:", ss.getBodyId())

local sensors = ss.getSensors()
for i, s in ipairs(sensors) do
    print(i, s.type,
          "pos:", s.pos.x, s.pos.y, s.pos.z,
          "pos_rel:", s.pos_rel.x, s.pos_rel.y, s.pos_rel.z,
          s.altitude, s.pressure)
end

-- 便捷方法：最后放置的静压孔
print("alt:", ss.getAltitude(), "press:", ss.getPressure())

-- 平均值 / 距离加权平均（权重 = 1/距物理体原点距离）
print("avg alt:", ss.getAverageAltitude(), "avg press:", ss.getAveragePressure())
print("wavg alt:", ss.getWeightedAltitude(), "wavg press:", ss.getWeightedPressure())
```

## 多个静压孔

物理体上可以放置多个静压孔，每个位置有独立的 `altitude/pressure` 读数（同一 tick 快照，可用于压差等计算）：

```lua
local sensors = ss.getSensors()
if #sensors >= 2 then
    print("压差:", sensors[1].pressure - sensors[2].pressure)
end
```

!!! warning "服务器重启后 `getAltitude()/getPressure()` 可能指向不同的静压孔"
    如果物理体上只有一个静压孔，不需要在意此条警告

    `getAltitude()/getPressure()` 返回**最后放置**的静压孔的数据，其判定依据是注册顺序（= 放置顺序），该顺序**只在当前会话内有效**。服务器重启后，静压孔按区块加载顺序重新注册，这两个方法**可能指向另一个静压孔**，且每次重启之间指向也可能不同。

    如果脚本需要稳定地读取**特定**静压孔，请使用 `getSensors()` 按 `pos_rel`（相对当前电脑的局部坐标）区分——`pos`（相对物理体原点）会在物理体上增删方块时随原点（质心）漂移：

    ```lua
    local sensors = ss.getSensors()
    for _, s in ipairs(sensors) do
        if math.abs(s.pos_rel.y - 2) < 0.5 then  -- 例：电脑上方 2 格的那个静压孔
            print("该孔气压:", s.pressure)
        end
    end
    ```
