# 引擎模块（aero_engine：engine_core + 燃烧室 + 冷却气道）— 最终版（P0~P7 + 蒸汽燃料定稿 P2.5）

> **状态：P0~P7 全部实现并编译通过；P2.5 蒸汽燃料定稿已实现（固体燃料并行燃烧 + 燃料箱自动抽取 + 余热运转 + 真实蒸汽车节流阀消耗 + Goggle 燃料行）；P2.5b 源抽取重构已实现并进游戏验证通过（四类源列表事件化重建 + 批量补料 + 删 priority 按查找顺序抽 + 断供换罐不重启）**。本文档为最终版权威描述（wiki 制作依据）。
> 实现前先读参考源码（见文末「参考来源」）；本文档各数值以「最终版常量」为准，不再标注中间方案。

> 设计封闭（用户逐项确认）：蒸汽室 = 水 + 燃料（无蒸汽流体）；**全引擎单燃料制**（P2.5 起按源列表查找顺序选一种，不混烧）+ **流体/蒸汽物理排斥**（不能混用）；严格零流体缓存（P2.5 起改为源列表 + 储备批量补料）；固体燃料从周围燃料箱（quick_fill_fuel_vault）自动抽取（**并行燃烧、流体优先**）；水走 drain 管线；**无红石控制，末段做 Lua 控制**；冷却气道（散热+风门数值消费）替换冷却风道；**setMixture / setCooling 均纯存值无门控**；**燃料体系分家**（蒸汽 = 纯原版解析，流体燃烧室 = 纯 datapack）；**温度阈值全部引擎固定**；过稀失火后续阶段。

---

## 1. 模块总览（wiki 用）

| 组成 | 作用 |
|---|---|
| `engine_core`（引擎核心） | 排排坐组网成一条引擎（1×1×N，N≤21），整条共享运行状态；仅 controller 发电/决策 |
| `fluid_combustion_chamber`（流体燃烧室） | 烧 datapack 流体燃料，**每室 8192 SU**（×燃料 stress 倍率） |
| `steam_power_chamber`（蒸汽动力室） | 烧水 + 燃料产生蒸汽，**每室 4096 SU**；简单稳定、永不过热；燃料箱固体燃料并行燃烧 + 余热运转 |
| `cooling_air_duct`（冷却气道） | 散热（计入 K_DUCT）+ 风门数值消费（setCooling 无门控纯存值）；**setMixture 无门控（纯存值，仅流体消费）** |

**两种引擎对照（wiki 用）**：

| | 流体引擎 | 蒸汽引擎 |
|---|---|---|
| 出力 | 8192 SU/室（×stress） | 4096 SU/室 |
| 燃料 | 纯 datapack（`engine_fuel/*.json`） | 纯原版（桶物品熔炉燃烧时长 + 固体物品 burnTime，燃料箱自动抽取） |
| 混合比/经济区/风门 | 有（玩法核心） | 无（恒 1.0） |
| 温度 | 牛顿冷却 + 过热/过冷管理（引擎固定阈值） | 闭式锅炉自调节钉 155°C，永不过热、高度免疫 |
| 消耗 | 油耗 ∝ 油门（杆×经济×过冷） | **消耗 ∝ 油门**（真实蒸汽车节流阀：低油门省燃料），并行燃烧 |
| 复杂度 | 高上限：省油/散热/混合比三重管理 | 简单稳定：点火暖机 → 出力；燃料箱并行燃烧 + 余热运转 |

---

## 2. 玩家核心玩法（wiki 向）

- **一个杆（油门）就够起飞**：油门同时定应力与转速（定距桨单杆，0~256rpm）；油门 0 = 停机不烧油。
- **流体引擎 = 经济管理游戏**：油耗 = 杆值 × 经济系数 × 过冷惩罚；发热 = heatFactor(杆 × 高空自动富油)。
  - 拉稀 = 省油奖励，但更热；富油 = 花油买冷。
  - 经济系数 = 温度（155°C 固定带）**且**实际混合比 m_eff≈1.0 双达标、持续保持 15s 渐入 ×0.75（离开 6s 流失）。
  - 高空自动富油 = **免费降温**（只影响发热，不进油耗）；拉杆到 m_eff≈1.0 = 海拔补偿，吃到经济。
- **蒸汽引擎 = 简单稳定**：燃料箱塞燃料 → 点火 → 暖机（<100°C 只烧不发电）→ 出力；**真实蒸汽车油门**（消耗 ∝ 油门，低油门省燃料）；**余热运转**（燃料耗尽但 T≥100°C 且水未断供仍满出力，缺水停机）；永不过热、高空无忧；无混合比/经济区/风门。
- **温度档位**（流体引擎，全部引擎固定）：见节 11。

---

## 3. 需求（最终版）

1. **引擎核心排成一排组成一个整体**：参考 CDG 模块化引擎——拿引擎物品右键已有引擎沿轴延伸放置（`PoleHelper`）；整条共享运行状态。
2. **引擎核心没有流体缓存**：自动从周围储罐 capability drain（零内部缓存）。
3. **燃烧室并入**：核心周围贴流体燃烧室/蒸汽动力室即并入模块。
4. **出力**：有可用燃料即启动；流体室每室 8192 SU（×stress），蒸汽室每室 4096；转速 = 油门 × 256（0~256 线性）。
5. **流体室消耗**：按 datapack 配方消耗流体（默认 1mb/s/室）。
6. **蒸汽室**：消耗原料——水（1mb/s/室 × 油门）+ 燃料（原版 burnTime：固体从燃料箱**一次抽 N×K 个并行燃烧（不足切下一箱）**；流体桶按熔炉燃烧时长；**流体优先**）。
7. **冷却气道**：散热 + 风门数值消费（setCooling 无门控纯存值，装风道时 K_DUCT 分量才生效）；**不门控经济系数/混合比**——setMixture 无门控（纯存值，非流体引擎不消费）。
8. **无红石控制**；控制走 CC:Tweaked Lua 外设。
9. **混合比（P5/P7 最终）**：`setMixture(0.6~1.4)` 只影响油耗与温度（应力/转速不变）；油耗只随杆值 × 经济 × 过冷；自动富油只降温；过稀失火后续阶段。

---

## 4. 决策记录（最终结论）

| 决策点 | 最终结论 |
|---|---|
| 蒸汽来源 | 无"蒸汽流体"；蒸汽室吃原料：水 + 燃料 |
| 水 | 原版水，每蒸汽室 1mb/s × 油门（消耗 ∝ 蒸汽流量），从周围储罐 drain |
| 燃料体系（P7 分家） | **蒸汽引擎 = 纯原版解析**：桶物品原版熔炉燃烧时长（熔岩桶 20000 tick；添加燃料 = 按原版为熔炉加燃料）；**流体燃烧室 = 纯 datapack**（`engine_fuel/*.json` 唯一途径）；两套互不交叉；`burn_ticks_per_bucket`/`optimal_temp` 字段已删除 |
| 燃料规则 | 全引擎单燃料制：按**源列表查找顺序**选第一个可用燃料，不混烧（P2.5 删 priority） |
| 引擎类型 | 流体/蒸汽物理排斥：`ChamberBlockItem#place` 拦截 + `getStateForPlacement` null 兜底 + 虚影拒绝 + controller 多数派仲裁 |
| 流体缓存 | 严格零缓存：每 tick 从源储罐 capability drain，无罐无液位显示 |
| 固体燃料 | 从模块邻居燃料箱（quick_fill_fuel_vault 等 ItemHandler 容器）自动抽取（物品 burnTime）；**流体燃料优先**；**并行燃烧**：一次抽 N×K 个（N = 蒸汽室个数，K = 配置批次倍数），当前箱不足 N×K → 切下一箱，每室同燃同熄，燃烧时长 = 单个燃料时长 |
| 红石 | 不要；末段 Lua 控制 |
| 过热行为 | T ≥ 220°C 硬停（不烧油）；**T ≤ 200°C 滞回解锁**；仅流体引擎 |
| 效率语义 | 效率 = 油门（定距桨单杆）：线性缩放应力输出与转速；发热/油耗 ∝ 油门；默认 0.25（64rpm） |
| 转速-油门耦合 | 转速 = 油门×256；应力与转速同比例 → 过载比例不随油门变；外部转速控制器无法作弊 |
| 混合比范围 | `setMixture(0.6~1.4)`，默认 1.0，NBT 持久化，引擎级（仅流体消费；**纯存值无门控**） |
| 混合比与功率 | 不影响应力/转速（油门是唯一功率杆） |
| 混合比热曲线 | 凸曲线：稀侧 `1+A(1−m)²`（A=2.0），浓侧 `1−B(m−1)`（B=0.5，下限 0.7） |
| 自动富油 | `autoRichness = 1 + 0.45×(1−P)` 钳 [1, 1.25]（与冷却同气压曲线）；**只进发热**（高空富油降温 ×0.875），不进油耗 |
| 经济系数 | 双因素 AND 门控 + 时间解锁：`|T−155|≤10 ∧ 0.8≤m_eff≤1.1` 持续达标 → 15s 渐入 ×0.75（离开 6s 归零）；混合比不对无奖励无惩罚；**不门控冷却气道** |
| 温度阈值（P7 最终） | **全部引擎固定**：经济目标 `ENGINE_T_OPT=155`、过冷 `ENGINE_MIN_WORK_TEMP=100`、过热 220——不随燃料、不随油门（真实 = 引擎设计点/节温器恒定，如塞斯纳 172 CHT 工作带固定） |
| 过冷惩罚 | `cold = 1 + COLD_K×max(0, 100−T)/100`（COLD_K=1.0：20°C ≈×1.8，只乘油耗）；小油门暖机玩法；仅流体 |
| 过稀失火 | 后续阶段：m_eff<0.8 概率掉出力 / <0.6 熄火（出力风险对冲拉稀收益） |
| 蒸汽温度 | 闭式锅炉自调节钉 `BOILER_T_OPT=155`；T<100°C 只烧不发电（暖机）；**运行 = 油门开启且 T≥100°C 且水可用（无燃料余热也运转，但缺水停机防干烧）**；**消耗 ∝ 油门（真实节流阀语义）**；永不过热、高度免疫；无经济区/混合比/风门 |
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
 ├─ 每 tick：枚举模块 → 源列表重建调度 → 逐燃烧室评估（储备补料/消耗）→ 汇总容量 → 发电 → 温度 → 同步
 └─ 同步：tooltip 数据差量门控 + 1Hz 心跳（P7+ 方案 A+B+D，原 20Hz 全量广播）→ 燃烧室 Visual 读父核心 → 活塞动画

燃烧室 BE：非动力、轻量（流体室无槽 drain；蒸汽室无槽，燃料由 controller 抽取）

燃料：流体燃烧室 = engine_fuel/*.json（datapack，删 priority）；蒸汽室 = 原版桶物品燃烧时长 + 固体物品 burnTime（源列表批量抽取）
```

**核心思想**：整条引擎（最多 21 节）只有 controller 一个决策点；燃烧室是纯"输入模块"；燃料/水都由 controller 统一从外部源抽取（源列表 + 储备，无内部流体缓存）。

---

## 7. 关键机制

### 7.1 组网（P0）

- 普通 `IMultiBlockEntityContainer`（无罐）；字段 `controller`/`length`/`lastKnownPos`。
- `onPlace`→`formMulti`；`onRemove`→`splitMulti`（拆中段全体解体后自动重组）。
- `PoleHelper` 沿轴延伸放置；`"Uninitialized"` NBT 标志 → 读档/蓝图后重组。
- `hasShaftTowards` 沿 AXIS 双面 true → 相邻核心轴耦合成一个传动网络。
- **运行状态继承**：新核心放在 controller 外侧会成为新 controller → `updateConnectivity` 在 `formMulti` 前 `adoptAdjacentEngineState` 从相邻引擎 controller 继承温度/油门/混合比/经济进度等（见踩坑 14）。
- `getMaxLength=21`、`getMaxWidth=1`。

### 7.2 零缓存流体抽取（P1 → P2.5b 批量源列表）

- 必须走 capability：`level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null)` 再 `drain()`；绝不直接改储罐 BE。
- **P2.5b 源列表**（替代单源缓存 + 冷却重扫）：controller 维护四类源列表，**事件化重建**，列表按 scanModule 邻居枚举序（查找序）构建，transient 不落盘（读档 onLoad 重建）：
  | 列表 | 元素 | 用途 |
  |---|---|---|
  | `waterSources` | `BlockPos` | 蒸汽室水源罐（罐内含原版水） |
  | `fluidFuelSources` | `FuelSource(pos, EngineFuels.Entry)` | 流体室燃料罐（datapack 表命中） |
  | `steamFuelSources` | `SteamFuelSource(pos, fluid, burnTicksPerBucket)` | 蒸汽室流体燃料罐（原版桶燃烧时长>0） |
  | `solidFuelSources` | `BlockPos` | 蒸汽室固体燃料箱（ItemHandler 含可烧物） |
  每列表一个游标（`waterSourceIdx` 等），**从列表头一直抽，头罐失败（空/被拆/流体不对）→ `advanceXxx()` 即时切下一个（同 tick，零停机）**。
- **重建触发**（方案 A：scanModule 保持每 tick 枚举当免费安全网，源列表构建事件化）：
  - `markSourcesDirtyNow()`（下 tick 就扫）：onLoad / 连接性变化（`updateConnectivity`/`notifyMultiUpdated`/`setController`/`removeController`/adopt 后）。
  - `markSourcesDirty()`（去抖 10 tick）：方块 `neighborChanged`（核心 + 两类燃烧室覆写转发 `onModuleNeighborChanged`，**仅邻居带流体/物品能力才触发**——红石/装饰噪声不重建）。
  - `scheduleSourcesRescan()`（延迟 20 tick + 置 `sourcesAllFailed`）：**全源失败**时由 drain 方法触发；等待期不补料（避免每 tick 扫空表），重建完成/新事件后复位。
- **批量补料**：储备 ≤0 才补一批；**批次 = 引擎数量 × 配置倍数 K**（`Config.ENGINE_SOURCE_BATCH_MULTIPLIER` 默认 1.0，进游戏由 `onServerStarting` 缓存到 `SOURCE_BATCH_MULTIPLIER`，每 tick 不读配置）；批次时长（满油门）≈1s/批（低油门按 1/效率拉长）。drain 接受部分量（罐剩多少收多少），`drain` 返回 0 才算失败切换。
- **储备**：水 `waterReserve` / 流体室燃料 `fluidFuelReserve`（float mb）；蒸汽燃料走 burnTicks（蒸汽室 BE）。**储备耗尽即时补料（同 tick 内）**，避免 running 判定 1 tick 空档 → 应力网络不闪断。`fluidFuel` = 当前活动燃料条目（最近一次成功补料的源决定；储备耗尽且补不到 → null → runningFluid=0）。
- 已知限制：移动装置（contraption）上的储罐找不到（v1 不支持）。

### 7.3 燃烧室并入与逐室评估（P1/P2）

- 归属：燃烧室 `FACING.getOpposite()` 即贴附核心，双面贴两核心时归一边不双计。
- 逐室评估：流体室 = 源列表第一个可用流体燃料（**P2.5 删 priority，按查找顺序抽**）；蒸汽室 = 水储备可用且燃料可用（并行燃烧：全室同周期同燃同熄）。
- **活塞动画**：父引擎运行时活塞沿 FACING ±2/16 块正弦往复，角度随转速推进（`speed×3/10` 度/tick，1 圈 = 1 往复）；停机回中间位。
- **"噗嗤"音效**：客户端 tick 检测相位回绕 → 入队 controller 音效池（mergeTicks=2 + maxConcurrent=4），Create `AllSoundEvents.STEAM`（pitch 0.8±0.2）；流体室音量 0.05、蒸汽室 0.1（音量=0 则跳过，省性能）。
- **贴附虚影**：`ChamberAttachPlacementHelper` 手持燃烧室对准核心自动渲染虚影。
- 固体燃料（P2.5）：水在才走；**流体燃料不可用** → `tryPullSolidFuelBatch` 从固体燃料箱列表（quick_fill_fuel_vault 等 ItemHandler 容器）**一次抽 N×K 个**（N = 蒸汽室个数，K = 配置批次倍数；**当前箱不足 → 切下一箱**），每室 + 抽到数量/N × burnTime；油门 0 不抽（停机不烧油）。
- **并行燃烧**：N 个燃料同时烧（每室 1 个，同燃同熄），燃烧时长 = 单个燃料时长（各室 burnTicks 同值）；全室空炉才批量补料。
- **燃烧倒计时独立于燃料源**：蒸汽室循环门控 = 水储备 OK 且（流体可用 || 固体可用 || 任一室仍有储备 `burnTicks>0`）——燃料箱/源罐被拆时已有储备继续燃烧（不冻结不停机），倒计时结束才尝试再抽，抽不到才停烧（停烧 = 停止加热，温度开始牛顿冷却）。

### 7.4 出力模型：定距桨单杆（P4）

- 转速 = 油门 × 256（0~256 线性）；容量 = 运行室数 × base × stress × 油门。
- 应力与转速同比例缩放 → 过载比例不随油门变；外部转速控制器无法作弊（预算 = 应力×转速 恒随油门缩放）。
- 发热/油耗 ∝ 油门（效率同缩出力/热量/消耗；25% 效率燃料耐用 4 倍）。

### 7.5 温度与过热冷却（P3 + P7 最终；流体引擎）

**模型**（controller 每 tick，服务端；牛顿冷却）：

```
Q_heat   = Σ运行室 × efficiency × 燃料.heat × heatFactor(m_eff) × 类型基础热
K_total  = (K_CORE×length + K_AMB×N + K_DUCT×D×风门) × ram(speed) × f(pressure)
T_amb    = 地狱维度（the_nether）恒 155℃、末地维度（the_end）恒 0℃，均全高度、与高度无关；其余维度分段线性：Y≤63 → 20℃；63→200（云层）20→0℃；200→320（世界顶）0→−40℃；≥320 恒 −40℃
T'       = (Q_heat − K_total×(T − T_amb)) / C_th
T       += T'/20；钳制 [T_amb, T_max×1.2]
overheat = T ≥ 220 → 硬停；resume = T ≤ 200（滞回）

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
| **OVERHEAT_TEMP / OVERHEAT_RESUME** | **220 / 200°C** | 过热硬停 / 滞回恢复（= 即将过热预警阈值） |
| T_AMB_SEA_Y / T_AMB_SEA / T_AMB_CLOUD_Y / T_AMB_CLOUD_TEMP / T_AMB_TOP_Y / T_AMB_FLOOR | 63 / 20°C / 200 / 0°C / 320 / −40°C | 环境温度（Minecraft 尺度分段线性：海平面→云层→世界顶；不再用真实对流层 0.0065°C/m） |
| **T_AMB_NETHER** | **155°C** | 地狱维度（the_nether）环境温度：**全高度恒定**（地狱 = 热环境，与高度无关；= 经济目标同值） |
| **T_AMB_END** | **0°C** | 末地维度（the_end）环境温度：**全高度恒定**（末地 = 冷寂环境，与高度无关；= 云层结冰层温度同值） |
| C_TH_BASE | 1.0 | 热容 ×length |
| PRESSURE_FLOOR | 0.25 | 气压因子下限 |
| **ENGINE_T_OPT** | **155°C** | 经济目标（引擎固定，不随燃料/油门） |
| **ENGINE_MIN_WORK_TEMP** | **100°C** | 过冷阈值（引擎固定，与蒸汽暖机门槛同值） |

**实现要点**：温度存 controller NBT 持久化；过载与过热独立；**tooltip 同步 P7+ 优化（方案 A+B+D）**——事件差量 + 温度 ≥1°C 门控 + 慢字段 20 tick（1Hz）心跳，稳态包量降到 1/20 以下；经济/过冷/油耗系数客户端按公式现算（`economyFactor()/coldFactor()/fuelFactor()`），蒸汽倒计时客户端线性外推（`steamBurnTicksDisplay()`）；客户端趋势外推显示温度。

### 7.6 混合比与经济（P5/P7 最终；仅流体引擎）

**现实依据**：化油器按进气体积配油 → 高空空气稀 → 混合气天然变浓（自动富油）。真实 CHT 与油量反向：富油吸热降温、贫油更热。P7：富油是出厂标定的默认态（不建模为油耗税），只表现降温。

**最终公式**：

```
autoRichness(P)  = 1 + 0.45×(1 − P)，钳制 [1.0, 1.25]        // 与冷却同气压曲线；海平面 1.0，Y≈260 ≈×1.25
leverMixture     = 杆（纯存值直取；非流体引擎因蒸汽分支强制 1.0 而不生效）
m_eff            = leverMixture × autoRichness(P)             // 实际混合比（发热/经济窗口用）

油耗  ×= leverMixture × eco × cold                            // 自动富油不进油耗
发热  ×= heatFactor(m_eff)                                    // 自动富油 = 只是发热减少

eco：satisfied = |T−155|≤10 ∧ 0.8≤m_eff≤1.1（双因素平底窗）
     达标 → ecoProgress += 1/15/20（15s 缓慢解锁）；不达标 → −= 1/6/20（6s 流失）
     eco = 1 − 0.25×ecoProgress（1.0 → 0.75，省 25%）；保持才奖励、快速掠过不奖励
cold = 1 + COLD_K×max(0, 100 − T)/100                           // 过冷惩罚（COLD_K=1.0，T<100°C，20°C ≈×1.8）

heatFactor(m)：m<1 → 1 + 2.0×(1−m)²（稀侧凸）；m≥1 → max(0.7, 1−0.5×(m−1))（浓侧平缓）
```

**常量**：`MIXTURE_MIN/MAX=0.6/1.4`、`MIXTURE_PRESSURE_K=0.45`、`MIXTURE_ALT_MAX=1.25`、`MIXTURE_LEAN_K=2.0`、`MIXTURE_RICH_K=0.5`、`MIXTURE_RICH_FLOOR=0.7`、`ECO_MIN=0.75`、`ECO_FLAT=10`、`ECO_MIX_MIN/MAX=0.8/1.1`、`ECO_UNLOCK_RATE=1/15`、`ECO_DECAY_RATE=1/6`、`COLD_K=1.0`。

**设计张力（高空管理博弈）**——高空冷却更差 + 自动富油 = 免费降温福利：

| 高空策略 | 油耗 | 温度 | 结果 |
|---|---|---|---|
| 不拉稀（杆 1.0，m_eff≈1.25） | ×1.0（无惩罚） | ×0.875（富油降温） | 省心安全，但吃不到 eco |
| 拉稀到补偿位（杆≈0.8 → m_eff≈1.0） | ×0.8×0.75 = **×0.6** | 回 ×1.0（需冷却管好 155 带） | 海拔补偿奖励：省 40% |
| 无脑拉大稀（杆<0.64 → m_eff<0.8） | ×杆值（纸面更低） | 偏热 | 后续过稀失火限制 |
| 富油（杆 1.4） | ×1.4 | ×0.7 | 花油买冷 |

### 7.7 蒸汽引擎（P6 Plan B）

**闭式锅炉自调节 + 暖机门控**：点火燃烧（有水+燃料）→ 温度指数收敛并钉 `BOILER_T_OPT=155`（饱和温度，与气压/环境/冲压无关 → 高度免疫）；**T<100°C 只烧不发电**（暖机，Goggle「暖机中」/ Lua `isWarmingUp`），≥ 阈值才出力；缺水 → 停烧（防干烧）→ 永不过热；停火 → 牛顿冷却。
**P2.5 运行条件 = 油门开启且 T ≥ 100°C 且水可用**（不再要求有燃料储备）：燃料耗尽但锅炉仍热且水未断供时靠余热继续发电（容量 = 全部蒸汽室满出力），缺水（防干烧）或温度冷却到 100°C 以下才停机；燃烧倒计时独立于燃料源（燃料箱被拆储备烧完为止，见 7.3）。**消耗 ∝ 油门（真实蒸汽车节流阀语义）**：每秒烧 油门 个 burnTick + 水/流体同缩（25% 油门 = 1/4 蒸汽流量 = 燃料耐用 4 倍）；Goggle 燃料行显示**墙钟剩余秒 = burnTicks/(油门×20)**（与真实时间同步；油门 0 停机不显示倒计时）。无经济区/混合比/风门/冷却气道需求。常量：`STEAM_MIN_WORK_TEMP=100`、`STEAM_WARMUP_RATE=0.25`（τ=4s）。

### 7.8 气压模型（wiki 用：空气模型来源）

- 冷却（`pressureFactor`）与自动富油（`autoRichness`）共用同一气压模型：Sable 维度大气曲线。
- `SensorSystemAPI.evaluatePressure(y)` 镜像同一条公式（锚点间三次 Hermite）作用在静态曲线快照上（Lua 需 mainThread=false 零 Level 访问）。
- 主世界默认锚点：(−38,1.5)/(63,1.0)/(263,0.4493)/(280,0.4198)/(320,0)；Y≥320 为 0。
- 引擎侧：`getPressureForEngine(engineAltitude())`；客户端显示一律用服务端同步值。

---

### 7.9 蒸汽燃料体系（P2.5 定稿，wiki 用）

**燃料来源与优先**：流体燃料（桶物品原版熔炉燃烧时长，如熔岩桶 20000 tick）**优先**；流体不可用 → 固体燃料从模块邻居燃料箱（`quick_fill_fuel_vault` 等 ItemHandler 容器）自动抽取。

**固体并行燃烧**：一次抽 **N×K 个**（N = 蒸汽室个数，K = 配置批次倍数 `Config.ENGINE_SOURCE_BATCH_MULTIPLIER`）；**当前箱不足 N×K 个 → 切下一箱**（先 `extractItem(slot, need, true)` simulate 校验，整组断供不拆零）；每室 + 抽到数量/N × `burnTime`，各室**同燃同熄**——燃烧时长 = 单个燃料时长，全室空炉才批量补料（`tryPullSolidFuelBatch`）。

**燃料箱（quick_fill_fuel_vault）**：单物品类型库存（容量 1024），暴露 `ItemHandler.BLOCK` 能力（注册于 `CCPeripheralExtender#registerBlockCapabilities`，`QuickFillFuelVaultBlockEntity#getItemHandler` 单槽视图：`getStackInSlot` 数量钳到最大堆叠、真实量在 `storedCount`）；引擎只走 capability 抽取，绝不直接改容器 BE。

**源列表（P2.5b，替代单源缓存）**：四类源列表（水 / 流体室燃料 / 蒸汽室流体燃料 / 固体燃料箱）transient 存于 controller，**事件化重建**——onLoad / 连接性变化 → `markSourcesDirtyNow()`（下 tick 就扫）；方块 neighborChanged（去抖 10 tick，仅当邻居带流体/物品能力才触发）→ `markSourcesDirty()`；全源失败 → `scheduleSourcesRescan`（延迟 20 tick + 等待期不补料）。重建 = 按 scanModule 邻居枚举序收集，`EngineCoreBlockEntity#rebuildSources`。

**燃烧倒计时独立于燃料源**：蒸汽室循环门控 = 水储备 OK 且（流体可用 || 固体可用 || 任一室 `burnTicks>0`）——燃料箱/源罐被拆时已有储备继续燃烧（不冻结不停机），倒计时结束才尝试再抽，抽不到才停烧（停烧 = 停止加热，温度开始牛顿冷却）。

**Goggle 燃料行**（悬停蒸汽室）：`燃料：熔岩`（流体，无数量/时间）/ `燃料：煤炭 x3 (1m 20s)`（固体：xN = 蒸汽室个数 + 墙钟剩余秒 = burnTicks/(油门×20)，getTime 格式 `Xh Ym Zs`，英文括号、无"剩余"）/ `燃料：无`（红）。油门 0 停机不显示倒计时。数据经 controller NBT 同步（键 `SteamFuelType`/`SteamFuelKey`/`SteamBurnTicks`/`SteamSolidFuelKey`），客户端不重算（数量 xN 用客户端 scanModule 轻扫）。

---

## 8. 消耗模型（最终）

| 输入 | 规则 |
|---|---|
| 流体燃料（流体室） | datapack `engine_fuel/*.json`；单燃料制；每室 consumption mb/s（默认 1）；**P2.5b**：删 priority 按源列表查找顺序选第一个可用燃料；**储备制**（`fluidFuelReserve` mb，批次 = 室数×K mb，耗尽才补，头罐失败即时切下一个） |
| 水（蒸汽室） | **1mb/s/室 × 油门**（消耗 ∝ 蒸汽流量，25% 油门 = 0.25mb/s/室）；水储备（批次 = 室数×K mb）从模块邻居水源罐批量补；水不可用整类暂停 |
| 固体燃料（蒸汽室） | 物品原版 `burnTime` → 每秒烧 **油门** 个 burnTick（25% 油门燃料耐用 4 倍）；**流体不可用**时从模块邻居燃料箱（quick_fill_fuel_vault 等）**一次抽 N×K 个并行燃烧**（N = 蒸汽室数、K = 配置倍数，当前箱不足切下一箱，每室同燃同熄），流体优先 |
| 流体燃料（蒸汽室） | **纯原版解析**：桶物品原版熔炉燃烧时长（熔岩桶 20000 tick → 1mb 烧 20 tick × 油门）；批量 drain 批次 mb → 每室 + 批次/N × burnTicksPerBucket/1000 burnTick；`EngineFuels.vanillaBucketBurnTicks` 权威 |

**批次倍数 K**：`Config.ENGINE_SOURCE_BATCH_MULTIPLIER`（默认 1.0，范围 0.25~64），进游戏时由 `CCPeripheralExtender.onServerStarting` 缓存到 `EngineCoreBlockEntity.SOURCE_BATCH_MULTIPLIER`（每 tick 不读配置）。批次 = 引擎数量 × K（mb 或 物品）。

---

## 9. controller tick 数据流（最终）

```
1. 枚举：scanModule —— 两类燃烧室（FACING 反面贴核心归属，去重）+ 冷却气道数 D + 去重邻居（方案 A：每 tick 枚举当安全网）
2. 仲裁：流体+蒸汽并存只让多数派工作（平局流体优先）；steamEngine 标志
3. 门控：!overheated（滞回仅流体）→ heatAllowed
4. 混合比（仅流体）：m_eff = 杆 × autoRichness（无气道杆钳 ≥1.0）；油耗因子 = 杆 × eco(T,m_eff) × cold(T)
5. 源列表重建调度：sourcesDirty → 去抖/延迟倒计时 → rebuildSources（onLoad / neighborChanged / 连接性 / 全失败触发）
6. 流体室评估：储备空才补料（批次 = 室数×K mb，从源列表头 drain、失败即时切下一个）→ runningFluid；消耗 ×效率 × 杆 × eco × cold
7. 蒸汽室评估：水储备空才补一批 → 燃料并行燃烧（流体可用 → 批量 mb → burnTicks；流体不可用 → 固体批量兜底 N×K 个，不足切下一箱）；储备倒计时独立于燃料源（源被拆仍烧完）
8. running：流体 = 有运行燃烧室；**蒸汽 = 油门开启且 T≥100°C 且水储备>0（无燃料余热也运转，缺水停机）**；均需 !过热 && 效率>0（**过载不再停机**——方案 C 对齐 Create，见踩坑 26）
9. speed = running ? 256×效率 : 0；capacity = 室数×base×效率（**蒸汽余热运转 = 全部蒸汽室满出力**）
10. 温度：流体 = 牛顿冷却（过热滞回 220/200）；蒸汽 = 收敛钉 155 / 停火冷却
11. 同步：tooltip 数据**差量门控**（离散字段变化 / 温度 ≥1°C / 慢字段 20 tick 心跳，P7+ 方案 A+B+D）——经济/过冷/油耗系数客户端现算，蒸汽倒计时客户端线性外推
```

---

## 10. Lua API（ccpe:engine 最终版）

`peripheral.wrap(...)` 任意引擎核心节（外设挂 controller，非 controller 委托）。读方法 mainThread=false 直读缓存（≤1 tick 滞后）；写方法与 `getFluidTanks` mainThread=true 服务端权威。

| 方法 | mainThread | 返回 | 说明 |
|---|---|---|---|
| `getTemperature()` | false | number | 温度 °C |
| `isOverheated()` | false | boolean | 过热锁定（仅流体；T≥220 硬停，T≤200 解锁） |
| `getFluidTanks()` | true | table[] | 所有连接储罐 `{fluid, amount, remaining, capacity}` |
| `getThrottle()` / `setThrottle(0~1)` | false/true | boolean | 油门：应力与转速同比例（0 = 停机不烧油，唯一启停控制；setEnabled/getEnabled 已移除） |
| `getMixture()` | false | number | 混合比杆 0.6..1.4（仅流体；蒸汽恒 1.0） |
| `setMixture(x)` | true | boolean | 混合比：油耗 ×杆值、温度 ×热因子；**无门控（纯存值，非流体引擎不生效）** |
| `getEffectiveMixture()` | false | number | 实际混合比 = 杆 × 自动富油（eco 窗口与发热反馈） |
| `getAutoRichness()` | false | number | 自然高度混合比 = 仅自动富油系数（**不含杆值**；海平面 1.0、Y≈260 ≈1.25；蒸汽恒 1.0）——海拔补偿参考：杆 ≈ 1/getAutoRichness() 使 m_eff≈1.0 |
| `getFuelEconomyFactor()` | false | number | 经济系数 0.75..1.0（温度+混合比双达标渐入；混合比不对无奖励无惩罚） |
| `hasAirDuct()` | false | boolean | 是否装冷却气道（信息用；setMixture/setCooling 均无门控，装风道时风门值才被 K_DUCT 分量消费） |
| `getActiveFuel()` | false | table | 活动燃料：流体 `{type="fluid", fluid, optimalTemp=155}`；蒸汽 `{type="steam", optimalTemp=155}`；停机 `{type="none"}` |
| `getCooling()` / `setCooling(0~1)` | false/true | boolean | 风门：只缩 K_DUCT 分量，只能降；**无门控（纯存值，仅装冷却气道时被消费）** |
| `isWarmingUp()` | false | boolean | 蒸汽暖机中（T<100°C 只烧不发电） |

**运行条件**（全满足才发电）：≥1 运行燃烧室 && 效率>0 && 未过热（仅流体）&& 蒸汽 T≥100°C 且水可用（**过载不停机**，方案 C 对齐 Create）。停机原因：缺燃料/缺水、油门 0、流体过热（滞回 T≤200 恢复）、蒸汽暖机未完成。

---

## 11. Goggle 显示（最终版）

**引擎核心**（悬停 engine_core，4 内容行）：

```
发动机状态
温度：XX.X°C
总应力输出：8192 SU          ← 服务端同步 moduleCapacity（用户要求显示产生的总应力）
目前转速：128 RPM
连接的模块：- 流体燃烧室 x1 / - 冷却气道 x1
```

**蒸汽动力室**（悬停 steam_power_chamber）：

```
发动机状态
状态：正常 / 暖机中 / 停机
温度：XX.X°C
油门：50%
燃料：熔岩 / 煤炭 x3 (1m 20s) / 无      ← 参考 simulated portable_engine：流体只显示名称不显示时间；固体 = 数量 xN（N = 蒸汽室个数，并行燃烧）+ 墙钟剩余秒 = burnTicks/(油门×20)（getTime 格式 `Xh Ym Zs`，英文括号，油门 0 停机不显示）；无储备显示红"无"
```

**冷却气道**（悬停 cooling_air_duct）：

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
| —（!running） | 停机（油门 0 / 缺燃料 / 缺水） | 灰 |
| < ~97°C | 过冷（油耗惩罚中） | 蓝 |
| 97 ~ 145 | 正常 | 绿 |
| 145 ~ 165 | **高效**（经济带 |T−155|≤10） | 青 |
| 165 ~ 200 | 正常 | 绿 |
| 200 ~ <220 | 即将过热 | 金 |
| ≥ 220 | 过热停机（滞回 200 解锁） | 红 |

---

## 13. 数据文件（wiki 用）

**流体燃烧室燃料**（datapack，`data/<ns>/engine_fuel/*.json`，`/reload` 热加载）：

```json
{ "fluid": "minecraft:water", "consumption": 1.0, "heat": 1.0, "stress": 1.0 }
```

- `fluid`：流体 id；`consumption`：mb/s/室；`heat`：发热倍率；`stress`：应力倍率。
- **P2.5 删 priority**：多个燃料可用时按源列表查找顺序选第一个（引擎扫邻居储罐的顺序，见 `EngineCoreBlockEntity#rebuildSources`）；旧数据包 priority 字段忽略不读。
- **无温度字段**——过热 220 / 过冷 100 / 经济目标 155 全部引擎固定。

**蒸汽引擎燃料**：不加 datapack——**流体燃料** = 桶物品原版熔炉燃烧时长 >0 即可用（熔岩桶 20000 tick；添加方式 = 按原版给桶物品设置熔炉燃料，与熔炉共享同一套燃料注册）；**固体燃料** = 任意物品原版 `burnTime > 0`（煤炭 1600 tick 等），存入周围 `quick_fill_fuel_vault` 燃料箱由引擎自动抽取（并行燃烧、流体优先）。

---

## 14. 玩家操作要点（wiki 文案）

- **一个杆（油门）就够起飞**：油门同时定应力与转速；转速不够带动机器时推油门；**油门 0 = 停机不烧油**。
- **流体引擎巡航省油**：拉杆到 m_eff≈1.0（高空 Y≈260 → 杆 0.8）= 海拔补偿 → 保持温度在 155°C 带内（145~165）且混合比达标 15s → 经济渐入 ×0.75。太热 → 拉富/开风门；太冷（高速冲压/多风道/低油门）→ 关风门保热；冷却吃紧（高空/静态）→ 富油或降油门。
- **高空自动富油（wiki 解释）**：杆 1.0 = 出厂标定，任何高度油耗 ×1.0（无惩罚）；高空空气稀 → 发动机天然富油（m_eff = 杆 × 自动富油，Y≈260 ≈×1.25）——**自动富油只带来富油降温（发热 ×0.875），不费油**。拉稀省油（油耗 ×杆值）；把杆拉到 m_eff≈1.0 = 海拔补偿，吃到经济系数。
- **过冷惩罚**：刚开机温度低于 100°C 时油耗上升（20°C ≈×1.8），先小油门暖机再推油门。
- **蒸汽引擎**：燃料箱塞燃料 → 点火 → 暖机（T 从环境爬到 100°C 约 4s，期间只烧不发电）→ 出力；**真实蒸汽车油门**：消耗 ∝ 油门（25% 油门燃料耐用 4 倍，Goggle 倒计时同步拉长），油门 0 = 停机不烧油；**余热运转**：燃料耗尽但 T≥100°C 且水储备未耗尽仍满出力发电，缺水/冷却到 100°C 以下才停机；**并行燃烧**：N 个蒸汽室同时烧 N 个燃料（一次抽 N×K 个、当前箱不足切下一箱）；永不过热、高空无忧；代价 = 应力相对流体低（4096/室）。
- **过载 = 不停烧（方案 C，对齐 Create：引擎照常运行烧油，goggle 顶部红字「网络过载」提示）**；过热锁存（流体）T≤200 才恢复；高频启停（油门 0↔1）触发 Create flicker 惩罚。

---

## 15. 边界清单（实现时逐条守住）

- 消耗只在服务端；客户端只读同步状态
- 源列表只存 BlockPos，每 tick 从 capability 重新取（被拆/卸载 → 能力查询返回 null 即失效，天然自愈）；不缓存 handler 引用
- 只走 capability，绝不直接改罐/容器
- 储罐/容器在移动装置上 = 已知限制（v1 不支持）
- 燃烧室双面贴两核心按 `FACING.getOpposite()` 归一边，不双计
- 冷却按"运行中室数"计
- 多引擎抢同一储罐：drain 原子，无死锁
- 非 controller 的 `getGeneratedSpeed()` 恒 0
- 无红石状态（POWERED 逻辑不引入）
- 经济系数只乘消耗、绝不反哺 Q_heat
- **冷却气道 = 纯散热模块**：风门值（setCooling，无门控）只在装风道时被 K_DUCT 分量消费；setMixture 无门控（纯存值，仅流体消费）；经济/油耗/过冷不门控
- 蒸汽 Plan B：永不过热、无经济区/混合比/风门、高度免疫
- **蒸汽运行 = 油门开启且 T≥100°C 且水储备>0**（无燃料余热也运转，容量 = 全部蒸汽室；缺水停机防干烧）；燃料只影响加热不影响发电
- **燃烧倒计时独立于燃料源**：燃料箱/源罐被拆 → 储备 burnTicks 继续烧完；耗尽且抽不到才停烧
- 蒸汽容量 = 运行态全部蒸汽室（余热运转满出力）；暖机/停机 = 0
- **蒸汽消耗 ∝ 油门**（burnTick 每秒烧 油门 个、水/流体同缩）；油门 0 = 停机不烧油（不抽不耗水）
- 固体燃料**当前箱不足 N×K 不抽 → 切下一箱**（simulate 先验，整组断供不拆零）；并行补料时机 = 全室空炉
- 蒸汽 Goggle 燃料行墙钟秒 = burnTicks/(油门×20)；油门 0 不显示倒计时
- **源列表事件化重建**（onLoad / 方块 neighborChanged 去抖 / 连接性变化 / 全源失败延迟重扫）；批次 = 室数×K；储备耗尽即时补料避免应力闪断
- P7 奖励驱动：自动富油/heatFactor 绝不进油耗
- P7 eco AND 门控 + 时间解锁；混合比不对无奖励无惩罚
- P7 温度全部引擎固定（155/100/220）
- 过冷惩罚只乘油耗
- 过稀失火（后续）只可能在流体引擎出现（混合比杆仅流体消费）
- 燃料体系分家：蒸汽 = 原版，流体室 = datapack
- 油门 0 = 停机不烧油；蒸汽点火判定必须带 efficiency>0
- 放置拦截只防交互路径；蓝图/命令直放靠 controller 多数派仲裁兜底

---

## 16. 后续计划

1. **过稀失火**（方案已定）：m_eff<0.8 概率失火（本 tick 出力 ×0）；m_eff<0.6 熄火停机（拉富回安全区自动重启）；高空拉稀余量更大（自动富油垫稀）；只可能在流体引擎出现。
2. ~~tooltip 差量/批量同步优化~~ **✅ 已实施（P7+ 方案 A+B+D）**：由每 tick 20Hz 全量广播改为（1）事件差量（running/overheated/warmingUp/steamEngine/airDuct/燃料类型/容量变化）；（2）温度 ≥1°C 门控（`SYNC_TEMP_DELTA`）；（3）慢字段（ecoProgress/EffectiveMixture/HeatFactor/SteamBurnTicks）20 tick（1Hz）心跳；可推导字段（经济/过冷/油耗系数）客户端按公式现算（`economyFactor()/coldFactor()/fuelFactor()`，不违反踩坑 10——高度相关量 EffectiveMixture/HeatFactor 仍随包同步）；蒸汽倒计时客户端线性外推（`steamBurnTicksDisplay()`，运行中才外推）；`setChanged` 仍每 tick 保温度持久化。稳态包量 ≈1/20 以下。**进游戏验证通过**（详见 §17 实施记录）。
3. **进游戏调参**：`ECO_MIN/ECO_FLAT/COLD_K/ENGINE_T_OPT/ENGINE_MIN_WORK_TEMP/解锁流失速率` 各值。
4. **蒸汽进游戏调参**：`STEAM_WARMUP_RATE`、消耗∝油门幅度（水/燃料）、余热冷却速率（K_CORE 停机散热）——用户计划逐项实测。
5. **移动装置（contraption）上的燃料箱/储罐**：当前源扫描只在静态世界方块上有效（v1 已知限制）。
6. **流体暖机「冷机加浓」（方案 A 已定，未实施）**：静止 4 室+2 风道实测 25% 油门平衡 ≈100°C（正压过冷线、零余量）、0.1 油门 ≈52°C（深过冷）——线性热模型（发热 ∝ 油门）下「低油门暖机 + 高油门经济带」数学上不能同时成立（100/155/220 三点油门比固定 1:1.69:2.5）。现实依据：塞斯纳 172（Lycoming O-320）暖机 = 1000–1200 RPM（≈满转 37–44%，定距桨功率仅 ~5–10%）2–5 分钟达油温 ≥100°F（38°C）；本 mod 的 100/155/220 更接近 CHT 尺度，框架不动。**方案：流体发热乘 `warmupHeatBoost(T) = 1 + COLD_RICH_K × max(0, (COLD_RICH_FADE−T)/COLD_RICH_FADE)`**（仅流体，蒸汽自调节不需）：`COLD_RICH_K=0.8`（冷机最大 ×1.8 发热）、`COLD_RICH_FADE=150`（经济中心附近衰减完，经济带/过热阈值不受影响）；**不加燃料倍率**（已有 coldPenalty 承担「冷=费油」）。改后（4+2 静止海平面）：0.20→100°C 跨过冷线、0.25→~114°C、经济带 ~40% 不变；过热阈值 200→220 后对应油门 % 待实测。实现点：`EngineCoreBlockEntity` 常量区 + 发热行（约 509 行）乘因子；备选方案 B（`BASE_HEAT_FLUID 30→44`：0.25→136°C 但经济带掉 ~30%、过热提前 ~39%，弃用）。常量进游戏实测微调。

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
| P2.5（蒸汽燃料定稿） | 蒸汽固体燃料并行燃烧（一次抽 N×K、不足切下一箱、流体优先）+ 燃料箱 ItemHandler 能力 + 储备倒计时独立于燃料源（拆箱不停机）+ 余热运转（运行 = 油门+T≥100 且水储备>0）+ 真实蒸汽车节流阀消耗（∝油门 + 墙钟显示）+ Goggle 燃料行（xN + 墙钟秒） | ✅ |
| P2.5b（源抽取重构） | 单源缓存+冷却重扫 → **四类源列表事件化重建**（onLoad / neighborChanged 去抖 10 / 连接性 / 全失败延迟 20 tick 重扫）+ **批量补料**（批次 = 室数×配置倍数 K，进游戏缓存；头罐失败即时切下一个；储备耗尽同 tick 补料防应力闪断）+ **删 priority 按查找顺序抽** + scanModule 方案 A（每 tick 枚举当安全网） | ✅ 进游戏验证通过（放罐/拆罐即生效、断供换罐不重启、全失败重扫恢复） |
| P7+（tooltip 同步优化，方案 A+B+D） | 每 tick 20Hz 全量广播 → **事件差量 + 温度 ≥1°C 门控 + 慢字段 20 tick（1Hz）心跳**；**可推导字段下放客户端**（经济/过冷/油耗系数按公式现算，`economyFactor()/coldFactor()/fuelFactor()`；高度相关 EffectiveMixture/HeatFactor 仍服务端同步）；**蒸汽倒计时客户端线性外推**（`steamBurnTicksDisplay()`，运行中才外推）；setChanged 每 tick 保留保温度持久化；Lua 读缓存/活塞动画/应力网络重激活均不受影响 | ✅ 编译通过 + 进游戏验证通过（四套 tooltip 温度连续、状态档位切换、蒸汽倒计时逐秒走正常；稳态包量降到 1Hz 级，详见下方实施记录） |

### P7+ tooltip 同步优化（方案 A+B+D）实施记录

**动机**：P7 起 tooltip 数据每 tick `sendData()`（20Hz 全量 NBT，广播给全部 chunk 追踪玩家）——频率 × 载荷 × 受众三处全占；参考 Create（`SyncedBlockEntity.sendData` 只在离散转变调用、连续量客户端从动力网络推导 + LerpedFloat 平滑）、Simulated（`velocity_sensor` 每 tick 但载荷仅 2 字段；`portable_engine` 纯事件驱动）后定案。

**方案 A：事件差量 + 温度门控 + 慢字段心跳**（`tick()` 末尾同步判定，改 `EngineCoreBlockEntity`）
- **事件差量**（任一变化即发包）：`running / overheated / warmingUp / steamEngine / hasAirDuct / moduleCapacity / steamFuelType / steamFuelKey / steamSolidFuelKey`；
- **温度门控**：`|T − lastSyncedTemp| ≥ SYNC_TEMP_DELTA(1°C)`（恢复 P7 前方案；`lastSyncedTemp` 恢复差量语义，不再每 tick 无条件覆盖）；
- **慢字段心跳**：`++syncHeartbeat ≥ SYNC_HEARTBEAT_INTERVAL(20 tick = 1Hz)` 无条件补发（ecoProgress / EffectiveMixture / HeatFactor / SteamBurnTicks 校准 + 防漏——慢字段随每包携带，心跳只是保底，不会被事件饿死）；
- `setChanged()` 每 tick 保留（温度存 controller NBT 需持久化，与发包解耦）；`reActivateSource`（Create 基类消费）只在运行态/容量变化置位，与发包无关；
- Lua `setThrottle/setMixture/setCooling/attach/detach` 等 setter 原本各自 `sendData()`（事件同步），不在 tick 判定内重复检测；
- 新增快照字段：`lastSyncedRunning/Overheated/WarmingUp/SteamEngine/AirDuct`、`lastSyncedSteamFuelType/Key/SolidFuelKey`、`syncHeartbeat`、`SYNC_HEARTBEAT_INTERVAL=20`。

**方案 B：可推导字段下放客户端**（`write()` 删 4 字段，磁盘包 + 客户端包同时生效；`read()` 保留 contains 兜底兼容旧包/旧档）
- 不再随包同步：`EconomyFactor`（= 1−0.25×ecoProgress）、`ColdFactor`（= coldPenalty(T)，纯 T 函数）、`FuelFactor`（= 杆×经济×过冷）、`OptimalTemp`（P7 后恒 155/155，无消费者，仅保留服务端字段）；
- 客户端派生 getter：`economyFactor() / coldFactor()（仅流体引擎运行中才计，与服务端 runningFluid>0 判定等价）/ fuelFactor()`；tooltip 状态行（过冷）、油耗行改读派生值；
- **仍必须服务端同步**：`EffectiveMixture / HeatFactor`（依赖运动体真实高度——客户端无法可靠自算，踩坑 10 约束）；
- 服务端字段本身保留（tick 每 tick 重算；Lua `getFuelEconomyFactor` 直读 `lastEconomyFactor`；`adoptAdjacentEngineState` 照常拷贝）。

**方案 D：蒸汽倒计时客户端线性外推**（`steamBurnTicksDisplay()`）
- 依据：burnTicks 每 tick **精确** −效率（服务端消耗逻辑）→ `remaining = 同步值 − 效率×(gameTime − steamBurnTicksSyncTime)`，墙钟秒 = remaining/(效率×20) 逐秒平滑跳动；
- **运行中才外推**（`running && efficiency > 0`）：停机/油门 0 时服务端冻结储备，不外推防止显示归零过早；`read(clientPacket)` 记录 `steamBurnTicksSyncTime` 基线；
- 同步时机：燃料类型变化（事件）+ setThrottle（事件）+ 1Hz 心跳校准；每包误差 ≤ 效率×20 burnTick = 满油门 1 墙钟秒，1s 粒度显示不可感知。

**停机状态行为（FAQ 记录）**：
- 服务端：controller 每 tick 仍执行全部轻量计算（`scanModule` 安全网 / 牛顿冷却 / ecoProgress 流失 / `setChanged` 落盘）——**无 drain、无补料、无发电/应力更新**；
- 发包：温度冷却期按 |ΔT|≥1°C 发（停机 155°C ≈2.7Hz → 指数衰减到 <0.2Hz），稳态只剩 **1Hz 心跳**；
- 客户端：活塞动画停（speed=0）、音效不触发、倒计时外推被 running 门控关闭，零额外开销。

**方案 C 评估（未实施，后续可选）**：
- **C1 距离过滤**（`PacketDistributor.sendToPlayersNear` 32 格替代 sendData 广播）：好做——项目现成案例 `MonitorPeripheral.playNiceSound`（sendToPlayersNear）+ `SyncGridPayload`（压缩 NBT payload 模板）+ `SensorPacketHandlers.playToClient` → `be.readClient()` 写客户端 BE；注意 payload 必须携带 `write(clientPacket=true)` **全量 NBT（含 super 的 Speed 字段）**，保证客户端动能网络/活塞动画不受影响；chunk 加载/连接性变化仍走原 `getUpdatePacket`/`sendBlockUpdated`，两套并行；
- **C2 悬停订阅**：Create/Simulated 均无现成案例（最近类比 = 原版 `ChunkMap` 追踪玩家投递）；需自管订阅生命周期（断线/区块卸载清理、订阅瞬间补发全量包防首帧空白）；工作量 ≈ C1 的 2~3 倍、收益边际递减（C1 后已只剩近处玩家 × 事件+1Hz）→ **推荐先 C1，多人服有压力再上 C2**。

**验证**：`./gradlew.bat classes` 编译通过（仅既有警告）；进游戏挂 goggle 实测四套 tooltip（核心/蒸汽室/气道/流体室全量）温度连续无台阶、状态档位切换正常、蒸汽固体燃料倒计时逐秒走无卡顿、放/拆燃烧室与气道后总应力输出 1s 内刷新——均正常；稳态包量降到 1Hz 级。

---

## 18. 踩坑记录（实现期，已修复）

1. **Direction vs BlockPos 比较（踩了两次）**：`FACING.getOpposite()` 是 Direction，不能直接 `.equals(corePos)`；要先算背面坐标再比。
2. **注册期静态初始化急切 `.get()` NPE**：方块类静态初始化里禁止急切解引用 DeferredHolder（谓词用 lambda 惰性求值）。
3. **蒸汽室流体燃料 50% 占空比缺陷**：消耗类累加器必须"运行期间每 tick 累计"，不能只在补货时累计。
4. **燃料表多条目选择顺序**：按 priority 降序、其次文件名升序（同为 0 时 lava < water）。
5. **burnTick 制换算**：蒸汽室每秒烧 油门 个 burnTick（100% 油门 = 1 tick 烧 1 burnTick = 原版熔炉速率）；固体 1 物品 = burnTime；流体 1mb = 原版桶时长/1000 burnTick。
6. **停机温度定格（P3）**：散热项不能只挂运行状态；加 K_CORE×length（停机也缓慢降温）。
7. **追赶式 lerp → 平台台阶**：温度显示用趋势外推（最近两采样点斜率 + ±10°C 钳制）；P7 起 tooltip 每 tick 同步（20Hz）保证实时，P7+ 改回差量门控（温度 ≥1°C + 1Hz 心跳），外推机制继续兜底显示连续性。
8. **Sable 速度读取坑**：不要裸读 `Sable.HELPER.getVelocity`（静止机体有幻影值）；用 `SableCompat.getWorldLinearVelocity`（pose 差分，静止严格 0）。
9. **`overheated` 漏同步**：新增客户端要显示的服务端布尔状态，务必同时加 write/read 两处。
10. **客户端算高度 = 永远错误**：依赖真实高度的客户端显示，一律用服务端同步值，不要客户端自算。
11. **`BlockPlaceContext.getClickedPos()` 陷阱**：点击不可替换方块时返回放置格；贴附核心 = `getClickedPos().relative(getClickedFace().getOpposite())`。
12. **`InteractionResult.FAIL.consumesAction() == false`**：方块 `useItemOn` 返回 FAIL 拦不住放置；正确模式 = 覆写 `BlockItem.place`（照 create:factory_gauge `FactoryPanelBlockItem`）。
13. **蒸汽 `runningSteam` 计「有储备」→ 油门 0 温度钉住**：凡是「有储备 ≠ 在消耗」的状态量，挂到消耗/运行判定时必须加 efficiency 门控。
14. **延长引擎在 controller 端放置 → 参数重置**：Create `formMulti` 以新核心为锚点沿轴正方向组网，新核心放在原 controller 外侧（负方向端）时正方向扫描覆盖整条旧引擎 → 新核心成为新 controller；引擎运行状态只存在 controller BE 内存字段、无迁移机制（未实现 `getExtraData/setExtraData`，CDG 参考实现同样没有）→ 温度/油门/混合比/经济进度等全重置。修复：`updateConnectivity` 在 `formMulti` 前 `adoptAdjacentEngineState`（沿轴找相邻引擎 controller 继承运行状态，两侧都有引擎取更长者）。**拆中段后不含原 controller 的那一半同样会重置（同根因，本次未修）**。参考：`api/create-.../api/connectivity/ConnectivityHandler.java`、`run/references/Create-Diesel-Generators-1.21.1 (1)/.../ModularDieselEngineBlockEntity.java`。
15. **拆燃料箱 → 引擎立即停机（P2.5 修复）**：蒸汽室循环门控原为「水 OK &&（流体可用 || 固体可用）」，燃料源被拆 → 门控 false → 整段循环跳过，已有 `burnTicks` 储备被冻结（不递减）→ 停机。修复：门控加「任一室有储备」（`hasSteamReserve`），**储备倒计时独立于燃料源存在性**（燃料箱只是存储，引擎内部维护燃烧倒计时）。
16. **蒸汽消耗"固定化"是误改 → 恢复 ∝ 油门**：用户反馈"倒计时 4 秒才 -1s"时一度把蒸汽消耗改成固定（1 burnTick/tick）。实为显示问题：消耗 ∝ 油门（25% 油门燃料耐用 4 倍）正是真实蒸汽车节流阀语义；正确修复 = 保留消耗 ∝ 油门 + **Goggle 显示改墙钟秒**（burnTicks/(油门×20)），而非改消耗模型。
17. **油门 0 白抽燃料**：蒸汽补料/喂料/耗水必须带 `efficiency > 0` 门控（油门 0 = 停机不烧油）；`burnTicks -= 油门` 天然冻结，但改固定速率后必须显式门控（喂料 ×0 / 批量补料条件 / 耗水 ×0）。
18. **"不足 N 不抽取"用 simulate 先验**：`extractItem(slot, N, true)` 返回不足 N 个 → 整组不抽（不拆零）；燃料箱 IItemHandler 的 `getStackInSlot` 把数量钳到最大堆叠（真实量在 `storedCount`），但 simulate extract 不受钳制影响，可正确校验 ≥ N。
19. **并行燃烧补料时机 = 全室空炉**：各室 burnTicks 同值同速递减，全部 ≤ 0 才批量补料；部分室空炉（如中途加室）会等到下个并行周期才补齐。
20. **P2.5b「1 tick 空档」闪断**：储备耗尽的那 tick，若补料放在下一 tick，`running` 判定（如 `steamWaterReady = waterReserve > 0f`）会闪 1 tick false → Create 应力网络重新激活（转速/应力瞬时归零）。修复：**储备耗尽在消耗之后同一 tick 内立即补料**；流体室侧 `runningFluid` 用补料前的 reserve 判定 + 补料在判定前执行，天然无空档，不需要同 tick 补。
21. **P2.5b 重扫倒计时反复重置 → 永不执行**：全源失败时每个失败 tick 都调 `scheduleSourcesRescan`，若每次都重置倒计时（cooldown=20）则永远到不了 0。修复：`sourcesAllFailed` 置位后不再重置（已有排程直接 return）；等倒计时走完 → 重建 → 复位才再试。
22. **P2.5b 邻居噪声饿死重建**：`markSourcesDirty` 若每次调用都重置去抖（cooldown=10），红石/装饰等连续邻居变化会让重建永远推迟。修复：已有排程（`sourcesDirty && cooldown>0`）则不重置 + **neighborChanged 只转发带流体/物品能力的邻居**（`onModuleNeighborChanged` 先查 capability），普通方块变化不触发。
23. **P2.5b 删 priority 后燃料顺序 = 扫描序**：`EngineFuels.sortedByPriority()` 删除；流体室燃料 = `rebuildSources` 按 scanModule 邻居枚举序找到的第一个表命中条目（同源罐的 fluid 副本 + entry 一起缓存，drain 按源 fluid，防止罐中途换流体被误抽）。
24. **P2.5b 配置「进游戏缓存」**：批次倍数 K 每 tick 读 `Config.get()` 有解析开销且改配置不热生效——按用户要求进游戏缓存一次到 `SOURCE_BATCH_MULTIPLIER`（`onServerStarting` 写入）。
25. **P3 散热侧「有储备 ≠ 在消耗」→ 停机冷得更快**：发热侧早已加 efficiency 门控（踩坑 13），但温度更新的散热项 `K_AMBIENT × runningTotal` 漏了门控——停机（油门 0）时 `runningFluid/runningSteam` 仍计有储备的室（油门 0 储备不消耗、不归零）→ 燃烧室散热分量（每室 0.05）持续生效，**有剩燃料的停机引擎比无燃料冷得快约 2 倍**（实测 4 节+2 风道静止：k=0.095 vs 0.045；200→20 显示值 30 秒 vs ~3 分钟）。修复：蒸汽/流体两分支冷却处改为 `K_AMBIENT * (running ? runningTotal : 0)`（`K_CORE×length` 停机自散热不受影响，与 memo §15「冷却按运行中室数计」一致）。
26. **过载高频闪烁（自激振荡）→ 方案 C 过载不停烧**：原实现把「燃料消耗」和「应力输出」都挂在 `!overstressed` 上——过载 → `running=false` → 轴停、容量归零（`calculateAddedStressCapacity()=0`）→ 网络重算 stress=0、capacity=0 → 解除过载 → 重启 → 又过载，约 20Hz 振荡，tooltip 状态（跟随同步 `running`）高频闪烁。修复（方案 C）：去掉 `!overstressed` 对流体/蒸汽室燃烧判定与 `running` 的门控——**过载 = 网络状态而非引擎状态，引擎照常运行烧油**（对齐 Create：Create 发电机过载从不停转、网络照常红字）；容量不随过载归零 → 无振荡；红字提示 = goggle 顶部新增「网络过载」行（客户端 `isOverStressed()` 由同步 capacity/stress 派生，见 Create `KineticBlockEntity.read`）。代价：**过载继续耗油**（§14「过载=停烧不白烧油」废弃）。
27. **Lua 每 tick 变油门 → 活塞音效/动画高频重复播放**：活塞相位原为 `gameTime × speed`（瞬时转速 × 总时间），不是累积量——快速变油门（尤其油门到 0）时相位跳变：`prevPistonPhase > phase` 回绕判定误触发（音效高频"噗嗤"），动画相位跳变 + 停机回中间/重启任意相位续走（视觉高频"重复往复"）。修复：改为**累积角度**（客户端每 tick `angle += speed×3/10°` mod 2π，`FluidCombustionChamberBlockEntity` / `SteamPowerChamberBlockEntity`）：转速变化只改推进速率、不产生相位跳变；回绕检测基于累积角跨 2π（每圈一次，真实频率）；渲染按 partialTick 在 prev/current 累积角间插值（`Mth.lerp(partialTick, prevPistonAngle, pistonAngle)`）；停机累积角归零 → 重启从中间位开始。注意：音量配置 = 0 只跳过音效，角度推进始终执行（动画独立于音量）。
28. **活塞每圈一次"卡一下"（相位回绕帧反向扫掠）**：渲染 `Mth.lerp(partialTick, prevPistonAngle, pistonAngle)` 在累积角跨 2π 回绕那一帧，prev（如 6.2）→ 当前（如 0.25）的插值会沿整圈**反向横扫**一次（sin 一路扫过整周期），视觉 = 每圈一次明显卡顿；默认油门 64rpm → 周期 18.75 tick ≈ 0.94s，正好"每隔一秒卡一下"（地狱无燃料纯水运行新场景下最容易注意到；高油门时高频化不易察觉）。修复：`getPistonOffset` 回绕帧把目标角展开到上一角之后再插值（`if (to < prevPistonAngle) to += 2π`）——插值差值 = 正常推进量，不反向扫掠；`pistonAngle` 仍保持 mod 2π 累积（回绕检测/相位跳变语义不变，与踩坑 27 兼容）。
29. **罐/燃料箱"内容变化"不触发源列表重建 → 引擎永久卡死**：源列表是事件驱动重建（方块 neighborChanged → `markSourcesDirty` 去抖 10 tick），但**储罐灌水/加燃料不产生任何事件**；且四个 drain 方法（`drainWaterBatch` / `drainFluidFuelBatch` / `refillSteamFluidFuel` / `tryPullSolidFuelBatch`）在**源列表为空**时的早退分支不调度重扫（"全源失败延迟重扫"只覆盖「有列表但全抽空」，漏了「列表压根为空」）→ 场景：引擎先搭好 → 空罐贴上去（重建时无料不入列）→ 之后灌满水/燃料 → 引擎永不重扫，`waterReserve=0` 补不上，永久卡死（放新核心/新罐触发 rebuild 才"复活"）。修复：**空列表早退分支也调 `scheduleSourcesRescan()`**（纳入 20 tick 延迟重扫自愈循环）——缺料时每 ~1s 重建一次源列表，罐被灌满/放好 1 秒内探测到并启动；运行中/有源时 drain 成功不调度，零额外开销。
30. **蒸汽无燃料不耗水（门控挂错对象）**：耗水原门控 `runningSteam > 0`（有燃料的室数）且公式 `× runningSteam`——无燃料余热运转 / 地狱环境热运转（T≥100 只靠环境，`runningSteam=0`）时永不耗水。违背设计：**水是工质**（蒸汽流量 ∝ 油门，memo §7.7「消耗 ∝ 油门：水/流体同缩」），**燃料只影响加热不影响发电**（§15）。修复：耗水门控 = `running || runningSteam > 0`（工作中 = 全部蒸汽室 `steamChambers.size()`，暖机 = 正在烧的室 `runningSteam`）；油门 0 时 `× efficiency=0` 仍零耗水（不抽不耗水语义不变）。

---

## 参考来源

- `run/references/Create-Diesel-Generators-1.21.1 (1)/.../modular/`（ModularDieselEngineBlockEntity / Block / CTBehavior）
- `run/references/Create-mc1.21.1-dev/.../api/connectivity/ConnectivityHandler.java`
- `run/references/Create-mc1.21.1-dev/.../foundation/blockEntity/IMultiBlockEntityContainer.java`
- `run/references/Create-mc1.21.1-dev/.../content/kinetics/steamEngine/SteamEngineBlockEntity.java`（WeakReference 缓存 + 模块上报核心模式）
- `run/references/Create-mc1.21.1-dev/.../content/logistics/factoryBoard/FactoryPanelBlockItem.java`（放置拒绝模式，见踩坑 12）
- `references/Simulated-Project-main/simulated/common/src/main/java/dev/simulated_team/simulated/content/blocks/portable_engine/PortableEngineBlockEntity.java`（Goggle 燃料行样式参考：燃料名+数量绿、无红、时间 getTime 格式 `Xh Ym Zs`）
