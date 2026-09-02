# 从动轮车轴（双胎位轴式）— 方案设计（存档）

> 状态：**设计存档，未开始实现**。先复读文末「参考来源」再动手。
> 需求口径（2026-08 与用户确认）：
> 1. **单方块双胎位（轴式）**：一个方块 = 一根横轴，左右两侧各一个轮胎槽位 + 各一套悬挂几何（渲染/射线/施力点 ×2，左右镜像）；
> 2. **完全从动（无 Create 动力）**：不接受任何旋转输入，轮子只随车身地面运动自由滚转（无 KineticBlockEntity / 轴 / 应力）；
> 3. **真实 Sable 悬挂物理**：弹簧支撑 / 地形检测 / 施力，与 offroad wheel_mount 行为一致；
> 4. **轮胎体系复用 offroad**（编译期抽 offroad jar 进 libs/，运行时 bundled 已带）。

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

## 与参考的差异（本设计）

| 维度 | offroad wheel_mount | 本设计（从动轮车轴） |
|---|---|---|
| 方块继承 | `HorizontalKineticBlock`（facing 单轮 + 背轴输入） | 普通 `Block` + `IBE`（**无动力**，删除 `hasShaftTowards`/`getRotationAxis`/应力） |
| 轮胎槽位 | 1 格（单侧） | **2 格（左右镜像，各侧一个）** |
| 渲染 | BER 单侧几何 | BER **两侧镜像**几何（tele/spring/轮 ×2，自转符号相反） |
| 物理 | 弹簧 + 驱动 + 刹车 + 侧滑（转速→推力） | 弹簧 + 阻尼 + 侧滑（**删驱动项**），刹车可选保留（红石驻车） |
| 作用点 | 1 个（`pos.relative(facing)` 轮心） | **2 个**（`pos.relative(facing)` 与 `pos.relative(opposite)` 轮心，独立三线射线/extension/施力） |
| 状态字段 | extension/angle/yaw 单组 | extension/angle **×2 组**（左右独立，镜像符号） |

## 准备清单（落到 ccpe 项目）

### 决策点 0：依赖（已定 = 复用 offroad 轮胎体系）
- 从 `libs/create-aeronautics-bundled-1.21.1-1.3.2.jar` 抽出
  `META-INF/jarjar/dev.ryanhcode.offroad.offroad-neoforge-1.21.1-1.3.2.jar` 放 `libs/`，
  `build.gradle` 加 `compileOnly files("libs/offroad-neoforge-1.21.1-1.3.2.jar")`（仿 simulated 的写法，transitive 无需）。
- 代码直接引用 `dev.ryanhcode.offroad.content.components.TireLike` + `index.OffroadDataComponents.TIRE`
  → offroad 全部轮胎 + Create 轮子（offroad 已批量挂 TIRE）直接可装，无需自建轮胎资产。
- 运行时依赖：offroad 已 jarJar 在 create-aeronautics-bundled 内，**无新增运行时依赖**。

### 1. 方块
- `block/<name>Block.java` extends `Block implements IBE<BE>`：不继承 kinetic；BlockState `HORIZONTAL_FACING` 表达**车轴方向**（双胎在 facing 与 opposite 两端）。
- 交互：两个侧面分别右键装/取对应侧轮胎（仿 `WheelMountBlock.useItemOn`，hit face = 该侧槽位）。
- 拆除：两胎分别掉落；`getShape` 静态。

### 2. BlockEntity（核心）
- extends 普通 `BlockEntity`（不必 `KineticBlockEntity`）+ 实现 `BlockEntitySubLevelActor`；客户端同步用项目既有 `getUpdatePacket` 模式（照 Monitor 系）。
- 左右两组状态：`extension/lastExtension`、`angle/lastAngle`、轮胎栈（2 格或 2 槽容器，仿 `WheelMountInventory` 过滤 `TIRE`）。
- NBT：两胎栈分别 `write/read` + 更新包。
- `createRenderBoundingBox` 按两侧最大 `radius + 1` 膨胀。
- **物理钩子（唯一新增基础设施）**：项目目前没有 `SableEventPlatform.onPhysicsTick` 挂钩 → 在 `CCPeripheralExtender` 构造里加一次
  `SableEventPlatform.INSTANCE.onPhysicsTick(<CommonEvents>::physicsTick)`（照 `Offroad.java:62`），事件里静态批处理施力（照 `OffroadCommonEvents.java:28-31` + `applyAllBatchedForces`）。

### 3. 物理
- `sable$physicsTick`：照抄结构，**删含 `kineticSpeed` 的驱动 fma**（从动不推车）；保留弹簧/阻尼/侧滑；刹车项可选（红石驻车）。
- 两侧各算：两个轮心（`pos.relative(facing)` 与 `pos.relative(facing.getOpposite())` 中心）分别三线射线、分别 extension、分别 `applyImpulseAtPoint`（两作用点 → 车体俯仰/滚转支撑，仿真车轴）。

### 4. 渲染
- `block/<name>Renderer.java` extends `BlockEntityRenderer<BE>`（**纯 BER**，无 SHAFT_HALF、不继承 kinetic 渲染器）。
- 左右两套 tele/spring/轮 partial：镜像可通过建模镜像模型，或渲染沿 axle `scale(-1,1,1)`；左右自转 `signMultiplier` 相反（照 offroad 符号处理）。
- 轮胎 partial / 物品渲染回退逻辑照抄；`getViewDistance()` 512。

### 5. 注册与资产
- 方块/BE：`block/MyModBlocks.java` / `MyModBlockEntities.java` 加条目（或按需走 RegistrateBlocks）。
- 渲染注册：客户端 `CCPeripheralExtenderClient` 的 `EntityRenderersEvent.RegisterRenderers`（仿 monitor 写法）。
- partial model：加进 `block/MyModPartialModels.java`。
- assets：blockstates（facing 4 变体）、`models/block/<name>/`（轴壳静态模型 + 左右 tele/spring/mount partial）、models/item、贴图、`zh_cn`/`en_us` lang、loot table。
- 可选后续：CC 外设（轮速/行程/刹车读数），参考 Simulated-CC-Compat / CreateAvionics 的 WheelMountPeripheral。

## 建议落地顺序（每步可进游戏验证）

1. 静态轴壳方块 + 双格轮胎槽 + 装/卸交互（不接物理）→ 验证两胎位装拆、NBT、渲染位置。
2. 接入 Sable `onPhysicsTick` 批处理 + 单侧悬挂支撑力（另一侧先关）→ 验证车能"坐"在轮子上起伏。
3. 双侧对称化（镜像几何/独立射线/两作用点）→ 验证不平地形车体俯仰/滚转自然。
4. 删驱动项调从动手感（滚动阻力/刹车可选）→ 验证被推着走时轮子纯滚动不打滑。

## 待确认问题

- [ ] 从动轮是否需要**驻车/刹车**（红石输入）？默认：可选保留 offroad 刹车项（不做则删净）。
- [ ] 是否需要 **CC 外设**（读轮速/行程/刹车）？（默认先不做，后续按需，参考 Simulated-CC-Compat `WheelMountPeripheral`）
- [ ] 是否要做悬挂强度滚轮 UI？（参考 `SuspensionStrengthValueBehaviour`；不做则常量）

## 参考来源

| 参考 | 位置 | 借鉴点 |
|---|---|---|
| WheelMountBlockEntity | `references/Simulated-Project-main/offroad/common/.../wheel_mount/WheelMountBlockEntity.java` | extension 双模（空中按转速 / 贴地从平移）、`computeMaxExtensionToTerrain` 三线射线、`sable$physicsTick` 弹簧+驱动+刹车、`queuedWheelMounts` 批处理 |
| WheelMountBlock / WheelMountInventory / WheelMountRenderer | 同目录 | 交互装/取、单格容器过滤 `TIRE`、BER 手摆 tele/spring + yaw/自转符号 |
| OffroadBlocks / OffroadItems / OffroadDataComponents / OffroadPartialModels | `.../index/` | 注册形态：物品挂 `TIRE`、partial 声明 |
| Offroad.java:62 + OffroadCommonEvents.java:28-31 | `.../events/` | `SableEventPlatform.INSTANCE.onPhysicsTick` 挂钩 + 批量施力（ccpe 目前缺此） |
| ServerSubLevel.prePhysicsTick | `api/sable-common-1.21.1-2.0.3-sources/.../ServerSubLevel.java:297-301` | actor 每 substep 被自动调 `sable$physicsTick` 的调度点 |
| MyBearingPlateBlockEntity | `src/main/java/com/zzy205/myfirstmod/block/` | 项目已有 `BlockEntitySubLevelActor` 接入先例 |
| WheelMountPeripheral | `references/Simulated-CC-Compat-master` / `CreateAvionics-main` | 集成 offroad wheel_mount 的 CC 外设范例（可选后续） |
