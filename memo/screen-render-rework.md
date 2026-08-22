# 屏幕渲染重构 — 需求分析与方案决策（存档）

> 2026-08-22 | 决策已定。**方案三（格子模型）已实施**（2026-08-22，含方案一 gzip 顺带落地；方案二未实施），详见文末「实施状态」。
> **方案三（格子模型）已定稿**为最终文本数据模型（2026-08-22 与作者确认），详见下文。
>
> **2026-08-22 review 更新**：方案三代码已逐文件核实（与计划一致）；`./gradlew.bat classes` 编译通过（build/classes 产物 2026-08-22 18:28 存在）；重构当天 18:43-18:45 有一次完整游戏会话（正常加载/进入/退出，日志 0 ERROR、无崩溃），但**日志中无屏幕功能验证痕迹**，「待游戏内验证」8 项仍全部未完成。screen 重构提交 = `d6201bf "big boom"`（14:07）；其后的提交（17:27-18:46 的旋钮/开关 `d01fa52`…`e162ba3`）未触碰 screen 渲染路径。

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

### ✅ 采用：三个递进步骤（只动同步与数据，不碰渲染底层；方案一/二 Lua API 不变，方案三为破坏性变更，已确认接受）

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

#### 方案三：格子模型（✅ 已定稿为最终文本数据模型，计划实施，未开始）

**设计**：
- 屏幕模块由用户设定格子数（新增 `setGrid(cols, rows)`），格子铺满内区；文本层改为「每格 = 字符 + 前景色 + 背景色」，**写入即覆盖该格**（LCD 帧缓冲语义，同位置永远只有一个值，无重叠面片）。
- 定位改为**光标制**：`setCursorPos(col, row)`（1 起，CC:T 风格），`write` 从光标处逐格写入（保留 wrap/truncate/ellipsis）。**取消文本自由定位**（浮点 x,y 仅图形层保留）。
- **填充（进度条）**：新增 `fill(col, row, w, h, colour)` 批量设置格子**背景色**（纯色填充），配合每格背景色实现分段进度条；新增 `setFillPadding(ratio)` 让填充色块每格内缩（LED 分段效果，默认 0）。平滑进度（连续条）用现有自由定位 `drawRect`（浮点坐标任意宽度），无需新 API。
- **整屏批量传输**：新增 `draw(batch)` —— 一次调用传**整屏所有需要绘制的格子**（每格：行列 + 字符 + 前景色 + 背景色，可选图形），**整屏替换语义**（原子 clear + 重建，无中间态）。Lua table 经 `@LuaFunction` 自动转 Java `Map`（CC:T 支持，见 `SpeakerPeripheral.playAudio(LuaTable)` 先例）。与方案二（每 tick 合并快照）互补：程序每 tick 调一次 `draw` → 服务端原子替换 → 每 tick 一个快照 → 客户端一帧显示，全链路无中间态。
- 渲染沿用**原版告示牌模型**：每帧 MultiBufferSource、每字符一 quad（`BakedGlyph.render` 4 顶点）、同图集同 RenderType 一批 draw call；格子坐标 → 世界坐标每帧计算（本规模开销可忽略）；深度用 **`RenderSystem.polygonOffset`**（原版 SignRenderer 的 `DisplayMode.POLYGON_OFFSET` 方案）替代 0.01px/16 手动偏移。
- 图形层（rect/line/circle）**保持自由定位与 z**，不受格子约束，但**仅在 screen 模块可绘制区域内**绘制（差异化保留，CC:T 无矢量图形能力）。

**关键决策（已定）**：
1. **格子数为文本布局唯一依据**：字形尺寸由格子推导（`cellW = 内区宽 / cols`，字形自动贴合格子），`setTextScale` 语义改为「按格子反推字号」（等价于重设格子）。现状 `colsFor/rowsFor` 公式（`scale×8` / `scale×9.6` 单位）恰是"格子==字形"的特例，耦合时行为不变。
2. `setGrid` 重设时**清空文本层**（CC:T resize 语义）。
3. 光标单位改格子坐标（1 起）——**破坏性变更**：旧 Lua 程序文本定位部分需改写（已确认接受）。
4. 每格含背景色（LCD 覆盖刷新成立），颜色沿用 24 位 RGB。`fill()` = 背景色的批量写入（无新数据结构）；`setFillPadding` = 渲染期背景 quad 内缩（进背景批次，字符画在填充色之上，天然支持"色块 + 文字"叠加）。
5. **可绘制区域 = screen 模块（重要范围收窄）**：内容**只能在 screen 模块上绘制**，不在 monitor 背景平面上绘制。因此：
   - **移除 monitor 背景平面绘制通道（仅此一项，已与作者确认）**：`MonitorBlockEntity.monitorDisplayText`（12×10 px 背景平面）及 `MonitorPeripheral` 上的背景平面绘制 API（`write`/`clear`/`drawRect`/`setCursorPos`/`setTextScale`/`setTextColour`/`setZIndex`/`setOverflowMode`/`clearRects`/`clearShapes`）——全部迁移到 screen 模块（`GridState.screenTexts` / `ScreenModuleHandle`）。
   - **不动的部分（已与作者确认）**：Monitor 的**网格线（`MonitorGridOverlay.drawGridLines`）与背景贴图（`MonitorBackground` / `renderBackground`）全部保留**，重构只删除 monitor 背景平面上的「字符绘制与图形绘制逻辑」，不改网格和背景。`GRID_INSET=1`（`MonitorBlock.java`）保留，仅用于放置逻辑，与绘制无关。
6. **screen 模块可绘制区域内缩（screen 模块规格，重要）**：screen 模块可绘制区域每侧内缩 **1/64 块 = 1/4 px = 2 drawRect 单位**（原因：screen 模型边缘缺 1/8px + 贴图边框占 1/8px）。方案三实施时统一为常量 `DRAWABLE_INSET = 1/64`，用于：格子计算（`cellW = 可绘制区宽 / cols`）、`draw(batch)`/`fill` 边界与裁剪、渲染内容边界（`ScreenTextRenderer` 的 left/right/top/bottom）。与决策 5 的 `GRID_INSET` 是两个独立常量。
7. **防 z-fighting 用 `DisplayMode.POLYGON_OFFSET`（参考原版告示牌 `SignRenderer`，已与作者确认）**：**废弃**手动 0.01px/16 深度偏移（`GLYPH_FRONT`/`Z_STEP`/`RECT_BACK`），也**不做手动前移**（`1/64~1/128` 块固定前移仅方案一可选缓解，方案三不采用）。**渲染平面 = screen 模块外边面**（screen 9 宫格模型 north 面，世界坐标 `zBase = (SCREEN_Z + 0.7) / 16`，与模型 `from.z = 0.7` 一致），内容 z 直接取外表面 z，深度区分完全交给 polygonOffset。实现方式：
   - 字形/填充绘制用带 polygonOffset 的 RenderType（实施中为 `RenderType.textPolygonOffset(ascii.png)`，其 `POLYGON_OFFSET_LAYERING` state shard 在 RenderType 切换时自动 `polygonOffset(-1,-10)` + enable/disable，适配 MultiBufferSource 延迟批处理）；
   - 背景格 / 图形层（纯色 quad）与字形不同平面时用固定深度差（图形层 `zBase - z/128`，z 越大越靠前），与字形同平面部分由 polygonOffset 区分。

**数据与同步影响**：
- 文本层 → 定长格子数组（char[] + 前景/背景色），**体积固定、不再增长**（根治"数据只增不减"）。
- 配合方案一（gzip）+ 方案二（每 tick 合并快照）→ 2 MiB 崩溃、闪烁、数据增长全部根治。
- `SyncGridPayload` 编码可进一步紧凑化（定长结构体，弃逐字符 CompoundTag）。

**API 变更清单（破坏性，实施前需确认）**：

| 变更 | 旧 | 新 |
|---|---|---|
| 新增 `setGrid(cols, rows)` / `getGrid()` | — | 用户先设定格子数 |
| 新增 `fill(col, row, w, h, colour)` | — | 批量设置格子背景色（分段进度条） |
| 新增 `setFillPadding(ratio)` | — | 填充色块每格内缩比例（0~0.5，默认 0） |
| 新增 `draw(batch)` | — | 整屏一次传输（格子+可选图形），整屏替换语义 |
| **移除背景平面绘制 API**（`MonitorPeripheral` 的 `write`/`clear`/`drawRect`/`setCursorPos`/`setTextScale`/`setTextColour`/`setZIndex`/`setOverflowMode`/`clearRects`/`clearShapes`） | monitor 背景平面（12×10 px） | **迁移到 screen 模块**（`ScreenModuleHandle`），monitor 背景不再可绘制 |
| `setCursorPos(x, y)` | 像素/drawRect 单位 | 格子坐标（1 起） |
| `write(text, z)` | 追加自由定位字符 | 光标处逐格写入覆盖 |
| `setTextScale` | 独立字号 | 按格子反推（或移除） |
| `drawRect/Line/Circle` | 自由定位 | **不变**（图形层） |

### ❌ 明确排除（已排雷，不再考虑）

| 方案 | 排除理由 | 证据来源 |
|---|---|---|
| VBO / FBO(TBO) 渲染缓存 | 本规模（30-60 元素）每帧 vanilla 绘制微秒级，缓存省不出肉眼可见收益；引入 GL 生命周期管理 + shader mod（Iris/Oculus）兼容债 | CC:T 历史：纹理渲染 → TBO → VBO 兜底，作者 2023 认错（见参考来源） |
| Flywheel 实例化渲染 | instancing 适用于「同一网格画 N 份拷贝」（齿轮/传动轴）；屏幕是「N 个不同元素」（不同 UV/颜色），优势用不上；API 只有 .class 无源码 | Create 源码：所有文字类渲染（`NixieTubeRenderer.java:115`、`SmartBlockEntityRenderer.java:56`）都走 vanilla `font.drawInBatch`，Flywheel 只画机械模型 |
| 自定义 shader / TBO | 与 Sodium/Iris 兼容要专门处理，调试成本高 | CC:T Issue #1140 |
| 全量重写渲染管线 | 无需求支撑 | — |

### 差异化（已天然成立，无需额外做）

- 矢量图形（rect/line/circle）+ 自由定位 + z 层级：CC:T monitor 全部做不到。
- 24 位色 + 每格背景色（方案三）：CC:T 只有 16 色调色板、无背景格。
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

原版告示牌渲染（方案三的渲染参考）：
- `net.minecraft.client.renderer.blockentity.SignRenderer`（1.21.x；1.21.4+ 为 `AbstractSignRenderer`）：每帧 `font.drawInBatch` + `DisplayMode.POLYGON_OFFSET` 防 z-fighting。javadoc：https://aldak0.ru/javadoc/1.21.4-21.4.x/net/minecraft/client/renderer/blockentity/AbstractSignRenderer.html
- 字形 = 每字符一 quad（`BakedGlyph.render` 4 顶点），同图集同 RenderType 一批 draw call，无几何合并。javadoc：https://aldak0.ru/javadoc/1.21.4-21.4.x/net/minecraft/client/gui/font/glyphs/BakedGlyph.html

Iris / Sodium 兼容调研（FBO 门控依据，`references/Iris-1.21.1/`、`references/sodium-1.21.1-stable/`）：
- `net.irisshaders.iris.api.v0.IrisApi`：`isShaderPackInUse()`（官方兼容开关）、`isRenderingShadowPass()`、`getMinorApiRevision()`。FBO 方案可据此「开光影包 → 回退普通渲染」。
- Iris 帧缓冲自管（`net.irisshaders.iris.targets.RenderTargets` 等），**不碰 mod 自建的 vanilla `RenderTarget`**；Sodium 保留 vanilla `MultiBufferSource`（`SodiumWorldRenderer.java:314`），方块实体渲染路径原样可用。

Create / Flywheel 证据（工作区）：
- `references/Create-mc1.21.1-dev/.../redstone/nixieTube/NixieTubeRenderer.java`（vanilla font 画字符）
- `references/Create-mc1.21.1-dev/.../foundation/blockEntity/renderer/SmartBlockEntityRenderer.java`（vanilla font 画标签）
- `api/create/flywheel-neoforge-api-1.21.1-1.0.6/`（仅 .class，无源码）

---

## 实施状态（2026-08-22 更新：代码已核实）

**方案三（格子模型）已实施并提交（git `d6201bf "big boom"`，2026-08-22 14:07），`./gradlew.bat classes` 编译通过；待游戏内验证（8 项验证清单均未完成）。**

> 2026-08-22 review 复核：以下改动与源码逐项核对一致（工作区干净、无未提交改动）。screen 重构提交之后至当日 18:46 的提交（`d01fa52`/`79615f8`/`0677e43`/`58043c9`/`e162ba3`）均为旋钮（knob）/开关（toggle）相关工作，未触碰 screen 渲染路径（`MonitorRenderer` 的改动只涉及 `renderKnobAngle` 角度文字显示，`ScreenTextRenderer`/`renderScreenText`/`screen*` 方法未变）。

### 已落地的改动

| 文件 | 改动 |
|---|---|
| `monitor/ScreenText.java` | 重写为格子模型：定长 `char[] cells` + `int[] fg` + `int[] bg`（LCD 帧缓冲语义，写入即覆盖，体积固定）；光标制 `setCursorPos(col,row)` 1 起；`write(text)` 覆盖字符+前景色、背景色不变；`fill(col,row,w,h,colour)` 批量设置背景色；`replaceAll` 整屏原子替换（draw 用）；`setGrid` 重设清空；`setTextScale(scale, innerWpx, innerHpx)` 保留为 setGrid 别名（按格子反推字号，可配格子高宽比）；图形层 rect/line/circle 保持自由定位与 z；NBT 用 int[] 定长数组紧凑编码（`cols/rows/cells/fg/bg`）。~~`setFillPadding`~~ 已删除 |
| `monitor/GridState.java` | 无结构改动（`screenTexts` 接口不变，NBT 序列化经 `ScreenText.save/load` 自动适配） |
| `compat/cc/ScreenModuleHandle.java` | Lua API：新增 `setGrid/getGrid`、`fill`、`draw(batch)`（cells+shapes 两段式，LuaTable 解析，原子替换）；`setCursorPos` 改格子坐标；`write` 去 z 参数；`setTextScale` 保留为别名（可传可选高宽比）；`getSize/getTextScale` 返回格子数；图形层 API 不变。~~`setFillPadding/getFillPadding`~~ 已删除 |
| `compat/cc/MonitorPeripheral.java` | 删除 monitor 背景平面绘制 API（write/clear/setCursorPos/setTextScale/setTextColour/setZIndex/setOverflowMode/drawRect/clearRects/drawLine/drawCircle/drawPoint/clearShapes/getSize）——内容只能在 screen 模块上绘制 |
| `block/MonitorBlockEntity.java` | 删除 `monitorDisplayText` 字段与全部 `monitorDisplay*` 方法、`getUpdateTag/save/load` 中的 `MonitorDisplayText`；`screen*` 方法改格子模型（screenWrite/screenSetCursor/screenSetTextScale/screenSetGrid/screenFill/screenDraw/screenDrawRect 等）。~~`screenSetFillPadding`~~ 已删除 |
| `block/MonitorRenderer.java` | 删除 `renderMonitorDisplay`（monitor 背景平面渲染）；`renderScreenText` 用 `DRAWABLE_INSET` 计算可绘制区，调用新 `ScreenTextRenderer.drawAll` 签名 |
| `client/ScreenTextRenderer.java` | 重写：背景格 quad（fill 填充）+ 字形 quad（`RenderType.textPolygonOffset(ascii.png)`，polygonOffset 防 z-fighting）+ 图形层 quad；**渲染平面 = screen 模块外边面**（`zBase = (SCREEN_Z+0.7)/16`，与 9 宫格模型 north 面 z=0.7 一致），内容仅极小贴面前移（1/2048 块级，量级对齐原版 `TEXT_OFFSET`）；格子坐标每帧计算（本规模开销可忽略）。~~fillPadding 内缩~~ 已删除 |
| `network/SyncGridPayload.java` | NBT 改 gzip 压缩（`NbtIo.writeCompressed/readCompressed` + `writeByteArray`），顺带落地方案一（2 MiB 崩溃缓解） |

### 与计划文档的差异

- **方案二（脏标记 + 每 tick 合并快照）未实施**：用户选择直接跳阶段 3。闪烁问题由 `draw(batch)` 整屏原子替换语义部分缓解（服务端一次替换 → 一次同步 → 客户端一帧显示）；若后续仍要根治「每次 Lua 调用一个包」，需补方案二。
- **`setFillPadding` 已删除（2026-08-22 用户要求）**：`fill` 保留（纯色背景格，铺满整格），`setFillPadding/getFillPadding` 及渲染期内缩逻辑全部移除（`ScreenText` 字段/NBT、`ScreenModuleHandle` 两个 Lua 方法、`MonitorBlockEntity.screenSetFillPadding`、`ScreenTextRenderer` 背景格 pad）。方案三设计文档中的「LED 内缩」描述保留为历史存档，不再实施。
- **只删 monitor 的字符/图形绘制，网格与背景保留（作者确认）**：`MonitorGridOverlay` 的网格线/模块边框/放置预览、`MonitorBackground` 背景贴图/`renderBackground` 均保留未动；仅删除 monitor 背景平面上的绘制通道（`monitorDisplay*`）。
- **渲染平面贴外边面（作者确认）**：screen 内容直接绘制在 screen 模块外边面（9 宫格模型 north 面）上，不做 1/64~1/128 块级手动前移；z-fighting 完全按原版 `SignRenderer` 方案用 `textPolygonOffset`（polygonOffset(-1,-10)）区分深度。
- **旧存档屏幕文本不迁移**：旧自由定位格式（无 `cols` 字段）加载时清空重置（破坏性变更，已确认接受）。
- `setTextScale` 保留为 setGrid 别名（旧 Lua 程序调用不报错，语义变为重设格子数）；`write` 的 z 参数移除（格子无层级，z 仅图形层用）。

### 待游戏内验证

> **2026-08-22 review**：`run/logs` 显示重构当天 18:43:54-18:45:56 有一次游戏会话（mod 正常加载、进入世界、正常退出，debug 日志 0 条 ERROR、无 screen 相关崩溃）；19:29 另有一次启动但仅 4 行日志即终止（疑手动关闭）。**两次会话均无屏幕渲染功能验证痕迹**（无 Lua 测试输出、无 screen 相关渲染日志），以下清单仍未完成，需按项实测：

1. `setGrid` 后格子文本渲染位置/大小正确（含 6×6px 小屏）。
2. `write` 覆盖语义：同格重写无残留、wrap/truncate/ellipsis 行为正确。
3. `draw(batch)` 整屏替换：无 clear+write 中间态闪烁。
4. `fill` 分段进度条（纯色背景格）。
5. 图形层 drawRect/drawLine/drawCircle 自由定位 + z 层级正常。
6. z-fighting：斜视角/远处无闪烁（polygonOffset 生效）。
7. 多屏幕并存、屏幕文本 NBT 持久化（重启世界后内容保留）。
8. 旧 Lua 程序按新 API 改写后行为正确。

> 建议验证方式：写一个 Lua 测试程序依次覆盖 1-6（`setGrid` → `write` 覆盖 → `draw(batch)` → `fill` → 图形层 → 斜视角观察），完成后勾选以上条目并回填本 memo 与 `.TO DO.md`。

### 调试辅助：格子网格线（2026-08-22）

- `ScreenTextRenderer.DEBUG_DRAW_GRID = true` 时，所有屏幕画**半透明品红格子边缘**（cols+1 竖线 + rows+1 横线，含外框，画在最上层），用于肉眼核对格子布局/定位/镜像。
- 涉及文件：`client/ScreenTextRenderer.java`（新增 `drawGridLines` + `colorQuad` alpha 重载 + `GRID_LINE_*` 常量）、`block/MonitorRenderer.java`（`renderScreen` 空屏也渲染的条件，`text.hasContent() || DEBUG_DRAW_GRID`）。
- **验证完成后把 `DEBUG_DRAW_GRID` 改回 `false`**（当前默认 true）。

### 格子高宽比可配置（2026-08-22）

- `setTextScale(scale, lineSpacing?)` 第二个可选参数 = **格子高/格子宽比**（行距系数），默认 `ScreenText.LINE_SPACING = 1.2`；传 `1.0` 得正方形格子，旧调用（单参数）行为不变。
- 链路：`ScreenModuleHandle.setTextScale(scale, Optional<Double>)` → `MonitorBlockEntity.screenSetTextScale(id, scale, @Nullable Double)` → `ScreenText.setTextScale(scale, lineSpacing, innerWpx, innerHpx)` → `rowsFor(innerHeightPx, scale, lineSpacing)`（`colsFor` 不变，格子宽 = 字号）。
