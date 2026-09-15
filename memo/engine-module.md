# 引擎模块（aero_engine：engine_core + 燃烧室 + 整合气道）— 最终版（P0~P7）

> **状态：P0~P7 全部实现并编译通过，本文档为最终版权威描述（wiki 制作依据）**。
> 实现前先读参考源码（见文末「参考来源」）；本文档各数值以「最终版常量」为准，不再标注中间方案。

> 设计封闭（用户逐项确认）：蒸汽室 = 水 + 燃料（无蒸汽流体）；**全引擎单燃料制**（priority 统一选一种，不混烧）+ **流体/蒸汽物理排斥**（不能混用）；严格零流体缓存；固体燃料从周围容器自动抽取；水走 drain 管线；**无红石控制，末段做 Lua 控制**；整合气道（散热+进气）替换冷却风道；**燃料体系分家**（蒸汽 = 纯原版解析，流体燃烧室 = 纯 datapack）；**温度阈值全部引擎固定**；过稀失火后续阶段。

---

## 1. 模块总览（wiki 用）

| 组成 | 作用 |
|---|---|
| `engine_core`（引擎核心） | 排排坐组网成一条引擎（1×1×N，N≤21），整条共享运行状态；仅 controller 发电/决策 |
| `fluid_combustion_chamber`（流体燃烧室） | 烧 datapack 流体燃料，**每室 4096 SU**（×燃料 stress 倍率） |
| `steam_power_chamber`（蒸汽动力室） | 烧水 + 燃料产生蒸汽，**每室 3072 SU**；简单稳定、永不过热 |
| `integrated_air_duct`（整合气道） | 散热（计入 K_DUCT）+ 门控：解锁**混合比拉稀权**与**冷却效率（风门）** Lua |

**两种引擎对照（wiki 用）**：

| | 流体引擎 | 蒸汽引擎 |
|---|---|---|
| 出力 | 4096 SU/室（×stress） | 3072 SU/室 |
| 燃料 | 纯 datapack（`engine_fuel/*.json`） | 纯原版（桶物品熔炉燃烧时长） |
| 混合比/经济区/风门 | 有（玩法核心） | 无（恒 1.0） |
| 温度 | 牛顿冷却 + 过热/过冷管理（引擎固定阈值） | 闭式锅炉自调节钉 155°C，永不过热、高度免疫 |
| 复杂度 | 高上限：省油/散热/混合比三重管理 | 简单稳定：点火暖机 → 出力 |

---

## 2. 玩家核心玩法（wiki 向）

- **一个杆（油门）就够起飞**：油门同时定应力与转速（定距桨单杆，0~256rpm）；油门 0 = 停机不烧油。
- **流体引擎 = 经济管理游戏**：油耗 = 杆值 × 经济系数 × 过冷惩罚；发热 = heatFactor(杆 × 高空自动富油)。
  - 拉稀 = 省油奖励，但更热；富油 = 花油买冷。
  - 经济系数 = 温度（155°C 固定带）**且**实际混合比 m_eff≈1.0 双达标、持续保持 15s 渐入 ×0.75（离开 6s 流失）。
  - 高空自动富油 = **免费降温**（只影响发热，不进油耗）；拉杆到 m_eff≈1.0 = 海拔补偿，吃到经济。
- **蒸汽引擎 = 简单稳定**：点火 → 暖机（<100°C 只烧不发电）→ 出力；永不过热、高空无忧；无混合比/经济区/风门。
- **温度档位**（流体引擎，全部引擎固定）：见节 11。

---

## 3. 需求（最终版）

1. **引擎核心排成一排组成一个整体**：参考 CDG 模块化引擎——拿引擎物品右键已有引擎沿轴延伸放置（`PoleHelper`）；整条共享运行状态。
2. **引擎核心没有流体缓存**：自动从周围储罐 capability drain（零内部缓存）。
3. **燃烧室并入**：核心周围贴流体燃烧室/蒸汽动力室即并入模块。
4. **出力**：有可用燃料即启动；流体室每室 4096 SU（×stress），蒸汽室每室 3072；转速 = 油门 × 256（0~256 线性）。
5. **流体室消耗**：按 datapack 配方消耗流体（默认 1mb/s/室）。
6. **蒸汽室**：消耗原料——水（1mb/s/室）+ 燃料（燃烧时长按原版熔炉逻辑）。
7. **整合气道**：散热 + 进气门控（混合比拉稀权/冷却风门）；**不门控经济系数**。
8. **无红石控制**；控制走 CC:Tweaked Lua 外设。
9. **混合比（P5/P7 最终）**：`setMixture(0.6~1.4)` 只影响油耗与温度（应力/转速不变）；油耗只随杆值 × 经济 × 过冷；自动富油只降温；过稀失火后续阶段。

---

## 4. 决策记录（最终结论）

| 决策点 | 最终结论 |
|---|---|
| 蒸汽来源 | 无"蒸汽流体"；蒸汽室吃原料：水 + 燃料 |
| 水 | 原版水，每蒸汽室 1mb/s，从周围储罐 drain |
| 燃料体系（P7 分家） | **蒸汽引擎 = 纯原版解析**：桶物品原版熔炉燃烧时长（熔岩桶 20000 tick；添加燃料 = 按原版为熔炉加燃料）；**流体燃烧室 = 纯 datapack**（`engine_fuel/*.json` 唯一途径）；两套互不交叉；`burn_ticks_per_bucket`/`optimal_temp` 字段已删除 |
| 燃料规则 | 全引擎单燃料制：controller 按 priority 统一选一种，不混烧（priority 相同按文件名升序） |
| 引擎类型 | 流体/蒸汽物理排斥：`ChamberBlockItem#place` 拦截 + `getStateForPlacement` null 兜底 + 虚影拒绝 + controller 多数派仲裁 |
| 流体缓存 | 严格零缓存：每 tick 从源储罐 capability drain，无罐无液位显示 |
| 固体燃料 | 从蒸汽室 6 邻居容器自动抽取（物品 burnTime）；**暂缓**（当前只走流体燃料） |
| 红石 | 不要；末段 Lua 控制 |
| 过热行为 | T ≥ 200°C 硬停（不烧油）；**T ≤ 180°C 滞回解锁**；仅流体引擎 |
| 效率语义 | 效率 = 油门（定距桨单杆）：线性缩放应力输出与转速；发热/油耗 ∝ 油门；默认 0.25（64rpm） |
| 转速-油门耦合 | 转速 = 油门×256；应力与转速同比例 → 过载比例不随油门变；外部转速控制器无法作弊 |
| 混合比范围 | `setMixture(0.6~1.4)`，默认 1.0，NBT 持久化，引擎级（仅流体） |
| 混合比与功率 | 不影响应力/转速（油门是唯一功率杆） |
| 混合比热曲线 | 凸曲线：稀侧 `1+A(1−m)²`（A=2.0），浓侧 `1−B(m−1)`（B=0.5，下限 0.7） |
| 自动富油 | `autoRichness = 1 + 0.45×(1−P)` 钳 [1, 1.25]（与冷却同气压曲线）；**只进发热**（高空富油降温 ×0.875），不进油耗 |
| 经济系数 | 双因素 AND 门控 + 时间解锁：`|T−155|≤10 ∧ |m_eff−1|≤0.05` 持续达标 → 15s 渐入 ×0.75（离开 6s 归零）；混合比不对无奖励无惩罚；**不门控整合气道** |
| 温度阈值（P7 最终） | **全部引擎固定**：经济目标 `ENGINE_T_OPT=155`、过冷 `ENGINE_MIN_WORK_TEMP=100`、过热 200——不随燃料、不随油门（真实 = 引擎设计点/节温器恒定，如塞斯纳 172 CHT 工作带固定） |
| 过冷惩罚 | `cold = 1 + 0.3×max(0, 100−T)/100`（20°C ≈×1.24，只乘油耗）；小油门暖机玩法；仅流体 |
| 过稀失火 | 后续阶段：m_eff<0.8 概率掉出力 / <0.6 熄火（出力风险对冲拉稀收益） |
| 蒸汽温度 | 闭式锅炉自调节钉 `BOILER_T_OPT=155`；T<100°C 只烧不发电（暖机）；永不过热、高度免疫；无经济区/混合比/风门 |
| 冲压冷却 | 10→30 m/s 线性爬升到 ×2.0 |

---

## 5. 参考实现

| 参考 | 抄什么 |
|---|---|
| CDG `ModularDieselEngineBlockEntity` / `Block` | controller 指针网络、`updateConnectivity`→`formMulti`、`onRemove`→`splitMulti`、NBT `Uninitialized`+`LastKnownPos` 重组、`fuelDebt` 累加器、仅 controller tick |
| Create `ConnectivityHandler` / `IMultiBlockEntityContainer` | 组网算法、接口契约 |
| Create `SteamEngineBlockEntity` | `WeakReference` 引用缓存 + `isRemoved()` 校验 + 失效重扫 + 委托 `getControllerBE()` |

---

## 6. 总体架构

```
EngineCoreBlockEntity（仅 controller 干活；非 controller 的 getGeneratedSpeed()=0）
 ├─ 组网（P0）：IMultiBlockEntityContainer + ConnectivityHandler.formMulti/splitMulti
 │              + PoleHelper 延伸放置 + Uninitialized 存档重组
 ├─ 每 tick：枚举模块 → 逐燃烧室评估 → 汇总容量 → 发电 → 消耗 → 温度 → 同步
 └─ 同步：tooltip 数据每 tick sendData（20Hz）→ 燃烧室 Visual 读父核心 → 活塞动画

燃烧室 BE：非动力、轻量（流体室无槽 drain；蒸汽室无槽，燃料由 controller 抽取）

燃料：流体燃烧室 = engine_fuel/*.json（datapack）；蒸汽室 = 原版桶物品燃烧时长
```

**核心思想**：整条引擎（最多 21 节）只有 controller 一个决策点；燃烧室是纯"输入模块"；燃料/水都由 controller 统一从外部源抽取（零内部缓存）。

---

## 7. 关键机制

### 7.1 组网（P0）

- 普通 `IMultiBlockEntityContainer`（无罐）；字段 `controller`/`length`/`lastKnownPos`。
- `onPlace`→`formMulti`；`onRemove`→`splitMulti`（拆中段全体解体后自动重组）。
- `PoleHelper` 沿轴延伸放置；`"Uninitialized"` NBT 标志 → 读档/蓝图后重组。
- `hasShaftTowards` 沿 AXIS 双面 true → 相邻核心轴耦合成一个传动网络。
- **运行状态继承**：新核心放在 controller 外侧会成为新 controller → `updateConnectivity` 在 `formMulti` 前 `adoptAdjacentEngineState` 从相邻引擎 controller 继承温度/油门/混合比/经济进度等（见踩坑 14）。
- `getMaxLength=21`、`getMaxWidth=1`。

### 7.2 零缓存流体抽取（P1）

- 必须走 capability：`level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null)` 再 `drain()`；绝不直接改储罐 BE。
- `WeakReference` + 每 tick `isRemoved()` 校验 + 失效重扫；源范围 = 模块（core 成员 ∪ 燃烧室）6 邻居。
- 消耗粒度：`fuelDebt`/`fluidFuelDebt`/`waterDebt` 累加器（float），每 tick 累计、≥1mb 才 drain。
- 已知限制：移动装置（contraption）上的储罐找不到（v1 不支持）。

### 7.3 燃烧室并入与逐室评估（P1/P2）

- 归属：燃烧室 `FACING.getOpposite()` 即贴附核心，双面贴两核心时归一边不双计。
- 逐室评估：流体室 = datapack 表第一个可用流体；蒸汽室 = 水可用且燃料可用。
- **活塞动画**：父引擎运行时活塞沿 FACING ±2/16 块正弦往复，角度随转速推进（`speed×3/10` 度/tick，1 圈 = 1 往复）；停机回中间位。
- **"噗嗤"音效**：客户端 tick 检测相位回绕 → 入队 controller 音效池（mergeTicks=2 + maxConcurrent=4），Create `AllSoundEvents.STEAM`（pitch 0.8±0.2）；流体室音量 0.05、蒸汽室 0.1（音量=0 则跳过，省性能）。
- **贴附虚影**：`ChamberAttachPlacementHelper` 手持燃烧室对准核心自动渲染虚影。
- 固体燃料计时器：水在才走；`tryPullSolidFuel` 保留未调用（暂缓）。

### 7.4 出力模型：定距桨单杆（P4）

- 转速 = 油门 × 256（0~256 线性）；容量 = 运行室数 × base × stress × 油门。
- 应力与转速同比例缩放 → 过载比例不随油门变；外部转速控制器无法作弊（预算 = 应力×转速 恒随油门缩放）。
- 发热/油耗 ∝ 油门（效率同缩出力/热量/消耗；25% 效率燃料耐用 4 倍）。

### 7.5 温度与过热冷却（P3 + P7 最终；流体引擎）

**模型**（controller 每 tick，服务端；牛顿冷却）：

```
Q_heat   = Σ运行室 × efficiency × 燃料.heat × heatFactor(m_eff) × 类型基础热
K_total  = (K_CORE×length + K_AMB×N + K_DUCT×D×风门) × ram(speed) × f(pressure)
T_amb    = 分段线性：Y≤63 → 20℃；63→200（云层）20→0℃；200→320（世界顶）0→−40℃；≥320 恒 −40℃
T'       = (Q_heat − K_total×(T − T_amb)) / C_th
T       += T'/20；钳制 [T_amb, T_max×1.2]
overheat = T ≥ 200 → 硬停；resume = T ≤ 180（滞回）

ram(speed)：<10 m/s = 1.0，10→30 线性爬升到 2.0
f(pressure)：pressure^0.8（钳位下限 0.25）
altitude：运动体取 getSubLevelWorldPos().y，静态取方块 Y
```

**最终常量**：

| 常量 | 值 | 说明 |
|---|---|---|
| H0 / STEAM_HEAT_FACTOR | 30 °C/s / 0.8 | 流体室基础热 / 蒸汽室倍率 |
| K_AMB / K_DUCT / K_CORE | 0.05 / 0.05 / 0.02 | 环境(每室) / 气道(每个) / 核心自身(每节) |
| RAM_START / RAM_FULL / RAM_MAX | 10 / 30 m/s / ×2.0 | 冲压冷却曲线 |
| **OVERHEAT_TEMP / OVERHEAT_RESUME** | **200 / 180°C** | 过热硬停 / 滞回恢复（= 即将过热预警阈值） |
| T_AMB_SEA_Y / T_AMB_SEA / T_AMB_CLOUD_Y / T_AMB_CLOUD_TEMP / T_AMB_TOP_Y / T_AMB_FLOOR | 63 / 20°C / 200 / 0°C / 320 / −40°C | 环境温度（Minecraft 尺度分段线性：海平面→云层→世界顶；不再用真实对流层 0.0065°C/m） |
| C_TH_BASE | 1.0 | 热容 ×length |
| PRESSURE_FLOOR | 0.25 | 气压因子下限 |
| **ENGINE_T_OPT** | **155°C** | 经济目标（引擎固定，不随燃料/油门） |
| **ENGINE_MIN_WORK_TEMP** | **100°C** | 过冷阈值（引擎固定，与蒸汽暖机门槛同值） |

**实现要点**：温度存 controller NBT 持久化；过载与过热独立；tooltip 数据每 tick `sendData()`（20Hz）；客户端趋势外推显示温度。

### 7.6 混合比与经济（P5/P7 最终；仅流体引擎）

**现实依据**：化油器按进气体积配油 → 高空空气稀 → 混合气天然变浓（自动富油）。真实 CHT 与油量反向：富油吸热降温、贫油更热。P7：富油是出厂标定的默认态（不建模为油耗税），只表现降温。

**最终公式**：

```
autoRichness(P)  = 1 + 0.45×(1 − P)，钳制 [1.0, 1.25]        // 与冷却同气压曲线；海平面 1.0，Y≈260 ≈×1.25
leverMixture     = 杆（无整合气道钳 ≥1.0 = 拉稀权门控）
m_eff            = leverMixture × autoRichness(P)             // 实际混合比（发热/经济窗口用）

油耗  ×= leverMixture × eco × cold                            // 自动富油不进油耗
发热  ×= heatFactor(m_eff)                                    // 自动富油 = 只是发热减少

eco：satisfied = |T−155|≤10 ∧ |m_eff−1|≤0.05（双因素平底窗）
     达标 → ecoProgress += 1/15/20（15s 缓慢解锁）；不达标 → −= 1/6/20（6s 流失）
     eco = 1 − 0.25×ecoProgress（1.0 → 0.75，省 25%）；保持才奖励、快速掠过不奖励
cold = 1 + 0.3×max(0, 100 − T)/100                           // 过冷惩罚（T<100°C，20°C ≈×1.24）

heatFactor(m)：m<1 → 1 + 2.0×(1−m)²（稀侧凸）；m≥1 → max(0.7, 1−0.5×(m−1))（浓侧平缓）
```

**常量**：`MIXTURE_MIN/MAX=0.6/1.4`、`MIXTURE_PRESSURE_K=0.45`、`MIXTURE_ALT_MAX=1.25`、`MIXTURE_LEAN_K=2.0`、`MIXTURE_RICH_K=0.5`、`MIXTURE_RICH_FLOOR=0.7`、`ECO_MIN=0.75`、`ECO_FLAT=10`、`ECO_MIX_FLAT=0.05`、`ECO_UNLOCK_RATE=1/15`、`ECO_DECAY_RATE=1/6`、`COLD_K=0.3`。

**设计张力（高空管理博弈）**——高空冷却更差 + 自动富油 = 免费降温福利：

| 高空策略 | 油耗 | 温度 | 结果 |
|---|---|---|---|
| 不拉稀（杆 1.0，m_eff≈1.25） | ×1.0（无惩罚） | ×0.875（富油降温） | 省心安全，但吃不到 eco |
| 拉稀到补偿位（杆≈0.8 → m_eff≈1.0） | ×0.8×0.75 = **×0.6** | 回 ×1.0（需冷却管好 155 带） | 海拔补偿奖励：省 40% |
| 无脑拉大稀（杆<0.64 → m_eff<0.8） | ×杆值（纸面更低） | 偏热 | 后续过稀失火限制 |
| 富油（杆 1.4） | ×1.4 | ×0.7 | 花油买冷 |

### 7.7 蒸汽引擎（P6 Plan B）

**闭式锅炉自调节 + 暖机门控**：点火燃烧（有水+燃料）→ 温度指数收敛并钉 `BOILER_T_OPT=155`（饱和温度，与气压/环境/冲压无关 → 高度免疫）；**T<100°C 只烧不发电**（暖机，Goggle「暖机中」/ Lua `isWarmingUp`），≥ 阈值才出力；缺水 → 停烧（防干烧）→ 永不过热；停火 → 牛顿冷却。无经济区/混合比/风门/整合气道需求。常量：`STEAM_MIN_WORK_TEMP=100`、`STEAM_WARMUP_RATE=0.25`（τ=4s）。

### 7.8 气压模型（wiki 用：空气模型来源）

- 冷却（`pressureFactor`）与自动富油（`autoRichness`）共用同一气压模型：Sable 维度大气曲线。
- `SensorSystemAPI.evaluatePressure(y)` 镜像同一条公式（锚点间三次 Hermite）作用在静态曲线快照上（Lua 需 mainThread=false 零 Level 访问）。
- 主世界默认锚点：(−38,1.5)/(63,1.0)/(263,0.4493)/(280,0.4198)/(320,0)；Y≥320 为 0。
- 引擎侧：`getPressureForEngine(engineAltitude())`；客户端显示一律用服务端同步值。

---

## 8. 消耗模型（最终）

| 输入 | 规则 |
|---|---|
| 流体燃料（流体室） | datapack `engine_fuel/*.json`；单燃料制；每室 consumption mb/s（默认 1） |
| 水（蒸汽室） | 固定 1mb/s/室，从模块邻居水源罐 drain；水不可用整类暂停 |
| 固体燃料（蒸汽室） | 物品原版 `burnTime` → 1 tick 烧 1 burnTick；自动抽取（**暂缓**） |
| 流体燃料（蒸汽室） | **纯原版解析**：桶物品原版熔炉燃烧时长（熔岩桶 20000 tick → 1mb 烧 20 tick）；`EngineFuels.vanillaBucketBurnTicks` 权威 |

---

## 9. controller tick 数据流（最终）

```
1. 枚举：scanModule —— 两类燃烧室（FACING 反面贴核心归属，去重）+ 整合气道数 D + 去重邻居
2. 仲裁：流体+蒸汽并存只让多数派工作（平局流体优先）；steamEngine 标志
3. 门控：!overheated（滞回仅流体）→ heatAllowed
4. 混合比（仅流体）：m_eff = 杆 × autoRichness（无气道杆钳 ≥1.0）；油耗因子 = 杆 × eco(T,m_eff) × cold(T)
5. 流体室评估：findFuel 按 priority 选一种 → runningFluid；消耗 ×效率 × 杆 × eco × cold
6. 蒸汽室评估：waterOk（水+燃料）→ 逐室 burnTick 消耗（×效率）；steamFuelBurnTicks 原版解析
7. running = 有燃烧室 && steamReady（蒸汽 T≥100°C）&& !过载 && !过热 && 效率>0
8. speed = running ? 256×效率 : 0；capacity = 室数×base×效率
9. 消耗：水 1mb/s/室×效率；流体燃料 ×杆 ×eco ×cold（零缓存累加器 ≥1mb drain）
10. 温度：流体 = 牛顿冷却（过热滞回 200/180）；蒸汽 = 收敛钉 155 / 停火冷却
11. 同步：tooltip 数据（温度/经济/过冷/发热系数/混合比实际值等）每 tick sendData（20Hz）
```

---

## 10. Lua API（ccpe:engine 最终版）

`peripheral.wrap(...)` 任意引擎核心节（外设挂 controller，非 controller 委托）。读方法 mainThread=false 直读缓存（≤1 tick 滞后）；写方法与 `getFluidTanks` mainThread=true 服务端权威。

| 方法 | mainThread | 返回 | 说明 |
|---|---|---|---|
| `getTemperature()` | false | number | 温度 °C |
| `isOverheated()` | false | boolean | 过热锁定（仅流体；T≥200 硬停，T≤180 解锁） |
| `getFluidTanks()` | true | table[] | 所有连接储罐 `{fluid, amount, remaining, capacity}` |
| `getThrottle()` / `setThrottle(0~1)` | false/true | boolean | 油门：应力与转速同比例（0 = 停机不烧油，唯一启停控制；setEnabled/getEnabled 已移除） |
| `getMixture()` | false | number | 混合比杆 0.6..1.4（仅流体；蒸汽恒 1.0） |
| `setMixture(x)` | true | boolean | 混合比：油耗 ×杆值、温度 ×热因子；**蒸汽或无整合气道拒绝 false** |
| `getEffectiveMixture()` | false | number | 实际混合比 = 杆 × 自动富油（eco 窗口与发热反馈） |
| `getFuelEconomyFactor()` | false | number | 经济系数 0.75..1.0（温度+混合比双达标渐入；混合比不对无奖励无惩罚） |
| `hasAirDuct()` | false | boolean | 是否装整合气道（只门控 setMixture/setCooling） |
| `getActiveFuel()` | false | table | 活动燃料：流体 `{type="fluid", fluid, optimalTemp=155}`；蒸汽 `{type="steam", optimalTemp=155}`；停机 `{type="none"}` |
| `getCooling()` / `setCooling(0~1)` | false/true | boolean | 风门：只缩 K_DUCT 分量，只能降；**蒸汽或无气道拒绝 false** |
| `isWarmingUp()` | false | boolean | 蒸汽暖机中（T<100°C 只烧不发电） |

**运行条件**（全满足才发电）：≥1 运行燃烧室 && 效率>0 && 未过载 && 未过热（仅流体）&& 蒸汽 T≥100°C。停机原因：缺燃料/缺水、油门 0、过载、流体过热（滞回 T≤180 恢复）、蒸汽暖机未完成。

---

## 11. Goggle 显示（最终版）

**引擎核心**（悬停 engine_core，4 内容行）：

```
发动机状态
温度：XX.X°C
总应力输出：8192 SU          ← 服务端同步 moduleCapacity（用户要求显示产生的总应力）
目前转速：128 RPM
连接的模块：- 流体燃烧室 x1 / - 整合气道 x1
```

**蒸汽动力室**（悬停 steam_power_chamber）：

```
发动机状态
状态：正常 / 暖机中 / 停机
温度：XX.X°C
油门：50%
```

**整合气道**（悬停 integrated_air_duct）：

```
发动机状态
温度：XX.X°C
冷却：80%                    ← = setCooling 风门百分比（coolingStrength；<100% 已关小保热）
```

**流体燃烧室**（全量引擎状态）：

```
发动机状态
状态：停机 / 过冷 / 正常 / 高效 / 即将过热 / 过热
温度：XX.X°C
油门：50%                    ← = 效率（定距桨单杆）
油耗：×0.60                  ← 最终油耗系数 = 杆值 × 经济 × 过冷
发热系数：×1.08              ← heatFactor(杆 × 高空自动富油)；稀>1 热 / 富油<1 凉
```

**已移除条目**（用户要求精简）：混合比行、高空实际行、最佳温度行、风门行、Lua控制行、过冷数值行、应力影响行（Create 默认 goggle 经不调用 `super.addToGoggleTooltip` 抑制）；过冷由状态行体现。**一律显示服务端同步值**（客户端不重算，见踩坑 10）。

---

## 12. 状态档位（流体引擎，最终）

| 温度区间 | 状态 | 颜色 |
|---|---|---|
| —（!running） | 停机（油门 0 / 缺燃料 / 缺水 / 过载） | 灰 |
| < ~97°C | 过冷（油耗惩罚中） | 蓝 |
| 97 ~ 145 | 正常 | 绿 |
| 145 ~ 165 | **高效**（经济带 |T−155|≤10） | 青 |
| 165 ~ 180 | 正常 | 绿 |
| 180 ~ <200 | 即将过热 | 金 |
| ≥ 200 | 过热停机（滞回 180 解锁） | 红 |

---

## 13. 数据文件（wiki 用）

**流体燃烧室燃料**（datapack，`data/<ns>/engine_fuel/*.json`，`/reload` 热加载）：

```json
{ "fluid": "minecraft:water", "consumption": 1.0, "heat": 1.0, "stress": 1.0, "priority": 0.0 }
```

- `fluid`：流体 id；`consumption`：mb/s/室；`heat`：发热倍率；`stress`：应力倍率；`priority`：选择优先级（越大越优先，同值按文件名升序）。
- **无温度字段**——过热 200 / 过冷 100 / 经济目标 155 全部引擎固定。

**蒸汽引擎燃料**：不加 datapack——流体桶物品的原版熔炉燃烧时长 >0 即可用（熔岩桶 20000 tick）；添加方式 = 按原版给桶物品设置熔炉燃料（与熔炉共享同一套燃料注册）。

---

## 14. 玩家操作要点（wiki 文案）

- **一个杆（油门）就够起飞**：油门同时定应力与转速；转速不够带动机器时推油门；**油门 0 = 停机不烧油**。
- **流体引擎巡航省油**：装整合气道 → 拉杆到 m_eff≈1.0（高空 Y≈260 → 杆 0.8）= 海拔补偿 → 保持温度在 155°C 带内（145~165）且混合比达标 15s → 经济渐入 ×0.75。太热 → 拉富/开风门；太冷（高速冲压/多风道/低油门）→ 关风门保热；冷却吃紧（高空/静态）→ 富油或降油门。
- **高空自动富油（wiki 解释）**：杆 1.0 = 出厂标定，任何高度油耗 ×1.0（无惩罚）；高空空气稀 → 发动机天然富油（m_eff = 杆 × 自动富油，Y≈260 ≈×1.25）——**自动富油只带来富油降温（发热 ×0.875），不费油**。拉稀省油（油耗 ×杆值）；把杆拉到 m_eff≈1.0 = 海拔补偿，吃到经济系数。
- **过冷惩罚**：刚开机温度低于 100°C 时油耗上升（×~1.24），先小油门暖机再推油门。
- **蒸汽引擎**：点火 → 暖机（T 从环境爬到 100°C 约 4s，期间只烧不发电）→ 出力；永不过热、高空无忧；代价 = 应力低（3072/室）+ 无折扣。
- 过载 = 停烧（不白烧油）；过热锁存（流体）T≤180 才恢复；高频启停（油门 0↔1）触发 Create flicker 惩罚。

---

## 15. 边界清单（实现时逐条守住）

- 消耗只在服务端；客户端只读同步状态
- 引用一律 WeakReference + `isRemoved()` 校验 + 失效重扫
- 只走 capability，绝不直接改罐/容器
- 储罐/容器在移动装置上 = 已知限制（v1 不支持）
- 燃烧室双面贴两核心按 `FACING.getOpposite()` 归一边，不双计
- 冷却按"运行中室数"计
- 多引擎抢同一储罐：drain 原子，无死锁
- 非 controller 的 `getGeneratedSpeed()` 恒 0
- 无红石状态（POWERED 逻辑不引入）
- 经济系数只乘消耗、绝不反哺 Q_heat
- 整合气道只门控混合比拉稀权 + 冷却风门；经济/油耗/过冷不门控
- 蒸汽 Plan B：永不过热、无经济区/混合比/风门、高度免疫
- P7 奖励驱动：自动富油/heatFactor 绝不进油耗
- P7 eco AND 门控 + 时间解锁；混合比不对无奖励无惩罚
- P7 温度全部引擎固定（155/100/200）
- 过冷惩罚只乘油耗
- 过稀失火（后续）只可能在有整合气道时出现
- 燃料体系分家：蒸汽 = 原版，流体室 = datapack
- 油门 0 = 停机不烧油；蒸汽点火判定必须带 efficiency>0
- 放置拦截只防交互路径；蓝图/命令直放靠 controller 多数派仲裁兜底

---

## 16. 后续计划

1. **过稀失火**（方案已定）：m_eff<0.8 概率失火（本 tick 出力 ×0）；m_eff<0.6 熄火停机（拉富回安全区自动重启）；高空拉稀余量更大（自动富油垫稀）；只可能在有整合气道时出现。
2. **tooltip 差量/批量同步优化**：当前每 tick 20Hz；恢复「温度 ≥1°C 才发包 + 趋势外推」1/20 带宽方案（`SYNC_TEMP_DELTA` 常量保留备用）。
3. **进游戏调参**：`ECO_MIN/ECO_FLAT/COLD_K/ENGINE_T_OPT/ENGINE_MIN_WORK_TEMP/解锁流失速率` 各值。

---

## 17. 实施阶段历史（P0~P7）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 组网：IMultiBlockEntityContainer + form/split + PoleHelper + Uninitialized 重组 | ✅ |
| P1 | 流体燃烧室 + 核心 tick：燃料 datapack、罐 drain、fuelDebt、发电 256rpm + 活塞动画 | ✅ |
| P2 | 蒸汽室：水 + 流体燃料 burnTick 制 + 活塞动画（固体燃料暂缓） | ✅ |
| P3 | 温度/冷却：牛顿冷却、过热硬停+滞回、冲压/气压冷却、K_CORE、趋势外推显示 | ✅ |
| P4 | Lua 外设 `ccpe.engine`：throttle + 状态读；定距桨单杆（转速 = 油门×256） | ✅ |
| P5 | 混合比：setMixture(0.6~1.4) + 自动富油 + 凸热曲线 + getEffectiveMixture | ✅ |
| P6 | 回炉（单燃料制 + 物理排斥放置拦截）、eco(T) 经济区、整合气道替换风道、风门 setCooling、蒸汽 Plan B、Lua 扩展 | ✅ |
| P7 | 奖励驱动重构：油耗 = 杆×eco×cold、eco 双因素 AND 门控 + 解锁进度、温度全部引擎固定、过冷惩罚、tooltip 每 tick 同步、燃料体系分家、Goggle 精简（油门/油耗/发热系数） | ✅ |

---

## 18. 踩坑记录（实现期，已修复）

1. **Direction vs BlockPos 比较（踩了两次）**：`FACING.getOpposite()` 是 Direction，不能直接 `.equals(corePos)`；要先算背面坐标再比。
2. **注册期静态初始化急切 `.get()` NPE**：方块类静态初始化里禁止急切解引用 DeferredHolder（谓词用 lambda 惰性求值）。
3. **蒸汽室流体燃料 50% 占空比缺陷**：消耗类累加器必须"运行期间每 tick 累计"，不能只在补货时累计。
4. **燃料表多条目选择顺序**：按 priority 降序、其次文件名升序（同为 0 时 lava < water）。
5. **burnTick 制换算**：蒸汽室 1 tick 烧 1 burnTick；固体 1 物品 = burnTime；流体 1mb = 原版桶时长/1000 tick。
6. **停机温度定格（P3）**：散热项不能只挂运行状态；加 K_CORE×length（停机也缓慢降温）。
7. **追赶式 lerp → 平台台阶**：温度显示用趋势外推（最近两采样点斜率 + ±10°C 钳制）；P7 起 tooltip 每 tick 同步（20Hz），差量优化列后续计划。
8. **Sable 速度读取坑**：不要裸读 `Sable.HELPER.getVelocity`（静止机体有幻影值）；用 `SableCompat.getWorldLinearVelocity`（pose 差分，静止严格 0）。
9. **`overheated` 漏同步**：新增客户端要显示的服务端布尔状态，务必同时加 write/read 两处。
10. **客户端算高度 = 永远错误**：依赖真实高度的客户端显示，一律用服务端同步值，不要客户端自算。
11. **`BlockPlaceContext.getClickedPos()` 陷阱**：点击不可替换方块时返回放置格；贴附核心 = `getClickedPos().relative(getClickedFace().getOpposite())`。
12. **`InteractionResult.FAIL.consumesAction() == false`**：方块 `useItemOn` 返回 FAIL 拦不住放置；正确模式 = 覆写 `BlockItem.place`（照 create:factory_gauge `FactoryPanelBlockItem`）。
13. **蒸汽 `runningSteam` 计「有储备」→ 油门 0 温度钉住**：凡是「有储备 ≠ 在消耗」的状态量，挂到消耗/运行判定时必须加 efficiency 门控。
14. **延长引擎在 controller 端放置 → 参数重置**：Create `formMulti` 以新核心为锚点沿轴正方向组网，新核心放在原 controller 外侧（负方向端）时正方向扫描覆盖整条旧引擎 → 新核心成为新 controller；引擎运行状态只存在 controller BE 内存字段、无迁移机制（未实现 `getExtraData/setExtraData`，CDG 参考实现同样没有）→ 温度/油门/混合比/经济进度等全重置。修复：`updateConnectivity` 在 `formMulti` 前 `adoptAdjacentEngineState`（沿轴找相邻引擎 controller 继承运行状态，两侧都有引擎取更长者）。**拆中段后不含原 controller 的那一半同样会重置（同根因，本次未修）**。参考：`api/create-.../api/connectivity/ConnectivityHandler.java`、`run/references/Create-Diesel-Generators-1.21.1 (1)/.../ModularDieselEngineBlockEntity.java`。

---

## 参考来源

- `run/references/Create-Diesel-Generators-1.21.1 (1)/.../modular/`（ModularDieselEngineBlockEntity / Block / CTBehavior）
- `run/references/Create-mc1.21.1-dev/.../api/connectivity/ConnectivityHandler.java`
- `run/references/Create-mc1.21.1-dev/.../foundation/blockEntity/IMultiBlockEntityContainer.java`
- `run/references/Create-mc1.21.1-dev/.../content/kinetics/steamEngine/SteamEngineBlockEntity.java`（WeakReference 缓存 + 模块上报核心模式）
- `run/references/Create-mc1.21.1-dev/.../content/logistics/factoryBoard/FactoryPanelBlockItem.java`（放置拒绝模式，见踩坑 12）
