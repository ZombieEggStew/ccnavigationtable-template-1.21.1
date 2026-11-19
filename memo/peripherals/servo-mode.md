# 传动外设舵机模式 — 技术文档

> 记录 `TransmissionPeripheralBlockEntity` 舵机模式的段式状态机实现，特别是
> **段内同向重瞄（re-aim）** 行为。Wiki 用户文档见 `wiki/docs/electronic-transmission/servo-mode.md`。

## 定位

| 文件 | 职责 |
|---|---|
| `block/TransmissionPeripheralBlockEntity.java` | 舵机模式入口：Lua API（setServoAngle/setServoSpeed/setServoReaim/resetServo）、`tickServoServer()` 段式推进、flicker 门控、应力计算 |
| `block/ServoMotionState.java` | 服务器权威角度状态机：`requestedTarget`/`activeTarget`/`currentAngle` 分离 + `reaimIfSameDirection()` |

## 核心设计（TiltAdapter 段式状态机）

- **三值分离**：`requestedTarget`（Lua 可随时改，归一化 ±180°）≠ `activeTarget`（段开始时锁定的展开终点）≠ `currentAngle`（展开值，可跨 ±180 累积）。
- **段（segment）**：单段 ≤ `MAX_SEGMENT_ANGLE`（179°），段内线性推进，避免 Create 最短角插值在 180° 的二义性与移动中反向翻转（DIR-FLIP）。
- **推进链**：`tickServoServer()` → 每 tick `advance(convertToAngular(driveSpeed))` → 到位后 `segmentSettleTicks`（2 tick）→ `finishActiveSegment()`（先 detach 再清段）。
- **flicker 门控**：段开始（attach）前检查 `flickerTally ≤ FLICKER_THRESHOLD(60)` 且 `getFlickerScore() ≤ 60`，超限则 `segmentStartQueued` 延后——防止 Create `MAX_FLICKER_SCORE(128)` 直接拆方块（`RotationPropagator`）。

## 段内同向重瞄（re-aim，2025 新增）

**问题**：原实现段内改目标只更新 `requestedTarget`，要等当前段（≤179°）走完才响应，段长时响应迟钝。

**方案**：`ServoMotionState.reaimIfSameDirection()` 在 `tickServoServer()` 段内推进前调用：

```java
float arc = shortestArc(wrap(currentAngle), requestedTarget);
if (Math.abs(arc) <= EPSILON) return false;
if (Math.signum(arc) != activeDirection) return false;   // 反向：保持段式保护
float segmentAngle = Math.min(Math.abs(arc), MAX_SEGMENT_ANGLE);
remainingAngle = segmentAngle;
activeTarget = currentAngle + activeDirection * segmentAngle;
```

| 场景 | 行为 |
|---|---|
| 段内同向改更远目标（0°→90° 途中改 120°） | **同一 tick 延长当前段到 120°**，零打断、零 flicker |
| 段内同向改更近目标（0°→90° 途中改 60°） | 缩短当前段，提前到位 |
| 段内反向改目标 | 保持旧行为：等段结束再开反向段（DIR-FLIP 保护） |
| 单段仍受 179° 限制 | 超出的部分由下一段继续（与 `startSegment` 一致） |

**可开关（`setServoReaim`，2025 追加）**：`reaimIfSameDirection()` 每次调用都会用
**完整弧长重置 `remainingAngle`** 且不重新评估方向。高频变化输入下目标在 ±180
边界来回摆动时，`shortestArc` 符号跳变但 `activeDirection` 不更新 → 段终点被反复
改写、错误定位。故提供 Lua 开关（**默认关**，旧存档兼容保持关）：

- `getServoReaim()` / `setServoReaim(boolean)`（Lua，mainThread）
- 开：同向改目标立即响应（快），但高频输入可能误定位；关（默认）：回退到
  「等当前段结束再响应新目标」的原始段式语义，定位稳定但响应慢
- NBT 键 `ServoReaim`；旧存档无该键时按默认 false 处理（`tag.contains` 判断），避免升级后行为突变
- tooltip 舵机模式下显示开关状态（「段内重瞄：开/关」，无颜色）

**为什么不直接改 PD**：PD 每 tick 变转速会持续触发 `KineticBlockEntity.onSpeedChanged` →
flicker 快速累积 → 超过 128 方块被拆；且与 Create `SequenceContext` 下游转速同步
深度绑定（段内恒定 modifier）。re-aim 不 detach/attach、不改转速方向，零 flicker 风险。

## 关键常量

| 常量 | 值 | 含义 |
|---|---|---|
| `MAX_SERVO_ANGLE` | 180° | 输出轴单圈绝对定位范围 |
| `MAX_SERVO_RPM` | 96 RPM | 输出转速上限（`SimBlockConfigs.maxSwivelBearingSpeed` 同值） |
| `MAX_SEGMENT_ANGLE` | 179° | 单段最大转角（避免 180° 插值二义性） |
| `SEGMENT_SETTLE_TICKS` | 2 | 到位后保留 modifier 的 tick 数 |
| `FLICKER_THRESHOLD` / `FLICKER_COST` | 60 / 10 | flicker 门控阈值 / 每次段开始计入的分数 |

## 应力计算

- 舵机模式：`impact = Config.servoModeStressImpact × |真实输出转速| / 输入转速`，静止时 0。
- 变速器模式：`impact = Config.servoStressImpact × |输出−输入| / 输入转速`。

## 验证

- Lua：`setServoAngle(90)` 转一半时 `setServoAngle(135)` → 输出轴直接继续转过去而非先停 90°。
- 反向场景：转一半改回 30° → 仍先到原目标再回转（保护未破坏）。
- 快速连续 `setServoAngle` → 方块不被拆（flicker 门控生效）。
- 高频输入错误定位场景：目标在 ±180 附近来回摆动时若定位漂移，`setServoReaim(true)` 后应恢复稳定（默认关）。
- 旧存档加载后 `getServoReaim()` 应为 false（无 NBT 键兼容）。
