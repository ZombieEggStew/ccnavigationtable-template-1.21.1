# 引擎 Lua API

引擎通过 CC:Tweaked 的 `ccpe:engine` 外设控制。**包裹任意核心节**——外设挂在引擎 controller 上，非 controller 节委托给它：

```lua
local e = peripheral.wrap("front")
-- 或：peripheral.find("ccpe:engine")
```

## 读与写

- **读方法** `mainThread=false` 直读 controller 缓存状态——最多滞后 1 tick，高频轮询几乎零成本。
- **写方法**与 `getFluidTanks()` `mainThread=true`（服务端权威，需扫描世界）。

## 方法表

| 方法 | mainThread | 返回 | 说明 |
|---|---|---|---|
| `getTemperature()` | false | number | 引擎温度 °C |
| `isOverheated()` | false | boolean | 过热锁定（仅流体引擎：T≥220°C 硬停，T≤200°C 解锁） |
| `getFluidTanks()` | true | table[] | 所有连接储罐：`{fluid, amount, remaining, capacity}`（空罐 fluid 为 nil） |
| `getThrottle()` | false | number | 油门 0..1（默认 0.25）。应力与转速同比例（0 = 停机不烧油，唯一启停控制） |
| `setThrottle(0~1)` | true | boolean | 设置油门（越界钳制；非法参数返回 false） |
| `getMixture()` | false | number | 混合比杆 0.6..1.4（仅流体引擎；蒸汽恒 1.0） |
| `setMixture(x)` | true | boolean | 混合比：油耗 × 杆值、温度 × 热因子；**无门控**（纯存值，非流体引擎不生效） |
| `getEffectiveMixture()` | false | number | 实际混合比 = 杆 × 高空自动富油（经济窗口与发热反馈用；高空 > 杆值） |
| `getFuelEconomyFactor()` | false | number | 经济系数 0.75..1.0（温度+混合比双达标渐入 15s；混合比不对无奖励无惩罚） |
| `hasAirDuct()` | false | boolean | 是否装冷却气道（信息用；setMixture/setCooling 均无门控，装风道时风门值才被 K_DUCT 分量消费） |
| `getActiveFuel()` | false | table | 活动燃料：流体 `{type="fluid", fluid=<id>, optimalTemp=155}`；蒸汽 `{type="steam", optimalTemp=155}`；停机 `{type="none"}` |
| `getCooling()` | false | number | 冷却风门 0..1（默认 1.0） |
| `setCooling(0~1)` | true | boolean | 风门：只缩 K_DUCT 风道散热分量，只能降；**无门控**（纯存值，仅装冷却气道时被消费） |
| `isWarmingUp()` | false | boolean | 蒸汽暖机中（T<100°C 只烧不发电） |

## 示例——巡航控制

```lua
local e = peripheral.wrap("front")

-- 启动
e.setThrottle(0.25)          -- 25% 油门：64rpm，蒸汽燃料耐用 4 倍

-- 经济巡航（流体引擎）：拉杆做海拔补偿
local m = e.getEffectiveMixture()
if m > 1.05 then
    e.setMixture(math.max(0.6, e.getMixture() - 0.05))  -- 朝 m_eff ≈ 1.0 拉稀
end

-- 盯温度
print(string.format("T = %.1f°C  eco = %.2f  fuel = %s",
    e.getTemperature(), e.getFuelEconomyFactor(), e.getActiveFuel().type))

-- 蒸汽？等暖机再推满
if e.isWarmingUp() then
    print("warming up...")
else
    e.setThrottle(1.0)
end
```

## 运行条件（全部满足才发电）

- ≥ 1 运行燃烧室，**且**
- 油门 > 0，**且**
- 未过热（仅流体引擎；220/200°C 滞回），**且**
- 蒸汽引擎：T≥100°C **且**水可用。

**过载不停机**——对齐 Create 发电机：照常运行烧油，goggle 顶部红字「网络过载」提示。停机原因：缺燃料/缺水、油门 0、流体过热（T≤200°C 恢复）、蒸汽暖机未完成。

## 状态档位（流体引擎，按 `getTemperature()`）

| 温度区间 | 状态 |
|---|---|
| —（!running） | 停机 |
| < ~97°C | 过冷（油耗惩罚中） |
| 97 ~ 145°C | 正常 |
| 145 ~ 165°C | **高效**（经济带） |
| 165 ~ 200°C | 正常 |
| 200 ~ <220°C | 即将过热 |
| ≥ 220°C | 过热 |
