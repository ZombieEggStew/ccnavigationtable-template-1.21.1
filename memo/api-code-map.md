# api/ 依赖源码代码地图（Codemap）

> 用途：快速定位 `api/` 下各依赖 mod（**Sable / Flywheel / Catnip** / Create / CC:Tweaked / JEI / Registrate / Ponder）的接口与公开源码，确认 API 签名、包路径与查找位置。
> 与 `code-map.md` 配套：**查外部依赖 API 用本文；查本 mod 自身 Java 源码用 `code-map.md`**。

## Agent 查阅规则

- `api/` 是**只读参考目录**，不要修改；需要改动的是 `src/main/java` 下的自家代码。
- 查**接口 / API 签名** → `api/`（干净源码，无实现噪音）；查**完整实现 / 设计模式** → `references/`（见 `minecraft-research` 技能索引）。
- **核心三件套（Sable Companion / Flywheel / Catnip）几乎渗透本 mod 所有物理与渲染代码，是项目根基**。涉及物理、子次元、动态方块渲染、Outliner 预览、GUI 控件、VoxelShape 旋转时，**最先查这三处**，再查 Create / CC / JEI。
- 本文路径均相对于仓库根目录。

## 总体结构

```text
api/
├── cc/                                CC:Tweaked 1.118.0（编译依赖，三件套外最常用）
│   ├── cc-tweaked-1.21.1-common-api-1.118.0/   dan200.computercraft.api.*（Lua/外设主体）
│   ├── cc-tweaked-1.21.1-core-api-1.118.0/     api/filesystem、api/lua、api/peripheral（核心拆分）
│   └── cc-tweaked-1.21.1-forge-api-1.118.0/    common-api 镜像 + api/peripheral（Forge/NeoForge 侧）
├── create/
│   ├── create-1.21.1-6.0.10-280-slim/          com.simibubi.create.*（只含 Create 本体，无 net/）
│   ├── flywheel-neoforge-api-1.21.1-1.0.6/     dev.engine_room.flywheel.*  ⭐ 核心三件套
│   ├── ponder-neoforge-1.0.82+mc1.21.1/        net.createmod.ponder.* + 内嵌 catnip  ⭐（catnip 在这里）
│   └── Registrate-MC1.21-1.3.0+67/             com.tterrag.registrate.*（Create 类间接引用）
├── jei/                                JEI 19.42.0.379（可选编译依赖）
│   ├── jei-1.21.1-common-api-19.42.0.379/      mezz.jei.api.*
│   └── jei-1.21.1-neoforge-api-19.42.0.379/    mezz.jei.api.neoforge.*
└── sable/
    ├── sable-companion-common-1.21.1-1.6.0/    dev.ryanhcode.sable.companion.*  ⭐ 核心三件套（接口+数学）
    └── sable-neoforge-1.21.1-2.0.3/            dev.ryanhcode.sable.*  ⭐ 核心三件套（实现+物理）
```

## ⭐ 核心三件套（项目根基，最常用）

> 这三份源码是本 mod 的「地基」：渲染链（Flywheel Visual + Catnip Outliner/SuperByteBuffer）、物理驱动（Sable RotaryConstraint）、GUI（Catnip AbstractSimiWidget）、命中检测（Sable plot 坐标回投）全部建立在其上。**遇到渲染 / 物理 / 子次元 / 预览 / 控件问题，先查这三处。**

### 1. Sable Companion（子次元 + 物理，`dev.ryanhcode.sable`）

本 mod 的 my_bearing 物理驱动（RotaryConstraint）、Monitor / monitor_2 命中检测的 plot 坐标回投、传感器系统的子次元感知都依赖它。Sable 是**必装运行时依赖**（neoforge.mods.toml 声明），companion 被 Sable 运行时 jarJar 内嵌。

| 位置 | 路径 | 内容 |
|---|---|---|
| 接口 / 数学（companion） | `api/sable/sable-companion-common-1.21.1-1.6.0/dev/ryanhcode/sable/companion/` | `math/Pose3d`、`Pose3dc`（4×4 变换矩阵）、`math/JOMLConversion`、`math/BoundingBox3d*`、`ClientSubLevelAccess` / `SubLevelAccess`、`SableCompanion` |
| 实现 / 物理（neoforge） | `api/sable/sable-neoforge-1.21.1-2.0.3/dev/ryanhcode/sable/` | `api/physics/*`（`PhysicsPipeline`、`RigidBodyHandle`、`MassData`、`RotaryConstraintConfiguration`/`Handle`、force/collider/object 等）、`api/sublevel/*`（`SubLevelContainer`、`ServerSubLevelContainer`、`ticket/SubLevelLoadingTicketType`）、`sublevel/`（`SubLevel`、`ServerSubLevel`、`plot/LevelPlot`、`system/SubLevelPhysicsSystem`）、`api/block/`（`BlockEntitySubLevelActor`、`BlockSubLevelAssemblyListener`）、`api/`（`SubLevelHelper`、`SubLevelAssemblyHelper`）、`physics/config/dimension_physics/DimensionPhysicsData`、顶层 `Sable` |
| 完整项目源码 | `references/sable-main/` | 子次元机制全貌（案例参考） |

**项目内使用**（16 个文件）：`block/MyBearingBlockEntity`（RotaryConstraint 物理驱动）、`block/MyBearingBlock` / `MyBearingPlate*`（子次元方块组装）、`client/MonitorHitDetector`、`client/Monitor2HitDetector`、`client/ControlDeskGhostPreviewRenderer`、`client/MonitorGridOverlay`、`client/KnobInteractionHandler`（Pose3dc 射线回投）、`compat/sable/SableCompat`、`compat/cc/SensorSystemAPI` / `BodySensorRegistry` / `PeripheralExtenderAPI` 等。

### 2. Flywheel（实例化渲染引擎，`dev.engine_room.flywheel`）

Create 的 GPU 实例化渲染引擎。本 mod 所有动态方块实体（Monitor、controlDesk、传动外设、my_bearing）的 **Flywheel Visual** 都基于它；Flywheel 不可用时回退到原版 BER（`*Renderer` 类）。

| 位置 | 路径 | 内容 |
|---|---|---|
| API（全部源码） | `api/create/flywheel-neoforge-api-1.21.1-1.0.6/dev/engine_room/flywheel/` | 顶层 `Flywheel`；`api/instance/`（`Instance`、`Instancer`、`InstanceType`、`InstanceHandle`）、`api/visualization/`（`VisualizationContext`、`VisualizationManager`、`VisualizerRegistry`）、`api/visual/`、`lib/instance/`（`InstanceTypes`、`OrientedInstance`、`TransformedInstance`）、`lib/model/baked/`（`PartialModel`）、`lib/model/`（`Models`）、`lib/visual/`（`AbstractBlockEntityVisual`、`SimpleDynamicVisual`）、`lib/visualization/`（`SimpleBlockEntityVisualizer`）、`lib/math`、`lib/transform` |

**项目内使用**（11 个文件）：`block/MonitorVisual`、`block/ControlDeskVisual`、`block/TransmissionPeripheralVisual`、`block/MyBearingVisual`、`block/MyModPartialModels`（`PartialModel` 定义）、`block/*Renderer`（BER 回退 + `CachedBuffers.partial`）、`client/ControlDeskGhostPreviewRenderer`、`CCPeripheralExtenderClient`（`SimpleBlockEntityVisualizer` 注册）。

### 3. Catnip（Create 公共工具库，`net.createmod.catnip`）

Create 的渲染 / 数学 / GUI / Outliner 工具库。本 mod 的 Outliner 预览、SuperByteBuffer 渲染、Create 风格 GUI 控件、VoxelShaper 选择框旋转全部用它。

> **⚠️ 位置注意（重要）**：项目编译用的 catnip 是 **ponder jar 内嵌的 shaded 副本**（`build.gradle` 注释说明：standalone catnip 0.8.x 把 `Couple` 移到了 `utility` 包，与 Create API 引用的包不匹配，因此依赖 ponder 携带的匹配副本）。不要误以为 catnip 在 create slim 里（slim 没有 `net/`）。

| 位置 | 路径 | 内容 |
|---|---|---|
| API / 接口（ponder 内嵌版，**与编译包结构一致**） | `api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/catnip/` | `outliner/Outliner`（showAABB/showLine/showCluster/chaseAABB/keep/remove）、`render/SuperByteBuffer`、`render/CachedBuffers`、`math/VoxelShaper`（forDirectional/rotatedCopy）、`math/AngleHelper`、`theme/Color`、`data/Couple`、`data/Iterate`、`animation/AnimationTickHolder`、`gui/element/ScreenElement`、`gui/widget/AbstractSimiWidget`、`nbt`、`net`、`placement`、`registry` 等 |
| 完整独立源码（**0.8.54，包结构不同：`utility/*`**） | `references/Catnip-NeoForge-1.21.1-0.8.54-sources/net/createmod/catnip/` | 实现细节参考；注意 `utility/math`、`utility/outliner`、`utility/animation` 等子包与 ponder 内嵌版的顶层包名不同（如 `net.createmod.catnip.utility.math.VoxelShaper`） |
| 临时调研 | `.research/catnip-tmp/` | 个人调研的 catnip 源码 |

**项目内使用**（35 个文件，覆盖渲染、GUI、交互）：`client/MonitorGridOverlay`、`client/Monitor2GridOverlay`、`client/ControlDeskPlacementOverlay`、`client/DeskTopGridOverlay`、`block/MonitorBlock`、`block/ControlDeskBlock`、`block/MyBearingShapes`（VoxelShaper）、`block/TransmissionPeripheralVisual`、`foundation/gui/*`（AbstractSimiWidget 控件族）、`screen/AbstractMonitorScreen` 及全部 `*Screen`、`compat/create/CreateRedstoneCompat` 等。

## 其余 api/ 依赖

### Create slim（`com.simibubi.create`）

- 位置：`api/create/create-1.21.1-6.0.10-280-slim/com/simibubi/create/`（slim 只含 `com.simibubi.create.*`，**不含 `net/`（catnip）**）
- 包地图：`content/kinetics/base/`（`KineticBlockEntity`、`IRotate`、`DirectionalKineticBlock`、`RotatedPillarKineticBlock`、`AbstractEncasedShaftBlock`、`KineticBlockEntityRenderer`、`KineticBlockEntityVisual`）、`content/kinetics/transmission/`（`SplitShaftBlockEntity`、`SequencerInstructions` 等）、`content/redstone/link/`（`RedstoneLinkNetworkHandler`、`IRedstoneLinkable`）、`content/equipment/wrench/IWrenchable`、`content/contraptions/`（`SeatEntity`、`AssemblyException`、`IDisplayAssemblyExceptions`）、`content/logistics/packagerLink/WiFiParticle`、`foundation/block/IBE`、`foundation/blockEntity/renderer/SafeBlockEntityRenderer`、`foundation/gui/`（`AllGuiTextures`、`AllIcons`、`widget/IconButton`）、`foundation/utility/CreateLang`、`api/schematic/nbt/PartialSafeNBT`、顶层 `AllBlocks` / `AllItems` / `AllPartialModels` / `AllSoundEvents` / `Create`
- 项目内使用：`block/TransmissionPeripheral*`、`block/ControlDeskBlock`（IWrenchable/IBE）、`block/MyBearing*`、`compat/create/CreateRedstoneCompat`、`foundation/gui/MyIcons` 等

### Registrate（`com.tterrag.registrate`）

- 位置：`api/create/Registrate-MC1.21-1.3.0+67/com/tterrag/registrate/`（`builders`、`providers`、`util`）
- Create 部分类引用；本项目未直接 import（只在 Create API 签名中出现）。

### Ponder（`net.createmod.ponder`）

- 位置：`api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/ponder/`（另含内嵌 catnip，见上）
- 本项目未直接 import ponder API，编译依赖它主要是为了拿到匹配的 catnip shaded 副本。

### CC:Tweaked（`dan200.computercraft`）

- 位置：`api/cc/` 三个 jar 并列：`common-api`（`dan200.computercraft.api.*` 主体 + `impl`）、`core-api`（`api/filesystem`、`api/lua`、`api/peripheral`）、`forge-api`（common-api 镜像 + `api/peripheral`）
- 项目实际用到的包：`api/lua`（`LuaFunction`、`MethodResult`、`ILuaAPI`、`IComputerSystem`、`LuaTable`、`ObjectLuaTable`、`LuaException`）、`api/peripheral`（`IPeripheral`、`PeripheralCapability`）、顶层 `ComputerCraftAPI`
- 项目内使用：`compat/cc/` 下全部外设与 Lua API 文件（`*Peripheral`、`*ModuleHandle`、`*API`、`CCPeripheralCapabilities` 等），以及 `block/` 下各 BlockEntity 的 CC 集成

### JEI（`mezz.jei`）

- 位置：`api/jei/jei-1.21.1-common-api-19.42.0.379/`（`mezz/jei/api/*`：`gui`、`ingredients`、`recipe`、`registration`、`runtime` 等）+ `jei-1.21.1-neoforge-api-19.42.0.379/`（`api/neoforge`）
- 项目实际用到：`IModPlugin`、`JeiPlugin`、`registration/IGuiHandlerRegistration`、`gui/handlers/IGhostIngredientHandler`、`ingredients/ITypedIngredient`
- 项目内使用：`compat/jei/AddonJEIPlugin.java`

## 查找步骤

1. **按 import 前缀定位目录**（见下方对照表），不确定时先读 `build.gradle` 的 `dependencies` 块确认版本与坐标。
2. 用 `grep` / `glob` **限定到 `api/` 对应目录**搜索，不要扫全工作区。
3. 接口签名在 `api/` 找不到或需要行为细节时，再进 `references/` 对应完整项目（如 `references/Create-mc1.21.1-dev/`、`references/sable-main/`、`references/CC-Tweaked-mc-1.21.x/`、`references/Catnip-NeoForge-.../`）。

| Import 前缀 | api/ 位置 | 备注 |
|---|---|---|
| `dev.ryanhcode.sable.companion` | `api/sable/sable-companion-common-1.21.1-1.6.0/` | 接口/数学；实现见 sable-neoforge |
| `dev.ryanhcode.sable`（其余） | `api/sable/sable-neoforge-1.21.1-2.0.3/` | 物理/子次元实现 |
| `dev.engine_room.flywheel` | `api/create/flywheel-neoforge-api-1.21.1-1.0.6/` | api.* 接口 + lib.* 现成实现 |
| `net.createmod.catnip` | `api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/catnip/` | **不在 create slim 里**；完整实现见 `references/Catnip-NeoForge-1.21.1-0.8.54-sources/`（0.8.54 为 `utility/*` 包结构） |
| `com.simibubi.create` | `api/create/create-1.21.1-6.0.10-280-slim/` | 只含 Create 本体 |
| `net.createmod.ponder` | `api/create/ponder-neoforge-1.0.82+mc1.21.1/` | 含内嵌 catnip |
| `com.tterrag.registrate` | `api/create/Registrate-MC1.21-1.3.0+67/` | 间接引用 |
| `dan200.computercraft` | `api/cc/cc-tweaked-1.21.1-common-api-1.118.0/` | core/forge-api 并列 |
| `mezz.jei` | `api/jei/jei-1.21.1-common-api-19.42.0.379/` | neoforge-api 并列 |
| `net.minecraft.*`（本体） | — | `.research/mc-src/` |

## 注意事项

- `api/`、`references/`、`.research/`、`libs/` 均为只读参考，不要修改。
- **create slim 不含 catnip**；catnip 的 API 源码在 ponder jar（`api/create/ponder-neoforge-.../net/createmod/catnip/`）。
- `references/Catnip-NeoForge-1.21.1-0.8.54-sources/` 是**独立新版（0.8.54）**，包结构与 ponder 内嵌版不同（`net.createmod.catnip.utility.*` vs 顶层包）；查实现时注意类名相同、包路径不同。
- Sable 拆两半：`sable-companion-common`（接口/数学，编译依赖）与 `sable-neoforge`（实现/物理，**必装运行时依赖**）；运行时 companion 被 sable-neoforge jarJar 内嵌。
- Flywheel 拆两层：`api.*`（接口：Instance/Instancer/VisualizationContext…）与 `lib.*`（现成实现：OrientedInstance/TransformedInstance/PartialModel/AbstractBlockEntityVisual…）。
- `api/` 只含 API 表面，行为细节、边界情况要看 `references/` 中的完整实现。
- 参考外部 mod 源码解决问题时，按 `AGENTS.md` 要求记录参考来源。
