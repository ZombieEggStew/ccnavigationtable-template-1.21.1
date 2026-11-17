# Monitor 多实例状态隔离 — 架构规范

> 核心原则：**客户端任何与具体 Monitor 实例相关的状态，必须按 BlockPos 隔离。禁止 static 全局变量。**

## 模式：`Map<BlockPos, InteractionState>`

```java
// ✅ 正确 —— 每个 Monitor 独立状态
private static final Map<BlockPos, InteractionState> interactions = new HashMap<>();

private static class InteractionState {
    int pressingModuleId = -1;   // 按钮按下
    int toggleFiredId = -1;      // 钮子防连发
    boolean knobDragging;         // 旋钮拖拽
    // ... 所有交互字段
}

// 使用时按 pos 获取
var interact = interactions.computeIfAbsent(pos, k -> new InteractionState());
```

```java
// ❌ 错误 —— 全局 static（多 Monitor 互相干扰）
private static int pressingModuleId = -1;
private static BlockPos pressingPos = null;
```

## 为什么需要

1. **moduleId 非全局唯一**：每个 `GridState` 从 0 独立递增，A 的 id=0 ≠ B 的 id=0
2. **玩家一次只能看一个 Monitor**：看向 B 时 A 的状态不能丢失（如旋钮拖拽中）
3. **未来多人支持**：外层 Map 可扩展为 `Map<BlockPos, Map<Player, InteractionState>>`

## 生命周期

| 阶段 | 操作 |
|------|------|
| 首次交互 | `computeIfAbsent(pos, ...)` 懒创建 |
| 视线移开 | `releaseStalePressesExcept(player, exceptPos)` 释放非当前 Monitor 的按钮 |
| 每 tick 清理 | `onClientTick` 移除无活跃交互的条目（`pressingModuleId<0 && toggleFiredId<0 && !knobDragging && !screenPlacing`） |

## 涉及文件

| 文件 | 改前 | 改后 |
|------|------|------|
| `MonitorGridOverlay.java` | 12 个 `static` 字段 | `Map<BlockPos, InteractionState>` + 内部类 |
| `MonitorRenderer.java` | `Map<Integer, Float>` animProgress | `Map<BlockPos, Map<Integer, Float>>` |
| `MonitorGridOverlay.java` Outliner keys | `"grid_v"+i` 等无前缀 | `pos.toShortString()+"/grid_v"+i` |

## computeCrosshairAngle 签名变更

```java
// 旧：隐式读取 static knobCenterX/Y
computeCrosshairAngle(player, pos, facing)

// 新：显式传参，纯函数
computeCrosshairAngle(player, pos, facing, state.knobCenterX, state.knobCenterY)
```

## 注意事项

- **不要**把 `InteractionState` 放进 `MonitorBlockEntity`（那是服务端/同步的数据，客户端交互状态是纯客户端的）
- 清理条件必须覆盖所有活跃字段：`pressingModuleId`、`toggleFiredId`、`knobDragging`、`screenPlacing`，漏一个就会导致状态被误删
- Outliner 的 `showLine(key, ...)` key 也必须带 BlockPos 前缀，否则多 Monitor 的网格线互相覆盖
