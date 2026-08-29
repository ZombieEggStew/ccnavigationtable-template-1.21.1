---
name: minecraft-research
description: 'Minecraft 相关调研：当需要确认 Minecraft 本体、依赖 mod（Create、CC:Tweaked、Sable、JEI 等）或参考案例 mod 的源码、API 与实现方式时使用。API/接口查询用 api/（干净源码，包级地图见 memo/api-code-map.md；Sable Companion、Flywheel、Catnip 是项目根基最常用），案例参考用 references/（完整项目），个人调研源码放 .research/；用于确认 API 方法签名、追踪调用链、调试兼容性问题或理解注册与渲染机制。'
---

# Minecraft 相关调研

> 调研 Minecraft 本体与 mod 源码：定位 API、参考案例实现、追踪跨 mod 调用链。

> **api/ 的详细包级代码地图见 `memo/api-code-map.md`**（含核心三件套重点说明、import 前缀→路径对照表、项目内使用文件清单）；需要精确的包路径或类位置时先查它，本技能只给目录级索引。

## ⭐ 核心三件套（项目根基，最常用）

**Sable Companion、Flywheel、Catnip 是本 mod 最常用的外部源码，几乎是项目根基**：渲染链（Flywheel Visual + Catnip Outliner/SuperByteBuffer）、物理驱动（Sable RotaryConstraint）、GUI 控件（Catnip AbstractSimiWidget）、命中检测（Sable plot 坐标回投）全部建立在它们之上。涉及渲染 / 物理 / 子次元 / 预览 / 控件问题，**最先查这三处**：

| 库 | 包前缀 | api/ 位置 | 一句话职责 |
|---|---|---|---|
| Sable Companion | `dev.ryanhcode.sable` | `api/sable/sable-companion-common-1.21.1-1.6.0/`（接口/数学）+ `api/sable/sable-neoforge-1.21.1-2.0.3/`（实现/物理） | 子次元 + 物理（RotaryConstraint、plot 坐标）；本 mod 的 my_bearing、命中回投、传感器都靠它 |
| Flywheel | `dev.engine_room.flywheel` | `api/create/flywheel-neoforge-api-1.21.1-1.0.6/` | Create 实例化渲染引擎；所有动态方块（Monitor/controlDesk/传动外设/my_bearing）的 Visual 基于它 |
| Catnip | `net.createmod.catnip` | `api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/catnip/` | Create 工具库：Outliner、SuperByteBuffer、VoxelShaper、GUI 控件、Color；**注意在 ponder jar 内，不在 create slim 里** |

> Catnip 版本注意：项目编译用的是 **ponder jar 内嵌的 shaded 副本**（包结构与 Create API 匹配）；`references/Catnip-NeoForge-1.21.1-0.8.54-sources/` 是独立新版（0.8.54，`net.createmod.catnip.utility.*` 子包结构），查实现时注意包路径不同。

## 何时使用

- 需要查看 Minecraft 本体（`net.minecraft.*`）或某个依赖 mod 的类、方法、接口源码
- 查询 API 方法签名、接口定义 → `api/`（干净源码）；参考整体实现、设计模式 → `references/`
- 需要参考 `references/` 中案例 mod 的实现方式（控制台/仪表/航空学等）
- 需要理解 Create、CC:Tweaked、Sable、JEI 等 mod 的 API 行为
- 调试与依赖 mod 的兼容性问题、追踪跨 mod 调用链或 mixin 目标
- 了解某个 mod 的网络包、事件、注册机制

不要为了了解项目概况或做常规源码修改而读取参考源码。只有当前代码的 import、编译错误或明确问题指向外部 API / 参考实现时才使用本 skill。

## 源码目录

| 目录 | 用途 |
|------|------|
| `api/` | 依赖 mod 的 **API / 接口源码**（只含接口与公开 API，源码干净易读）；查询 API 方法签名、接口定义时**首选** |
| `references/` | 案例项目源码（主要用于**案例参考**：看整体架构、实现细节、设计模式） |
| `.research/` | 个人调研源码：Minecraft 本体 `mc-src/`、Create 分版本 `create-src/`；新调研的源码统一放这里 |
| `libs/` | 必须依赖的 mod 接口、源码或本地库 |

### api/ 依赖 API 源码（查询接口首选）

按 mod 分组的干净 API 源码，只含接口与公开 API，没有实现噪音：

| 目录 | 内容 |
|------|------|
| `api/cc/` | CC:Tweaked 1.118.0：`common-api` / `core-api` / `forge-api`（`dan200.computercraft.*`） |
| `api/create/` | Create 6.0.10 `slim`（`com.simibubi.create.*`，**不含 catnip**）、Flywheel `neoforge-api`、Ponder（**内含 catnip：`net.createmod.catnip.*`**）、Registrate |
| `api/jei/` | JEI 19.42.0.379：`common-api` / `neoforge-api`（`mezz.jei.*`） |
| `api/sable/` | Sable：`sable-companion-common`（接口/数学，`dev.ryanhcode.sable.companion.*`）与 `sable-neoforge`（实现/物理，`dev.ryanhcode.sable.*`） |

> 想确认「这个 API 怎么用 / 接口长什么样」→ 查 `api/`；想参考「某个功能整体是怎么实现的」→ 才进 `references/`。

### references/ 案例项目索引（案例参考）

| 目录 | 是什么 | 可参考点 |
|------|--------|----------|
| `references/Create-mc1.21.1-dev/` | 机械动力（Create）完整源码 | 本 mod 的基础；GUI、Outliner、渲染、网络、旋转等大量机制 |
| `references/Catnip-NeoForge-1.21.1-0.8.54-sources/` | Catnip 独立完整源码（0.8.54） | Catnip 实现细节（Outliner/VoxelShaper/SuperByteBuffer 等）；**注意 0.8.54 为 `net.createmod.catnip.utility.*` 子包结构，与 ponder 内嵌版顶层包不同** |
| `references/control-panels-master/` | 控制台 mod | 可拆卸模块架构、自研 3D 命中检测设计 |
| `references/aeroworks-decompiled/` | 反编译源码（航空学相关） | 控制台、可拆卸模块架构、多层 UI 设计、完全贴合模型的选择框设计 |
| `references/CC-Tweaked-mc-1.21.x/` | 电脑模组 CC:Tweaked 完整源码 | 外设 / Lua API 的实际实现（接口定义见 `api/cc/`） |
| `references/sable-main/` | Sable（航空学基础） | 子次元相关知识；本 mod 兼容层的基础，很重要 |
| `references/Simulated-Project-main/` | 航空学 mod | 本 mod 的基础；simulated 部分有各种仪表和精细建模设计 |
| `references/create-propulsion-simulated-main/` | Create 推进器模拟 | 推进器实现 |
| `references/CreateAvionics-main/` | Create × 航空学兼容 | CC peripheral 设计范例 |

### .research/ 调研源码

- `mc-src/`：Minecraft 本体源码（如 `BlockEntity` 体系、粒子包等）
- `create-src/`：Create 分版本源码（`mc1.19.2_dev` / `mc1.20.1_dev` / `mc1.21.1_dev`）
- 新调研的源码（反编译 jar、临时下载等）统一放入 `.research/`，不进 `references/`，也不作为正式参考目录

## 查找步骤

### 1. 确认目标

根据用户问题或当前代码中的 import 语句确定要看哪份源码。**不确定包路径或想知道某个 api 包里有啥 → 先查 `memo/api-code-map.md`**。然后按意图分档：

- **查 API / 接口**（方法签名、接口定义、返回类型）→ 优先 `api/` 对应目录
- **参考案例实现**（某功能整体怎么做、设计模式、行为细节）→ 进 `references/` 对应项目
- **查 Minecraft 本体 / Create 历史版本** → 进 `.research/`

常见 import 前缀与源码位置：

| Import 前缀 | API / 接口查询（`api/`） | 案例参考（`references/`） |
|-------------|--------------------------|---------------------------|
| `com.simibubi.create` | `api/create/create-1.21.1-6.0.10-280-slim/` | `references/Create-mc1.21.1-dev/` |
| `net.createmod.catnip` | `api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/catnip/`（**不在 create slim 里**） | `references/Catnip-NeoForge-1.21.1-0.8.54-sources/`（注意 0.8.54 为 `utility.*` 包结构） |
| `net.createmod.ponder` | `api/create/ponder-neoforge-1.0.82+mc1.21.1/`（含内嵌 catnip） | — |
| `dan200.computercraft` | `api/cc/cc-tweaked-1.21.1-common-api-1.118.0/`（core/forge-api 并列） | `references/CC-Tweaked-mc-1.21.x/` |
| `dev.engine_room.flywheel` | `api/create/flywheel-neoforge-api-1.21.1-1.0.6/`（`api.*` 接口 + `lib.*` 现成实现） | Create 完整源码内；找不到再查 `.research/` |
| `mezz.jei` | `api/jei/jei-1.21.1-common-api-19.42.0.379/`（neoforge-api 并列） | — |
| `dev.ryanhcode.sable.companion` | `api/sable/sable-companion-common-1.21.1-1.6.0/`（接口/数学） | `references/sable-main/` |
| `dev.ryanhcode.sable`（其余） | `api/sable/sable-neoforge-1.21.1-2.0.3/`（实现/物理） | `references/sable-main/` |
| `net.minecraft.*`（Minecraft 本体） | — | `.research/mc-src/` |
| Create 历史版本实现 | — | `.research/create-src/<版本>/` |
| 控制台类参考（可拆卸模块/命中检测） | — | `references/control-panels-master/`、`references/aeroworks-decompiled/` |
| 仪表/建模类参考 | — | `references/Simulated-Project-main/` |

### 2. 在对应目录中限定搜索

使用 `grep` / `glob` 限定到 `api/`、`references/` 或 `.research/`，不要扫描整个工作区。查接口先搜 `api/`，搜不到实现细节再进 `references/`。

示例 — 查 CC:Tweaked IPeripheral 接口定义（API 查询）：
```
grep: path = "api/cc/cc-tweaked-1.21.1-common-api-1.118.0", pattern = "interface IPeripheral"
```

示例 — 搜索 Create 的 RedstoneLinkNetworkHandler（案例实现）：
```
grep: path = "references/Create-mc1.21.1-dev", pattern = "RedstoneLinkNetworkHandler"
```

示例 — 搜索 Sable 的 Pose3dc：
```
grep: path = "references/sable-main", pattern = "transformPosition"
```

示例 — 搜索 Minecraft 本体 BlockEntity 的加载逻辑：
```
grep: path = ".research/mc-src", pattern = "loadAdditional"
```

### 3. 阅读源码文件

找到目标文件后，用 `read` 先读取包含目标方法、类型或调用点的局部范围；只有局部内容无法确定 API 合约时才扩大读取范围。mod 源码目录结构通常遵循标准 Java 包结构：

```
references/<mod>-<version>/
├── META-INF/
├── <mod>.mixins.json
├── assets/
├── data/
└── <com|net|dev>/
    └── <author>/
        └── <mod>/
            └── ...
```

### 4. 必要时使用网络

如果源码不在工作区中，可用 `web_search` 在线查找。常见仓库：

- Create: `Creators-of-Create/Create`（分支 `mc1.21.1/dev`）
- 本 mod 基于 Create / 航空学系 mod 的衍生项目，`references/` 中案例大多来自 GitHub 开源仓库

## 注意事项

- `api/`、`references/`、`.research/`、`libs/` 均为只读参考，不要修改；改源码请直接改 `src/main/java`。
- `api/` 只含 API 表面，行为细节、边界情况要看 `references/` 中的完整实现。
- `references/` 中的源码可能是反编译产物（如 `aeroworks-decompiled`），可能不包含完整注释。
- CC:Tweaked 的 API 已按 `common-api` / `core-api` / `forge-api` 拆分在 `api/cc/`；对应完整实现见 `references/CC-Tweaked-mc-1.21.x/`。
- Sable 拆两半：接口/数学在 `sable-companion-common`（编译依赖，`dev.ryanhcode.sable.companion.*`），实现/物理在 `sable-neoforge`（**必装运行时依赖**，`dev.ryanhcode.sable.*`）；运行时 companion 被 sable-neoforge jarJar 内嵌。`references/sable-main/` 是完整项目源码。
- Catnip 不在 create slim 里：API 源码在 `api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/catnip/`（ponder 内嵌 shaded 副本，与 Create API 包结构匹配）；`references/Catnip-NeoForge-1.21.1-0.8.54-sources/` 是独立新版（`net.createmod.catnip.utility.*` 包结构），两者类名相同但包路径不同。
- 参考其他 mod 源码解决问题时，按 AGENTS.md 要求记录参考来源。
