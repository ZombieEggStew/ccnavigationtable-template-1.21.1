# 引擎模块（aero_engine：engine_core + 燃烧室 + 冷却风道）— 方案设计

> 状态：**方案已定稿；P0 已实现并编译通过**（引擎核心排成一排 + 延伸放置 + 存档重组）；**P1 已实现并编译通过**（流体燃烧室烧水：JSON 燃料表、零缓存抽罐、发电 256rpm、Create STEAM 音效、活塞动画+对置/轴向交替相位）；**P2 已实现并编译通过**（蒸汽室：水 + 固体/流体燃料 burnTick 制自动消耗 + 活塞动画同款）；P3~P4 待做。
> 设计封闭（本会话内用户确认）：蒸汽室=水+燃料（无蒸汽流体）、冷却风道=过热约束、各燃烧室独立配方可混烧、严格零流体缓存、固体燃料从周围容器自动抽取、水走 drain 管线、**无红石控制，末段做 Lua 控制**。
> 实现前先读参考源码（见文末「参考来源」）。

## 需求

1. **引擎核心排成一排组成一个整体**：参考 CDG 模块化引擎（large_diesel_engine）——拿着引擎物品右键已有引擎，沿轴自动延伸放置（`PoleHelper`）；整条共享同一套运行状态。
2. **引擎核心没有流体缓存**：自动从周围流体储罐中抽取所需流体（零内部缓存，直接 capability drain）。
3. **燃烧室并入**：在核心周围放置燃烧室（蒸汽动力室 `steam_power_chamber` / 流体燃烧室 `fluid_combustion_chamber`）即并入引擎模块。
4. **出力**：有可用燃料即启动；**流体燃烧室每室贡献 4096 应力（×燃料 stress 倍率），蒸汽动力室每室 3072**；引擎核心计算总应力，转速 **256**。
5. **配方消耗**：按配方消耗流体（如 CDG 汽油：每个流体燃烧室 1mb/s）。
6. **蒸汽室**：不直接消耗蒸汽，而是消耗原料——**水**（原版水，1mb/s/室）+ **燃料**（固体/流体均可，燃烧时长按原版熔炉 `burnTime`）。
7. **冷却风道**：过热约束，不足则降效/停摆。
8. **无红石控制**；控制最终通过 CC:Tweaked Lua 外设（末段阶段）。

## 决策记录（用户确认）

| 决策点 | 结论 |
|---|---|
| 蒸汽来源 | 无"蒸汽流体"；蒸汽室吃原料：水 + 燃料 |
| 水 | 原版水（minecraft:water），每蒸汽室 1mb/s，**从周围储罐 drain**（与流体燃料同一管线） |
| 燃料效率 | 按原版熔炉 `burnTime`：固体物品 burnTime tick → 烧 burnTime/20 秒（煤=1600tick→80s）；流体按 datapack `burn_ticks_per_bucket`（熔岩桶 20000tick → 1000mB 烧 1000s） |
| 冷却风道 | 过热约束：C 个**运行中**燃烧室需 ≥ ceil(C/N) 个冷却风道（N 默认 4）；不足则容量 × min(1, D/D_req)，**< 0.5 停摆**（0 风道=直接停） |
| 燃料规则 | **各燃烧室按自己配方独立消耗，可混烧**：流体室吃流体燃料、蒸汽室吃水+燃料，互不干扰 |
| 流体缓存 | **严格零缓存**：所有消耗 = controller 每 tick 直接从源储罐 capability drain，无罐无液位显示 |
| 固体燃料输入 | 从周围容器**自动抽取**（蒸汽室 6 邻居的 `IItemHandler`，`extractItem`），零 UI；可加 tag `ccpe:engine_fuel_sources` 白名单（默认不启用） |
| 红石 | 不要；末段做 Lua 控制（见「Lua 控制」节） |

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
- **动画**：燃烧室 Visual 每帧直接读父核心"是否运行"字段（客户端 1 次方块查询/帧），不推送。**P1 已实现（流体室）**：`FluidCombustionChamberBlockEntity#getPistonOffset(partialTick)` —— 父引擎（FACING 反方向贴的核心，经 getControllerBE 解析整条 controller）运行时，活塞沿 FACING 方向 ±2/16 块正弦往复（`PISTON_STROKE=2/16`、`PISTON_PERIOD=8tick`），停止时回中间位；Visual/Renderer 用 partialTick 平滑插值。
- **性能现状（P1）**：燃烧室计数为每 tick 全量扫描 blockstate（length×6 次，短排无压力）；**脏标记缓存（neighborChanged 置脏 → 只重建一次列表）列为最后优化（P3）**，不提前做。
- **贴附虚影（P1 已实现）**：`ChamberAttachPlacementHelper`（`IPlacementHelper`，流体/蒸汽室共用）——手持燃烧室对准引擎核心时 catnip `PlacementClient` 自动渲染虚影（位置 = 核心点击面相邻格，FACING = 点击面，与放置一致）；右键放置走默认 BlockItem（`getStateForPlacement` 正好背贴核心），无需额外代码。
- **固体燃料计时器**：水在才走（熔炉"产物满停烧"语义，避免白烧煤）；归零时 controller 从该室 6 邻居容器 `extractItem(slot,1,simulate)` 找第一个 `burnTime>0` 的物品，真实抽取 1 个，`burnTicks = burnTime`。**（暂缓：用户要求先做流体燃料；`tryPullSolidFuel` 保留未调用，恢复时在 burnTicks≤0 时先于流体燃料尝试）**

### 4. 冷却过热（P3）

- D = 贴在**任意核心成员**上的 `cooling_duct` blockstate 计数（无 BE，纯 blockstate 判定）。
- C = **运行中**燃烧室数（停摆的室不产热、不占冷却）。
- `D_req = ceil(C/N)`（N 默认 4）；`coolingFactor = min(1, D/D_req)`；容量 × coolingFactor；**coolingFactor < 0.5 → 整机停摆**（消耗也停）。

### 5. Lua 控制（末段，设计草案）

- 无红石状态；`enabled()` 改为 Lua 可控（后续接入项目现有 CC 外设基建）。
- 外设草案（`ccpe.engine`，挂 controller）：
  - 状态读：`getStatus()` → running / speed / capacity / chamberCount / coolingFactor / fuelSources / consumptionRates
  - 控制：`setEnabled(bool)`、`setThrottle(0~1)`（可选）
  - 语义：Lua 是唯一控制入口（对应 CDG 的红石/模拟油门位，全部由 Lua 承担）
- 待末段阶段按项目外设模式（参考 `my_bearing` 的 CC 外设控制、`transmission_peripheral`、`short-range link`）细化。

## 消耗模型

| 输入 | 规则 |
|---|---|
| 流体燃料（流体室） | `data/ccpe/engine_fuel/*.json` 定义（见下）；每燃烧室 fuel.consumption mb/s，fuelDebt → drain 源罐 |
| 水（蒸汽室） | 固定 1mb/s/室，从模块邻居水源罐 drain（`minecraft:water`）；水不可用整类暂停（不烧） |
| 固体燃料（蒸汽室） | 物品 `burnTime`（原版熔炉值，`getBurnTime(RecipeType.SMELTING)`）→ 1 tick 烧 1 burnTick；从该室 6 邻居容器 `extractItem` 自动抽取；burnTicks 持久化在蒸汽室 BE（P2 已实现；**暂缓**，当前走流体燃料） |
| 流体燃料（蒸汽室） | `burn_ticks_per_bucket`（熔岩桶 20000tick → 1mb 烧 20 tick）；1 tick 烧 1 burnTick 等价熔炉速率（P2 已实现） |

引擎燃料表 JSON（P1 已实现，`EngineFuels` 服务端 `SimpleJsonResourceReloadListener`，`data/<namespace>/engine_fuel/*.json`）：

```json
{ "fluid": "minecraft:water", "consumption": 1.0, "heat": 1.0, "stress": 1.0, "priority": 0.0 }
```

- `fluid`：流体 id；`consumption`：消耗速度（mb/s/室）；`heat`：发热倍率（P3 用过热，P1 只解析不消费）；`stress`：产生应力倍率（容量 = 燃烧室数 × 4096 × stress）；`priority`：可选，多燃料可用时的选择优先级（越大越优先）。
- 燃料表只加载在服务端（`AddReloadListenerEvent`），客户端只消费同步的 `Running` 状态 → 无需同步燃料表。

## controller tick 数据流

```
1. 枚举（缓存+脏标记）：
   ├─ 燃烧室列表（按类型分：流体室/蒸汽室）
   ├─ 冷却风道数 D（核心成员邻居 cooling_duct blockstate 计数）
   └─ 可用流体源集合（capability 收集 + tag 过滤）
2. 逐室评估（配方优先序）：
   ├─ 流体室：燃料表第一个可用流体 → 运行
   └─ 蒸汽室：水可用 且 燃料可用（计时器>0 / 能抽到固体 / 流体燃料命中）→ 运行
3. C_active = 运行室数；D_req = ceil(C_active/N)；coolingFactor = min(1, D/D_req)
   ├─ coolingFactor < 0.5 → 过热停摆（消耗也停）
   └─ 否则 capacity = (流体室数 × 4096 × stress + 蒸汽室数 × 3072) × coolingFactor
4. speed = (C_active>0 && !过热停) ? 256 : 0
5. 消耗（每类输入一个 fuelDebt 累加器，≥1mb 才 drain）：
   ├─ 水       ：蒸汽室数 × 1mb/s
   ├─ 流体燃料X：烧X的室数 × rate_X
   └─ 固体燃料 ：各蒸汽室计时器 1tick/1tick 递减（水在才走）
6. isOverStressed → 跳过消耗（不白烧油，CDG 同款）
7. 状态变化 → sendData（燃烧室 Visual 读父核心驱动活塞动画）
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

## 踩坑记录（实现期，已修复）

1. **Direction vs BlockPos 比较（踩了两次！）**：判定"燃烧室背面贴核心"时，`FACING.getOpposite()` 是 **Direction**，直接 `.equals(corePos)`（BlockPos）**永远 false** → 燃烧室永远检测不到。正确写法：`neighbor.relative(state.getValue(FACING).getOpposite()).equals(corePos)`（先算出背面坐标再比）。第一次修在 `countCombustionChambers`，P2 重写 `scanModule` 时**又复现**，导致流体室+蒸汽室一起"没反应"（表现：整个引擎不运行，活塞不动）——**凡涉及贴附方向判定的代码，写完立即自查这类比较**。
2. **注册期静态初始化急切 `.get()` NPE**：`PlacementHelpers.register(new ChamberAttachPlacementHelper(MyModBlocks.xxx.get()))` 在方块类 `<clinit>` 急切解引用 DeferredHolder → `NullPointerException: Trying to access unbound value` → **mod 加载失败**（连锁引发 Sodium "config not found" 崩溃，极其误导）。修复：谓词写成 lambda 惰性求值（`stack -> stack.is(MyModBlocks.xxx.get().asItem())`，EngineCoreBlock 的 PoleHelper 模式）。**方块类静态初始化里禁止急切 `.get()`**。
3. **蒸汽室流体燃料 50% 占空比缺陷**：初版只在"burnTicks 归零补燃料"时才累计 `steamFuelDebt` → 20 tick 攒 1mb → +20 burnTick 烧 20 tick → 停机 20 tick 再攒 → 只跑一半时间。修复：**每 tick 每室连续累计** `fluidFuelDebt += 1000/burn_ticks_per_bucket`（熔岩 0.05mb/t = 1mb/s），≥1mb 抽罐 → 稳态持续燃烧。消耗类累加器必须"运行期间每 tick 累计"，不能只在补货时累计。
4. **燃料表多条目时选择顺序**：`sortedByPriority()` 按 `priority` 降序、其次文件名升序——同为 0 时 water.json 与 lava.json 并存，流体室会优先熔岩（lava < water 字典序）。想指定优先顺序给对应条目设更高 `priority`。
5. **burnTick 制换算**：蒸汽室 1 tick 烧 1 burnTick（等价原版熔炉速率）；固体 1 物品 = 其 `burnTime`；流体 1mb = `burn_ticks_per_bucket/1000` tick（熔岩 20 tick/mb）。蒸汽室固体燃料**暂缓**（`tryPullSolidFuel` 保留未调用），当前只走流体燃料。

## 实施阶段

| 阶段 | 文件 | 内容 | 风险 |
|---|---|---|---|
| P0 ✅ | `EngineCoreBlockEntity` / `EngineCoreBlock` | `IMultiBlockEntityContainer` + form/split + PoleHelper 延伸放置 + Uninitialized 存档重组 | 低（照抄 CDG） |
| P1 ✅ | 流体室 + 核心 tick | 燃料 datapack、罐 drain、fuelDebt、发电（4096/室、256rpm）+ 活塞动画 | 低 |
| P2 ✅ | 蒸汽室 | 水 + 流体燃料（熔岩，burnTick 制，**固体燃料暂缓**）+ 暂停规则 + 活塞动画 | 中（双输入） |
| P3 | 冷却/打磨 | 冷却计数与过热公式、Goggle（运行态/室数/燃料源/速率）、引擎声、缓存与脏标记优化 | 低 |
| P4 | Lua 外设 | `ccpe.engine` 外设（状态读 + 控制），按项目外设模式 | 中（末段） |

## 参考来源

- `references/Create-Diesel-Generators-1.21.1 (1)/Create-Diesel-Generators-1.21.1/src/main/java/com/jesz/createdieselgenerators/content/diesel_engine/modular/`（ModularDieselEngineBlockEntity / Block / CTBehavior）
- `references/Create-mc1.21.1-dev/src/main/java/com/simibubi/create/api/connectivity/ConnectivityHandler.java`
- `references/Create-mc1.21.1-dev/src/main/java/com/simibubi/create/foundation/blockEntity/IMultiBlockEntityContainer.java`
- `references/Create-mc1.21.1-dev/src/main/java/com/simibubi/create/content/kinetics/steamEngine/SteamEngineBlockEntity.java`（WeakReference 引用缓存 + 模块上报核心模式）
