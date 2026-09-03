# 从动轮悬架（trailing_wheel）— 设计与实现

> 状态：**单轮版已落地（2026-08）**：方块 `ccpe:trailing_wheel`（单轮无动力、完全从动、模型直接复用 offroad wheel_mount 资产），
> `./gradlew.bat classes` 通过；**待进游戏验证**（见文末「进游戏验证清单」）。
> 双轮轴式（两侧各一套悬挂）为后续扩展，本文件同时保留其设计。
> 实现前先复读文末「参考来源」。

## 需求口径（2026-08 与用户确认）

1. **先做单轮版**：单轮悬架、无 Create 动力输入、完全从动（轮子被车身推着滚，不推车）；
2. **模型直接用 offroad 的**：不拷资产，blockstate / partial / item 模型全部跨 namespace 引用 `offroad:block/wheel_mount/...`；
3. 轮胎体系**复用 offroad**（编译期抽 offroad jar 进 libs/，运行时 bundled 已带）；
4. 真实 Sable 悬挂物理（弹簧支撑 / 地形检测 / 施力），照 offroad WheelMount 移植并**删驱动项**；
5. 首版极简：无红石转向 / 无驻车刹车 / 无悬挂强度滚轮 UI（刚度常量 10）；
6. 轮胎槽 = 单槽（照 offroad 原版），朝向面/底面右键装卸；
7. **双轮轴式（后续）**：单方块 = 一根横轴，左右各一个轮胎槽位 + 各一套悬挂几何（渲染/射线/施力点 ×2，左右镜像）。

## 参考实现机制速览（offroad wheel_mount）

参考源码：`references/Simulated-Project-main/offroad/common/.../content/blocks/wheel_mount/`（老版本 `Simulated-Project-main-old` 同款机制，两版均含转向/悬挂强度）。

### 1. 车轮装配 = 数据组件 + 单格物品栏 + 朝向面交互
- 轮胎不是专属物品：任何物品带 `TIRE` DataComponent（值 = `TireLike` record：`radius / rotation / offset / model / minimumFriction`）即可装。
  - `OffroadItems` 自研轮胎物品挂组件（`TIRE, TireLike.SMALL_TIRE/...`）；
  - `OffroadCommonEvents.modifyDefaultComponents` 给 Create 飞轮/粉碎轮/水车/机械压路机**批量补挂** `TIRE` → 皆可当轮子。
- BE 持 1 格 `WheelMountInventory`（`canInsertItem` 只放行带 `TIRE` 的），NBT `CurrentStack` + 客户端同步。
- `WheelMountBlock.useItemOn`：只在**朝向面/底面**（`hitDirection == FACING || DOWN`）右键 → `switchStacks` 对换手中轮胎与槽位（音效反馈）；拆除时轮胎掉 facing 前方。
- `createRenderBoundingBox` 按 `radius + 1` 膨胀（防大胎被剔除）。

### 2. 悬挂渲染 = 一个 extension 数值驱动整条几何链（纯 BER，无 Flywheel Visual）
- **extension（行程，-0.45..0.65）**：客户端 `tick()` 里 `computeMaxExtension(radius)`——轮胎横向 3 偏移点向下 5 格射线找地（`ClipContext` 忽略自身 sub-level），`距地 - radius` 后 clamp；悬空 `liftedUp` 同源；无胎缓动回 `NO_WHEEL_EXTENSION=0.5`。
- **渲染**（`WheelMountRenderer.renderSafe`）：
  - 底盘 + Create `SHAFT_HALF`（kinetic 旋转件）+ `FilteringRenderer`（悬挂强度滚轮 UI）；
  - 摆臂 = `tele_outer/tele_inner/mount` + `spring_upper/middle/lower` 5 个 partial model 手摆：按 extension `atan2` 求 `teleAngle/springAngle`、按两点距伸缩，弹簧中段 Y `scale`；
  - 轮子：yaw（红石转向）→ 自转，转角 `signMultiplier` 含镜像符号修正；轮胎 = `TireLike.model` partial，否则退化物品渲染（`TireLike.rotation/offset` 生效）。
- assets：`wheel_mount/` 10 json + `wheelsuspension.bbmodel` + `suspension_0/1` 贴图 + blockstate（facing 4 向 y 旋转）。

### 3. 物理 = Sable sub-level 弹簧支撑（服务端权威，前提：方块在物理体 plot 内）
- BE 实现 `BlockEntitySubLevelActor`；Sable 每个 physics substep 前（`ServerSubLevel.prePhysicsTick`）自动调 `sable$physicsTick(subLevel, handle, timeStep)`。
- `sable$physicsTick`：质量法向缩放 → 三线射线找地（`computeMaxExtensionToTerrain`，命中 sub-level 法线投影回本 plot）→ 弹簧力+阻尼 → **驱动项（`getSpeed()`→沿 normalD 推力）+ 刹车（红石）+ 侧滑摩擦 + 路面摩擦** → `ForceTotal.applyImpulseAtPoint`（攒冲量）。
- **批处理**：BE 加入静态 `queuedWheelMounts`；`Offroad.java` 用 `SableEventPlatform.INSTANCE.onPhysicsTick(...)` 注册 `OffroadCommonEvents::physicsTick` → 每物理 tick 后 `applyAllBatchedForces`（`RigidBodyHandle.applyForcesAndReset`）。
- 转向：facing CW/CCW 红石差速 → `computeYaw`；悬挂强度 `SuspensionStrengthValueBehaviour`（5~180 滚轮）。

## 已实现（单轮版，ccpe）

| 文件 | 说明 |
|---|---|
| `block/TrailingWheelBlock.java` | 方块：`BaseEntityBlock` + `HORIZONTAL_FACING`（非 kinetic），放置 facing=点击面水平方向；朝向面/底面右键装卸轮胎（客户端预判 + 服务端 `switchStacks`，音效）；拆除掉胎。`getTicker` → `TrailingWheelBlockEntity::tick` |
| `block/TrailingWheelBlockEntity.java` | 核心：vanilla `BlockEntity` + `BlockEntitySubLevelActor` + `Clearable`。客户端 tick = extension 射线 + 从动滚动角（贴地按车身平移/周长，离地停转）；`sable$physicsTick` = offroad 移植（弹簧/阻尼/侧滑 + **删 `kineticSpeed` 驱动项**，滚动阻力保留 0.075 基础项）；静态 `queuedWheelMounts` + `onPhysicsTick` 批处理；NBT `CurrentStack`（saveAdditional/loadAdditional + update packet） |
| `block/TrailingWheelRenderer.java` | 纯 BER（`SafeBlockEntityRenderer`）：tele/spring/mount + 轮胎渲染照 offroad，**去掉** SHAFT_HALF / FilteringRenderer / 转向 yaw / diode；partial 跨 namespace `offroad:block/wheel_mount/...`；`getViewDistance` 512、`getRenderBoundingBox` 按 `radius+1` 膨胀 |
| `build.gradle` | `compileOnly files("libs/offroad-neoforge-1.21.1-1.3.2.jar")`（从 create-aeronautics-bundled 抽出） |
| `CCPeripheralExtender.java` | 构造器注册 `SableEventPlatform.INSTANCE.onPhysicsTick(TrailingWheelBlockEntity::onPhysicsTick)`（照 offroad `Offroad.java:62`） |
| `MyModBlocks` / `MyModBlockEntities` / `CCPeripheralExtenderClient` / `MyModCreativeModeTabs` | 注册 `trailing_wheel` 方块/BE/BER/创造标签 |
| `assets/ccpe/blockstates/trailing_wheel.json` | facing 4 向 → 模型 `offroad:block/wheel_mount/block`（y 旋转照 offroad wheel_mount.json） |
| `assets/ccpe/models/item/trailing_wheel.json` | parent `offroad:block/wheel_mount/item`（物品展示=offroad 全套） |
| `data/ccpe/loot_table/blocks/trailing_wheel.json` | 掉自己 |
| `assets/ccpe/lang/zh_cn.json` / `en_us.json` | `block.ccpe.trailing_wheel` = 从动轮悬架 / Trailing Wheel Suspension |

**关键设计决策**：
- 模型零拷贝：blockstate / item / partial 全部跨 namespace 引用 offroad 资产 → 依赖运行时 offroad（bundled 必带），无资产维护成本；
- 无 Create：不继承 `HorizontalKineticBlock`/`KineticBlockEntity`，无轴/应力/`getSpeed`；
- 从动：物理删除驱动项（`getSpeed` 相关 fma），仅保留弹簧+阻尼+侧滑+基础滚动阻力；客户端轮子贴地滚动角由车身平移推导，离地无动力自然停转；
- 批处理沿用 offroad（静态队列 + physics tick 事件统一 `applyForcesAndReset`），`CCPeripheralExtender` 注册一次。

## 双轮轴式（后续扩展设计）

| 维度 | 单轮版（已实现） | 双轮轴式（后续） |
|---|---|---|
| 方块继承 | `BaseEntityBlock` + `HORIZONTAL_FACING`（无动力） | 同左，facing=车轴方向 |
| 轮胎槽位 | 1 格（单侧） | **2 格（左右镜像，各侧一个）** |
| 渲染 | BER 单侧几何 | BER **两侧镜像**几何（tele/spring/轮 ×2，自转符号相反） |
| 物理作用点 | 1 个（`pos.relative(facing)` 轮心） | **2 个**（`pos.relative(facing)` 与 `pos.relative(facing.getOpposite())` 轮心，独立三线射线/extension/施力） |
| 状态字段 | extension/angle 单组 | extension/angle **×2 组**（左右独立，镜像符号） |

## 建议落地顺序（双轮版，每步可进游戏验证）

1. 静态轴壳方块 + 双格轮胎槽 + 装/卸交互（不接物理）→ 验证两胎位装拆、NBT、渲染位置。
2. 接入 Sable `onPhysicsTick` 批处理 + 单侧悬挂支撑力（另一侧先关）→ 验证车能"坐"在轮子上起伏。
3. 双侧对称化（镜像几何/独立射线/两作用点）→ 验证不平地形车体俯仰/滚转自然。
4. 调从动手感（滚动阻力/刹车可选）→ 验证被推着走时轮子纯滚动不打滑。

## 进游戏验证清单（单轮版）

1. `/give @p ccpe:trailing_wheel`（创造标签「电脑外设扩展」也有），手持轮胎（offroad 的 tire / Create 的 crushing wheel 等带 `offroad:TIRE` 的物品）右键**朝向面/底面** → 轮胎装上/取下，客户端渲染正确（无轮胎时悬架缩回）。
2. 把 `trailing_wheel` 装进车辆结构并装配成 Sable 物理体（同 INS/bearing 前提）→ 车体能被轮子撑住、随地形起伏；推车时轮子贴地滚动、离地停转。
3. 观察：模型朝向与 offroad wheel_mount 是否一致（facing 旋转）；大轮胎（monster tire radius=2）渲染盒是否被剔除。
4. 观察：无动力时是否真的不推车（车身前进完全靠其它驱动轮/外力）。

## 待确认问题

- [ ] 从动轮是否需要**驻车/刹车**（红石输入）？默认：不做（首版已删净）。
- [ ] 是否需要 **CC 外设**（读轮速/行程/刹车）？（默认先不做，后续按需，参考 Simulated-CC-Compat `WheelMountPeripheral`）
- [ ] 是否要做悬挂强度滚轮 UI？（参考 `SuspensionStrengthValueBehaviour`；不做则常量）

## 参考来源

| 参考 | 位置 | 借鉴点 |
|---|---|---|
| WheelMountBlockEntity | `references/Simulated-Project-main/offroad/common/.../wheel_mount/WheelMountBlockEntity.java` | extension 双模（空中按转速 / 贴地从平移）、`computeMaxExtensionToTerrain` 三线射线、`sable$physicsTick` 弹簧+驱动+刹车、`queuedWheelMounts` 批处理（本项目删驱动/刹车/转向） |
| WheelMountBlock / WheelMountInventory / WheelMountRenderer | 同目录 | 交互装/取、单格容器过滤 `TIRE`、BER 手摆 tele/spring + 自转符号（本项目删 SHAFT/Filtering/yaw/diode） |
| OffroadBlocks / OffroadItems / OffroadDataComponents / OffroadPartialModels | `.../index/` | 注册形态：物品挂 `TIRE`、partial 声明 |
| Offroad.java:62 + OffroadCommonEvents.java:28-31 | `.../events/` | `SableEventPlatform.INSTANCE.onPhysicsTick` 挂钩 + 批量施力（ccpe 的 `CCPeripheralExtender` 已照此注册） |
| ServerSubLevel.prePhysicsTick | `api/sable-common-1.21.1-2.0.3-sources/.../ServerSubLevel.java:297-301` | actor 每 substep 被自动调 `sable$physicsTick` 的调度点 |
| MyBearingPlateBlockEntity / InsBlockEntity | `src/main/java/com/zzy205/myfirstmod/block/` | 项目已有 `BlockEntitySubLevelActor` / vanilla BE + ticker 接入先例 |
| WheelMountPeripheral | `references/Simulated-CC-Compat-master` / `CreateAvionics-main` | 集成 offroad wheel_mount 的 CC 外设范例（可选后续） |
