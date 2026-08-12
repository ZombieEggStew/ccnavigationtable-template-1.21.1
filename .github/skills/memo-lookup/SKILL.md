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
| `add_module_guide.md` | 添加新 Monitor 元件的标准流程 | 添加按钮/旋钮/滑条等新元件时参考 |
| `catnip.md` | Catnip Outliner API 用法参考 | 需要画 3D 线框、AABB、线段、聚合轮廓时 |
| `obj_model.md` | NeoForge OBJ 模型渲染方法 | 制作/调试 OBJ 模型、理解 OBJ→MTL→JSON→PNG 管线 |
| `render_item.md` | 多部件物品的物品栏渲染 | 实现 CustomRenderedItemModel 组合渲染 |
| `rotation.md` | NeoForge 1.21.1 旋转问题手册 | 调试方向/旋转相关 bug、理解 CW vs CCW |
| `standalone_model.md` | Standalone 模型烘焙备忘 | BER 中渲染独立模型、ModelEvent 两步烘焙 |

## 查阅步骤

### 1. 确定需要查阅的文档

根据用户问题或当前任务确定需要查阅哪个 memo 文件：

- **项目进度相关** → `.TO DO.md`
- **当前开发任务** → `current.md`
- **添加新元件** → `add_module_guide.md`
- **3D 线框/高亮** → `catnip.md`
- **OBJ 模型** → `obj_model.md`
- **物品栏渲染** → `render_item.md`
- **旋转/朝向** → `rotation.md`
- **模型烘焙** → `standalone_model.md`

### 2. 读取对应文件

使用 `read_file` 读取 `memo/<文件名>`，不需要搜索源码。

### 3. 结合源码实施

memo 文档提供了关键 API 速查和代码模板，结合项目现有代码结构实施。

## 文档间关联

- `current.md` 中的设计方案会引用 `add_module_guide.md` 的流程
- `add_module_guide.md` 引用 `obj_model.md` 的 OBJ 导出设置
- `add_module_guide.md` 引用 `render_item.md` 的物品栏渲染方法
- `add_module_guide.md` 引用 `catnip.md` 的线框渲染
- `render_item.md` 引用 `standalone_model.md` 的模型烘焙模式

## 注意事项

- `current.md` 内容会随开发进度频繁更新，每次开始新任务前应先查阅
- `.TO DO.md` 中的 checkbox 状态是项目进度的重要参考
- memo 文档是手动整理的技术要点，比源码搜索更快更准确
- 如果 memo 文档中没有覆盖所需知识点，再回退到 `minecraft-mod-source-lookup` 技能查阅源码
