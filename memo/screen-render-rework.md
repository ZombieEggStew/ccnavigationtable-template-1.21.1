# 屏幕渲染重构 — 需求分析与方案决策（存档）

> 2026-08-22 | 决策已定，**未实施**。本文档为选型记录，实施时按「方案一 → 二 → 三」递进，每步独立可用、可随时喊停。

---

## 背景与需求约束（已与作者确认）

- 单人游玩，视野内通常只有 1~2 块小屏幕；屏幕典型规模 **6×6 px（1 px = 1/16 块），一行约 10 字符，每屏 30~60 个元素**。
- 帧率需求低：20fps 足够，1fps 也可接受。
- 刷新模式：**每 tick 局部更新**（时钟、计数、状态灯这类只有少数格子变化）。
- 作者定位：游戏爱好者、会一点编程，**明确不碰图形学/底层 GPU**。
- 可接受的妥协：z 冲突（z-fighting）时**把内容整体前移**，侧面看奇怪可以接受。
- CC:T 是必装前置（`computercraft` 1.120.0）。
- 差异化需求：不与 CC:T monitor 雷同。

### 当前实现（现状快照）

- 数据：`monitor/ScreenText.java` — 自由定位元素列表（`TextChar`/`Rect`/`Line`/`Circle`），每字符带 (x, y, char, z)，**无背景色**，`write` 只追加、被覆盖的旧字符不删除（只有 `clear()` 清空）。
- 同步：`network/SyncGridPayload.java` 全量 NBT（每字符一个 CompoundTag）；`MonitorBlockEntity.monitorDisplayChanged()` → `blockChanged()` 每次 Lua 调用触发一次全量同步。
- 渲染：`client/ScreenTextRenderer.java` 每帧遍历元素生成 quad（MultiBufferSource）；深度用 0.01px/16 级偏移（`GLYPH_FRONT`/`Z_STEP`/`RECT_BACK`）。

### 已知问题（按严重度）

| 问题 | 根因 | 相关文件 |
|---|---|---|
| 2 MiB 断线崩溃 | 每次调用发整份 NBT（每字符一个 CompoundTag），屏内容多时超 2,097,152 字节上限 | `SyncGridPayload.java`、`GridState.save()` |
| 闪烁（clear+write 各 1 tick） | 每次调用一个包，客户端能看到「已清空未写入」的中间态 | `MonitorPeripheral.java` → `MonitorBlockEntity` |
| 数据只增不减 | 覆盖不删除，NBT 体积随运行时长增长 | `ScreenText.write()` |
| z-fighting | 0.01px/16 级深度偏移在斜视角/远处精度不足 | `ScreenTextRenderer.java` |

---

## 方案选型结论

### ✅ 采用：三个递进步骤（都只动同步与数据，不碰渲染底层，Lua API 不变）

#### 方案一：止血（改动最小，数行）
| 改动 | 治什么 |
|---|---|
| `SyncGridPayload` 的 NBT 改 **gzip 压缩**（`NbtIo.writeCompressed` → `writeByteArray`，读侧 `readCompressed`） | 2 MiB 崩溃。注意 `writeByteArray` 也有 ~2 MiB 上限，但压缩后文本类数据远小于此 |
| `ScreenTextRenderer` 的 `GLYPH_FRONT`/`Z_STEP`/`RECT_BACK` 改为 **1/64~1/128 块级固定前移** | z-fighting（作者已接受侧面观感） |
| （Lua 侧可选）不 clear、带背景覆盖的 LCD 写法 | 清屏闪烁（不改 Java 也能缓解） |

#### 方案二：根治闪烁（推荐，性价比最高）
- 同步层重构：**脏标记 + 每 tick 最多合并发一次快照**，客户端原子应用（思路照搬 CC:T `MonitorWatcher`/`ServerMonitor`，见参考来源）。
- 治：闪烁（同 tick 内 clear+write 合一个包 → 无中间态）、每次调用一个包的浪费、带宽。
- 范围：`MonitorBlockEntity`（脏标记 + 队列）、`MonitorPacketHandlers`/`SyncGridPayload`（快照化），**不碰 `ScreenTextRenderer`**。

#### 方案三：数据模型升级（以后有需要再做，当前不需要）
- `ScreenText` 改成「格子 + 每格前景/背景色」，覆盖即替换（LCD 帧缓冲模型）。
- 治：数据只增不减、为将来大屏/特效（辉光等）留路。
- 当前单人 1-2 小屏，数据增长要很久才够着 2 MiB，**先不做**。

### ❌ 明确排除（已排雷，不再考虑）

| 方案 | 排除理由 | 证据来源 |
|---|---|---|
| VBO / FBO(TBO) 渲染缓存 | 本规模（30-60 元素）每帧 vanilla 绘制微秒级，缓存省不出肉眼可见收益；引入 GL 生命周期管理 + shader mod（Iris/Oculus）兼容债 | CC:T 历史：纹理渲染 → TBO → VBO 兜底，作者 2023 认错（见参考来源） |
| Flywheel 实例化渲染 | instancing 适用于「同一网格画 N 份拷贝」（齿轮/传动轴）；屏幕是「N 个不同元素」（不同 UV/颜色），优势用不上；API 只有 .class 无源码 | Create 源码：所有文字类渲染（`NixieTubeRenderer.java:115`、`SmartBlockEntityRenderer.java:56`）都走 vanilla `font.drawInBatch`，Flywheel 只画机械模型 |
| 自定义 shader / TBO | 与 Sodium/Iris 兼容要专门处理，调试成本高 | CC:T Issue #1140 |
| 全量重写渲染管线 | 无需求支撑 | — |

### 差异化（已天然成立，无需额外做）

- 矢量图形（rect/line/circle）+ 自由定位 + z 层级：CC:T monitor 全部做不到。
- 24 位色 + 每字符背景色（方案三落地后）：CC:T 只有 16 色调色板、无背景格。
- 可选加分：用 vanilla `font.drawInBatch` 替代手写字形（简化代码 + 观感与 CC:T 方块像素字拉开），但**不是必须**。

---

## 参考来源（调研记录）

CC:T 源码（工作区 `references/CC-Tweaked-mc-1.21.x/`）：
- `projects/common/src/client/java/dan200/computercraft/client/render/monitor/MonitorBlockEntityRenderer.java`、`MonitorRenderState.java`
- `projects/common/src/main/java/dan200/computercraft/shared/peripheral/monitor/ClientMonitor.java`、`ServerMonitor.java`、`MonitorWatcher.java`、`MonitorBlockEntity.java`
- `projects/core/src/main/java/dan200/computercraft/core/terminal/Terminal.java`
- `projects/common/src/main/java/dan200/computercraft/shared/computer/terminal/TerminalState.java`、`NetworkedTerminal.java`
- `projects/common/src/client/java/dan200/computercraft/client/render/text/FixedWidthFontRenderer.java`

网络资料：
- SquidDev《An optimised monitor renderer》(2020-05)：https://squiddev.cc/2020/05/08/monitors.html
- SquidDev《Monitor rendering: A couple of mea culpas》(2023-03)：https://joncoates.co.uk/2023/03/18/monitors-again.html
- CC:T Wiki《Monitor renderers》：https://github.com/cc-tweaked/CC-Tweaked/wiki/Monitor-renderers
- TBO 渲染器 PR #443：https://github.com/cc-tweaked/CC-Tweaked/commit/70b457ed185a1629a015292d1e2c0c16f4fae1d7
- 「装了 OptiFine 用 VBO」提交：https://git.osmarks.net/mirrors/CC-Tweaked/commit/4bfdb65989b6a427b423b1c76383dea5d4405716
- shader mod 兼容问题：https://github.com/cc-tweaked/CC-Tweaked/issues/1140

Create / Flywheel 证据（工作区）：
- `references/Create-mc1.21.1-dev/.../redstone/nixieTube/NixieTubeRenderer.java`（vanilla font 画字符）
- `references/Create-mc1.21.1-dev/.../foundation/blockEntity/renderer/SmartBlockEntityRenderer.java`（vanilla font 画标签）
- `api/create/flywheel-neoforge-api-1.21.1-1.0.6/`（仅 .class，无源码）
