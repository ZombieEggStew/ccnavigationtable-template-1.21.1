# 引擎模块（aero_engine：engine_core + 燃烧室 + 整合气道）— 方案设计

> 状态：**方案已定稿；P0 已实现并编译通过**（引擎核心排成一排 + 延伸放置 + 存档重组）；**P1 已实现并编译通过**（流体燃烧室烧水：JSON 燃料表、零缓存抽罐、发电 256rpm、Create STEAM 音效、活塞动画+对置/轴向交替相位）；**P2 已实现并编译通过**（蒸汽室：水 + 流体燃料熔岩 burnTick 制 + 活塞动画同款；固体燃料暂缓）；**P3 已实现并编译通过**（温度/冷却：牛顿冷却、过热硬停+滞回、效率同缩出力/热量/消耗、冷却风道计数、冲压冷却+Sable 气压/速度复用、Goggle 标题行+温度平滑外推显示、核心自身散热 K_CORE）；**P4 已实现并编译通过**（Lua 外设 `ccpe.engine`：enabled 开关 + throttle 油门（**定距桨单杆模型**：转速 = 油门×256 线性，0 油门=停机）+ getTemperature/isOverheated/getFluidTanks 状态读，外设挂在 controller，包裹任意核心节均委托到 controller；默认 enabled=true 保持既有行为；Goggle 显示状态行 + Lua 控制连接行）；**P5 已实现并编译通过**（混合比 mixture：`setMixture(0.6~1.4)` 经济性/热管理杆 + 高空自动富油 + 凸热曲线 + Goggle 混合比行，见「关键机制 7」）；**P6 已实现并测试通过**（P1/P2 回炉：全引擎单燃料制 + 流体/蒸汽物理排斥放置拦截；`integrated_air_duct` 替换冷却风道（散热+进气门控）；经济区 `eco(T)` 只省油耗（仅流体引擎）；风门 `setCooling(0~1)`；**蒸汽 Plan B**：闭式锅炉自调节 + 暖机门控，永不过热/高度免疫/无经济区无混合比；Lua `getFuelEconomyFactor`/`hasAirDuct`/`getActiveFuel`/`getCooling`/`setCooling`/`isWarmingUp`，见节 10）。
> 设计封闭（本会话内用户确认）：蒸汽室=水+燃料（无蒸汽流体）、**全引擎单燃料制（priority 统一选一种，不混烧）+ 流体/蒸汽物理排斥（不能混用）**、严格零流体缓存、固体燃料从周围容器自动抽取、水走 drain 管线、**无红石控制，末段做 Lua 控制**。P6 追加：整合气道（散热+进气）替换冷却风道、经济区只省油耗、蒸汽 Plan B 自调节暖机。
> 实现前先读参考源码（见文末「参考来源」）。

## 需求

1. **引擎核心排成一排组成一个整体**：参考 CDG 模块化引擎（large_diesel_engine）——拿着引擎物品右键已有引擎，沿轴自动延伸放置（`PoleHelper`）；整条共享同一套运行状态。
2. **引擎核心没有流体缓存**：自动从周围流体储罐中抽取所需流体（零内部缓存，直接 capability drain）。
3. **燃烧室并入**：在核心周围放置燃烧室（蒸汽动力室 `steam_power_chamber` / 流体燃烧室 `fluid_combustion_chamber`）即并入引擎模块。
4. **出力**：有可用燃料即启动；**流体燃烧室每室贡献 4096 应力（×燃料 stress 倍率），蒸汽动力室每室 3072**；引擎核心计算总应力，转速 = **油门 × 256**（0~256 线性，定距桨单杆模型）。
5. **配方消耗**：按配方消耗流体（如 CDG 汽油：每个流体燃烧室 1mb/s）。
6. **蒸汽室**：不直接消耗蒸汽，而是消耗原料——**水**（原版水，1mb/s/室）+ **燃料**（固体/流体均可，燃烧时长按原版熔炉 `burnTime`）。
7. **整合气道（P6 替换冷却风道）**：散热（计入 K_DUCT 系数）+ 进气（装 ≥1 个解锁经济区/拉稀权/风门）；过热由温度滞回判定（见节 6/10）。
8. **无红石控制**；控制最终通过 CC:Tweaked Lua 外设（末段阶段）。
9. **混合比（P5）**：`setMixture(0.6~1.4)` 经济性/热管理杆——**只影响油耗与温度**（应力/转速不变）；自动富油（实际混合比 = 杆 × 气压系数，气压低 = 空气稀 = 天然变浓），拉稀省油但更热，富油费油但降温。

## 决策记录（用户确认）

| 决策点 | 结论 |
|---|---|
| 蒸汽来源 | 无"蒸汽流体"；蒸汽室吃原料：水 + 燃料 |
| 水 | 原版水（minecraft:water），每蒸汽室 1mb/s，**从周围储罐 drain**（与流体燃料同一管线） |
| 燃料效率 | 按原版熔炉 `burnTime`：固体物品 burnTime tick → 烧 burnTime/20 秒（煤=1600tick→80s）；流体按 datapack `burn_ticks_per_bucket`（熔岩桶 20000tick → 1000mB 烧 1000s） |
| 冷却风道 | 过热约束：C 个**运行中**燃烧室需 ≥ ceil(C/N) 个冷却风道（N 默认 4）；不足则容量 × min(1, D/D_req)，**< 0.5 停摆**（0 风道=直接停）（**实现时被牛顿冷却取代**：风道 → 散热系数 K_DUCT，过热由温度滞回判定，见节 6；「容量×D/D_req、<0.5 停摆」未落地） |
| 燃料规则 | **全引擎单燃料制（P6 修订，P1/P2 回炉已实现并测试通过）**：controller 按 priority 统一选**一种**可用燃料，所有运行流体室烧同一种（priority 相同按文件名升序）；**不混烧**。实现上 `findFuel`/`findSteamFluidFuel` 本就是引擎级单选，无需改燃料选择；**未合并 fuelDebt**（蒸汽室 burnTicks 逐室持有，合并消耗累加器无法把 burnTick 分给各室，无功能收益） |
| 引擎类型 | **流体/蒸汽物理排斥（P6 修订，P1/P2 回炉已实现并测试通过）**：同一引擎模块只能贴一种燃烧室——主拦截 = 自定义 `ChamberBlockItem#place`（覆写 `BlockItem.place`，DENY 音效 + 状态条提示 + FAIL，同 create:factory_gauge 的 `FactoryPanelBlockItem#place` 模式；`place` 是 `useOn` 的终点无回退）+ `ChamberBlock.getStateForPlacement` 返回 null 兜底 + `ChamberAttachPlacementHelper` 虚影拒绝；controller 兜底仲裁：混合模块（旧档/蓝图残留）只让多数派类型工作，平局流体优先 |
| 流体缓存 | **严格零缓存**：所有消耗 = controller 每 tick 直接从源储罐 capability drain，无罐无液位显示 |
| 固体燃料输入 | 从周围容器**自动抽取**（蒸汽室 6 邻居的 `IItemHandler`，`extractItem`），零 UI；可加 tag `ccpe:engine_fuel_sources` 白名单（默认不启用） |
| 红石 | 不要；末段做 Lua 控制（见「Lua 控制」节） |
| 过热行为 | T ≥ T_max 硬停（不烧油）；T ≤ 0.8×T_max（滞回）才允许重启，防启停振荡。**P6 Plan B：仅流体引擎生效**——蒸汽引擎闭式锅炉自调节，永不过热（见「蒸汽温度」行） |
| 效率语义 | 效率 = 油门（P4 定稿：**定距桨单杆模型**）：**线性缩放应力输出与转速**（100% = 满应力 + 256rpm，50% = 半应力 + 128rpm，0 = 停机）；发热/油耗 ∝ 油门；P3 默认 0.25——静态无风道不过热，现有无风道测试机继续可用（默认档转速 = 64rpm，多数机器可工作） |
| 转速-油门耦合 | **转速 = 油门 × 256（0~256 线性）**；应力与转速同比例缩放 → 同一条网络**过载比例不随油门变**（定距桨特性：油门只改整体快慢）；外部转速控制器无法作弊（预算 = 应力×转速 恒随油门缩放，50% 油门 + ×2 变速 → 下游 256rpm 但预算减半 → 必过载） |
| 混合比范围 | `setMixture(0.6~1.4)`，默认 1.0，NBT 持久化，引擎级（**仅流体引擎生效**；蒸汽引擎无混合比轴，锁 1.0，见节 10）；油门 0 = 停机时混合比无意义 |
| 混合比与功率 | **不影响应力/转速**（油门是唯一功率杆；混合比只管经济性与热管理） |
| 混合比热曲线 | **凸曲线**（防"永远拉稀"驻点）：稀侧热惩罚加速上升 `×1+A(1−m)²`（A≈2.0 起步），浓侧平缓收敛 `×1−B(m−1)`（B≈0.5，下限 ~0.7） |
| 自动富油 | **建模（方案 B，气压自变量）**：实际混合比 = 杆 × autoRichness(P)，P = `getPressureForEngine(高度)`（与冷却模型同源同曲线，海平面 1.0、Y=320 为 0）；`autoRichness = 1 + K×(1−P)` 钳 [1, 1.25]，K=0.45 起步 → Y≈200（云层）≈×1.19、Y≈260 ≈×1.25 达上限；高空不拉稀 = 白烧油（真实：化油器按进气体积配油，空气稀 → 天然变浓）。**不用高度线性标定——游戏高度仅 0~320 格，"10km"标定跑不满**。**仅流体引擎（蒸汽引擎无自动富油，P6 修订）** |
| 过稀失火 | 之后再加（加入计划，P5 后单独阶段；P5 先靠凸曲线防驻点） |
| 蒸汽室热量 | 基础热比流体室**低 20%**（吃水 = 天然冷却）——**P6 Plan B 已退役**：不再参与温度计算（蒸汽改自调节钉温模型，见下） |
| 蒸汽温度（P6 Plan B） | **闭式锅炉自调节 + 暖机门控**：点火燃烧（有水+燃料）→ 温度指数收敛并钉 `BOILER_T_OPT`（饱和温度；燃料热量用于产汽=功率而非升温；与气压/环境/冲压无关 → **高度免疫**）；**T < `STEAM_MIN_WORK_TEMP`(100°C) 只烧不发电（热机过程，升温速率 `STEAM_WARMUP_RATE`=0.25 τ=4s）**，≥ 阈值才「开始工作」；缺水 → 停烧（waterOk 防干烧）→ **永不过热**（过热滞回仅流体）；停火 → 牛顿冷却。**无经济区**（恒 1.0）、无混合比、无风门、无需整合气道 |
| 冲压冷却 | **连续曲线**：10→30 m/s 线性爬升，30 m/s 达满（ram_max = 2.0） |

## 参考实现

| 参考 | 抄什么 |
|---|---|
| CDG `ModularDieselEngineBlockEntity` / `ModularDieselEngineBlock` | controller 指针网络（`controller`/`length` 字段）、`updateConnectivity`→`formMulti`、`onRemove`→`splitMulti`、NBT `Uninitialized`+`LastKnownPos` 重组、`fuelDebt` 累加器、仅 controller tick、Goggle 委托 |
| Create `ConnectivityHandler` / `IMultiBlockEntityContainer` | 组网算法（洪泛搜索、controller 选择、拆中段自动重组）、接口契约（`getController/setController/getHeight/setHeight/getMaxLength/getMaxWidth/getMainConnectionAxis/...`） |
| Create `SteamEngineBlockEntity` | `WeakReference` 引用缓存 + `isRemoved()` 校验 + 失效重扫 + 委托 `getControllerBE()`（模块找核心/源的模式） |

## 总体架构

```
EngineCoreBlockEntity（仅 controller 干活；非 controller 的 getGeneratedSpeed()=0）
 ├─ 组网（P0）：IMultiBlockEntityContainer + ConnectivityHandler.formMulti/splitMulti
 │              + PoleHelper 延伸放置 + Uninitialized 存档重组
 ├─ 每 tick（P1~P3，见「数据流」）：
 │   枚举模块 → 逐燃烧室评估 → 汇总容量/冷却 → 发电 → 消耗 → 同步
 └─ 同步：运行态/容量 sendData → 燃烧室 Visual 读父核心 → 活塞动画

燃烧室 BE：非动力、轻量
 ├─ fluid_combustion_chamber：无槽（流体燃料走 drain 管线）
 └─ steam_power_chamber：无槽（固体燃料由 controller 从周围容器自动抽取）

FuelType：datapack JSON（流体燃料表 + 蒸汽室流体燃料表 + 各室 power）
```

**核心思想**：整条引擎（最多 21 节）只有 controller 一个决策点；燃烧室是纯"输入模块"（非动力 BE），由 controller 拉取评估；流体/固体燃料都由 controller 统一从外部源抽取（零内部缓存）。

## 关键机制

### 1. 引擎核心排成一行（P0）

- `EngineCoreBlockEntity` 实现 **普通 `IMultiBlockEntityContainer`**（无罐 → 不需要 `.Fluid` 子接口；`ConnectivityHandler` 的流体合并按 `instanceof Fluid` 跳过）。
- 字段：`BlockPos controller`、`int length`（默认 1）、`BlockPos lastKnownPos`。
- `EngineCoreBlock.onPlace` → `updateConnectivity()` → `formMulti`；`onRemove` → `splitMulti`（拆中段自动重组：全体解体 → 下一 tick 各自 `formMulti` 重新抱团）。
- **放置助手**：`PlacementHelper extends PoleHelper<Direction>`（`state -> FACING.getAxis()`），引擎物品右键已有引擎沿轴延伸。
- **存档/蓝图**：`"Uninitialized"` NBT 标志 → 读档/蓝图部署后下一 tick 重组；`"LastKnownPos"` 找回 controller。
- **轴网络**：`hasShaftTowards` 已沿 AXIS 双面 true（`EngineCoreBlock.java:49`）→ 相邻核心天然轴耦合成一个传动网络，controller 发电、整条同转。
- 客户端：controller/`length` 变化 → `sendData()` + 刷新渲染包围盒。
- `getMaxLength = 21`、`getMaxWidth = 1`（1×1×N 线性柱体，沿 FACING 轴）。

### 2. 零缓存流体抽取（P1）

- **必须走 capability**：`level.getCapability(Capabilities.FluidHandler.BLOCK, 罐controllerPos, null)` 再 `drain()`；绝不直接改储罐 BE（Create 储罐自身也是多方块，真罐在 controller 上）。
- **引用缓存**：`WeakReference` + 每 tick `isRemoved()` 校验 + 失效重扫（Create `SteamEngineBlockEntity.getTank()` 同款）；找到的储罐委托 `getControllerBE()`。
- **源范围**：模块（core 全部成员 ∪ 燃烧室）的 6 邻居中带流体能力且满足方块 tag 过滤的方块；缓存 + 脏标记（`neighborChanged`/`onPlace` 置脏）。
- **消耗粒度**：`fuelDebt` 累加器（float），每 tick `debt += rate_per_tick`，`>=1` 时从对应源罐 drain 1mb（1mb/s = 0.05mb/t）。
- 客户端绝不消耗；运行态用 `sendData()` 同步。
- 已知限制：移动装置（contraption）上的储罐找不到（虚拟 BE），v1 不支持。

### 3. 燃烧室并入与逐室评估（P1/P2）

- **归属**：燃烧室 `FACING` 的反面即贴附的核心：`parent = pos.relative(FACING.getOpposite())`；双面贴两个核心时天然归一边，不双计。
- **聚合点 = controller**：controller 沿 row 每节扫 6 邻居收集燃烧室（缓存+脏标记），求和。
- **逐室评估**（每 tick）：
  - 流体室：datapack 燃料表**第一个可用流体**（源罐中有）→ 运行；消耗 rate mb/s/室。
  - 蒸汽室：**水可用 且 燃料可用**（固体：计时器>0 或能从容器抽到；流体：燃料表命中且源罐有）→ 运行。
- **动画**：燃烧室 Visual 每帧直接读父核心"是否运行"字段（客户端 1 次方块查询/帧），不推送。**P1 已实现（流体室，P2 同款）**：`FluidCombustionChamberBlockEntity#getPistonOffset(partialTick)` —— 父引擎（FACING 反方向贴的核心，经 getControllerBE 解析整条 controller）运行时，活塞沿 FACING 方向 ±2/16 块正弦往复（`PISTON_STROKE=2/16`），**角度随引擎输出转速推进**（照 Create SteamEngine `getTargetAngle`：每 tick 推进 `speed×3/10` 度，1 圈 = 1 往复 → 动画速度与转速挂钩；停机回中间位）；Visual/Renderer 用 partialTick 平滑插值。
- **活塞"噗嗤"音效**：每个燃烧室/动力室**客户端 tick** 检测活塞相位回绕（一周期一次、固定位置）→ 把自身坐标入队到引擎 controller 的音效池（照 Create `BoilerData/SoundPool`：mergeTicks=2 合并窗口 + maxConcurrent=4 并发上限、超出随机截断，各室在自己坐标发声），controller 每 tick `play()` 统一播放；声音 = Create `AllSoundEvents.STEAM`（pitch 0.8±0.2）。**两类燃烧室各一个池、音量各自配置**：流体室 `Config.ENGINE_FLUID_PUFF_VOLUME` 默认 0.05、蒸汽室 `Config.ENGINE_STEAM_PUFF_VOLUME` 默认 0.1（播放时读取、改配置即时生效）；**某类音量 = 0 → 该类的 tickClient 直接 return（不检测相位/不入队），controller 也不创建/不播放对应池（省性能）**。已移除核心的持续 STEAM 嘶声 `EngineSoundInstance`，只有随转速的噗嗤；相位与活塞动画同源（角度随转速推进 + 对置/轴向交替偏移），不同相位的燃烧室在不同角度触发 → 波浪式"噗嗤噗嗤"。
- **性能现状（P1）**：燃烧室计数为每 tick 全量扫描 blockstate（length×6 次，短排无压力）；**脏标记缓存（neighborChanged 置脏 → 只重建一次列表）列为最后优化（P3）**，不提前做。
- **贴附虚影（P1 已实现）**：`ChamberAttachPlacementHelper`（`IPlacementHelper`，流体/蒸汽室共用）——手持燃烧室对准引擎核心时 catnip `PlacementClient` 自动渲染虚影（位置 = 核心点击面相邻格，FACING = 点击面，与放置一致）；右键放置走默认 BlockItem（`getStateForPlacement` 正好背贴核心），无需额外代码。
- **固体燃料计时器**：水在才走（熔炉"产物满停烧"语义，避免白烧煤）；归零时 controller 从该室 6 邻居容器 `extractItem(slot,1,simulate)` 找第一个 `burnTime>0` 的物品，真实抽取 1 个，`burnTicks = burnTime`。**（暂缓：用户要求先做流体燃料；`tryPullSolidFuel` 保留未调用，恢复时在 burnTicks≤0 时先于流体燃料尝试）**

### 4. 冷却过热（原草案，已被 P3 牛顿冷却取代）

> 本节是 P3 初版草案（风道按「容量×D/D_req、<0.5 停摆」约束）。**实际实现改为牛顿冷却模型**：风道计入散热系数 K_DUCT、过热由温度滞回判定（见节 6）。本节仅保留作设计参考；其中"风道计数范围（核心成员 ∪ 燃烧室邻居）"沿用至今。

- D = 贴在**任意核心成员或燃烧室**上的 `cooling_duct` blockstate 计数（无 BE，纯 blockstate 判定）。
- C = **运行中**燃烧室数（停摆的室不产热、不占冷却）。
- `D_req = ceil(C/N)`（N 默认 4）；`coolingFactor = min(1, D/D_req)`；容量 × coolingFactor；**coolingFactor < 0.5 → 整机停摆**（消耗也停）。

### 5. Lua 控制（P4，已实现：ccpe.engine 外设，挂 controller）

- 无红石状态；`enabled` 是 Lua 专属开关（**默认 true** 保持 P0–P3 行为；false = 整机停摆：不发电、不消耗，温度自然冷却；NBT 持久化，旧存档无该字段按开启处理）。
- 接入方式：`compat/cc/CCPeripheralCapabilities` 把 `engine_core` 注册为 CC:T 外设（`PeripheralCapability`），`peripheral.wrap` 直接包裹任意核心节；**非 controller 经 `getPeripheral()` 委托到整条引擎 controller**（Capability 查询发生在主线程——CC 外设挂载路径 BlockCapabilityCache + ServerLevel.getBlockEntity，跨 BE 取 controller 安全）。外设类型 `ccpe:engine`。
- Lua API（读方法 mainThread=false 直读 controller 缓存状态，≤1 tick 滞后；`getFluidTanks` 与写方法 mainThread=true 服务端权威）：
  - `getTemperature()` → 温度 °C（服务端权威值；Goggle 显示的是客户端趋势外推平滑值，两者可能略有差异）
  - `isOverheated()` → 是否过热锁定（T≥200 硬停，T≤160 滞回解锁）
  - `getFluidTanks()` → 数组：所有连接储罐（模块邻居中带流体能力的方块，含燃料/水源罐，经 seen 去重），每罐一项 `{fluid=流体id|nil, amount=当前量mb, remaining=剩余量mb, capacity=总量mb}`；mainThread=true 现场扫描模块邻居
  - `getEnabled()` / `setEnabled(bool)`：引擎开关（默认 true；setEnabled 返回是否发生变更）
  - `getThrottle()` / `setThrottle(0~1)`：油门（定距桨单杆）：**线性缩放应力输出与转速**（100% = 满应力+256rpm，50% = 半应力+128rpm），发热/油耗 ∝ 油门，越界钳制；**0 = 停机**（不发电不烧油，与 setEnabled(false) 等效）
  - `getMixture()` / `setMixture(0.6~1.4)` / `getEffectiveMixture()`：混合比（P5，仅流体引擎，见节 7 与节 8 速查表）
  - P6 追加（仅流体引擎为主）：`getFuelEconomyFactor()` / `hasAirDuct()` / `getActiveFuel()` / `getCooling()` / `setCooling(0~1)`；蒸汽引擎 `isWarmingUp()`（详见节 8 速查表）
- **运行条件**（全部满足才发电）：≥1 运行中的燃烧室（流体室有燃料 / 蒸汽室有水+燃料）&& `enabled` && `efficiency>0` && 未过载 && 未过热锁定（**仅流体**）&& **蒸汽 T≥STEAM_MIN_WORK_TEMP（暖机门控）**。停机原因：缺燃料/缺水、`setEnabled(false)`、油门 0、过载、流体过热（滞回 T≤160 才恢复）、蒸汽暖机未完成。
- **变速/变容不会像 transmission_peripheral 那样出 flicker 问题**：引擎是源本身（Creative Motor 同款 `updateGeneratedRotation → applyNewSpeed` 路径），1~256 同向变速不触发 flicker 惩罚、无 `RotationPropagator.handleRemoved` 级联；唯一要避免的是高频启停（油门 0↔1 反复或过载临界振荡 = 零穿越 → flicker 惩罚，Create 自带兜底）。
- 注：早期草案的 `getStatus`（running/speed/chamberCount/coolingFactor/fuelSources/consumptionRates 聚合表）与 `getCapacity` **未采用**，状态读改为按需单字段 + `getFluidTanks`——P3 实际为牛顿冷却（风道走散热系数 K_DUCT，无「容量 ×D/D_req」缩放），无 coolingFactor 可报。
- 语义：Lua 是唯一控制入口（对应 CDG 的红石/模拟油门位，全部由 Lua 承担）。
- 实施记录：实现按项目外设模式（`MyBearingBlockEntity` / `TransmissionPeripheralBlockEntity` 内嵌 Peripheral 类 + `CCPeripheralCapabilities` capability 注册）；Goggle 自定义信息 = **状态行**（正常 / 即将过热 T≥0.8×T_max / 过热，lang 键 `tooltip.ccpe.engine.status*`，红/金/绿三色）+ 温度行 + 效率行 + **Lua 控制连接行**（`Peripheral.attach/detach` 维护 `luaConnected`，NBT 仅同步客户端不落盘，lang 键 `tooltip.ccpe.engine.lua_*`）；已移除「已禁用（Lua）」行。

### 6. 温度与过热冷却（P3 已实现；P6 Plan B 后 = 流体引擎专用）

> **P6 Plan B 后本模型只作用于流体引擎**；蒸汽引擎 = 闭式锅炉自调节 + 暖机门控（温度钉 BOILER_T_OPT、永不过热、高度免疫，见节 10「蒸汽引擎」）。

**模型**（controller 每 tick，服务端；牛顿冷却）：

```
Q_heat   = Σ运行室 × efficiency × 燃料.heat × 类型基础热      // 流体室 H0，蒸汽室 0.8×H0（吃水=天然冷却）
K_total  = (K_CORE×length + K_AMB×N + K_DUCT×D) × ram(speed) × f(pressure)
                                                             // K_CORE：核心自身散热（停机也缓慢降温，τ≈50s）
T_amb    = 20 − 0.0065×altitude，下限 −40℃                    // 对流层递减率
T'       = (Q_heat − K_total×(T − T_amb)) / C_th
T       += T'/20；钳制 [T_amb, T_max×1.2]
overheat = T ≥ T_max → 硬停（running=false，不消耗）；resume = T ≤ 0.8×T_max（滞回）

ram(speed)：<10 m/s = 1.0，10→30 线性爬升到 2.0（连续，无悬崖）
f(pressure)：pressure^0.8（钳位下限 0.25；气压来自 SensorSystemAPI.getPressureForEngine——P3 新增的无门控静态方法）
speed/onBody：SableCompat.getContainingSubLevel（子次元=运动体）+ SableCompat.getWorldLinearVelocity（pose 差分，静止严格 0）
altitude    ：运动体取 getSubLevelWorldPos().y，静态取方块自身 Y
```

**实际常量**（已调，三个目标场景 T_eq 均 ≈170°C，留 30°C 裕量）：

| 常量 | 值 | 说明 |
|---|---|---|
| H0 / STEAM_HEAT_FACTOR | 30 °C/s / 0.8 | 流体室基础热 / 蒸汽室倍率 |
| K_AMB / K_DUCT / K_CORE | 0.05 / 0.05 / 0.02 | 环境(每室) / 风道(每个) / 核心自身(每节) |
| RAM_START / RAM_FULL / RAM_MAX | 10 / 30 m/s / ×2.0 | 冲压冷却曲线 |
| T_MAX / T_RESUME | 200 / 160°C | 过热硬停 / 滞回恢复 |
| T_AMB_SEA / LAPSE / FLOOR | 20 / 0.0065°C/m / −40°C | 环境温度 |
| C_TH_BASE | 1.0 | 热容 ×length（大引擎热得慢） |
| PRESSURE_FLOOR | 0.25 | 气压因子下限（防大气顶冷却归零） |

**平衡定标**（约束结构，H0=30、ΔT_max=180 时成立）：

| 目标 | 约束 |
|---|---|
| 静态 0 风道 eff25% 不过热 | K_AMB ≥ 0.25×H0/ΔT_max |
| 静态 1 风道/室 eff50% 不过热 | K_AMB + K_DUCT ≥ 0.5×H0/ΔT_max |
| 运动 ≥30m/s 1 风道/室 eff100% 不过热 | (K_AMB + K_DUCT)×ram_max ≥ H0/ΔT_max → ram_max ≈ 2.0 |

**高空冷却结论（物理）**：**更差**——压力项主导。气压 1.0→0.4（10km）换热系数 h∝ρ^0.8 掉到 ~0.48，低温 ΔT 只补 ~1.4 倍 → 净 ~0.67（差 1/3）。公式保留两项（压力进 h、低温进 ΔT），净效果由常量定。

**实现要点（P3 已落地）**：
- `temperature` 存 controller NBT 持久化；`efficiency` 字段默认 0.25（P4 Lua 控制）
- **效率=油门，出力和热量和消耗同缩**：`capacity = Σ室×base×efficiency`、`fuelDebt += ...×efficiency`、蒸汽室 burnTicks 改为 **float** 每 tick 减 efficiency（25% 效率下 1 个 burnTick 烧 4 tick，燃料耐用 4 倍）
- 燃料 JSON 的 `heat` 字段 P3 接入 Q_heat
- 冷却风道计数沿用模块扫描（贴在核心成员**或燃烧室**，blockstate 计数，无 BE；与储罐抽油同范围）
- 过载（isOverStressed）与过热独立：过载停烧照旧；过热是温度机制（滞回锁定 `overheated`）
- **温度同步（性能最优方案）**：服务端权威 → 差量发包（≥1°C 才 `sendData`，约 1/20 频率）→ 客户端**趋势外推**显示（最近两采样点斜率继续走 + ±10°C 外推带钳制，`displayedTemperature`）。对比 simulated velocity_sensor 每 tick `sendData()` 20Hz 发包换"看起来平滑"——我们同视觉效果、1/20 带宽（见踩坑记录 7）
- **Goggle**：Create overlay 把 tooltip **第一行当标题行**（后有间距）→ 首行加 `tooltip.ccpe.engine.header`（en_us/zh_cn 已加），内容行 5 空格缩进对齐 MyBearing 惯例；温度用 `displayedTemperature`（客户端平滑值）
- **模块 tooltip 代理**：流体/蒸汽燃烧室与冷却风道方块实现 `IProxyHoveringInformation`，`getInformationSource` 经 `EngineCoreBlockEntity.engineControllerPos` 把 tooltip 源代理到整条引擎 controller——与看核心**完全共用**同一 tooltip（含无护目镜悬停的传动信息）；未连接核心时返回自身坐标 → 无 BE/goggle 信息 → 不显示

### 7. 混合比 mixture（P5，已实现并编译通过）

**现实依据**：化油器/机械燃油喷射按「进气**体积**」配油，发动机实际需要的是进气**质量**——高度升高空气密度降 → 同样体积油偏多 → 混合气**天然变浓（自动富油）**。混合比杆的真实职责 = **海拔补偿**（高空手动拉稀把多给的油补回来）。真实 CHT 与油量**反向**：富油 = 多余燃油汽化吸热（油多但缸温低），贫油 = 燃烧接近完全（油少但缸温高）。

**核心矛盾（设计地基）**：发热**不能**跟「烧了多少油」走（否则拉稀 = 省油又降温 → 永远拉稀，机制塌掉）。必须**解耦且反向**：

```
油耗  ×= 实际混合比 m_eff          // 稀=省油，浓=费油
发热  ×= heatFactor(m_eff)        // 与油反着走（凸曲线）
```

**模型公式**（已落地 controller tick；`engineAltitude()` 已有，移动装置取物理体原点高度，与冷却同源）：

```
autoRichness(P)  = 1 + K_P × (1 − P)，钳制 [1.0, 1.25]     // P = getPressureForEngine(engineAltitude)
                                                           // （与冷却同源同曲线；海平面 1.0，Y≈260 ≈0.45 → ×1.25 达上限）
                                                           // K_P = 0.45 起步：Y≈200（云层）≈×1.19
m_eff            = 杆 × autoRichness(P)                    // 高空不拉稀 = 实际变浓 = 白烧油（真实）
heatFactor(m)：
   m < 1.0 : 1 + A×(1 − m)²       // 稀侧凸（A≈2.0 起步：m=0.8→×1.08，m=0.6→×1.32）
   m ≥ 1.0 : 1 − B×(m − 1)        // 浓侧平缓（B≈0.5：m=1.3→×0.85，下限 ~0.7）

油耗落地：fuelDebt += running × consumption × efficiency × m_eff / 20
         蒸汽室流体  fluidFuelDebt += 1000/burn_ticks_per_bucket × efficiency × m_eff
发热落地：heat += running × efficiency × fuel.heat × heatFactor(m_eff) × 类型基础热
         水不受混合比影响（水是冷却剂/蒸汽原料，固定 1mb/s/室）
应力/转速：完全不动（getGeneratedSpeed / capacity 与 P4 定距桨逐字节一致）
```

**P5 常量（当前值，进游戏可调）**：

| 常量（代码名） | 值 | 说明 |
|---|---|---|
| `MIXTURE_MIN` / `MIXTURE_MAX` | 0.6 / 1.4 | 杆范围（setMixture 钳制） |
| `MIXTURE_PRESSURE_K` | 0.45 | 自动富油：×K×(1−P)；Y≈200（云层）≈×1.19 |
| `MIXTURE_ALT_MAX` | 1.25 | 自动富油上限（Y≈260 达上限，至 Y=320 维持） |
| `MIXTURE_LEAN_K` | 2.0 | 稀侧凸惩罚 A（m=0.8→×1.08，m=0.6→×1.32） |
| `MIXTURE_RICH_K` | 0.5 | 浓侧降温 B（m=1.3→×0.85） |
| `MIXTURE_RICH_FLOOR` | 0.7 | 浓侧热因子下限 |

**进游戏已验证**：定距桨油门（应力/转速同比例）、混合比油耗与温度变化、Goggle「高空实际」随高度同步——均正常。

**设计张力（高空三方博弈）**——高空冷却本就更差（压力项主导，见节 6 高空结论），自动富油部分抵消：

| 高空策略 | 油耗 | 温度 | 结果 |
|---|---|---|---|
| 不拉稀（杆=1.0，实际≈1.25） | 费油 | 偏凉（富油吸热） | 安全但浪费 |
| 主动拉稀省油 | 省油 | 偏热 + 高空散热更差 | 高技巧高回报，双倍过热风险 |

- **环境联动**：低空 + 高速（冲压 ×2）+ 风道 → 冷却富余 → 大胆拉稀省油；高空 / 静态 / 轻量化 → 富油买安全或降油门。Goggle 状态行（正常/即将过热）天然成为「混合比是否过稀」的仪表。
- **富油是预防药不是急救药**：过热为滞回锁存（T≥200 硬停，≤160 解锁），已过热停机时拉富油不会立即重启。
- **默认 = 现状**：海平面杆 = 1.0 时油耗/发热与 P3/P4 逐字节一致，不破坏既有定标与存档；高空自动富油是新增的「海拔成本」。
- **玩家收益闭环**：轻量化设计（少风道/少水冷）用富油换安全（油费贵）；精良冷却（风道/冲压/低空高速）拉稀省油。同一引擎两种流派，与定距桨单杆正交。

**实施要点（P5 已落地）**：
- `mixture` 字段（默认 1.0），NBT 读写（同 efficiency 模式，旧存档无字段按 1.0）
- Lua：`getMixture()` / `setMixture(0.6~1.4)`（mainThread=true，越界钳制，非法返回 false）；**不需要 reActivateSource**（混合比不影响转速/容量），setChanged+sendData 同步；**`getEffectiveMixture()`** 读实际混合比（杆 × 自动富油，服务端每 tick 计算，供自动拉稀校正反馈）
- Goggle 新增「混合比」行（显示杆值，高空自动富油使实际值不同时追加「高空实际」行）；lang 键 `tooltip.ccpe.engine.mixture` / `.mixture_actual`
- 凸曲线防「永远拉稀」驻点：最优混合比落在中间某处、随冷却能力变化（稀侧惩罚加速 → 极端不划算）
- 过稀失火（**后续阶段**）：m_eff < 下限 → 随机断续掉功率/停摆，作为极端下限惩罚；P5 暂不做，靠凸曲线即可

### 8. Lua API 速查与玩家行为（wiki 用：ccpe:engine）

`peripheral.wrap("...")` 任意引擎核心节（外设挂在 controller）。读方法直读 controller 缓存（≤1 tick 滞后）；写方法与 `getFluidTanks` 为 mainThread=true 服务端权威。

| 方法 | mainThread | 返回 | 说明 |
|---|---|---|---|
| `getTemperature()` | false | number | 温度 °C（服务端实时值；Goggle 显示客户端趋势外推平滑值，可能有 ≤1°C 差异） |
| `isOverheated()` | false | boolean | 过热锁定（**仅流体引擎**；蒸汽 Plan B 恒 false） |
| `getFluidTanks()` | true | table[] | 所有连接储罐，每项 `{fluid=id\|nil, amount, remaining, capacity}`（mb） |
| `getEnabled()` | false | boolean | 引擎开关（默认 true） |
| `setEnabled(bool)` | true | boolean | 开关引擎（false = 整机停摆：不发电不消耗，温度自然冷却） |
| `getThrottle()` | false | number | 油门 0..1（定距桨单杆：= 应力与转速因子） |
| `setThrottle(x)` | true | boolean | 油门：应力与转速同比例（100%=满应力+256rpm、50%=半应力+128rpm、0=停机=不烧油）；越界钳制，非法返回 false |
| `getMixture()` | false | number | 混合比杆 0.6..1.4（默认 1.0）。**仅流体引擎**（蒸汽恒 1.0） |
| `setMixture(x)` | true | boolean | 混合比：只影响油耗（×实际混合比）与温度（×凸热因子）；稀=省油但更热、浓=费油但降温；**蒸汽引擎或无整合气道（拉稀权未解锁）拒绝返回 false**；越界钳制 |
| `getEffectiveMixture()` | false | number | 实际混合比 = 杆 × 自动富油（气压驱动，高空 > 杆值；自动拉稀校正反馈用）。**仅流体引擎**（蒸汽恒 1.0） |
| `getFuelEconomyFactor()` | false | number | P6 经济系数 0.8..1.0（**仅流体引擎**；无整合气道 / 停机 / 油门 0 → 1.0） |
| `hasAirDuct()` | false | boolean | P6 是否已装整合气道（解锁经济区 / 拉稀权 / 风门） |
| `getActiveFuel()` | false | table | P6 当前活动燃料：流体 `{type="fluid", fluid=<id>, optimalTemp=..}`；蒸汽 `{type="steam", optimalTemp=155}`（无混合比）；停机 `{type="none"}` |
| `getCooling()` | false | number | P6 风门 0..1（默认 1.0 = 全开；**蒸汽引擎恒 1.0**） |
| `setCooling(x)` | true | boolean | P6 风门 0..1：只缩放风道散热分量（冲压/气压/环境不动），只能降；**蒸汽引擎或无整合气道拒绝返回 false**；越界钳制 |
| `isWarmingUp()` | false | boolean | P6 蒸汽锅炉暖机中（点火燃烧但 T<100°C，只烧不发电） |

**Goggle 显示布局**（戴 Create 护目镜悬停任意引擎模块方块，模块 tooltip 代理到 controller；蒸汽引擎不显示经济/风门/混合比行）：

```
发动机状态                       ← 标题行（Create 视为首行，其后有间距）
[Create 默认传动信息：转速/容量/应力]
状态：正常 / 即将过热 / 过热 / 暖机中
                                 ← 绿/金/红/金（暖机中仅蒸汽 T<100°C 点火时）
温度：XX.X°C                     ← 客户端趋势外推平滑值
过热停机！                      （仅流体过热锁定，红）
效率：25%                        ← 油门（AQUA）
经济：×0.88                      ← 仅流体：绿(<1 在带内)/灰(=1.0)；未装整合气道显示「未装整合气道」（金）
风门：100%                       ← 仅流体+整合气道：青(<1 关小保热)/灰
混合比：0.80                     ← 仅流体：杆值（AQUA）
高空实际：0.95                   （仅流体，|实际−杆|≥0.005 时，金）
Lua控制：已连接 / 未连接          ← 绿/深灰
```

**玩家操作要点**：
- 一个杆（油门）就够起飞：油门同时定应力与转速（定距桨）；转速不够带动机器时推油门；**油门 0 = 停机不烧油**。
- **流体引擎**：巡航省油 = 装整合气道 → 保持温度在该燃料 `optimal_temp`（缺省 155°C）附近（经济 ×0.8 省 20%）；太热 → 拉富/开风门，太冷（冲压过冷）→ 拉稀/关风门（lean of peak 双赢）；轻载 / 冷却富余时拉稀 `setMixture(0.6~1.0)`，冷却吃紧（高空/静态）时富油或降油门；高空不拉稀 = 白烧油（自动富油）。
- **蒸汽引擎**：简单稳定——点火后暖机（T 从环境爬到 100°C 约 4s，期间只烧不发电），T≥100°C 才出力，继续爬向 155°C 钉住；永不过热、高空无忧；无混合比/经济区/风门，无需整合气道；代价 = 应力低（3072/室）+ 无折扣 + 方块重（Sable 质量）。
- 过载 = 停烧（不白烧油）；过热锁存（流体）T≤160 才恢复；高频启停（油门 0↔1）会触发 Create flicker 惩罚。

### 9. 气压模型技术细节（wiki 用：空气模型来源）

引擎冷却（`pressureFactor`）与混合比自动富油（`autoRichness`）共用**同一套气压模型**，来源 = Sable 的维度大气曲线：

- **Sable 现成方法**：`DimensionPhysicsData.getAirPressure(Level, Vector3dc pos)` → `basePressure × BezierResourceFunction.evaluateFunction(pos.y())`（位置 + 维度感知；Sable 自己 `FloatingBlockData` 也用）。
- **本 mod 的 `SensorSystemAPI.evaluatePressure(y)`**：**镜像同一条公式**（锚点间三次 Hermite `f(t)=((c·t+q)·t+l)·t+v1` + basePressure）作用在**静态曲线快照**上——因为 Lua API（`getPressureFromAltitude`/`getAltitudeFromPressure`）必须 `mainThread=false`（电脑线程零 Level 访问），且引擎要**无门控 + 高度自变量**（`getPressureForEngine(y)`，静态方块/任意机床可用）。公式与回退链与 Sable 严格一致。
- **曲线**：维度数据包 `dimension_physics` 的锚点，分段三次 Hermite；主世界默认（basePressure=1.0，海平面 63）：(−38, 1.5)/(63, 1.0)/(263, 0.4493)/(280, 0.4198)/(320, 0)；Y=320 以上为 0（建筑高度上限无空气）。
- **快照刷新时机**：服务器启动（按主世界）+ 放置/加载 FMC 或 AIC（`onLoad`）；`/reload` 维度数据包后需重放/重载 FMC/AIC。多维度共存时取最后一次加载的维度曲线。
- **引擎侧用法**：`getPressureForEngine(engineAltitude())`——运动体高度 = `SableCompat.getSubLevelWorldPos().y`（冷却与混合比同源；客户端显示一律用服务端同步值，见踩坑记录 10）。

## 消耗模型

| 输入 | 规则 |
|---|---|
| 流体燃料（流体室） | `data/ccpe/engine_fuel/*.json` 定义（见下）；**controller 按 priority 统一选一种，全部运行流体室烧同一种（P6 修订：单燃料制，不混烧）**；每燃烧室 fuel.consumption mb/s，fuelDebt → drain 源罐 |
| 水（蒸汽室） | 固定 1mb/s/室，从模块邻居水源罐 drain（`minecraft:water`）；水不可用整类暂停（不烧） |
| 固体燃料（蒸汽室） | 物品 `burnTime`（原版熔炉值，`getBurnTime(RecipeType.SMELTING)`）→ 1 tick 烧 1 burnTick；从该室 6 邻居容器 `extractItem` 自动抽取；burnTicks 持久化在蒸汽室 BE（P2 已实现；**暂缓**，当前走流体燃料） |
| 流体燃料（蒸汽室） | `burn_ticks_per_bucket`（熔岩桶 20000tick → 1mb 烧 20 tick）；1 tick 烧 1 burnTick 等价熔炉速率（P2 已实现） |

引擎燃料表 JSON（P1 已实现，`EngineFuels` 服务端 `SimpleJsonResourceReloadListener`，`data/<namespace>/engine_fuel/*.json`）：

```json
{ "fluid": "minecraft:water", "consumption": 1.0, "heat": 1.0, "stress": 1.0, "priority": 0.0, "optimal_temp": 155.0 }
```

- `fluid`：流体 id；`consumption`：消耗速度（mb/s/室）；`heat`：发热倍率（流体引擎 Q_heat 用）；`stress`：产生应力倍率（容量 = 燃烧室数 × 4096 × stress）；`burn_ticks_per_bucket`：可选，蒸汽室流体燃料燃烧时长（>0 才可作蒸汽室燃料）；`priority`：可选，多燃料可用时的选择优先级（越大越优先）；`optimal_temp`：可选，**最佳工作温度 °C（P6，仅流体引擎经济区目标；缺省 155）**。
- 燃料表只加载在服务端（`AddReloadListenerEvent`），客户端只消费同步的 `Running` 状态 → 无需同步燃料表。

## controller tick 数据流（当前实现：P1~P6，Plan B）

> P6 已实现并测试通过：物理排斥放置拦截（踩坑 11/12）、全引擎单燃料制、经济区（仅流体）、整合气道门控 + 风门、蒸汽 Plan B（自调节 + 暖机）。**剩余**：过稀失火（后续阶段）、流体冷侧处理（待定）、进游戏调参（`ECO_MIN/δ/BOILER_T_OPT/optimal_temp` 各值）。

```
1. 枚举：scanModule —— 两类燃烧室（按 FACING 反面贴核心归属，去重）+ 整合气道数 D + 去重邻居（源罐范围）
2. 仲裁（P6 物理排斥兜底）：流体+蒸汽并存只让多数派工作（平局流体优先）；steamEngine = 是否蒸汽引擎
3. 门控：enabled && !overheated（滞回仅流体）→ heatAllowed；蒸汽永不过热（overheated 恒 false）
4. P5/P6 混合比（仅流体）：m_eff = 杆 × autoRichness(气压)；无整合气道钳 ≥1.0（拉稀权）；蒸汽 m_eff=1.0
5. 流体室评估：findFuel 按 priority 全引擎选一种 → runningFluid；消耗 ×efficiency × m_eff × eco(T, optimal_temp)（无气道 eco=1.0）
6. 蒸汽室评估：waterOk（水+燃料）→ 逐室 burnTick 消耗（×efficiency，无经济区）；runningSteam = 有储备且 efficiency>0 的室
7. running = 有燃烧室 && steamReady（蒸汽 T≥100°C 暖机门控）&& !过载 && !过热 && enabled && efficiency>0
8. speed = running ? 256×efficiency : 0（定距桨）；capacity = 室数×base×efficiency
9. 消耗（fuelDebt / fluidFuelDebt 累加器 ≥1mb 才 drain，零缓存）：水 1mb/s/室×efficiency；流体燃料 ×m_eff ×eco（仅流体）
10. 温度：
    流体：牛顿冷却（Q_heat − K_total×(T−T_amb)）/C_th；
         K_total = (K_CORE×len + K_AMB×运行室 + K_DUCT×D×风门) × ram(空速) × 气压因子；
         过热滞回 T≥200 硬停 / ≤160 解锁
    蒸汽：点火（runningSteam>0 && efficiency>0）→ 指数收敛钉 BOILER_T_OPT(155)；停火 → 牛顿冷却；
         永不过热、高度免疫（与气压/环境/冲压无关）
11. 缓存：lastEconomyFactor（仅流体）/ warmingUp / activeFuel；状态变化或温度差量 ≥1°C → sendData
    （活塞动画读父核心运行态；温度客户端趋势外推显示）
```

## 边界清单（实现时逐条守住）

- 消耗只在服务端；客户端只读同步状态
- 引用一律 WeakReference + `isRemoved()` 校验 + 失效重扫
- 只走 capability，绝不直接改罐/容器
- 储罐/容器在移动装置上 = 已知限制（v1 不支持）
- 燃烧室双面贴两个核心时按 `FACING.getOpposite()` 归一边，不双计
- 冷却按"运行中室数"计（停摆的室不产热、不占冷却）
- 多引擎抢同一储罐：drain 原子，无死锁；抽空瞬间按 fuelDebt 到达顺序，另一个下 tick 再试
- 非 controller 的 `getGeneratedSpeed()` 恒 0（否则整条网络出错）
- 无红石状态（POWERED 相关逻辑全部不引入）
- **P6 经济系数只乘消耗、绝不反哺 Q_heat**（防「追经济→温度变→系数变」自震荡）
- **P6 整合气道门控**：无气道 → 经济区不可达（eco 恒 1.0）+ 有效混合比钳 ≥1.0 + `setMixture`/`setCooling` 拒绝
- **P6 蒸汽 Plan B**：永不过热（`overheated` 恒 false）、无经济区（恒 1.0）、无混合比/风门/整合气道需求、温度与气压/环境/冲压无关（高度免疫）
- **油门 0 = 停机不烧油**（与 `setEnabled(false)` 等效）；蒸汽点火/暖机判定必须带 `efficiency > 0f`（踩坑 13：有储备 ≠ 在消耗）
- **P6 放置拦截只防交互路径**（`ChamberBlockItem#place`）；蓝图/命令直放不拦 → 靠 controller 多数派仲裁兜底（只让一种类型工作）

## 踩坑记录（实现期，已修复）

1. **Direction vs BlockPos 比较（踩了两次！）**：判定"燃烧室背面贴核心"时，`FACING.getOpposite()` 是 **Direction**，直接 `.equals(corePos)`（BlockPos）**永远 false** → 燃烧室永远检测不到。正确写法：`neighbor.relative(state.getValue(FACING).getOpposite()).equals(corePos)`（先算出背面坐标再比）。第一次修在 `countCombustionChambers`，P2 重写 `scanModule` 时**又复现**，导致流体室+蒸汽室一起"没反应"（表现：整个引擎不运行，活塞不动）——**凡涉及贴附方向判定的代码，写完立即自查这类比较**。
2. **注册期静态初始化急切 `.get()` NPE**：`PlacementHelpers.register(new ChamberAttachPlacementHelper(MyModBlocks.xxx.get()))` 在方块类 `<clinit>` 急切解引用 DeferredHolder → `NullPointerException: Trying to access unbound value` → **mod 加载失败**（连锁引发 Sodium "config not found" 崩溃，极其误导）。修复：谓词写成 lambda 惰性求值（`stack -> stack.is(MyModBlocks.xxx.get().asItem())`，EngineCoreBlock 的 PoleHelper 模式）。**方块类静态初始化里禁止急切 `.get()`**。
3. **蒸汽室流体燃料 50% 占空比缺陷**：初版只在"burnTicks 归零补燃料"时才累计 `steamFuelDebt` → 20 tick 攒 1mb → +20 burnTick 烧 20 tick → 停机 20 tick 再攒 → 只跑一半时间。修复：**每 tick 每室连续累计** `fluidFuelDebt += 1000/burn_ticks_per_bucket`（熔岩 0.05mb/t = 1mb/s），≥1mb 抽罐 → 稳态持续燃烧。消耗类累加器必须"运行期间每 tick 累计"，不能只在补货时累计。
4. **燃料表多条目时选择顺序**：`sortedByPriority()` 按 `priority` 降序、其次文件名升序——同为 0 时 water.json 与 lava.json 并存，流体室会优先熔岩（lava < water 字典序）。想指定优先顺序给对应条目设更高 `priority`。
5. **burnTick 制换算**：蒸汽室 1 tick 烧 1 burnTick（等价原版熔炉速率）；固体 1 物品 = 其 `burnTime`；流体 1mb = `burn_ticks_per_bucket/1000` tick（熔岩 20 tick/mb）。蒸汽室固体燃料**暂缓**（`tryPullSolidFuel` 保留未调用），当前只走流体燃料。
6. **停机温度定格（P3）**：散热项 `K_AMB×N` 绑定"**运行中**室数"——停机后 N=0、无风道时 K_total=0 → 温度冻结。修复：加 `K_CORE×length`（核心自身辐射，τ≈50s），停机仍缓慢降温。**冷却公式的"自然散热"项不能只挂在运行状态上**。
7. **追赶式 lerp → 平台台阶**：温度显示初版向同步目标 lerp（20%/tick），包之间收敛完就"平台期"等下一包 → 视觉"爬一段→停→再爬"。修复：**趋势外推**（最近两采样点斜率继续走 + ±10°C 外推带钳制）。对比 simulated velocity_sensor 是**每 tick `sendData()`（20Hz）发包、客户端零插值**——"看起来平滑"靠 20Hz 高频刷新，代价是 20 包/秒/方块；我们低频差量 + 客户端外推，同视觉、1/20 带宽（Create ServerSpeedProvider 同思路）。
8. **Sable 速度读取坑（复用现有结论）**：**不要裸读 `Sable.HELPER.getVelocity`**——内部 = ω×r + 裸读物理 handle，世界静止的机体上仍返回非零"幻影值"（约 −0.03 m/s，见 SensorSystemAPI 注释/FlightDataRecorder 诊断）。冷却模型用 `SableCompat.getWorldLinearVelocity(sub)`（Sable 每 tick pose 位置差分 ×20，静止严格 0）。
9. **`overheated` 漏同步（P4 状态行暴露）**：`overheated` 是服务端滞回锁存，但 P3 的 NBT 同步**从未写/读该字段**——客户端恒 false → Goggle 状态行 T≥200 仍显示「即将过热」（走 `temperature≥0.8×T_max` 分支），温度行也不变红、「过热停机」行不出现。修复：`write()` 加 `putBoolean("Overheated", ...)`、`read()` 加对应读取（与 Running 同级无条件读写，随存档持久化）。**新增客户端要显示的服务端布尔状态，务必同时加 write/read 两处，并进游戏验证两种状态**。
10. **客户端算高度 = 永远错误（P5 混合比显示暴露）**：Goggle「高空实际」初版在客户端调 `autoRichness()`（用 `engineAltitude()`）——Sable 子次元（运动体）上客户端拿不到真实世界高度（`SableCompat.getContainingSubLevel` 客户端返回 null → 回退 plot 局部坐标 Y≈64 → autoRichness≈1.0000x）→ 显示值恒等于混合比、不随飞行高度变（服务端其实在正确应用自动富油）。修复：服务端每 tick 把 `lastEffectiveMixture = mixture × autoRichness()` 同步进 NBT（`EffectiveMixture`），Goggle 直读同步值（与温度「服务端权威」同思路）；显示阈值 `|实际−杆| ≥ 0.005` 才显示（消除 2 位小数下无意义的相同值行）。**依赖真实高度的客户端显示，一律用服务端同步值，不要客户端自算**。
11. **`BlockPlaceContext.getClickedPos()` 点击不可替换方块时返回放置格，不是被点击方块**（物理排斥第一版失效根因）：点击引擎核心（不可替换）→ `replaceClicked=false` → `getClickedPos()` 返回 `relativePos`（= 被点击核心沿点击面偏移一格的放置格）。在 `getStateForPlacement` 里检查 `getBlockState(context.getClickedPos()).is(engine_core)` 检查的是**空气格** → 永远 false → 永不拦截。正确写法：贴附核心 = `context.getClickedPos().relative(context.getClickedFace().getOpposite())`（放置格沿点击面反方向一格 = 被点击的核心；可替换格点击场景下同样是燃烧室背面贴附的方块）。
12. **`InteractionResult.FAIL.consumesAction() == false`（1.21.1）——方块 `useItemOn` 返回 FAIL 拦不住放置**：`consumesAction()` 只对 SUCCESS/CONSUME/CONSUME_PARTIAL/SUCCESS_NO_ITEM_USED 为 true；FAIL 会从 `ServerPlayerGameMode.useItemOn` 落到 `stack.useOn()` → `BlockItem.place`，音效/提示照发但方块照样放。正确模式（照 create:factory_gauge `FactoryPanelBlockItem`）：**覆写 `BlockItem.place`**，检查在 `super.place` 之前——`place` 是 `useOn` 的终点、返回 FAIL 无后续回退 → 真正拦死 + DENY 音效 + `displayClientMessage` 状态条提示。
13. **蒸汽 `runningSteam` 计「有燃料储备（burnTicks>0）的室」→ 油门 0 时温度钉住（P6 暖机暴露）**：油门 0（efficiency=0）→ 蒸汽室 `fluidFuelDebt += 0`（不抽油）、`burnTicks -= 0`（储备永不消耗）→ `runningSteam` 恒 > 0 → 温度分支误判「锅炉点火中」持续收敛向 155°C ——「不转（running 有 efficiency>0 门控）、不耗油、不降温」三症状并存。修复：点火/暖机判定加 `efficiency > 0f`（油门 0 = 停机不烧油，与 setEnabled(false) 等效 → 降温）。**凡是「有储备 ≠ 在消耗」的状态量，挂到消耗/运行判定时必须加 efficiency 门控**。

## 实施阶段

| 阶段 | 文件 | 内容 | 风险 |
|---|---|---|---|
| P0 ✅ | `EngineCoreBlockEntity` / `EngineCoreBlock` | `IMultiBlockEntityContainer` + form/split + PoleHelper 延伸放置 + Uninitialized 存档重组 | 低（照抄 CDG） |
| P1 ✅ | 流体室 + 核心 tick | 燃料 datapack、罐 drain、fuelDebt、发电（4096/室、256rpm）+ 活塞动画 | 低 |
| P2 ✅ | 蒸汽室 | 水 + 流体燃料（熔岩，burnTick 制，**固体燃料暂缓**）+ 暂停规则 + 活塞动画 | 中（双输入） |
| P3 ✅ | 温度/冷却 | 牛顿冷却温度模型（过热硬停+滞回）、效率同缩出力/热量/消耗、冷却风道计数、核心自身散热 K_CORE、冲压冷却+Sable 气压/速度复用、Goggle 标题行+温度趋势外推显示 | 中（跨 Sable 集成） |
| P4 ✅ | Lua 外设 | `ccpe.engine` 外设（enabled/throttle 控制 + getTemperature/isOverheated/getFluidTanks 状态读，挂 controller，包裹任意核心节委托；**定距桨单杆**：转速 = 油门×256，默认 enabled=true、throttle=0 停机） | 中（已落地） |
| P5 ✅ | 混合比 | `setMixture(0.6~1.4)` 经济性/热管理杆（只影响油耗与温度）+ 自动富油（实际混合比 = 杆×气压系数，与冷却同曲线）+ 凸热曲线 + Goggle 混合比行 + `getEffectiveMixture`；过稀失火后续阶段 | 中（已落地，热曲线/气压系数待进游戏调） |
| P6 ✅ | 最佳工作温度 + 经济气路（只省油耗） | **P1/P2 回炉**（全引擎单燃料制 + 流体/蒸汽物理排斥放置拦截，踩坑 11/12/13）、`eco(T)` 经济区（仅流体：平底窗+sqrt、`optimal_temp`、拉稀权门控）、`integrated_air_duct` 替换 `cooling_duct`（散热+进气）、风门 `setCooling(0~1)`（只缩 K_DUCT 分量）、**蒸汽 Plan B**（闭式锅炉自调节 + 暖机门控 100°C，永不过热/高度免疫/无经济区无混合比）、Lua `getFuelEconomyFactor`/`hasAirDuct`/`getActiveFuel`/`getCooling`/`setCooling`/`isWarmingUp`、Goggle 经济/风门/暖机/未装气道行。**剩余**：过稀失火（后续阶段）、流体冷侧处理（待定）、进游戏调参（见节 10） | 低~中 |

### 10. 最佳工作温度与经济气路（P6，已实现并测试通过）

> 现实依据：内燃机存在**设计工作温度**（正常水温 ~90°C、油温 ~100–120°C）——太冷（燃烧不完全、机油黏稠、积碳）与太热（爆震、机油失效）都伤，节温器把水温锁住 = 效率峰值；航空 CHT/EGT 管理 = **peak 之富**（费油降温）/**peak 之贫**（省油更热）——与 P5 混合比同构；**过度冷却也是真问题**（cowl flap 关散热保温）。本阶段把「温度 → 油耗经济性」闭环补全。

**已确认决策（用户逐项选定）**：
- 收益 = **只省油耗**：消耗步乘 `eco(T)`，应力/转速/水完全不动（不碰定距桨自洽与 P3 定标）。**单燃料制下每 tick 只有一个活动燃料 → 只有一个系数，无加权问题**。
- 经济曲线 = **单点最优 + 平底容差窗**：`|T−T_opt| ≤ δ_flat` 恒 0.8；窗外**越接近掉得越快**（sqrt 形）；δ ≥ δ_outer → 1.0。
- **T_opt 来源**：流体引擎 = 每种燃料 datapack 字段 `optimal_temp`（缺省默认 155）；蒸汽引擎 = **固定 `BOILER_T_OPT`（锅炉设计温度，不随燃料变）**。
- **蒸汽引擎无混合比轴**：锁 1.0、自动富油关闭（真实：蒸汽机缸内无混合气，燃烧在锅炉；锅炉过量空气是「中位最优」另一套语义，P6 不做）。Plan B 后更彻底：温度自调节钉 `BOILER_T_OPT`，**无控温旋钮**（见下「蒸汽引擎」）。
- **进气 = 拉稀权（仅流体引擎）**：无整合气道 → 有效混合比钳 ≥1.0、不能拉稀省油、`setMixture` 拒绝；装了才开放 0.6~1.4（自动富油仍按物理生效）。
- **冷却强度 = 风门（仅流体引擎）**：`setCooling(0~1)` 默认 1.0 = 全开，**只缩放 `K_DUCT×D` 分量**（冲压/气压/环境不动），只能降（想更冷 = 多装风道）。蒸汽 Plan B 无风门（温度自调节，散热无对象）。
- **经济系数只乘消耗、绝不反哺 Q_heat**（防「追经济→温度变→系数变」自震荡）；无整合气道时系数恒 1.0（经济区不可达，**仅流体引擎**——蒸汽无经济区恒 1.0）。
- 旧 cooling_duct 处置：**P6 定案 = 直接替换**（开发期无迁移，见下）。

**模型**（controller tick 消耗步按燃料乘；T 用本 tick 计算后温度，无新持久化温度字段）：

```
eco_j(T)：δ = |T − T_opt_j|
  δ ≤ δ_flat         : 0.8
  δ ≥ δ_outer        : 1.0
  之间                : 0.8 + 0.2 × ((δ−δ_flat)/(δ_outer−δ_flat))^0.5   ← 出窗掉得快、远处趋平
fuelDebt_j ×= eco_j(T)（每燃料类型独立乘；水固定 1mb/s 不乘；Q_heat / 应力 / 转速 完全不动）
```

**门控表**（≥1 个整合气道解锁；未装 = P5 行为逐字节一致 = 旧存档兼容；流体/蒸汽物理排斥 → 每次只面对一种类型）：

| 能力 | 流体引擎（无→有整合气道） | 蒸汽引擎（无→有整合气道） |
|---|---|---|
| 混合比杆 | 锁 1.0、`setMixture` 拒绝 → 0.6~1.4（自动富油仍生效） | **不适用**：恒 1.0、无自动富油（无此轴） |
| 冷却强度 | 固定 1.0 → `setCooling(0~1)`，只缩 `K_DUCT` 分量 | **不适用**（Plan B 恒温自调节，散热无对象；`setCooling` 拒绝） |
| 经济系数 | 恒 1.0 → `eco(T, T_opt_当前燃料)` | **恒 1.0**（Plan B：无经济区） |
| 拉稀省油 | 不可 → 可，与温度经济区联动 | 无此轴 |
| 过稀失火（后续） | 只流体有进气路径触发 | 不触发 |

**初稿常量（待进游戏调）**：

| 常量 | 值 | 说明 |
|---|---|---|
| δ_flat / δ_outer | 10 / 40 °C | 平底容差窗 / 经济区半径（eco 曲线，仅流体） |
| ECO_MIN | 0.8 | 最优处最大折扣（省 20%） |
| optimal_temp 缺省 | 155 °C | **仅流体燃料**；旧燃料包缺字段默认值 |
| BOILER_T_OPT（蒸汽） | 155 °C | 蒸汽机固定设计点（锅炉设计温度，不随燃料变；Plan B = 点火钉此值） |
| STEAM_MIN_WORK_TEMP | 100 °C | 蒸汽最低工作温度（饱和蒸汽门槛，<100°C 只烧不发电暖机） |
| STEAM_WARMUP_RATE | 0.25 /s | 蒸汽升温收敛速率（τ=4s；20→100°C 约 4s，~20s 到设计点） |
| T_opt 设计锚点 | ~135–155 | **低于流体自然平衡 170** → 经济收益靠风门/拉稀挣来，且远离 160 预警线 |

**多燃料混烧（已删除，P6 修订）**：~~整机一个 T、各燃料各自 T_opt → 无法同时满足全部燃料经济区 → 燃料选择本身是经济决策；显示 = 消耗加权平均系数。~~ 改为**全引擎单燃料制**：controller 按 priority 统一选一种，所有流体室烧同一种 → 每 tick 只有一个活动 T_opt、一个系数，无加权显示问题。

**与混合比的闭环（流体引擎，航空 EGT 完整版）**：太热 → 开风门/拉富（拉富费油但降回经济区，折扣部分抵消）；太冷（高速冲压/多风道）→ 关风门/拉稀（又省油又升温，**双赢**，lean of peak）；低负载平衡温度低于 T_opt → 靠「拉稀升温 + 关风门保热」进区。三工具（油门/混合比/风门）双向控制，温度有惯量（C_th）→ 有手感的控制问题，Lua autopilot（PID 钉 T_opt）是自然的高级玩法。

**蒸汽引擎（P6 Plan B，已实现）**：**闭式锅炉自调节 + 暖机门控**——点火燃烧（有水+燃料）→ 温度指数收敛并钉在 `BOILER_T_OPT`（饱和温度，燃料热量用于产汽=功率而非升温；**与气压/环境/冲压无关 → 高度免疫**）；**T < `STEAM_MIN_WORK_TEMP`(100°C) 时只烧不发电（热机过程，Goggle「暖机中」/ Lua `isWarmingUp`），≥ 阈值才开始工作**；缺水 → 停烧（waterOk 门控防干烧）→ **永不过热**（过热滞回仅流体引擎）；停火 → 牛顿冷却降温。**无经济区**（系数恒 1.0）、无混合比、无风门、无需整合气道。真实依据：锅炉闭式承压、饱和温度=内部压力（与海拔无关）、<100°C 无蒸汽压力、有水自调节缺水才干烧。流体 = 复杂高上限（经济区/混合比/过热管理，4096/室）；蒸汽 = 简单稳定高空无忧（3072/室 + 无折扣 + Sable 质量重）。

**旧 cooling_duct 处置（P6 定案，已实现）**：**直接替换**为 `integrated_air_duct`，无迁移代码（开发期无存档包袱）。注册 id/物品/lang/创造标签/blockstate/双模型/物品模型/贴图/战利品表/Sable 物理属性全量换新；旧风道从游戏中消失，旧蓝图失效（可接受）。

**Goggle**：状态行（含蒸汽「暖机中」）+ 温度行 + 「经济 ×0.8~1.0」+「风门 XX%」+ 未装整合气道提示行——**经济/风门/混合比行仅流体引擎**（蒸汽无这些行）；**一律显示服务端同步值**（踩坑 10，客户端不重算 T_opt/系数）。

**Lua 新增（已实现）**：`getActiveFuel()`（当前选中燃料 + 其 T_opt；蒸汽引擎返回 `{type="steam", optimalTemp=155}`）、`getFuelEconomyFactor()`（经济系数，仅流体）、`setCooling(x)/getCooling()`（风门，蒸汽恒 1.0/拒绝）、`hasAirDuct()`（是否已装整合气道）、`isWarmingUp()`（蒸汽暖机中）；未解锁时 `setMixture` 返回 false；蒸汽引擎 `getMixture/setMixture` 恒 1.0/拒绝。

**边界**：停机/油门 0/过热锁存不消耗 → 系数无意义（显示 1.0 或不显示）；经济系数与 P5 凸曲线/自动富油**正交**（温度自变量 vs 混合比自变量）无冲突；过稀失火（后续）只可能在有进气路径触发。

**落地状态（P6 已全部实现并测试通过）**：P1/P2 回炉（全引擎单燃料制 + 物理排斥放置拦截）→ 消耗步 ×eco(T)（仅流体，无气道门控）→ 燃料 datapack `optimal_temp`（仅流体）→ `integrated_air_duct` 直接替换 `cooling_duct`（散热+进气门控）→ 风门 `setCooling(0~1)`（只缩 K_DUCT 分量，NBT 持久化）→ 蒸汽 Plan B（自调节 + 暖机门控）→ NBT 同步（SteamEngine/WarmingUp/AirDuct/EconomyFactor/CoolingStrength）→ Goggle 行 → Lua `getActiveFuel`/`getFuelEconomyFactor`/`setCooling·getCooling`/`hasAirDuct`/`isWarmingUp`。

## 参考来源

- `run/references/Create-Diesel-Generators-1.21.1 (1)/Create-Diesel-Generators-1.21.1/src/main/java/com/jesz/createdieselgenerators/content/diesel_engine/modular/`（ModularDieselEngineBlockEntity / Block / CTBehavior）
- `run/references/Create-mc1.21.1-dev/src/main/java/com/simibubi/create/api/connectivity/ConnectivityHandler.java`
- `run/references/Create-mc1.21.1-dev/src/main/java/com/simibubi/create/foundation/blockEntity/IMultiBlockEntityContainer.java`
- `run/references/Create-mc1.21.1-dev/src/main/java/com/simibubi/create/content/kinetics/steamEngine/SteamEngineBlockEntity.java`（WeakReference 引用缓存 + 模块上报核心模式）
- `run/references/Create-mc1.21.1-dev/.../content/logistics/factoryBoard/FactoryPanelBlockItem.java`（放置拒绝模式：覆写 `BlockItem.place` + DENY 音效 + 状态条提示，见踩坑 12）
