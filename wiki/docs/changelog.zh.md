# 更新日志

## 2026-08-22 — 屏幕渲染重构（格子模型）

**破坏性变更：**

- 移除 Monitor 的**背景平面绘制 API**（`monitor.write` / `clear` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `setCursorPos` / `setTextScale` / `setTextColour` / `setZIndex` / `setOverflowMode` / `clearRects` / `clearShapes` / `getSize`）。内容只能在**屏幕模块**上绘制。Monitor 的网格线与背景贴图保留不变。
- 屏幕改为**格子模型**（LCD 帧缓冲语义）：`setGrid(cols, rows)` 设定格子数；文本从光标处逐格写入（`setCursorPos`/`write`），写入即覆盖该格，内容体积固定（不再随运行时长增长）。
- 屏幕新 API：`setGrid` / `getGrid`、`fill`、`draw(batch)`（整屏原子替换），`getSize` 改为返回格子数。`setTextScale` 保留为 `setGrid` 的别名（可传可选高宽比参数）；`write` 的 z 参数移除（z 仅图形层使用）。
- 旧存档的自由定位屏幕文本（无 `cols` 字段）加载时清空重置。
- 网络同步改 gzip 压缩（`SyncGridPayload`），缓解 2 MiB 包上限。
- 使用旧自由定位文本 API 的 Lua 程序需要按格子模型改写（见 [屏幕模块](monitor/screen.md)）。

**新渲染方式（参考原版 `SignRenderer`）：**

- 渲染平面 = 屏幕模块外边面；字形用 `RenderType.textPolygonOffset`（polygonOffset 深度区分，不再手动深度偏移）。
- 每格背景 quad（`fill` 填充）、每格字形 quad、自由定位图形层（rect/line/circle）带 z 层级。
