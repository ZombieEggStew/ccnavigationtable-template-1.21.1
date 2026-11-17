# 可变尺寸屏幕模块 — 实现记录

> 2026-08-12 | 已完成

---

## 架构决策（实际落地）

| 决策 | 选择 | 说明 |
|------|------|------|
| 数据模型 | `List<ScreenRegion>` 独立层 | 一个 Monitor 可放多个屏幕，与模块互斥（grid=-2） |
| 9 宫格 | 3 类 JSON 模型 + 代码旋转 | corner / edge / center，Z 轴旋转复用，无需 OBJ |
| 边渲染 | **平铺**（非拉伸） | 循环渲染 N-2 或 M-2 个边 tile，纹理不变形 |
| 放置交互 | 两点选择 | 第一次右键=锚点，第二次右键=确认；同格/不足 2×2 不响应 |
| 最小尺寸 | 2×2 | GridState.SCREEN_MIN_SIZE |
| 屏幕交互 | 配置和拆卸 | 空手悬停高亮；蹲下右键打开配置；扳手右键拆卸 |

---

## 数据模型

### ScreenRegion (GridState 内部 record)

```java
public record ScreenRegion(int minX, int minY, int maxX, int maxY) {
    public int width()  { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
}
```

### GridState 屏幕 API

```java
List<ScreenRegion> screenRegions;           // 多屏幕列表

boolean addScreen(x1, y1, x2, y2);          // 新增（不替换已有）
boolean removeScreenAt(gx, gy);             // 按格子坐标删除所属屏幕
ScreenRegion getScreenAt(gx, gy);           // 按格子查找所属屏幕
void clearAllScreens();                     // 清除全部
boolean canPlaceScreen(minX, minY, maxX, maxY); // 冲突检查（只检查模块）
```

### NBT 格式

```java
// 保存: ListTag "screens" → 每个 { minX, minY, maxX, maxY }
// 加载: 遍历恢复 ScreenRegion + grid 标记
```

---

## 模型设计

### 只需 3 个 JSON 模型（Blockbench 原版格式，非 OBJ）

| 文件 | 用途 | 尺寸 | 朝向 |
|------|------|------|------|
| `screen_corner.json` | 4 角 | 1×1 格 | 右下角（装饰边朝右+下） |
| `screen_edge.json` | 4 边 | 1×1 格 | 右边 |
| `screen_center.json` | 中央面板 | 1×1 格 | 任意 |

### Z 轴旋转映射（`Axis.ZP.rotationDegrees`）

9 宫格所有部件绕各自格子中心 Z 轴旋转，法线安全无光照问题：

**角（corner）**：

| 屏幕位置 | Z 旋转 | 装饰边朝向 |
|---------|--------|-----------|
| 左上 | 0° | ←↑ |
| 右上 | 90° | →↑ |
| 左下 | -90° | ←↓ |
| 右下 | 180° | →↓ |

**边（edge）**：

| 屏幕位置 | Z 旋转 |
|---------|--------|
| 左边 | 0° |
| 右边 | 180° |
| 上边 | 90° |
| 下边 | -90° |

### 平铺逻辑

- 水平边 tile 数 = `screen.width - 2`
- 垂直边 tile 数 = `screen.height - 2`
- 每个 tile 是完整的 1×1 格模型，`renderCorner()` 渲染（复用角渲染的 pivot 旋转逻辑）

---

## 渲染流程（MonitorRenderer）

```java
render(be) {
    // 1. PoseStack 朝向旋转（facing → NORTH 等效）
    // 2. 遍历模块渲染（与原有逻辑相同）
    // 3. 遍历 screenRegions → renderScreen(每个)
}

renderScreen(ps, buffer, scr, light, overlay) {
    cellSize = 1/16, borderSize = cellSize (1 格)
    scrX = (SCREEN_X_MIN + minX) / 16
    scrY = (SCREEN_Y_MIN + minY) / 16
    scrW = width * cellSize, scrH = height * cellSize
    innerW = scrW - 2*borderSize, innerH = scrH - 2*borderSize
    
    // 四角：renderCorner(cellX, cellY, zDegrees)
    // 四边：双层 for 循环平铺 renderCorner
    // 中央：PoseStack.scale(innerW/cellSize, innerH/cellSize, 1)
}

renderCorner(ps, vc, model, cellX, cellY, scrZ, zDegrees) {
    halfCell = 0.5/16
    translate(cellX + halfCell, cellY + halfCell, scrZ)
    mulPose(ZP.rotationDegrees(zDegrees))
    translate(-halfCell, -halfCell, 0)
    renderModel(ps, vc, model)
}
```

---

## 网络协议

| Payload | 方向 | 字段 |
|---------|------|------|
| `PlaceScreenPayload` | C→S | `pos, gridX1, gridY1, gridX2, gridY2` |
| `RemoveScreenPayload` | C→S | `pos, gridX, gridY`（按格子定位要删的屏幕） |
| `SyncGridPayload` | S→C | 不变，ScreenRegion NBT 随 GridState 自动同步 |

---

## 客户端交互（MonitorGridOverlay）

### 状态机

```
IDLE:
  手持 module_screen → 显示网格
  右键 → 记录锚点(screenAnchorX/Y) → PLACING_SCREEN

PLACING_SCREEN:
  实时 Catnip 矩形预览（绿=可放置，红=冲突/不足2×2）
  右键(≥2×2) → PlaceScreenPayload → IDLE
  切换物品/看向其他方块 → IDLE（取消）
  不足 2×2 的右键 → 不响应

扳手 + 右键屏幕格 → RemoveScreenPayload(pos, gx, gy)
```

### 关键字段

```java
screenPlacing, screenAnchorPos, screenAnchorFacing,
screenAnchorX, screenAnchorY, screenLastUseDown  // 边沿触发防连发
```

---

## 文件清单

| 文件 | 操作 |
|------|------|
| `GridState.java` | `ScreenRegion` record + `List<ScreenRegion>` + 多屏幕 API |
| `MonitorBlockEntity.java` | `addScreen()` / `removeScreenAt()` |
| `MonitorRenderer.java` | `renderScreen()` + `renderCorner()` |
| `MonitorPreloadedModels.java` | `SCREEN_CORNER/EDGE/CENTER` 键注册 |
| `MonitorGridOverlay.java` | 两点选择状态机 + 矩形预览 + 扳手拆卸 |
| `PlaceScreenPayload.java` | 新建 |
| `RemoveScreenPayload.java` | 新建（含 gridX/gridY） |
| `CCPeripheraExtender.java` | 注册 2 个新 Payload 处理器 |
| `MyModItems.java` | `MODULE_SCREEN` 物品 |
| `MyModCreativeModeTabs.java` | 创造标签页 |
| `zh_cn.json` / `en_us.json` | 语言条目 |
| `module_screen.json` | 物品栏模型（→ `ccpe:block/screen/screen`） |
| `screen_corner.json` | Blockbench 导出（1×1 格，右下角朝向） |
| `screen_edge.json` | Blockbench 导出（1×1 格，右边朝向） |
| `screen_center.json` | Blockbench 导出（1×1 格） |

---

## 已知限制

- 旧存档（单屏幕 NBT 格式 `scrMinX`）不兼容，旧屏幕会丢失
- 屏幕格子与模块互斥，不能重叠
- 屏幕无普通点击行为；配置通过蹲下右键，拆卸通过扳手右键。
