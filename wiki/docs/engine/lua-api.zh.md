# 引擎 Lua API

引擎通过 CC:Tweaked 的 `ccpe:engine` 外设控制。**包裹任意核心节**——外设挂在引擎 controller 上，非 controller 节委托给它：

```lua
local e = peripheral.wrap("front")
-- 或：peripheral.find("ccpe:engine")
```

## [示例——温度与混合比控制](../examples/trainer_aircraft_2.zh.md)

> 引擎**默认自带**温度与混合比自动控制（不需要任何脚本），见[温度与混合比自动控制](auto-control.zh.md)。下面的方法用于**自己写**控制循环（先调 `setAutoControl(false)`）。

## 读与写

- **读方法** `mainThread=false` 直读 controller 缓存状态——最多滞后 1 tick，高频轮询几乎零成本。
- **写方法**与 `getFluidTanks()` `mainThread=true`（服务端权威，需扫描世界）。

## 方法表

| 方法 | mainThread | 返回 | 说明 |
|---|---|---|---|
| `getTemperature()` | false | number | 引擎温度 °C |
| `isOverheated()` | false | boolean | 过热锁定（仅流体引擎：T≥220°C 硬停，T≤200°C 解锁） |
| `getFluidTanks()` | true | table[] | 所有连接储罐：`{fluid, amount, remaining, capacity}`（空罐 fluid 为 nil） |
| `getThrottle()` | false | number | 油门 0..1（默认 0.25）。**只决定输出转速**（转速 = 油门 × 256 rpm；应力容量恒定；0 = 停机不烧油，唯一启停控制） |
| `getLoad()` | false | number | **负载** 0..1 = 应力网络「已用应力 / 可提供容量」（**实际值，不叠加怠速下限**；空转 0、停机 0）。**油耗与发热的驱动量**，适合做经济调度 |
| `getStressUsed()` | false | number | 所在网络的已用应力（SU，整网总量：Σ 机器应力影响 × 转速；停机/脱网 → 0） |
| `getStressCapacity()` | false | number | 所在网络的可提供容量（SU，整网总量；本引擎自身贡献恒定 = 8192×流体室 / 4096×蒸汽室） |
| `setThrottle(0~1)` | true | boolean | 设置油门（越界钳制；非法参数返回 false）。**只改转速**，不改应力容量；油耗随负载变化 |
| `isReversed()` / `setReversed(bool)` | false/true | boolean | **输出转向**：`false` = 正转（默认）、`true` = 反转。**只翻转向**——转速绝对值、应力容量、负载、油耗、发热全部不变（整条传动网络被反向驱动，下游机器朝相反方向转）；纯存值 + NBT 持久化（拆装/重进世界保持），核心 Goggle「目前转速」反转时显示负值 |
| `getMixture()` | false | number | 混合比杆 0.6..1.4（仅流体引擎；蒸汽恒 1.0）。只影响**给定负载下**的油耗与发热 |
| `setMixture(x)` | true | boolean | 混合比：油耗 × 杆值、温度 × 热因子；**无门控**（纯存值，非流体引擎不生效） |
| `getEffectiveMixture()` | false | number | 实际混合比 = 杆 × 高空自动富油（经济窗口与发热反馈用；高空 > 杆值） |
| `getAutoRichness()` | false | number | 自然高度混合比 = 仅高度自动浓稀系数（不含杆值）：海平面 1.0，高空最高 ×1.25，海平面以下高气压最低 ×0.75（自动稀油）（蒸汽恒 1.0）。海拔补偿参考——拉到 ≈ 1 / getAutoRichness() 使 m_eff ≈ 1.0 |
| `getFuelEconomyFactor()` | false | number | 经济系数 0.75..1.0（温度+混合比双达标渐入 15s；混合比不对无奖励无惩罚） |
| `hasAirDuct()` | false | boolean | 是否装冷却气道（信息用；setMixture/setCooling 均无门控，装风道时风门值才被 K_DUCT 分量消费） |
| `getActiveFuel()` | false | table | 活动燃料：流体 `{type="fluid", fluid=<id>, optimalTemp=155}`；蒸汽 `{type="steam", optimalTemp=155}`；停机 `{type="none"}` |
| `getCooling()` | false | number | 冷却风门 0..1（默认 1.0） |
| `setCooling(0~1)` | true | boolean | 风门：只缩 K_DUCT 风道散热分量，只能降；**无门控**（纯存值，仅装冷却气道时被消费） |
| `hasWaterCooling()` | false | boolean | 是否装了水冷模块（见[水冷模块](water-cooling-module.zh.md)） |
| `getWaterCooling()` | false | number | 水冷**回路开度** 0..1（默认 1.0） |
| `setWaterCooling(0~1)` | true | boolean | 水冷回路开度：只缩 `k_water` 水冷散热分量；**0 = 关闭回路**（既不散热、也不蒸发失液）；**无门控**（纯存值，没装模块存了不生效） |
| `getWaterCoolingPower()` | false | number | 当前有效水冷散热 `k_water`（液量比例 × 冷却液效率 × 回路开度；不吃冲压/气压） |
| `getWaterCoolingModules()` | false | number | 工作中的水冷模块数（冷却液量 > 0） |
| `getCoolantAmount()` | false | number | 冷却液总量 mb（工作模块合计；服务端同步值） |
| `getWaterCoolingStress()` | false | number | 水冷模块**占用的应力 SU**（= 工作模块数 × 512）——真实进网，见[水冷模块](water-cooling-module.zh.md) |
| `getAutoControl()` | false | boolean | 内置**自动控制**是否开启（默认 **开**）：引擎自己把温度钉 155°C、实际混合比钉 0.9。见[温度与混合比自动控制](auto-control.zh.md) |
| `setAutoControl(bool)` | true | boolean | 显式开关自动控制（开启时重置积分/富油档）。**调任一 `setMixture` / `setCooling` / `setWaterCooling` 会自动关闭它**并交还玩家 |
| `isWarmingUp()` | false | boolean | 蒸汽暖机中（T<100°C 只烧不发电） |

## 状态档位（流体引擎，按 `getTemperature()`）

温度档位由「**负载 vs 散热**」共同决定：负载越高越热，散热（气道/风门/冲压/低海拔）越好越凉。想凉下来就降负载或加强散热。

| 温度区间 | 状态 |
|---|---|
| —（!running） | 停机 |
| < ~97°C | 过冷（油耗惩罚中） |
| 97 ~ 145°C | 正常 |
| 145 ~ 165°C | **高效**（经济带） |
| 165 ~ 200°C | 正常 |
| 200 ~ <220°C | 即将过热 |
| ≥ 220°C | 过热 |
