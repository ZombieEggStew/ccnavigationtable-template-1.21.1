# 屏幕字符 / 矩形渲染 — 实现记录与踩坑

> 2026-08-16 | 已完成第一版
>
> ⚠️ 渲染重构选型（方案一/二/三 + 已排除方案）见 [`screen-render-rework.md`](screen-render-rework.md)，本文档为旧实现记录。

---

## 目标

在「可变尺寸屏幕模块」的表面渲染字符与矩形，由 CC:T Lua 控制：
- 文本：`write` / `clear` / `setCursorPos` / `setTextScale` / `setTextColour` / `setOverflowMode` / `setZIndex`
- 图形：`drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `clearRects` / `clearShapes`
- 层级：`write` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` 可选 z，`setZIndex` 设默认层级
- 行列数随字号自动重算（参考值）：`getSize()`

---

## 方案选型：位图字体（方案 A）

字形源用 vanilla `assets/minecraft/textures/font/ascii.png`（**128×128，16×16 网格，每格 8×8 像素**），
字符 `c` 映射到第 `c` 格（`col = c % 16`，`row = c / 16`），逐字符画 quad（最近邻、无 mipmap），
思路仿 CC:T 的 `FixedWidthFontRenderer`。背景与矩形用纯色 `POSITION_COLOR` quad。

| 对比 | 结果 |
|---|---|
| 原版 `font.drawInBatch`（方案 B） | 小字号发糊、非等宽、无逐格背景，否决 |
| 动态纹理（方案 C） | 复杂，暂不需要 |
| **位图字体（方案 A）** | **选用**：等宽、锐利、省性能、纯 Java quad |

---

## 数据模型（`monitor/ScreenText.java`）

```java
public class ScreenText {
    List<TextChar> chars;          // 每个字符带自己的左上角 (x, y) 与层级 z（drawRect 坐标，1/128 块）
    double cursorX, cursorY;       // 光标（drawRect 坐标，1/128 块，原点 = 内区左上角）
    int textColour;                // 0xRRGGBB（文本无背景色）
    double zIndex;                 // 默认层级，write/drawRect 未显式传 z 时用
    double textScale;              // 字号（MC 像素，1px = 1/16 块）
    OverflowMode overflowMode;     // truncate / ellipsis / wrap（默认 wrap）

    // 嵌套类型
    enum OverflowMode { TRUNCATE, ELLIPSIS, WRAP }
    record TextChar(double x, double y, char ch, double z) {}
    record Rect(double x, double y, double width, double height,
                int colour, boolean solid, double lineWidth, double z) {}
    record Line(double x1, double y1, double x2, double y2,
                int colour, double lineWidth, double z) {}
    record Circle(double cx, double cy, double radius, int colour,
                  boolean solid, double lineWidth, int segments, double z) {}
}
```

- 文本**不再用行列格子**，每个字符按 drawRect 坐标直接定位；`write` 推进量：`字形宽 = scale×8`、`行高 = scale×9.6`（单位 1/128 块）。
- 文本**无背景色**；需要背景由调用方自己 `drawRect`。
- `z` 越大越靠前；每调用可传显式 z，否则用 `zIndex`。深度映射：`glyphDepth = zBase - 0.01px - z*0.01px`，`rectDepth = glyphDepth + 0.005px`。
- `getSize()` 保留为参考值：`cols = floor(innerWpx / scale)`，`rows = floor(innerHpx / (scale * 1.2))`。
- `GridState` 里是 `Map<Integer, ScreenText> screenTexts`（按屏幕 id），与 `ScreenRegion` 平级，
  随 `GridState.save/load` 序列化，**自动随 `SyncGridPayload` 同步，无需新网络包**。
- 屏幕 id 变更（`updateScreen`）时要迁移 `screenTexts` 的 key（与 `trySetId` 迁移配置同理）。

---

## 渲染（`client/ScreenTextRenderer.java` + `MonitorRenderer.java`）

### 字形 quad（`RenderType.text(ascii.png)`）

```java
RenderType.text(FONT_TEXTURE)  // 顶点格式 = POSITION_COLOR_TEX_LIGHTMAP（含 UV2）
vc.addVertex(pose, x0, y0, z).setColor(r,g,b,1f).setUv(uRight, vBottom).setLight(FULL_BRIGHT); // 左下
vc.addVertex(pose, x0, y1, z).setColor(r,g,b,1f).setUv(uRight, vTop)   .setLight(FULL_BRIGHT); // 左上
vc.addVertex(pose, x1, y1, z).setColor(r,g,b,1f).setUv(uLeft,  vTop)   .setLight(FULL_BRIGHT); // 右上
vc.addVertex(pose, x1, y0, z).setColor(r,g,b,1f).setUv(uLeft,  vBottom).setLight(FULL_BRIGHT); // 右下
```

### 纯色 quad（`SOLID_BG`，背景/矩形/描边）

```java
RenderType.create(modid + ":screen_text_bg", DefaultVertexFormat.POSITION_COLOR, QUADS, ...)
    .setShaderState(POSITION_COLOR_SHADER)
    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
    .setCullState(NO_CULL)   // 纯色无纹理，NO_CULL 保证总可见
```

- 顶点顺序统一「左下→左上→右上→右下」（正面朝向玩家）。
- 深度按 z 层级映射：`glyphDepth = zBase - 0.01px - z*0.01px`，`rectDepth = glyphDepth + 0.005px`（同一 z 下文字盖在矩形上）。

---

## 踩过的坑（务必遵守）

1. **`RenderType.text` 顶点格式是 `POSITION_COLOR_TEX_LIGHTMAP`，不是 `POSITION_COLOR_TEX`。**
   每个顶点必须 `.setLight(...)`，否则崩溃 `IllegalStateException: Missing elements in vertex: UV2`。
   屏幕用 `LightTexture.FULL_BRIGHT`（全亮发光）。

2. **「北面局部 X 轴」与「屏幕逻辑 X 轴」相反（镜像根源）。**
   屏幕面朝北时，模型局部 +X 对应玩家的**右**，而逻辑 X=0 应在玩家**左**。
   所以：
   - 文本（每字符坐标 `ch.x`）：`physRight = fullRight - ch.x / 128`，`physLeft = physRight - glyph`（逻辑左 ↔ 物理右）。
   - 字形 UV：水平翻转（左顶点采 `uRight`、右顶点采 `uLeft`）。
   - 矩形：`worldX = fullRight - logicalX / 128`（逻辑左 ↔ 物理右）。
   - Y 轴不翻：`worldY = fullTop - logicalY / 128`（逻辑上 = 物理上）。

3. **环绕顺序（winding）**：用「左下→左上→右上→右下」才是正面朝向玩家（通过 CULL 可见）。
   反了会看到 quad 背面（左右镜像），或直接被背面剔除掉。
   参考：旋钮度数文字 `font.drawInBatch` 经 `-Y` 缩放后，有效环绕顺序正是这一种。

4. **文本没有背景**：只画前景字形；需要底色/背景由调用方自己 `drawRect`（可把 rect 的 z 设低一层）。

5. **溢出模式**：`write` 里当 `cursorX + glyphW > innerW` 时按 `overflowMode` 分支——
   `WRAP` 换行、`TRUNCATE` 直接 return、`ELLIPSIS` 把本行末尾最多 3 个字符替换成 `"."` 后 return。

6. **`clear()` 现在同时清空文本和矩形**（原只清文本）。

---

## 文件清单

| 文件 | 操作 |
|------|------|
| `monitor/ScreenText.java` | 新建：字符缓冲 + `OverflowMode` + `Rect` + NBT |
| `monitor/GridState.java` | `screenTexts` 映射 + 生命周期 + 序列化 |
| `block/MonitorBlockEntity.java` | `screenWrite/clear/setCursor/setTextScale/setTextColour/setZIndex/setOverflowMode/drawRect/clearRects/getScreenSize` |
| `compat/cc/ScreenModuleHandle.java` | Lua API（含 `drawRect`、`drawLine`、`drawCircle`、`drawPoint`、`clearRects`、`clearShapes`、`setOverflowMode`、`setZIndex` 等） |
| `client/ScreenTextRenderer.java` | 新建：字形/纯色 quad 渲染 |
| `block/MonitorRenderer.java` | `renderScreenText` 调用文本 + `drawRects` |

Lua API 完整文档见 `instruction_temp.md` 第 6 节。
