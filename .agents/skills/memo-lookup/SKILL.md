---
name: memo-lookup
description: '查阅项目 memo 文档。code-map.md 是定位 Java 源码的首要入口。memo 目录还包含项目进度（.TO DO.md）以及关键技术参考文档。当需要定位应修改的 Java 文件、了解项目整体状态、当前开发的功能、或查阅 OBJ 模型、旋转、Catnip、物品栏渲染等技术要点时使用此技能。'
---

# 项目 Memo 查阅

## code-map.md：定位 Java 源码的首要入口

> 需要定位或修改 `src/main/java/com/zzy205/myfirstmod` 下的 Java 代码时，**第一步先读 `memo/code-map.md`，不要直接搜索源码**。

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

- 需要定位或修改 Java 源码 → 先读 `code-map.md`
- 需要查外部依赖 API（Sable / Flywheel / Catnip / Create / CC:Tweaked / JEI）的包路径、类位置、方法签名 → 先读 `api-code-map.md`，再进 `api/` 目录搜索
- 需要了解项目整体进度、已完成/待完成的功能
- 需要查阅 OBJ 模型、旋转、渲染、Catnip 等技术参考文档
- 添加新 Monitor 元件时需要参考标准流程
- 需要了解独立模型烘焙、物品栏渲染等技术细节

## Memo 文件索引

所有 memo 文件位于工作区根目录下的 `memo/` 文件夹：

| 文件 | 用途 | 何时查阅 |
|------|------|---------|
| `code-map.md` | Java 源码职责、核心数据流、修改入口和已知边界 | 需要定位或修改 Java 文件时（首要入口，先查它） |
| `api-code-map.md` | **api/ 依赖源码代码地图**（全部为 Java 源码 `-sources`）：Sable Companion / Flywheel / Catnip（核心三件套，项目根基）与 Create / CC:Tweaked / Ponder 的包级地图、import 前缀→路径对照、项目内使用文件清单；JEI / Registrate 未提取（标注为不在 api/） | 需要查外部依赖 API 的包路径/类位置/签名（先查它，再进 api/ 搜索）；涉及渲染、物理、子次元、Outliner、GUI 控件时尤其有用 |
| `.TO DO.md` | 项目整体进度清单 | 了解已完成/待完成功能、规划下一步工作 |
| `record_screen_module.md` | 可变尺寸屏幕实现记录 | 修改屏幕数据、渲染、放置或拆卸行为时 |
| `record_screen_text.md` | 屏幕字符/矩形渲染实现记录（位图字体、UV 环绕、镜像/深度坑） | 修改屏幕文本/图形渲染或 `ScreenTextRenderer` 时 |
| `knob-interaction.md` | 旋钮交互数据流 | 修改旋钮拖拽、角度同步或音效时 |
| `monitor-state-isolation.md` | 多 Monitor 客户端状态规范 | 修改交互状态、动画缓存或 Outliner key 时 |
| `gui-infrastructure.md` | 已落地 GUI 基础设施 | 修改现有控件的实现细节时 |
| `control-desk-grid-slot.md` | controlDesk 桌顶棋盘网格自由放置系统（joystick_2 完整接入；常量/数据流/变换链/添加新模块 checklist） | 修改或新增 controlDesk 自由放置模块（throttle / monitor_2 / 新控件）的预览、放置、占用、拆除时 |
| `create-schematic-nbt.md` | Create 蓝图 NBT 经验记录（保存/部署中的配置丢失问题） | 排查 PeripheralExtender / RedstoneTransceiver 在 Create 蓝图中的行为时 |
| `servo-mode.md` | 传动外设舵机模式实现记录（TiltAdapter 段式状态机 + 段内同向重瞄 re-aim + flicker 门控） | 修改舵机角度定位、段推进、flicker 门控或 Lua 舵机 API 时 |
| `my_bearing.md` | 自研风帆轴承方案设计（轴向应力输入 + RotaryConstraint 物理驱动，不贯通应力） | 实现/修改 my_bearing 方块时，先读方案再动手 |
| `pitot-selection-box.md` | 皮托管选择框调试记录（VoxelShaper 旋转坑：单位混用/基准朝向/end_rod 风格下 Catnip 与原版 X 旋转方向相反，水平四向绕 Y 180°；新增方块选择框的 checklist） | 新增或修改 6 向 FACING 方块的选择框/碰撞框，或排查选择框"不可见/差 90°/水平反了"时 |
| `neoforge-debugging.md` | 本项目 F5 启动事实 | 调试启动配置或 classpath 时 |

## 最小上下文原则

- 不要在每次任务开始时读取 `.TO DO.md` 或全部 memo。
- 只读取与当前请求直接对应的一份 memo；仅当它引用了另一个必须的文档时再读取下一份。
- 与项目进度或设计取舍没有直接关系时，不加载 memo。
- 需要定位 Java 文件职责、模块边界或修改入口时，查阅 `code-map.md`；需要定位外部依赖 API 时查阅 `api-code-map.md`。不要因此读取全部其他 memo。

## 查阅步骤

### 1. 确定需要查阅的文档

根据用户问题或当前任务确定需要查阅哪个 memo 文件。**定位 Java 文件时优先查 `code-map.md`**：

- **定位/修改 Java 源码** → `code-map.md`（首要入口）
- **查外部依赖 API（Sable/Flywheel/Catnip/Create/CC/JEI）** → `api-code-map.md`（首要入口）
- **项目进度相关** → `.TO DO.md`
- **添加新元件** → `add-monitor-module` skill
- **3D 线框/高亮** → `catnip-outliner` skill
- **OBJ 模型或模型烘焙** → `neoforge-model-rendering` skill
- **物品栏渲染** → `create-custom-item-rendering` skill
- **旋转/朝向** → `neoforge-create-rotation` skill
- **Create 风格 GUI** → `create-style-gui` skill
- **CC:Tweaked 传感器 Lua API** → `cc-sensor-lua-api` skill

### 2. 读取对应文件

使用 `read_file` 读取 `memo/<文件名>` 中与当前任务有关的段落，不需要搜索源码。

### 3. 结合源码实施

memo 文档提供了关键 API 速查和代码模板，结合项目现有代码结构实施。

## 文档间关联

- 任务型技术资料已迁移到 `.agents/skills/`，只在描述匹配时加载。
- memo 只保存当前项目已实现的状态、特殊约束和短期进度。

## 注意事项

- `.TO DO.md` 中的 checkbox 状态是项目进度的重要参考
- 如果 memo 和相关 skill 都无法回答当前 API 问题，再回退到 `minecraft-research` skill。
