---
name: memo-lookup
description: '查阅项目 memo 文档。memo 目录包含项目进度（.TO DO.md）、当前任务（current.md）以及关键技术参考文档。当需要了解项目整体状态、当前正在开发的功能、或查阅已整理的 OBJ 模型、旋转、Catnip、物品栏渲染等技术要点时使用此技能。'
---

# 项目 Memo 查阅

## 何时使用

- 需要了解项目整体进度、已完成/待完成的功能
- 需要知道当前正在进行的开发任务及其设计方案
- 需要查阅 OBJ 模型、旋转、渲染、Catnip 等技术参考文档
- 添加新 Monitor 元件时需要参考标准流程
- 需要了解独立模型烘焙、物品栏渲染等技术细节

## Memo 文件索引

所有 memo 文件位于工作区根目录下的 `memo/` 文件夹：

| 文件 | 用途 | 何时查阅 |
|------|------|---------|
| `.TO DO.md` | 项目整体进度清单 | 了解已完成/待完成功能、规划下一步工作 |
| `current.md` | 当前正在进行的任务及设计文档 | 了解当前功能的架构决策、实现方案 |
| `record_screen_module.md` | 可变尺寸屏幕实现记录 | 修改屏幕数据、渲染、放置或拆卸行为时 |
| `knob-interaction.md` | 旋钮交互数据流 | 修改旋钮拖拽、角度同步或音效时 |
| `monitor-state-isolation.md` | 多 Monitor 客户端状态规范 | 修改交互状态、动画缓存或 Outliner key 时 |
| `gui-infrastructure.md` | 已落地 GUI 基础设施 | 修改现有控件的实现细节时 |
| `neoforge-debugging.md` | 本项目 F5 启动事实 | 调试启动配置或 classpath 时 |
| `code-map.md` | Java 源码职责、核心数据流和修改入口 | 需要快速定位应修改的 Java 文件时 |

## 最小上下文原则

- 不要在每次任务开始时读取 `.TO DO.md`、`current.md` 或全部 memo。
- 只读取与当前请求直接对应的一份 memo；仅当它引用了另一个必须的文档时再读取下一份。
- 项目进度、当前任务或设计取舍没有直接关系时，不加载 memo。
- 需要定位 Java 文件职责、模块边界或修改入口时，查阅 `code-map.md`；不要因此读取全部其他 memo。

## 查阅步骤

### 1. 确定需要查阅的文档

根据用户问题或当前任务确定需要查阅哪个 memo 文件：

- **项目进度相关** → `.TO DO.md`
- **当前开发任务** → `current.md`
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

- 任务型技术资料已迁移到 `.github/skills/`，只在描述匹配时加载。
- memo 只保存当前项目已实现的状态、特殊约束和短期进度。

## 注意事项

- `current.md` 内容会随开发进度频繁更新；仅在当前任务、架构决策或未完成工作需要它时查阅
- `.TO DO.md` 中的 checkbox 状态是项目进度的重要参考
- 如果 memo 和相关 skill 都无法回答当前 API 问题，再回退到 `minecraft-mod-source-lookup` skill。
