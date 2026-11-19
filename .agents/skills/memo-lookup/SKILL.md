---
name: memo-lookup
description: '查阅项目 memo 文档。`memo/README.md` 是唯一目录索引；`memo/project/code-map.md` 是定位 Java 源码的首要入口。memo 按主题分目录（project / flight / propulsion / peripherals / cockpit / rendering / archive），含项目进度与关键技术参考。当需要定位应修改的 Java 文件、了解项目整体状态、或查阅 CC:T 飞行控制、喷管与传感器 API、Monitor 与屏幕渲染等技术要点时使用此技能。'
---

# 项目 Memo 查阅

## `memo/project/code-map.md`：定位 Java 源码的首要入口

> 需要定位或修改 `src/main/java/com/zzy205/myfirstmod` 下的 Java 代码时，**第一步先读 `memo/project/code-map.md`，不要直接搜索源码**。

`code-map.md` 是一份按行为组织的 Java 代码地图，包含以下内容：

- **按修改目标定位**表：把需求（如「新增方块」「修改 Monitor 右键命中」「修改屏幕文本渲染」）直接映射到「首先查看的文件」和「通常还要检查的文件」。定位文件时优先查这张表。
- **模块职责表**：按「启动与注册 / 方块与方块实体 / Monitor 状态与模块模型 / GUI 与配置 / 网络 payload / 频道系统 / CC 兼容层 / 物品」分组，列出每个文件的职责和修改场景。
- **核心数据流图**：Monitor 交互与独立外设两条 mermaid 流程图，用于理解「客户端 → payload → 服务端 BlockEntity → GridState → 同步渲染」的链路。
- **当前已知边界**：记录不可违反的约束（如服务端是 Monitor 状态的权威来源、`getUpdatePacket()` 不能恢复默认实现、多 Monitor 状态按 `BlockPos` 隔离等）。改动相关代码前务必核对这些约束。

### 使用方法

1. 先到「按修改目标定位」表按行为查对应行，得到「首先查看」的文件。
2. 再到对应「模块职责表」读该文件的职责和修改场景，必要时按「通常还要检查」列补查关联文件。
3. 涉及交互链路时看「核心数据流」图，确认改动落在哪一环。
4. 动手改之前核对「当前已知边界」，避免触碰已知约束。

> 注意：`code-map.md` 中的路径均相对于仓库根目录；`client/`、`screen/` 中的 `*Screen` 类只应由客户端代码加载，`*Menu` 类是可被服务端加载的菜单逻辑。

## 何时使用

- 需要定位或修改 Java 源码 → 先读 `memo/project/code-map.md`
- 需要查外部依赖 API（Sable / Flywheel / Catnip / Create / CC:Tweaked / JEI）的包路径、类位置、方法签名 → 先读 `memo/project/api-code-map.md`，再进 `api/` 目录搜索
- 需要了解项目整体进度、已完成/待完成的功能
- 需要查阅 CC:T 飞行控制、喷管、传感器、控制台、屏幕渲染等技术参考文档
- 添加新 Monitor 元件或 controlDesk 控件时需要参考标准流程
- 需要了解独立模型烘焙、物品栏渲染等技术细节

## Memo 目录索引

`memo/` 已按主题分 7 个目录。**完整索引（每份文件一句话 + 何时读 + 维护规则）在 `memo/README.md`** —— 那里是唯一的目录真相源，本技能不再复制清单。

| 目录 | 内容 | 首要文件 |
|---|---|---|
| `memo/project/` | 项目元信息与入口（5 份） | `code-map.md`（Java 定位）、`api-code-map.md`（依赖 API）、`ATTENTION.md`（踩坑合集） |
| `memo/flight/` | CC:T 飞行与自动驾驶（6 份） | `starship15-ROUND.md`（本轮工作卡）、`starship15-constants.md`（常数/黑名单）、`flip-autopilot.md`（通用经验库） |
| `memo/propulsion/` | 喷管与引擎（3 份） | `thrust-nozzle.md`（母文档）、`advanced-thrust-nozzle.md`（矢量喷管定稿） |
| `memo/peripherals/` | 传感器/频道/通信/机械件（9 份） | `my_aero_sensor.md`（INS）、`short-range-link.md`（频道注册）、`cc-rednet-communication.md`（跨电脑） |
| `memo/cockpit/` | 控制台/Monitor/屏幕/GUI（9 份） | `control-desk-grid-slot.md`（加控件先读）、`record_screen_text.md`（屏幕渲染） |
| `memo/rendering/` | 渲染/兼容/注册（5 份） | `buffer-source-render-lifecycle.md`、`registrate-datagen.md` |
| `memo/archive/` | 历史记录（1 份） | `9.24.md` |

命名约定：文件名英文短横线、无空格、无中文；日期型记录进 `archive/`；移动或改名后必须跑引用校验（见 `memo/README.md` 维护规则）。

## 最小上下文原则

- 不要在每次任务开始时读取 `memo/project/ATTENTION.md` 或全部 memo。
- 只读取与当前请求直接对应的一份 memo；仅当它引用了另一个必须的文档时再读取下一份。
- 与项目进度或设计取舍没有直接关系时，不加载 memo。
- 需要定位 Java 文件职责、模块边界或修改入口时，查阅 `memo/project/code-map.md`；需要定位外部依赖 API 时查阅 `memo/project/api-code-map.md`。不要因此读取全部其他 memo。
- **CC:T 飞行迭代轮次**（星舰 / 飞车）：只读 `memo/flight/starship15-ROUND.md` + `memo/flight/starship15-constants.md`（各 ≤60/≤120 行），程序按函数锚点局部读，日志一律走 `tools/digest_csv.ps1` 取数 —— 原始 CSV（最大 74 MB）与 `memo/flight/starship15-catch.md`（77 KB）/`memo/flight/flip-autopilot.md`（320 KB）**不允许整篇进对话**。

## 查阅步骤

### 1. 确定需要查阅的文档

根据用户问题或当前任务确定需要查阅哪个 memo 文件（先看 `memo/README.md` 的「按任务找文档」表）：

- **定位/修改 Java 源码** → `memo/project/code-map.md`（首要入口）
- **查外部依赖 API（Sable/Flywheel/Catnip/Create/CC/JEI）** → `memo/project/api-code-map.md`（首要入口）
- **改任何代码前核对已知坑** → `memo/project/ATTENTION.md`
- **星舰 CC:T 飞行迭代（每轮开工）** → `memo/flight/starship15-ROUND.md` + `memo/flight/starship15-constants.md`
- **添加新 Monitor 元件** → `add-monitor-module` skill
- **3D 线框/高亮** → `catnip-outliner` skill
- **OBJ 模型或模型烘焙** → `neoforge-model-rendering` skill
- **物品栏渲染** → `create-custom-item-rendering` skill
- **旋转/朝向** → `neoforge-create-rotation` skill
- **Create 风格 GUI** → `create-style-gui` skill
- **CC:Tweaked 传感器 Lua API** → `cc-sensor-lua-api` skill

### 2. 读取对应文件

用 `read` 读取 `memo/<主题目录>/<文件名>` 中与当前任务有关的段落（大文件按标题/§N 定位，不要整篇读），不需要搜索源码。

### 3. 结合源码实施

memo 文档提供了关键 API 速查和代码模板，结合项目现有代码结构实施。

## 文档间关联

- 任务型技术资料已迁移到 `.agents/skills/`，只在描述匹配时加载。
- memo 只保存当前项目已实现的状态、特殊约束和短期进度。

## 注意事项

- `memo/project/ATTENTION.md` 是踩坑与注意事项的首选入口；各功能的**实施总结与后续计划写在各主题文档里**（引擎 → `memo/propulsion/engine-module.md` §17；定向喷管/排气粒子 → `memo/propulsion/thrust-nozzle.md`、矢量喷管 → `memo/propulsion/advanced-thrust-nozzle.md`；航空轴承 → `memo/peripherals/my_bearing.md`；屏幕格子模型 → `memo/cockpit/screen-render-rework.md`「实施状态」；传感器物理读数 → `memo/peripherals/my_sensor_system.md`「实施补充记录」；INS → `memo/peripherals/my_aero_sensor.md`）
- 如果 memo 和相关 skill 都无法回答当前 API 问题，再回退到 `minecraft-research` skill。
