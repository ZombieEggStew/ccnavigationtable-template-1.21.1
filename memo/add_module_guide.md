# 添加新 Monitor 元件流程

> 以钮子开关 (Toggle Switch) 为参考，旋钮 (Knob) 同理。

---

## 一、模型资产

### 文件结构
```
assets/ccpe/
├── models/block/
│   └── <name>/
│       ├── <name>_base.json    ← OBJ loader JSON（底座）
│       ├── <name>_base.obj     ← Blender 导出的底座 3D 模型
│       ├── <name>_base.mtl     ← 底座材质（map_Kd #0）
│       ├── <name>.json         ← OBJ loader JSON（把手/活动部件）
│       ├── <name>.obj          ← 把手 3D 模型
│       └── <name>.mtl          ← 把手材质
└── textures/block/
    └── <name>.png              ← 贴图
```

### OBJ Loader JSON 模板
```json
{
    "parent": "block/block",
    "loader": "neoforge:obj",
    "flip_v": true,
    "model": "ccpe:models/block/<name>/<name>_base.obj",
    "textures": {
        "0": "ccpe:block/<name>_tex",
        "particle": "#0"
    }
}
```

### MTL 模板
```mtl
newmtl mat
map_Kd #0
```

### Blender 导出设置
- Forward: Y, Up: Z（或 -Z/Y 匹配 MC 坐标系）
- ☑ Write Materials, ☑ UVs, ☑ Triangulate Faces
- 可动部件（把手、拉杆）居中建模，pivot 在旋转中心

---

## 二、代码注册

### 1. ModuleType 枚举
```java
// src/.../monitor/ModuleType.java
TOGGLE_SWITCH("toggle_switch", 1, 1),  // name, 宽, 高
KNOB("knob", 2, 2),
```

### 2. MonitorPreloadedModels 模型烘焙
```java
// src/.../block/MonitorPreloadedModels.java

// 键名常量
public static final String TOGGLE_LEVER = "toggle_lever";
public static final String KNOB_HANDLE = "knob_handle";

// 静态注册
MAIN_LOC.put(ModuleType.TOGGLE_SWITCH, rl("block/toggle/toggle_base"));
EXTRA_LOC.put(TOGGLE_LEVER, rl("block/toggle/toggle"));
MAIN_LOC.put(ModuleType.KNOB, rl("block/knob_1/knob_1_base"));
EXTRA_LOC.put(KNOB_HANDLE, rl("block/knob_1/knob_1"));
```

### 3. ModuleRenderBehavior 渲染行为
```java
// src/.../block/ModuleRenderBehavior.java

// 注册
REGISTRY.put(ModuleType.KNOB, new KnobBehavior());

// 子类实现
public static class KnobBehavior extends ModuleRenderBehavior {
    @Override public boolean usePressDepth() { return false; }  // 底座不凹
    @Override public float animPressSpeed() { return 0.4f; }    // 动画速度

    @Override
    public void applyInitialRotation(PoseStack ps) {
        ps.mulPose(Axis.XP.rotationDegrees(-90));  // 竖→横
    }

    @Override
    public void renderExtra(PoseStack ps, MultiBufferSource buffer,
                            float anim, int light, int overlay) {
        BakedModel handle = MonitorPreloadedModels.getExtra(KNOB_HANDLE);
        if (handle == null) return;
        ps.pushPose();
        ps.translate(0, 0.5f, 0);              // 把手偏移
        // ps.mulPose(Axis.YP.rotationDegrees(anim * 45f));  // 动画（后续）
        renderModel(ps, buffer.getBuffer(Sheets.solidBlockSheet()),
                    handle, light, overlay);
        ps.popPose();
    }
}
```

### 4. 物品注册
```java
// src/.../item/MyModItems.java
public static final DeferredItem<Item> MODULE_KNOB = MyItems.register(
        "module_knob", () -> new Item(new Item.Properties()));
```

### 5. 创造选项卡
```java
// src/.../item/MyModCreativeModeTabs.java
output.accept(MyModItems.MODULE_KNOB);
```

### 6. 物品栏模型
```json
// assets/ccpe/models/item/module_knob.json
{
  "parent": "ccpe:block/knob_1/knob_1_base"
}
```

### 7. 语言文件
```json
// zh_cn.json
"item.ccpe.module_knob": "旋钮 (2×2)",

// en_us.json
"item.ccpe.module_knob": "Knob (2×2)",
```

---

## 三、可覆写方法速查

| 方法 | 默认值 | 用途 |
|---|---|---|
| `offsetX/Y/Z()` | `0` | 微调偏移（块单位） |
| `usePressDepth()` | `true` | 底座是否跟随按下凹陷 |
| `animPressSpeed()` | `0.1f` | 按下动画速度 |
| `animReleaseSpeed()` | `0.1f` | 弹起动画速度 |
| `applyInitialRotation(ps)` | 空 | 初始旋转（如竖→横 90°） |
| `renderExtra(ps, buf, anim, light, ov)` | 空 | 渲染额外部件 + 动画 |

---

## 四、坐标/旋转微调

### 底座位置 → `offsetX/Y/Z()`

这三个方法在模型空间（NORTH-facing，已应用 `applyInitialRotation` 之前）添加偏移：

```java
@Override public float offsetX() { return 1f / 32f; }  // 水平微调（块单位）
@Override public float offsetY() { return 0; }           // 垂直微调
@Override public float offsetZ() { return 1f / 16f; }    // 深度微调
```

| 轴 | 屏幕方向 | 正值效果 |
|---|---|---|
| X | 水平向右 | 右移 |
| Y | 垂直向上 | 上移 |
| Z | 屏幕深度 | 向屏幕内凹 |

> ⚠️ 如果 `applyInitialRotation()` 有旋转（如 `rotateX(-90°)`），偏移在**旋转前**的模型空间，方向可能和屏幕不一致。调的时候对照游戏实际效果。

### 把手/活动部件位置 → `renderExtra()` 内的 `ps.translate()`

```java
ps.pushPose();
ps.translate(x, y, z);   // ← 改这里，在 applyInitialRotation 之后的坐标系
ps.mulPose(Axis.YP.rotationDegrees(anim * 45f));  // 动画旋转
renderModel(ps, ...);
ps.popPose();
```

**把手偏移在 `applyInitialRotation` 之后的坐标系中生效。** 例如旋钮 `rotateX(-90°)` 后：
| `translate` 参数 | 屏幕方向 |
|---|---|
| `translate(X, 0, 0)` | 水平 |
| `translate(0, Y, 0)` | **深度**（原来 Z→Y） |
| `translate(0, 0, Z)` | **垂直**（原来 Y→Z） |

### 初始旋转 → `applyInitialRotation()`

```java
ps.mulPose(Axis.XP.rotationDegrees(-90));  // 绕 X 轴旋转（模型竖→屏幕横）
ps.mulPose(Axis.YP.rotationDegrees(180));  // 绕 Y 轴旋转（翻转朝向）
ps.mulPose(Axis.ZP.rotationDegrees(45));   // 绕 Z 轴旋转（倾斜）
```

### 动画旋转 → `renderExtra()` 内

```java
// 钮子开关：绕 X，-30° ↔ +30°
ps.mulPose(Axis.XP.rotationDegrees(-30 + anim * 60));

// 旋钮：绕 Y，0° ↔ 270°（模拟旋转编码器）
ps.mulPose(Axis.YP.rotationDegrees(anim * 270));
```

---

## 五、交互逻辑（后续添加）

目前只有按钮（momentary press/release）和钮子开关（toggle latch）有交互。

新增交互类型时涉及：
1. **客户端** `MonitorGridOverlay`: 按/释检测逻辑
2. **服务端** `CCPeripheraExtender`: `ModulePressPayload` handler 分流
3. **数据层** `GridState` + `MonitorBlockEntity`: 状态管理 + 同步

---

## 六、检查清单

- [ ] `.obj` + `.mtl` + `.png` 文件到位
- [ ] OBJ loader JSON 创建
- [ ] `ModuleType` 枚举添加
- [ ] `MonitorPreloadedModels` MAIN + EXTRA 注册
- [ ] `ModuleRenderBehavior` 子类 + 注册
- [ ] `MyModItems` 物品注册
- [ ] `MyModCreativeModeTabs` 选项卡
- [ ] `models/item/module_<name>.json` 物品栏模型
- [ ] `lang/zh_cn.json` + `en_us.json`
