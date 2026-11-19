# Knob 旋转交互 — 技术文档

## 数据流

```
玩家右键按住旋钮
  → MonitorGridOverlay 进入拖拽模式
  → 每帧 ClientTickEvent.Pre:
      ① computeCrosshairAngle(): 射线打屏幕平面 → atan2(Δy, Δx) = rawAngle
      ② 解缠绕: diff = normalizeToPi(rawAngle - prevRawAngle)
         unwrappedDelta += diff (可超过 2π, 支持无限圈)
      ③ newAngle = knobAccumAngle + toDegrees(unwrappedDelta)
      ④ 谢泼德音阶: 每 ±12° 播放 LEVER_CLICK
         pitch = 0.5 + (angle%360)/360*1.5 (正转), 2.0 - ... (反转)
      ⑤ ModuleKnobRotatePayload → 服务端
  → MonitorBlockEntity.rotateKnob() → GridState.setKnobAngle() → syncGrid
  → MonitorRenderer: target = grid.getKnobAngle(), 指数平滑插值 → KnobBehavior
  → KnobBehavior.renderExtra(): ps.mulPose(Axis.YP.rotationDegrees(-anim))
```

## 关键文件

| 文件 | 改动 |
|---|---|
| `GridState.java` | `Map<Integer, Float> knobAngles` + set/get + NBT |
| `ModuleKnobRotatePayload.java` | `(BlockPos, int moduleId, float angle)` net packet |
| `MonitorBlockEntity.java` | `rotateKnob()` 方法 |
| `CCPeripheraExtender.java` | 注册 knob rotate handler |
| `MonitorGridOverlay.java` | 拖拽 + 准心角度计算 + 解缠绕 + 音效；**2025-08 重构为 per-BlockPos InteractionState** |
| `MonitorRenderer.java` | knob 用 getKnobAngle 替代 isPressed 做动画目标；**animProgress 改为 Map<BlockPos, Map<Integer, Float>>** |
| `ModuleRenderBehavior.java` | `KnobBehavior.renderExtra()` 用 anim 作 Y 轴旋转 |

## 准心角度计算

- `computeCrosshairAngle(player, pos, facing, knobCenterX, knobCenterY)`: 射线-平面求交 → 屏幕局部坐标 (1/16格单位) → `atan2(sy - knobCy, sx - knobCx)`
- 屏幕局部坐标转换：`worldHitToGrid` 同款数学，NORTH→lx, SOUTH→1-lx, EAST→lz, WEST→1-lz
- 旋钮中心: `knobCenterX = SCREEN_X_MIN + gridX + width/2f`
- **2025-08 重构**：`knobCenterX/Y` 改为显式传参（不再读 static 字段），以支持多 Monitor 状态隔离（见 `monitor-state-isolation.md`）

## 解缠绕 (Phase Unwrapping)

- `atan2` 输出 [-π,+π]，跨边界从 +π 跳 -π
- 解法：`diff = rawAngle - prevRawAngle`，规范化 diff 到 [-π,+π]，累计到 unwrappedDelta
- 结果：unwrappedDelta 可单调增长超过 2π、4π...

## 谢泼德音阶

- 每 12° 播放 `SoundEvents.LEVER_CLICK`
- pitch 在 [0.5, 2.0] 循环：`0.5 + (angle%360)/360*1.5`
- 正转升调，反转 `2.0 - (pitch-0.5)` 即倒序
- 客户端 `player.playSound()` 零延迟反馈

## 已废弃方案
- ❌ Mixin MouseHandler + accessor → ClassCastException, 删除了
- ❌ 反射 accumulatedDX → 字段 private 不可见
- ❌ player.getYRot() → 只能水平转，不符合"准心绕旋钮转"
