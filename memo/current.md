# 可变尺寸屏幕模块 — 实现方案

> 2026-08-12 | 设计阶段

---

## 架构决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 数据模型 | **思路 B**：屏幕作为独立 GridState 层（`ScreenRegion`） | 不与现有固定尺寸模块耦合 |
| 9 宫格实现 | **Blockbench 预切 3 块 OBJ** + 代码 PoseStack 缩放 | 匹配现有烘焙管线，3 块优于 9 块 |
| 渲染方案 | MonitorRenderer 中分支渲染，PoseStack.scale() 控制拉伸 | 复用现有 BER 架构 |
| 放置交互 | 两点选择：锚点 → 目标格 → 确认 | 同格 = 1×1 屏幕（不取消） |

---

## 一、数据模型

### 1.1 GridState 新增

```java
// GridState.java

/** 屏幕区域，null 表示没有屏幕 */
@Nullable
private ScreenRegion screenRegion;

/**
 * 屏幕矩形。min 为左上角（较小坐标），max 为右下角（较大坐标）。
 */
public record ScreenRegion(int minX, int minY, int maxX, int maxY) {
    public int width()  { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
}

// 访问器
@Nullable public ScreenRegion getScreenRegion() { return screenRegion; }
public boolean hasScreen() { return screenRegion != null; }
```

### 1.2 setScreen / removeScreen

```java
/**
 * 放置或更新屏幕。参数顺序任意（内部归一化）。
 * @return true 成功，false 失败（与已有模块冲突）
 */
public boolean setScreen(int x1, int y1, int x2, int y2) {
    // 1. 归一化：min = 左上，max = 右下
    int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
    int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
    
    // 2. 边界检查
    if (minX < 0 || maxX >= GRID_WIDTH || minY < 0 || maxY >= GRID_HEIGHT) return false;
    
    // 3. 冲突检查：矩形内不能有已安装模块
    for (int x = minX; x <= maxX; x++)
        for (int y = minY; y <= maxY; y++)
            if (grid[x][y] >= 0) return false;  // 被其他模块占用
    
    // 4. 先清除旧屏幕的格子标记
    clearScreenCells();
    
    // 5. 标记格子为屏幕占用（-2 或独立标记）
    screenRegion = new ScreenRegion(minX, minY, maxX, maxY);
    for (int x = minX; x <= maxX; x++)
        for (int y = minY; y <= maxY; y++)
            grid[x][y] = SCREEN_CELL_MARKER;  // = -2
    
    return true;
}

/** 移除屏幕，清除格子标记 */
public void removeScreen() {
    clearScreenCells();
    screenRegion = null;
}

private void clearScreenCells() {
    if (screenRegion == null) return;
    for (int x = screenRegion.minX(); x <= screenRegion.maxX(); x++)
        for (int y = screenRegion.minY(); y <= screenRegion.maxY(); y++)
            if (grid[x][y] == SCREEN_CELL_MARKER)
                grid[x][y] = -1;
}

// 常量
public static final int SCREEN_CELL_MARKER = -2;
```

### 1.3 canPlace 扩展

```java
// 现有 canPlace 方法需要加上屏幕检查
public boolean canPlace(int x, int y, int w, int h) {
    for (int dx = 0; dx < w; dx++)
        for (int dy = 0; dy < h; dy++) {
            int cell = getCell(x + dx, y + dy);
            if (cell >= 0 || cell == SCREEN_CELL_MARKER) return false;  // 屏幕占用也不可放置
        }
    return true;
}
```

### 1.4 NBT 序列化

```java
// save()
if (screenRegion != null) {
    tag.putInt("scrMinX", screenRegion.minX());
    tag.putInt("scrMinY", screenRegion.minY());
    tag.putInt("scrMaxX", screenRegion.maxX());
    tag.putInt("scrMaxY", screenRegion.maxY());
}

// load()
if (tag.contains("scrMinX")) {
    int minX = tag.getInt("scrMinX");
    int minY = tag.getInt("scrMinY");
    int maxX = tag.getInt("scrMaxX");
    int maxY = tag.getInt("scrMaxY");
    screenRegion = new ScreenRegion(minX, minY, maxX, maxY);
    // 恢复格子标记
    for (int x = minX; x <= maxX; x++)
        for (int y = minY; y <= maxY; y++)
            grid[x][y] = SCREEN_CELL_MARKER;
}
```

---

## 二、物品注册

### 2.1 物品

```java
// MyModItems.java
public static final DeferredItem<Item> MODULE_SCREEN = 
    MyItems.register("module_screen", () -> new Item(new Item.Properties()));
```

### 2.2 创造标签页

```java
// MyModCreativeModeTabs.java
output.accept(MyModItems.MODULE_SCREEN);
```

### 2.3 物品栏模型（先用简单 flat，后续可用 CustomRenderedItemModel）

```json
// assets/ccpe/models/item/module_screen.json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "ccpe:item/module_screen"
  }
}
```

### 2.4 语言文件

```json
// zh_cn.json
"item.ccpe.module_screen": "屏幕模块",

// en_us.json
"item.ccpe.module_screen": "Screen Module",
```

---

## 三、客户端放置交互（两点选择）

### 3.1 MonitorGridOverlay 状态机

```java
// 新增字段
private boolean screenPlacing = false;     // 是否在放置屏幕模式
private int screenAnchorX = -1;            // 第一个锚点 X
private int screenAnchorY = -1;            // 第一个锚点 Y

// 在 onClientTick 或 onRenderLevel 中处理
```

### 3.2 状态转换

```
IDLE:
  手持 module_screen 物品 → 右键点击格子
    → screenAnchorX/Y = 当前格坐标
    → screenPlacing = true
    → 不发送网络包，等待第二个点

PLACING_SCREEN:
  每帧：根据当前准心格子和锚点画 Catnip 矩形预览
  右键点击格子 B:
    → 计算矩形，发送 PlaceScreenPayload(pos, anchorX, anchorY, currentX, currentY)
    → screenPlacing = false
    → 消耗物品

  取消条件（回到 IDLE）:
    - 当前手持物品不再是 module_screen（切换物品）
    - 玩家走太远（超出交互距离）
    - 按 Q 丢弃物品

  右键与锚点相同格子：
    → 不取消，发送 1×1 屏幕
```

### 3.3 矩形预览

```java
// 在 onRenderLevel 中
if (screenPlacing) {
    int[] gp = MonitorBlock.rayToGrid(pos, facing, cameraPos, lookVec);
    if (gp != null) {
        int minX = Math.min(screenAnchorX, gp[0]);
        int maxX = Math.max(screenAnchorX, gp[0]);
        int minY = Math.min(screenAnchorY, gp[1]);
        int maxY = Math.max(screenAnchorY, gp[1]);
        
        // 检查是否可放置
        MonitorBlockEntity be = getBE(pos);
        boolean canPlace = be != null && be.getGridState().canPlaceScreen(minX, minY, maxX, maxY);
        
        // 用 Catnip 画矩形预览
        AABB worldBox = gridRectToWorldAABB(minX, minY, maxX, maxY, pos, facing);
        Outliner.getInstance().showAABB("screen_preview", worldBox)
            .colored(canPlace ? 0x4CDA64 : 0xFF5E5E)
            .lineWidth(1 / 16f);
    }
}
```

---

## 四、网络协议

### 4.1 PlaceScreenPayload (C→S)

```java
public record PlaceScreenPayload(
    BlockPos pos,
    int gridX1, int gridY1,
    int gridX2, int gridY2
) implements CustomPacketPayload {
    
    public static final Type<PlaceScreenPayload> TYPE = 
        new Type<>(ResourceLocation.fromNamespaceAndPath("ccpe", "place_screen"));
    
    public static final StreamCodec<FriendlyByteBuf, PlaceScreenPayload> STREAM_CODEC = 
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaceScreenPayload::pos,
            ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridX1,
            ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridY1,
            ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridX2,
            ByteBufCodecs.VAR_INT, PlaceScreenPayload::gridY2,
            PlaceScreenPayload::new
        );
}
```

### 4.2 RemoveScreenPayload (C→S)

```java
public record RemoveScreenPayload(BlockPos pos) 
    implements CustomPacketPayload {
    
    public static final Type<RemoveScreenPayload> TYPE = 
        new Type<>(ResourceLocation.fromNamespaceAndPath("ccpe", "remove_screen"));
    
    public static final StreamCodec<FriendlyByteBuf, RemoveScreenPayload> STREAM_CODEC = 
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoveScreenPayload::pos,
            RemoveScreenPayload::new
        );
}
```

### 4.3 SyncGridPayload

无需改结构——`ScreenRegion` 的 NBT 已包含在 `GridState.save()` 中，随 `SyncGridPayload.gridTag` 自动同步。

---

## 五、服务端处理

### 5.1 MonitorBlockEntity 新增方法

```java
public boolean setScreen(int x1, int y1, int x2, int y2) {
    if (gridState.setScreen(x1, y1, x2, y2)) {
        setChanged();
        syncGridToClients();
        return true;
    }
    return false;
}

public void removeScreen() {
    gridState.removeScreen();
    setChanged();
    syncGridToClients();
}
```

### 5.2 CCPeripheraExtender 注册处理

```java
// RegisterPayloadHandlersEvent
registrar.playToServer(PlaceScreenPayload.TYPE, PlaceScreenPayload.STREAM_CODEC,
    (payload, ctx) -> {
        Player player = ctx.player();
        Level level = player.level();
        BlockEntity be = level.getBlockEntity(payload.pos());
        if (be instanceof MonitorBlockEntity monitorBE) {
            boolean ok = monitorBE.setScreen(
                payload.gridX1(), payload.gridY1(),
                payload.gridX2(), payload.gridY2()
            );
            if (ok && !player.isCreative()) {
                player.getMainHandItem().shrink(1);
            }
        }
    }
);

registrar.playToServer(RemoveScreenPayload.TYPE, RemoveScreenPayload.STREAM_CODEC,
    (payload, ctx) -> {
        Player player = ctx.player();
        BlockEntity be = player.level().getBlockEntity(payload.pos());
        if (be instanceof MonitorBlockEntity monitorBE) {
            monitorBE.removeScreen();
            // TODO: 归还屏幕物品
        }
    }
);
```

### 5.3 扳手拆卸

```java
// MonitorBlock.useItemOn() 或 MonitorGridOverlay 中
// 手持 create:wrench，准心指向屏幕区域（grid[gx][gy] == SCREEN_CELL_MARKER）
// → PacketDistributor.sendToServer(new RemoveScreenPayload(pos))
```

---

## 六、Blockbench 建模（3 块 OBJ）

### 6.1 模型拆分

```
┌──────────┬──────────────┬──────────┐
│  corner  │    edge_h    │  corner  │  ← corner.obj — 角装饰（固定尺寸）
│  固定    │   水平拉伸    │  固定    │
├──────────┼──────────────┼──────────┤
│  edge_v  │   center     │  edge_v  │  ← edge_v.obj — 垂直边框（Y 拉伸）
│  垂直拉伸 │   双向拉伸    │  垂直拉伸 │
├──────────┼──────────────┼──────────┤
│  corner  │    edge_h    │  corner  │  ← edge_h.obj — 水平边框（X 拉伸）
└──────────┴──────────────┴──────────┘
                                     ← center.obj — 屏幕面板（XY 拉伸）
```

### 6.2 模型尺寸约定

以 1 个 grid cell = 1/16 方块为基准：

| 部件 | 模型空间尺寸 | OBJ 文件名 |
|------|-------------|------------|
| 角 (corner) | 边框宽度 × 边框宽度 | `screen_corner.obj` |
| 水平边 (edge_h) | 1 格宽 × 边框宽度 | `screen_edge_h.obj` |
| 垂直边 (edge_v) | 边框宽度 × 1 格高 | `screen_edge_v.obj` |
| 中央 (center) | 1×1 格 | `screen_center.obj` |

> 边框宽度建议 2px（即 2/16 方块 = 0.125），与屏幕外框风格统一。

### 6.3 Blockbench 导出设置

- Forward: **Y**, Up: **Z**
- ☑ Write Materials, ☑ UVs, ☑ Triangulate Faces
- 每个 OBJ 的 pivot 在自身左上角（便于代码 translate 到正确位置）
- 真实尺寸建模（1m = 16px）

### 6.4 文件结构

```
assets/ccpe/models/block/screen/
├── screen_corner.json     ← OBJ loader JSON
├── screen_corner.obj
├── screen_corner.mtl
├── screen_edge_h.json
├── screen_edge_h.obj
├── screen_edge_h.mtl
├── screen_edge_v.json
├── screen_edge_v.obj
├── screen_edge_v.mtl
├── screen_center.json
├── screen_center.obj
└── screen_center.mtl

assets/ccpe/textures/block/
└── screen_tex.png          ← 屏幕纹理（含边框和屏幕面）
```

### 6.5 OBJ Loader JSON

```json
{
    "parent": "block/block",
    "loader": "neoforge:obj",
    "flip_v": true,
    "model": "ccpe:models/block/screen/screen_corner.obj",
    "textures": {
        "0": "ccpe:block/screen_tex",
        "particle": "#0"
    }
}
```

---

## 七、MonitorPreloadedModels 烘焙注册

```java
// MonitorPreloadedModels.java

// 键名常量（使用 EXTRA_LOC 因为不是 ModuleType 关联的 MAIN 模型）
public static final String SCREEN_CORNER = "screen_corner";
public static final String SCREEN_EDGE_H = "screen_edge_h";
public static final String SCREEN_EDGE_V = "screen_edge_v";
public static final String SCREEN_CENTER = "screen_center";

// 静态注册（放在现有 EXTRA_LOC.put 附近）
EXTRA_LOC.put(SCREEN_CORNER, rl("block/screen/screen_corner"));
EXTRA_LOC.put(SCREEN_EDGE_H, rl("block/screen/screen_edge_h"));
EXTRA_LOC.put(SCREEN_EDGE_V, rl("block/screen/screen_edge_v"));
EXTRA_LOC.put(SCREEN_CENTER, rl("block/screen/screen_center"));

// 获取方法（复用现有 getExtra）
public static BakedModel getExtra(String key) { return EXTRA_MODELS.get(key); }
```

---

## 八、MonitorRenderer 屏幕渲染

### 8.1 渲染入口

```java
// MonitorRenderer.render() 中，在现有模块渲染循环之后新增：

GridState.ScreenRegion screen = grid.getScreenRegion();
if (screen != null) {
    renderScreen(poseStack, buffer, screen, light, overlay);
}
```

### 8.2 9 宫格渲染逻辑

```java
private void renderScreen(PoseStack ps, MultiBufferSource buffer,
                          GridState.ScreenRegion scr, int light, int overlay) {
    BakedModel corner  = MonitorPreloadedModels.getExtra(SCREEN_CORNER);
    BakedModel edgeH   = MonitorPreloadedModels.getExtra(SCREEN_EDGE_H);
    BakedModel edgeV   = MonitorPreloadedModels.getExtra(SCREEN_EDGE_V);
    BakedModel center  = MonitorPreloadedModels.getExtra(SCREEN_CENTER);
    
    VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());
    
    // 网格坐标 → 模型空间坐标 (0-1 范围)
    float cellSize = 1f / 16f;                    // 每格宽度
    float borderSize = 2f / 16f;                  // 边框宽度（与角/边模型一致）
    
    float scrX = (SCREEN_X_MIN + scr.minX()) / 16f;
    float scrY = (SCREEN_Y_MIN + scr.minY()) / 16f;
    float scrW = scr.width()  * cellSize;         // 屏幕总宽
    float scrH = scr.height() * cellSize;         // 屏幕总高
    float scrZ = SCREEN_Z / 16f;
    
    // 可拉伸区域的尺寸
    float innerW = scrW - 2 * borderSize;
    float innerH = scrH - 2 * borderSize;
    
    // ── 四个角（固定尺寸）──
    // 左上角
    ps.pushPose();
    ps.translate(scrX, scrY, scrZ);
    renderModel(ps, vc, corner, light, overlay);
    ps.popPose();
    
    // 右上角（镜像 X）
    ps.pushPose();
    ps.translate(scrX + scrW - borderSize, scrY, scrZ);
    ps.scale(-1, 1, 1);  // 镜像翻转
    renderModel(ps, vc, corner, light, overlay);
    ps.popPose();
    
    // 左下角
    ps.pushPose();
    ps.translate(scrX, scrY + scrH - borderSize, scrZ);
    renderModel(ps, vc, corner, light, overlay);  // 或做 Y 翻转
    ps.popPose();
    
    // 右下角
    ps.pushPose();
    ps.translate(scrX + scrW - borderSize, scrY + scrH - borderSize, scrZ);
    ps.scale(-1, 1, 1);
    renderModel(ps, vc, corner, light, overlay);
    ps.popPose();
    
    // ── 水平边（X 拉伸）──
    if (innerW > 0) {
        // 上边
        ps.pushPose();
        ps.translate(scrX + borderSize, scrY, scrZ);
        ps.scale(innerW / borderSize, 1, 1);  // 拉伸比例
        renderModel(ps, vc, edgeH, light, overlay);
        ps.popPose();
        
        // 下边
        ps.pushPose();
        ps.translate(scrX + borderSize, scrY + scrH - borderSize, scrZ);
        ps.scale(innerW / borderSize, 1, 1);
        renderModel(ps, vc, edgeH, light, overlay);
        ps.popPose();
    }
    
    // ── 垂直边（Y 拉伸）──
    if (innerH > 0) {
        // 左边
        ps.pushPose();
        ps.translate(scrX, scrY + borderSize, scrZ);
        ps.scale(1, innerH / borderSize, 1);
        renderModel(ps, vc, edgeV, light, overlay);
        ps.popPose();
        
        // 右边
        ps.pushPose();
        ps.translate(scrX + scrW - borderSize, scrY + borderSize, scrZ);
        ps.scale(1, innerH / borderSize, 1);
        renderModel(ps, vc, edgeV, light, overlay);
        ps.popPose();
    }
    
    // ── 中央面板（XY 双向拉伸）──
    if (innerW > 0 && innerH > 0) {
        ps.pushPose();
        ps.translate(scrX + borderSize, scrY + borderSize, scrZ);
        ps.scale(innerW / cellSize, innerH / cellSize, 1);
        renderModel(ps, vc, center, light, overlay);
        ps.popPose();
    }
}
```

### 8.3 1×1 特殊情况

当 `scr.width() == 1 && scr.height() == 1` 时，`innerW` 和 `innerH` 可能 ≤ 0（取决于边框宽度 vs 格子大小），此时只渲染角（或只渲染角拼起来的整体），不渲染边和中央。

### 8.4 注意事项

- `ps.scale(-1, 1, 1)` 会让面法线翻转，可能导致光照异常。如果遇到，改用单独的"右上角/右下角"OBJ（旋转 90°/180° 导出），避免运行时镜像。
- 拉伸后的模型纹理也会被拉伸，确保纹理在拉伸方向上设计为平铺或纯色。
- `SCREEN_Z` 深度需要根据边框模型的厚度微调。

---

## 九、GridState 冲突检查补充

```java
// 新增：检查矩形是否可放置屏幕
public boolean canPlaceScreen(int minX, int minY, int maxX, int maxY) {
    for (int x = minX; x <= maxX; x++)
        for (int y = minY; y <= maxY; y++)
            if (getCell(x, y) >= 0) return false;  // 已有模块占用
    return true;
}

// 同时修改现有的 canPlace(int x, int y, int w, int h)，增加屏幕检查：
// if (getCell(x+dx, y+dy) == SCREEN_CELL_MARKER) return false;
```

---

## 十、实现步骤总览

| Step | 内容 | 涉及文件 | 预计时间 |
|------|------|---------|---------|
| 1 | GridState 数据模型 | `GridState.java` | 30 min |
| 2 | 物品注册 + 语言文件 | `MyModItems.java`, `MyModCreativeModeTabs.java`, zh_cn/en_us.json | 15 min |
| 3 | 客户端两点选择交互 | `MonitorGridOverlay.java` | 1 h |
| 4 | 网络协议 (Payload ×2) | `PlaceScreenPayload.java`, `RemoveScreenPayload.java`, `CCPeripheraExtender.java` | 20 min |
| 5 | 服务端放置处理 | `MonitorBlockEntity.java` | 20 min |
| 6 | Blockbench 建模 (3 OBJ) | `assets/ccpe/models/block/screen/` | 建模时间不定 |
| 7 | 模型烘焙注册 | `MonitorPreloadedModels.java` | 15 min |
| 8 | BER 9 宫格渲染 | `MonitorRenderer.java` | 1-2 h |
| 9 | 扳手拆卸 + 物品归还 | `MonitorBlock.java`, `MonitorGridOverlay.java` | 30 min |
| 10 | CC:T Lua API | `CCPeripheraExtender.java` | 后续 |

---

## 十一、物品栏 3D 渲染（后续优化）

屏幕物品在物品栏中的渲染可参考 `render_item.md` 中的 `CustomRenderedItemModel` 方案，先显示为简单 flat 图标，后续可实现 3D 预览（类似钮子开关的 ToggleSwitchItemRenderer）。

---

## 十二、已确认的交互细节

- ✅ 右键与锚点**相同**格子 → 创建 **1×1** 屏幕（不取消）
- ✅ 右键与锚点**不同**格子 → 创建 M×N 屏幕
- ❌ 切换物品 → 取消放置模式
- ❌ 走太远 → 取消放置模式
- ❌ 按 Q 丢弃物品 → 取消放置模式
