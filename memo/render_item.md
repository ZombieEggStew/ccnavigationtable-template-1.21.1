# 多部件物品的物品栏渲染

> 参考：AeroWorks 的 `ModuleItemRenderer` / `ConsoleItemRenderer`
>
> 适用于：OBJ + JSON 混合模型、模块化可装配部件、非方块物品

---

## 一、核心思路

无论子部件是 OBJ 还是 JSON 方块模型，都注册为 `PartialModel`，
然后用 Create 的 `CustomRenderedItemModelRenderer` 逐个组装渲染。

```
物品数据 (ItemStack NBT)
  → 解析部件列表
    → 每个部件 = PartialModel + Matrix4f 变换
      → 统一计算总包围盒 → 自动缩放
        → 逐个 SuperByteBuffer.renderInto()
          → 物品栏中的完整 3D 物品
```

---

## 二、Create API 基类

| 类 | 作用 |
|---|------|
| `CustomRenderedItemModel` | 替代普通 item model，告诉 Minecraft "我用自定义渲染" |
| `CustomRenderedItemModelRenderer` | 自定义渲染器基类，在 `render()` 中写组装逻辑 |
| `PartialItemModelRenderer` | 渲染默认物品模型（没有部件时的回退） |

---

## 三、实现步骤

### Step 1：注册 CustomRenderedItemModel

```java
// 在 AeroworksClient 或你的客户端初始化中
// ModelEvent.ModifyBakingResult 事件
event.getModels().put(
    ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath("ccpe", "my_module")),
    new CustomRenderedItemModel(
        event.getModels().get(ModelResourceLocation.inventory(...)),  // 原始模型回退
        new MyModuleItemRenderer()                                     // 自定义渲染器
    )
);
```

### Step 2：实现渲染器

```java
public class MyModuleItemRenderer extends CustomRenderedItemModelRenderer {
    
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final float TARGET_EXTENT = 0.7f;  // 物品栏中目标大小
    
    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model,
                          PartialItemModelRenderer renderer,
                          ItemDisplayContext transformType, PoseStack ms,
                          MultiBufferSource buffer, int light, int overlay) {
        
        // ① 从 NBT 解析部件列表
        List<PartDesc> parts = parseParts(stack);
        
        // ② 没有部件 → 显示原始模型
        if (parts.isEmpty()) {
            renderer.render(model.getOriginalModel(), light);
            return;
        }
        
        // ③ 计算所有部件的总包围盒
        AABB bounds = computeBounds(parts);
        if (bounds == null) return;
        
        // ④ 计算缩放比例
        double extent = Math.max(bounds.getXsize(), 
                       Math.max(bounds.getYsize(), bounds.getZsize()));
        float scale = (float)(TARGET_EXTENT / extent);
        Vec3 center = bounds.getCenter();
        
        // ⑤ 应用居中 + 缩放
        VertexConsumer vb = buffer.getBuffer(RenderType.cutout());
        ms.pushPose();
        ms.scale(scale, scale, scale);
        ms.translate(-center.x, -center.y, -center.z);
        
        // ⑥ 逐个渲染部件
        for (PartDesc part : parts) {
            PartialModel partial = getPartial(part.modelId);
            if (partial == null) continue;
            
            SuperByteBuffer buf = CachedBuffers.partial(partial, AIR);
            applyTransform(buf, part);   // 施加位置旋转
            buf.light(light).renderInto(ms, vb);
        }
        
        ms.popPose();
    }
}
```

### Step 3：定义部件 PartialModel

```java
// 部件注册（OBJ 和 JSON 无差别）
public static final PartialModel PANEL_BODY = block("panel_body");      // OBJ
public static final PartialModel PANEL_SCREEN = block("panel_screen");  // JSON
public static final PartialModel PANEL_KNOB = block("panel_knob");      // OBJ

private static PartialModel block(String path) {
    return PartialModel.of(ResourceLocation.fromNamespaceAndPath("ccpe", "block/" + path));
}
```

---

## 四、自动缩放算法

```java
// 遍历所有部件的包围盒，取 min/max 并集
AABB box = null;
for (PartDesc part : parts) {
    AABB local = getLocalBounds(part.modelId);   // 部件本地包围盒
    AABB world = transformMatrix(part).transform(local);  // 应用部件变换
    box = (box == null) ? world : box.minmax(world);
}

// 缩放到目标大小
float scale = TARGET_EXTENT / box.getLongestAxis();  // 0.7 格
ms.translate(-box.getCenter());   // 居中
ms.scale(scale, scale, scale);
```

---

## 五、ConsoleItemRenderer：底座 + 模块叠加

当物品是一个方块物品且需要先渲染底座再叠加模块时：

```java
// ① 先渲染底座方块模型
ms.mulPose(Axis.YP.rotationDegrees(180));
ms.translate(-0.5, -0.5, -0.5);  // 方块模型坐标修正
CachedBuffers.block(baseState).light(light).renderInto(ms, vb);

// ② 再逐个渲染插槽上的模块
for (Socket slot : slots) {
    ms.pushPose();
    ms.translate(slot.offset());          // 插槽位置
    ms.mulPose(slot.orientation());       // 插槽朝向
    // 递归渲染模块部件...
    ms.popPose();
}
```

---

## 六、部件变换链

每个部件有自己的变换矩阵，组成层级链：

```
底座 (base)
  └─ 插槽 A (offset + orientation)
       └─ 面板 (position + rotation)
            └─ 旋钮 (position + rotation relative to panel)
```

```java
// 构建变换矩阵
Matrix4f matrix = new Matrix4f();
for (ChainLink link : chain) {
    switch (link) {
        case Placement(var offset, var quat) -> {
            matrix.translate(offset);
            matrix.rotate(quat);
        }
        case Steps(var part, ...) -> {
            part.applySteps(matrix);  // 部件自身的动画/位置步骤
        }
    }
}
```

---

## 七、关键 API 速查

| 操作 | 代码 |
|------|------|
| 获得 PartialModel | `PartialModel.of(ResourceLocation.fromNamespaceAndPath(...))` |
| 创建渲染缓冲 | `CachedBuffers.partial(partial, Blocks.AIR.defaultBlockState())` |
| 创建方块缓冲 | `CachedBuffers.block(blockState)` |
| 施加变换 | `buf.translate()`, `buf.rotateCentered()`, `buf.transform(matrix)` |
| 渲染 | `buf.light(light).renderInto(ms, vb)` |
| PoseStack 操作 | `ms.pushPose()`, `ms.translate()`, `ms.scale()`, `ms.mulPose()` |
| 物品栏缩放目标 | `0.7f`（约 11.2px，物品栏格子是 16px） |

---

## 八、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 部件不在物品栏中间 | 没计算包围盒居中 | 先算 bounds.getCenter()，再 translate |
| 部件太大超出格子 | 没缩放 | `TARGET_EXTENT / extent` |
| OBJ 和 JSON 错位 | 变换矩阵不一致 | 检查每个部件的 `Matrix4f` |
| 只有底座没有模块 | 插槽数据未保存到 NBT | 检查 ItemStack component |
| 渲染方向不对 | 物品栏坐标系与方块不同 | 加 `Axis.YP.rotationDegrees(180)` |

---

## 九、参考文件

- `sources/aeroworks-decompiled/.../ModuleItemRenderer.java` — 纯模块物品渲染
- `sources/aeroworks-decompiled/.../ConsoleItemRenderer.java` — 方块底座+模块叠加
- `sources/aeroworks-decompiled/.../ModulePartRender.java` — 部件变换链
- Create `CustomRenderedItemModelRenderer` — API 基类
