# api/ 依赖源码代码地图（Codemap）

> 用途：快速定位 `api/` 下各依赖 mod（**Sable Companion / Flywheel / Catnip** / Create / CC:Tweaked / Ponder）的 Java 源码，确认 API 签名、包路径与查找位置。
> **api/ 现全部为可读 Java 源码**（`-sources` 目录，原 class 解压文件已替换）。
> 与 `code-map.md` 配套：**查外部依赖 API 用本文；查本 mod 自身 Java 源码用 `code-map.md`**。

## Agent 查阅规则

- `api/` 是**只读参考目录**，不要修改；需要改动的是 `src/main/java` 下的自家代码。
- 查**接口 / API 签名** → `api/` 对应 `-sources` 目录（源码干净可读）；查**完整实现 / 设计模式** → `references/`（见 `minecraft-research` 技能索引）。
- **核心三件套（Sable Companion / Flywheel / Catnip）几乎渗透本 mod 所有物理与渲染代码，是项目根基**。涉及物理、子次元、动态方块渲染、Outliner 预览、GUI 控件、VoxelShape 旋转时，**最先查这三处**，再查 Create / CC / Ponder。
- **`api/` 中目前没有 jei 与 Registrate 的源码**（可选/间接依赖，未提取）：需要它们的 API 细节时用 `web_search` 或去 maven 拉 sources jar，不要到 api/ 里找。
- 本文路径均相对于仓库根目录。

## 总体结构

```text
api/                                       全部为 Java 源码（-sources）
├── Catnip-NeoForge-1.21.1-0.8.54-sources/        ⭐ Catnip 独立源码（0.8.54，utility.* 布局）
├── cc-tweaked-1.21.1-common-api-1.118.0-sources/ CC:Tweaked common-api
├── cc-tweaked-1.21.1-core-api-1.118.0-sources/   CC:Tweaked core-api（Lua 注解 / 外设接口在这里）
├── cc-tweaked-1.21.1-forge-api-1.118.0-sources/  CC:Tweaked forge-api（common 镜像 + peripheral）
├── create-1.21.1-6.0.10-280-sources/             Create 完整源码（2017 个 java 文件，无 net/）
├── flywheel-neoforge-1.21.1-1.0.6-sources/       ⭐ Flywheel 源码（api/ lib/ backend/ impl/）
├── ponder-neoforge-1.0.82+mc1.21.1-sources/      Ponder + 内嵌 catnip（编译匹配版）
├── sable-common-1.21.1-2.0.3-sources/            ⭐ Sable 实现/物理（项目版本 2.0.3）
├── sable-common-1.21.1-2.0.5-sources/            Sable 2.0.5（更新版，同布局，参考用）
└── sable-companion-common-1.21.1-1.6.0-sources/  ⭐ Sable Companion 接口/数学
```

## ⭐ 核心三件套（项目根基，最常用）

> 这三份源码是本 mod 的「地基」：渲染链（Flywheel Visual + Catnip Outliner/SuperByteBuffer）、物理驱动（Sable RotaryConstraint）、GUI（Catnip AbstractSimiWidget）、命中检测（Sable plot 坐标回投）全部建立在其上。**遇到渲染 / 物理 / 子次元 / 预览 / 控件问题，先查这三处。**

### 1. Sable Companion（子次元 + 物理，`dev.ryanhcode.sable`）

本 mod 的 my_bearing 物理驱动（RotaryConstraint）、Monitor / monitor_2 命中检测的 plot 坐标回投、传感器系统的子次元感知都依赖它。Sable 是**必装运行时依赖**（neoforge.mods.toml 声明），companion 被 Sable 运行时 jarJar 内嵌。

| 位置 | 路径 | 内容 |
|---|---|---|
| 接口 / 数学（companion） | `api/sable-companion-common-1.21.1-1.6.0-sources/dev/ryanhcode/sable/companion/` | `math/Pose3d.java`、`math/Pose3dc.java`（4×4 变换矩阵）、`math/JOMLConversion.java`、`math/BoundingBox3d*.java`、`ClientSubLevelAccess.java` / `SubLevelAccess.java`、`SableCompanion.java`、`impl/` |
| 实现 / 物理（sable-common） | `api/sable-common-1.21.1-2.0.3-sources/dev/ryanhcode/sable/` | `api/physics/constraint/`（`RotaryConstraintConfiguration.java`、`RotaryConstraintHandle.java`）、`api/physics/handle/RigidBodyHandle.java`、`api/physics/mass/MassData.java`、`api/physics/PhysicsPipeline.java`、`api/sublevel/`（`SubLevelContainer`、`ServerSubLevelContainer`）、`api/sublevel/ticket/SubLevelLoadingTicketType.java`、`sublevel/`（`SubLevel`、`ServerSubLevel`、`ClientSubLevel`、`plot/LevelPlot`、`system/SubLevelPhysicsSystem`）、`api/block/`（`BlockEntitySubLevelActor`、`BlockSubLevelAssemblyListener`）、`api/`（`SubLevelHelper`、`SubLevelAssemblyHelper`）、`physics/config/dimension_physics/DimensionPhysicsData.java`、顶层 `Sable.java` |
| 完整项目源码 | `references/sable-main/` | 子次元机制全貌（案例参考） |

> **版本**：项目 `gradle.properties` 的 `sable_version=2.0.3` → 签名查询用 **2.0.3-sources**；`sable-common-1.21.1-2.0.5-sources/` 是同布局更新版，升级/对比时参考。

**项目内使用**（16 个文件）：`block/MyBearingBlockEntity`（RotaryConstraint 物理驱动）、`block/MyBearingBlock` / `MyBearingPlate*`（子次元方块组装）、`client/MonitorHitDetector`、`client/Monitor2HitDetector`、`client/ControlDeskGhostPreviewRenderer`、`client/MonitorGridOverlay`、`client/KnobInteractionHandler`（Pose3dc 射线回投）、`compat/sable/SableCompat`、`compat/cc/SensorSystemAPI` / `BodySensorRegistry` / `PeripheralExtenderAPI` 等。

### 2. Flywheel（实例化渲染引擎，`dev.engine_room.flywheel`）

Create 的 GPU 实例化渲染引擎。本 mod 所有动态方块实体（Monitor、controlDesk、传动外设、my_bearing）的 **Flywheel Visual** 都基于它；Flywheel 不可用时回退到原版 BER（`*Renderer` 类）。

| 位置 | 路径 | 内容 |
|---|---|---|
| 全部源码 | `api/flywheel-neoforge-1.21.1-1.0.6-sources/dev/engine_room/flywheel/` | 顶层 `Flywheel.java`；`api/instance/`（`Instance`、`Instancer`、`InstanceType`、`InstanceHandle`）、`api/visualization/`（`VisualizationContext`、`VisualizationManager`、`VisualizerRegistry`）、`api/visual/`、`api/event/`、`api/material/`、`lib/instance/`（`InstanceTypes`、`OrientedInstance`、`TransformedInstance`）、`lib/model/baked/`（`PartialModel`）、`lib/model/`（`Models`）、`lib/visual/`（`AbstractBlockEntityVisual`、`SimpleDynamicVisual`）、`lib/visualization/`（`SimpleBlockEntityVisualizer`）、`lib/math`、`lib/transform`、`backend/`、`impl/`（源码 jar 比原 api jar 多出 backend/impl 实现） |

**项目内使用**（11 个文件）：`block/MonitorVisual`、`block/ControlDeskVisual`、`block/TransmissionPeripheralVisual`、`block/MyBearingVisual`、`block/MyModPartialModels`（`PartialModel` 定义）、`block/*Renderer`（BER 回退 + `CachedBuffers.partial`）、`client/ControlDeskGhostPreviewRenderer`、`CCPeripheralExtenderClient`（`SimpleBlockEntityVisualizer` 注册）。

### 3. Catnip（Create 公共工具库，`net.createmod.catnip`）

Create 的渲染 / 数学 / GUI / Outliner 工具库。本 mod 的 Outliner 预览、SuperByteBuffer 渲染、Create 风格 GUI 控件、VoxelShaper 选择框旋转全部用它。

> **⚠️ 位置注意（重要）**：项目编译用的 catnip 是 **ponder jar 内嵌的 shaded 副本**（`build.gradle` 注释说明：standalone catnip 0.8.x 把 `Couple` 移到了 `utility` 包，与 Create API 引用的包不匹配，因此依赖 ponder 携带的匹配副本）。**api/ 里有两份 catnip，包结构不同**：
> - **编译匹配版**（包名与项目 import 一致）：ponder sources 内 → 查签名用这份
> - **独立新版 0.8.54**（`utility.*` 布局）：完整独立源码 → 查实现细节用这份，注意类路径不同

| 位置 | 路径 | 内容 |
|---|---|---|
| 编译匹配版（ponder 内嵌，**与项目 import 包结构一致**） | `api/ponder-neoforge-1.0.82+mc1.21.1-sources/net/createmod/catnip/` | `outliner/Outliner.java`（showAABB/showLine/showCluster/chaseAABB/keep/remove）、`math/VoxelShaper.java`（forDirectional/rotatedCopy）、`math/AngleHelper.java`、`render/SuperByteBuffer.java`、`render/CachedBuffers.java`、`theme/Color.java`、`data/Couple.java`、`data/Iterate.java`、`animation/AnimationTickHolder.java`、`gui/element/ScreenElement.java`、`gui/widget/AbstractSimiWidget.java`、`nbt`、`net`、`placement`、`registry` 等 |
| 独立新版 0.8.54（**`utility.*` 布局**） | `api/Catnip-NeoForge-1.21.1-0.8.54-sources/net/createmod/catnip/` | `utility/outliner/Outliner.java`、**`utility/VoxelShaper.java`（直接在 utility 下）**、`utility/math/AngleHelper.java`、`utility/AnimationTickHolder.java`（utility 下直接）、`utility/theme/Color.java`、`utility/Couple.java`、`render/SuperByteBuffer.java`、`render/CachedBuffers.java`、`gui/widget/AbstractSimiWidget.java`、`codecs`、`config`、`mixin`、`net`、`platform` 等 |

> 同一份 0.8.54 还保留在 `references/Catnip-NeoForge-1.21.1-0.8.54-sources/`（历史位置），内容相同。

**项目内使用**（35 个文件，覆盖渲染、GUI、交互）：`client/MonitorGridOverlay`、`client/Monitor2GridOverlay`、`client/ControlDeskPlacementOverlay`、`client/DeskTopGridOverlay`、`block/MonitorBlock`、`block/ControlDeskBlock`、`block/MyBearingShapes`（VoxelShaper）、`block/TransmissionPeripheralVisual`、`foundation/gui/*`（AbstractSimiWidget 控件族）、`screen/AbstractMonitorScreen` 及全部 `*Screen`、`compat/create/CreateRedstoneCompat` 等。

## 其余 api/ 依赖

### Create（`com.simibubi.create`）

- 位置：`api/create-1.21.1-6.0.10-280-sources/com/simibubi/create/`（**完整源码**，2017 个 java 文件，含 assets/data；**不含 `net/`（catnip）**）
- 包地图：`content/kinetics/base/`（`KineticBlockEntity`、`IRotate`、`DirectionalKineticBlock`、`RotatedPillarKineticBlock`、`AbstractEncasedShaftBlock`、`KineticBlockEntityRenderer`、`KineticBlockEntityVisual`）、`content/kinetics/transmission/`（`SplitShaftBlockEntity`、`SequencerInstructions` 等）、`content/redstone/link/`（`RedstoneLinkNetworkHandler`、`IRedstoneLinkable`）、`content/equipment/wrench/IWrenchable`、`content/contraptions/`（`SeatEntity`、`AssemblyException`、`IDisplayAssemblyExceptions`）、`content/logistics/packagerLink/WiFiParticle`、`foundation/block/IBE`、`foundation/blockEntity/renderer/SafeBlockEntityRenderer`、`foundation/gui/`（`AllGuiTextures`、`AllIcons`、`widget/IconButton`）、`foundation/utility/CreateLang`、`api/schematic/nbt/PartialSafeNBT`、顶层 `AllBlocks` / `AllItems` / `AllPartialModels` / `AllSoundEvents` / `Create`
- 项目内使用：`block/TransmissionPeripheral*`、`block/ControlDeskBlock`（IWrenchable/IBE）、`block/MyBearing*`、`compat/create/CreateRedstoneCompat`、`foundation/gui/MyIcons` 等

### Ponder（`net.createmod.ponder`）

- 位置：`api/ponder-neoforge-1.0.82+mc1.21.1-sources/net/createmod/ponder/`（同目录还含内嵌 catnip，见上）
- 本项目未直接 import ponder API，编译依赖它主要是为了拿到匹配的 catnip shaded 副本。

### CC:Tweaked（`dan200.computercraft`）

- 位置：`api/` 下三个 `-sources` 目录并列，**分工明确**：
  - `cc-tweaked-1.21.1-common-api-1.118.0-sources/`：`dan200/computercraft/api/{client,component,detail,lua,media,network,pocket,redstone,turtle,upgrades}` + 顶层 `ComputerCraftAPI.java` / `ComputerCraftTags.java`；`api/lua/` 仅 `IComputerSystem` + `ILuaAPIFactory`
  - `cc-tweaked-1.21.1-core-api-1.118.0-sources/`：`api/{filesystem,lua,peripheral}` —— **Lua 注解与外围接口在这**：`api/lua/`（`LuaFunction`、`MethodResult`、`LuaTable`、`ObjectLuaTable`、`ILuaAPI`、`IArguments`、`LuaException` 等）、`api/peripheral/`（`IPeripheral`、`IComputerAccess`、`IDynamicPeripheral` 等）
  - `cc-tweaked-1.21.1-forge-api-1.118.0-sources/`：common-api 镜像 + `api/peripheral`
- 项目实际用到：`api/lua`（LuaFunction/MethodResult/LuaTable/ObjectLuaTable/ILuaAPI/IComputerSystem/LuaException，**在 core-api**）、`api/peripheral`（IPeripheral/PeripheralCapability，在 core-api/forge-api）、`ComputerCraftAPI`（在 common-api）
- 项目内使用：`compat/cc/` 下全部外设与 Lua API 文件（`*Peripheral`、`*ModuleHandle`、`*API`、`CCPeripheralCapabilities` 等），以及 `block/` 下各 BlockEntity 的 CC 集成

### 不在 api/ 中的依赖（jei、Registrate）

- **JEI**（`mezz.jei`）：`api/` 中无源码（可选依赖，未提取）。项目内 `compat/jei/AddonJEIPlugin.java` 用到 `IModPlugin` / `JeiPlugin` / `IGuiHandlerRegistration` / `IGhostIngredientHandler` / `ITypedIngredient`。需要签名时用 `web_search` 或从 maven（`maven.blamejared.com`）拉 sources jar。
- **Registrate**（`com.tterrag.registrate`）：`api/` 中无源码（Create 类间接引用，项目未直接 import）。需要时去 tterrag maven 拉 sources。

## 查找步骤

1. **按 import 前缀定位目录**（见下方对照表），不确定时先读 `build.gradle` 的 `dependencies` 块与 `gradle.properties` 确认版本与坐标。
2. 用 `grep` / `glob` **限定到 `api/` 对应 `-sources` 目录**搜索（现在是 .java 文件，直接可读），不要扫全工作区。
3. 接口签名在 `api/` 找不到或需要行为细节时，再进 `references/` 对应完整项目（如 `references/Create-mc1.21.1-dev/`、`references/sable-main/`、`references/CC-Tweaked-mc-1.21.x/`、`references/Catnip-NeoForge-.../`）。

| Import 前缀 | api/ 位置 | 备注 |
|---|---|---|
| `dev.ryanhcode.sable.companion` | `api/sable-companion-common-1.21.1-1.6.0-sources/` | 接口/数学；实现见 sable-common |
| `dev.ryanhcode.sable`（其余） | `api/sable-common-1.21.1-2.0.3-sources/` | 物理/子次元实现（项目版本 2.0.3；2.0.5-sources 为更新版） |
| `dev.engine_room.flywheel` | `api/flywheel-neoforge-1.21.1-1.0.6-sources/` | api.* 接口 + lib.* 现成实现（另含 backend/impl） |
| `net.createmod.catnip` | 编译匹配：`api/ponder-neoforge-1.0.82+mc1.21.1-sources/net/createmod/catnip/`；独立新版：`api/Catnip-NeoForge-1.21.1-0.8.54-sources/` | 两份包结构不同（顶层包 vs `utility.*`），查签名用 ponder 版 |
| `com.simibubi.create` | `api/create-1.21.1-6.0.10-280-sources/` | Create 完整源码，无 catnip |
| `net.createmod.ponder` | `api/ponder-neoforge-1.0.82+mc1.21.1-sources/` | 含内嵌 catnip |
| `dan200.computercraft` | `api/cc-tweaked-1.21.1-common-api-1.118.0-sources/` | Lua 注解/外设接口在 core-api 或 forge-api |
| `mezz.jei` | **api/ 中无** | 未提取；web_search / maven sources |
| `net.minecraft.*`（本体） | — | `.research/mc-src/` |

## 注意事项

- `api/`、`references/`、`.research/`、`libs/` 均为只读参考，不要修改。
- **api/ 现在全是 Java 源码**（`.java`），可以直接 `read` / `grep`；不再有不可读的 class 文件。
- **catnip 有两份**：ponder sources 内嵌版（`outliner/math/render/theme/data/gui/animation` 顶层包，与项目 import 一致）与独立 0.8.54（`utility.*` 布局：`utility/outliner/Outliner.java`、`utility/VoxelShaper.java`、`utility/theme/Color.java`）。**查签名用 ponder 版，查实现用 0.8.54**。
- **sable 拆两半**：`sable-companion-common`（接口/数学，编译依赖）与 `sable-common`（实现/物理，**必装运行时依赖**）；运行时 companion 被 sable-common jarJar 内嵌。项目编译版本 2.0.3。
- **cc 拆三个 jar**：Lua 注解（`LuaFunction`/`MethodResult`/`LuaTable`…）与外设接口（`IPeripheral`/`IComputerAccess`…）在 **core-api**，`ComputerCraftAPI` 在 common-api，forge-api 是镜像 + peripheral。
- Flywheel 拆两层：`api.*`（接口：Instance/Instancer/VisualizationContext…）与 `lib.*`（现成实现：OrientedInstance/TransformedInstance/PartialModel/AbstractBlockEntityVisual…）；sources jar 还多出 `backend/`、`impl/`。
- **jei / Registrate 不在 api/ 中**；需要时走网络/maven，不要假设它们在 api/。
- 参考外部 mod 源码解决问题时，按 `AGENTS.md` 要求记录参考来源。
