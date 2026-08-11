# Catnip Outliner 用法参考

> `net.createmod.catnip.outliner.Outliner` — Catnip 库的 3D 线框渲染工具
>
> 基于 Create 6.0.10 源码中所有实际用法整理

---

## 一、核心 API

### 1. 获取单例

```java
import net.createmod.catnip.outliner.Outliner;

Outliner outliner = Outliner.getInstance();
```

---

## 二、渲染方法

### `showAABB(Object slot, AABB aabb)` — 线框盒子

最常用，渲染一个 AABB 包围盒线框。

```java
// 基础用法：选中目标的方块形状
// ClickToLinkBlockItem.java:168
Outliner.getInstance().showAABB("target", lastShownAABB)
    .colored(0xffcb74)
    .lineWidth(1 / 16f);

// 带 faceTextures + highlightFace（蓝图选择框）
// SchematicAndQuillHandler.java:~330
outliner().chaseAABB(outlineSlot, currentSelectionBox)
    .colored(0x6886c5)
    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
    .lineWidth(1 / 16f)
    .highlightFace(selectedFace);

// 多方块范围（水车 3x3 预览）
// LargeWaterWheelBlockItem.java:52
Outliner.getInstance().showAABB(Pair.of("waterwheel", pos), new AABB(pos).inflate(1))
    .colored(0xFF0000)  // 实际颜色可能不同
    .lineWidth(1 / 16f);

// 定时自动消失（200 ticks = 10 秒）
// HighlightPacket.java:27
Outliner.getInstance().showAABB("highlightCommand", Shapes.block().bounds().move(pos), 200)
    .colored(color);
```

**参数说明：**
- `slot` — 唯一标识，同一 slot 重复调用会覆盖，常用 `String`、`BlockPos`、`Pair` 等
- `aabb` — 世界坐标下的包围盒
- 第三个可选参数 `int ticks` — 持续 tick 数，到期自动消失

---

### `showLine(Object slot, Vec3 from, Vec3 to)` — 单条线段

在两个 3D 点之间绘制一条线。

```java
// 两点之间连线（矿车耦合调试）
// CouplingRenderer.java:230
Outliner.getInstance().showLine(mainCart.getId() + "", mainCenter, connectedCenter)
    .colored(color)
    .lineWidth(1 / 8f);

// 极短的"点"（高度仅 1/128 ≈ 一个像素点）
// CouplingRenderer.java:236
Outliner.getInstance().showLine(mainCart.getId() + "_dot", point, point.add(0, 1 / 128f, 0))
    .colored(0xffffff)
    .lineWidth(1 / 4f);

// 两条平行线（传送带连接预览）
// ChainConveyorConnectionHandler.java:194-197
Outliner.getInstance().showLine("chain_connect_line", from.add(normal), to.add(normal))
    .lineWidth(1 / 16f)
    .colored(color);
Outliner.getInstance().showLine("chain_connect_line_1", from.subtract(normal), to.subtract(normal))
    .lineWidth(1 / 16f)
    .colored(color);

// 用多段短线拼成八角形环
// ChainConveyorConnectionHandler.java:210
for (int i = 0; i < 8; i++) {
    Vec3 v = VecHelper.rotate(new Vec3(0, .125 + y * .75, 1.25), 22.5 + i * 45, Axis.Y)
        .add(Vec3.atBottomCenterOf(pos));
    Outliner.getInstance().showLine(key + y + i, prevV, v)
        .lineWidth(1 / 16f)
        .colored(color);
    prevV = v;
}

// 旋转轴可视化
// KineticDebugger.java:58
Outliner.getInstance().showLine("rotationAxis", center.add(vec), center.subtract(vec))
    .colored(0x5EEDFF)
    .lineWidth(1 / 16f);

// 火车 relocator 路径线
// TrainRelocator.java:125
Outliner.getInstance().showLine(Pair.of(relocating, i), vec1.add(0, -.925f, 0), vec2.add(0, -.925f, 0))
    .colored(color)
    .lineWidth(1 / 16f);
```

---

### `showCluster(Object slot, Collection<BlockPos>)` — 聚合轮廓

将多个方块位置渲染为一个整体轮廓。

```java
// 粘性活塞底盘范围
// ChassisRangeDisplay.java:38
Outliner.getInstance().showCluster(getOutlineKey(), createSelection(be))
    .colored(color)
    .lineWidth(1 / 16f);

// 超级胶水选中的方块集合
// SuperGlueSelectionHandler.java:160
Outliner.getInstance().showCluster(clusterOutlineSlot, currentCluster)
    .colored(isValid ? HIGHLIGHT : PASSIVE)
    .lineWidth(1 / 8f);

// 地形 Zapper 预览位置
// WorldshaperRenderHandler.java:35
Outliner.getInstance().showCluster("terrainZapper", renderedPositions.get())
    .colored(0xFFFFFF)
    .lineWidth(1 / 16f);

// 铁轨放置有效/无效位置
// TrackPlacement.java:671-675
Outliner.getInstance().showCluster("track_valid", hints.getFirst())
    .colored(0x4CDA64)
    .lineWidth(1 / 16f);
Outliner.getInstance().showCluster("track_invalid", hints.getSecond())
    .colored(0xFF5E5E)
    .lineWidth(1 / 16f);
```

---

### `showOutline(Object slot, OutlineParams)` — 变换后的轮廓

用于需要额外变换的轮廓（如旋转、缩放）。

```java
// LinkRenderer.java:66
Outliner.getInstance().showOutline(Pair.of(Boolean.valueOf(first), pos), box.transform(transform))
    .colored(color)
    .lineWidth(1 / 16f);

// EdgeInteractionRenderer.java:82
Outliner.getInstance().showOutline("edge", box)
    .colored(color)
    .lineWidth(1 / 16f);

// ScrollValueRenderer.java:98
Outliner.getInstance().showOutline(behaviour, box.transform(behaviour.slotPositioning))
    .colored(0xFFFFFF)
    .lineWidth(1 / 16f);
```

---

### `showItem(Object slot, Vec3 pos, ItemStack)` — 世界中的物品图标

在 3D 空间渲染一个物品图标。

```java
// TrackGraphVisualizer.java:270-278
Outliner.getInstance().showItem(Pair.of(edge, edge.edgeData), materialPos, edge.getTrackMaterial().asStack());
Outliner.getInstance().showAABB(edge.edgeData, AABB.ofSize(materialPos, .25, 0, .25))
    .colored(color)
    .lineWidth(1 / 16f);
Outliner.getInstance().showLine(edge, edge.getPosition(graph, 0), materialPos)
    .colored(color)
    .lineWidth(1 / 16f);
```

---

### `chaseAABB(Object slot, AABB aabb)` — 带动画的 AABB

平滑过渡到目标 AABB，适合持续变化的选择框。

```java
// 蓝图选择框（实时跟随鼠标）
// SchematicAndQuillHandler.java:~330
outliner().chaseAABB(outlineSlot, currentSelectionBox)
    .colored(0x6886c5)
    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
    .lineWidth(1 / 16f)
    .highlightFace(selectedFace);

// 动能调试器高亮源
// KineticDebugger.java:48
Outliner.getInstance().chaseAABB("kineticSource", shape.bounds().move(pos))
    .colored(0x5EEDFF)
    .lineWidth(1 / 16f);

// Ejector 有效目标
// EjectorTargetHandler.java:216
Outliner.getInstance().chaseAABB("valid", bb)
    .colored(0x4CDA64)
    .lineWidth(1 / 16f);

// BeltSlicer 反馈框
// BeltSlicer.java:495
Outliner.getInstance().chaseAABB("BeltSlicer", feedback.bb)
    .colored(color)
    .lineWidth(1 / 16f);
```

---

## 三、生命周期管理

### `keep(Object slot)` — 保持渲染

阻止 Outliner 自动清除某个 slot，使其持续可见。

```java
// 粘性活塞底盘范围保持
// ChassisRangeDisplay.java:100,110
Outliner.getInstance().keep(entry.getOutlineKey());
Outliner.getInstance().keep(group.getOutlineKey());

// 超级胶水预览保持
// SuperGlueSelectionHandler.java:66
Outliner.getInstance().keep(clusterOutlineSlot);
```

---

### `remove(Object slot)` — 手动移除

立即移除某个 slot 的渲染。

```java
// 清除底盘范围
// ChassisRangeDisplay.java:174-187
Outliner.getInstance().remove(Pair.of(included.getBlockPos(), 1));
groupEntries.forEach(entry -> Outliner.getInstance().remove(entry.getOutlineKey()));

// 铁轨放置清除
// TrackPlacement.java:774-775
Outliner.getInstance().remove(Pair.of(key, i * 2));
Outliner.getInstance().remove(Pair.of(key, i * 2 + 1));
```

---

### `getOutlines()` — 获取所有活跃轮廓

```java
// GoggleOverlayRenderer.java:58
private static final Map<Object, OutlineEntry> outlines = Outliner.getInstance().getOutlines();
```

---

## 四、Builder 链式配置方法

所有渲染方法（`showAABB`/`showLine`/`showCluster`/`chaseAABB` 等）返回 Builder，支持链式调用：

| 方法 | 说明 | 示例 |
|------|------|------|
| `.colored(int)` | 设置 RGB 颜色 | `.colored(0xffcb74)` |
| `.lineWidth(float)` | 线宽（以方块为单位） | `.lineWidth(1 / 16f)` |
| `.withFaceTextures(ResourceLocation, ResourceLocation)` | 面纹理（用于蓝图/胶水等特殊效果） | `.withFaceTextures(CHECKERED, HIGHLIGHT_CHECKERED)` |
| `.highlightFace(Direction)` | 高亮指定面（用于蓝图面选择） | `.highlightFace(selectedFace)` |
| `.disableLineNormals()` | 禁用线法线（平面着色） | `.disableLineNormals()` |

**常用颜色值：**

```java
0xffcb74  // 橙色（Display Link 目标选中）
0x6886c5  // 蓝色（蓝图选择框）
0x4CDA64  // 绿色（有效/可用）
0xFF5E5E  // 红色（无效/不可用）
0x5EEDFF  // 青色（动能调试）
0xFFFFFF  // 白色
0xabf0e9  // 浅青色（矿车耦合理想距离）
0xee8572  // 浅红色（矿车耦合过远/过近）
```

---

## 五、两种典型模式

### 模式 A：Tick 驱动（持续渲染）

每帧调用，适合需要实时更新的预览。Display Link、Mechanical Arm、蓝图等使用此模式。

```java
// 在客户端 tick 事件中
@EventBusSubscriber(Dist.CLIENT)
public class MyHandler {
    public static void clientTick() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        // 1. 检查条件（手持特定物品等）
        if (!isHoldingMyItem(player)) return;

        // 2. 计算 AABB / 位置
        AABB bounds = getTargetBounds();

        // 3. 调用 Outliner
        Outliner.getInstance().showAABB("myPreview", bounds)
            .colored(0xffcb74)
            .lineWidth(1 / 16f);
    }
}
```

### 模式 B：事件驱动 + `keep`（持久渲染）

点击时设置，通过 `keep()` 保持，不需要每帧调用。粘性活塞底盘、超级胶水等使用此模式。

```java
// 注册/更新时
Outliner.getInstance().showCluster(outlineKey, positions)
    .colored(color)
    .lineWidth(1 / 16f);
Outliner.getInstance().keep(outlineKey);

// 不需要时移除
Outliner.getInstance().remove(outlineKey);
```

---

## 六、Slot 键的常用类型

| 类型 | 示例 | 适用场景 |
|------|------|----------|
| `String` | `"target"`, `"chain_connect_line"` | 全局唯一的单例预览 |
| `BlockPos` | `pos` | 每个方块一个预览 |
| `Pair` | `Pair.of("waterwheel", pos)` | 需要组合键 |
| 实体 ID 字符串 | `mainCart.getId() + ""` | 每个实体一个预览 |
| 自定义对象 | `ArmInteractionPoint` 实例 | 对象级别的独立槽位 |

**关键规则**：同一 slot 再次调用会被覆盖，无需手动 `remove`。这就是为什么 tick 模式不需要 `remove` — 每帧覆盖即可。

---

## 七、相关 import

```java
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.outliner.Outline;            // 仅 GoggleOverlayRenderer 使用
import net.createmod.catnip.outliner.Outliner.OutlineEntry; // 获取轮廓条目
import net.createmod.catnip.outliner.AABBOutline;         // Schematic 工具
import net.createmod.catnip.outliner.LineOutline;          // RotateTool
import net.createmod.catnip.outliner.ChasingAABBOutline;   // ValueBox
```
