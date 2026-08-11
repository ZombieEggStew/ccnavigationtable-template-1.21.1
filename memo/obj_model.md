# Minecraft NeoForge OBJ 模型渲染方法

> 参考：Create 6.0.10 的 `copper_valve_handle` — NeoForge 内置 `obj` loader
>
> OBJ 是工业标准 3D 格式，用顶点/面代替像素方块，适合曲面、斜面、复杂几何。

---

## 一、文件结构

```
assets/<modid>/
└── models/
    └── block/
        ├── copper_valve_handle.json   ← 纹理变体（parent 指向基模型）
        ├── valve_handle.json           ← 基模型（指定 OBJ loader + OBJ 路径）
        └── valve_handle/
            ├── valve_handle.obj        ← 真正的 3D 几何体
            └── valve_handle.mtl        ← 材质定义（可选）
```

---

## 二、模型 JSON 写法

### 纹理变体（`copper_valve_handle.json`）

```json
{
  "parent": "create:block/valve_handle",
  "textures": {
    "3": "create:block/valve_handle/valve_handle_copper"
  }
}
```

### 基模型（`valve_handle.json`）— 核心（多纹理）

```json
{
    "parent": "block/block",
    "loader": "neoforge:obj",
    "flip_v": true,
    "model": "create:models/block/valve_handle/valve_handle.obj",
    "textures": {
        "3": "create:block/valve_handle/valve_handle_copper",
        "particle": "#3"
    },
    "display": {
        "fixed": {
            "rotation": [270, 0, 0],
            "translation": [0, 0, -2],
            "scale": [0.5, 0.5, 0.5]
        }
    }
}
```

### 关键字段说明

| 字段 | 必需 | 说明 |
|------|------|------|
| `"loader": "neoforge:obj"` | ✅ | 启用 NeoForge 的 OBJ 模型加载器 |
| `"model"` | ✅ | OBJ 文件路径，格式 `modid:models/block/...obj` |
| `"flip_v"` | 推荐 | OBJ 的 V 坐标与 Minecraft 相反，通常设为 `true` |
| `"textures"` | ✅ | 材质映射，`"#3"` 等 key 对应 MTL 中的材质名 |
| `"display"` | 可选 | 物品栏/掉落物等显示变换 |
| `"parent": "block/block"` | 推荐 | 继承基础方块渲染属性 |

### 单纹理简化写法（无多纹理需求）

如果整个 OBJ 只有一个纹理，不需要父子 JSON 分离，一个文件就够了：

```json
{
    "parent": "block/block",
    "loader": "neoforge:obj",
    "flip_v": true,
    "model": "ccpe:models/block/my_panel/my_panel.obj",
    "textures": {
        "0": "ccpe:block/my_panel_tex",
        "particle": "#0"
    }
}
```

对应的 MTL 只需一行：

```mtl
newmtl Material
map_Kd #0
```

**文件结构简化后：**

```
assets/<modid>/
├── models/block/
│   ├── my_panel.json          ← 唯一 JSON（自带 OBJ loader）
│   └── my_panel/
│       ├── my_panel.obj
│       └── my_panel.mtl
└── textures/block/
    └── my_panel_tex.png
```

> **对比**：Create 用父子 JSON 是因为铜/铁/金阀门共用一个 OBJ，只换纹理。
> 如果你不需要这种复用，一个 JSON 直接搞定。

---

## 三、Blockstates 引用

和普通方块一样，blockstates 指向 JSON 模型（不是 OBJ）：

```json
{
  "variants": {
    "facing=north": { "model": "create:block/copper_valve_handle", "x": 90 },
    "facing=east":  { "model": "create:block/copper_valve_handle", "x": 90, "y": 90 }
  }
}
```

---

## 四、纹理管线：OBJ → MTL → JSON → PNG

### 完整链路（以 Create valve_handle 为例）

```
┌─ valve_handle.obj ───┐
│ f 1/1/1 2/2/1 ...    │  面引用材质 "Material"
│ usemtl Material      │
└──────────────────────┘
          │
          ▼
┌─ valve_handle.mtl ───┐
│ newmtl Material      │  材质 "Material" 使用纹理槽 #3
│ map_Kd #3            │  ← #N = 纹理槽编号，不是文件名！
└──────────────────────┘
          │
          ▼
┌─ valve_handle.json ──┐
│ "textures": {        │  槽 #3 映射到实际纹理路径
│   "3": "create:block/│
│     valve_handle/    │
│     valve_handle_    │
│     copper"          │
│ }                    │
└──────────────────────┘
          │
          ▼
┌─ 实际纹理文件 ─────────┐
│ assets/create/       │
│   textures/block/    │
│   valve_handle/      │
│   valve_handle_copper│
│   .png               │
└──────────────────────┘
```

### 关键规则

| 位置 | 语法 | 含义 |
|------|------|------|
| MTL: `map_Kd #3` | `#` + 数字 | 引用 JSON 中 `"3"` 纹理槽 |
| MTL: `map_Kd texture.png` | 直接文件名 | 直接用同目录 PNG（不推荐，无法换皮） |
| JSON: `"3": "modid:block/..."` | 槽号 → 资源路径 | 支持多纹理变体 |

**核心优势**：同一 OBJ 可用于多种纹理变体。`copper_valve_handle.json` 只改 `"3"` 指向不同纹理，就能复用同一个 OBJ。

---

## 五、Blender 制作流程

### Step 1：建模 + 材质

```
1. 建模（单位：米，1m = 1 Minecraft 方块 = 16px）
2. 展开 UV（必须有 UV，否则材质槽无效）
3. 创建材质 → 给材质取任意名（如 "panel_screen"）
4. 材质中使用 Image Texture 节点（任意预览图即可）
```

### Step 2：导出 OBJ

```
文件 → 导出 → Wavefront (.obj)
  ☑ 写入材质 (Write Materials)     ← 生成 .mtl
  ☑ 写入 UV (Write UVs)
  ☑ 三角化面 (Triangulate Faces)
  前向轴: Y Forward    ← 重要！
  上向轴: Z Up         ← 重要！Blender 默认 Z-Up
```

### Step 3：编辑 MTL 文件

```mtl
# Blender 导出的原始 MTL（会写实际纹理文件名）
newmtl panel_screen
map_Kd panel_screen.png    ← 替换为 #N

# 改为 NeoForge 格式
newmtl panel_screen
map_Kd #0                   ← 槽号 0
```

### Step 4：写 JSON 模型

```json
{
    "parent": "block/block",
    "loader": "neoforge:obj",
    "flip_v": true,
    "model": "ccpe:models/block/monitor_panel/panel.obj",
    "textures": {
        "0": "ccpe:block/monitor_panel_screen",   ← 槽 #0 → PNG
        "particle": "#0"
    }
}
```

### Step 5：纹理文件

```
assets/ccpe/textures/block/monitor_panel_screen.png
```

### Blender 导出坐标修正

| Blender 导出设置 | 效果 |
|-----------------|------|
| Forward: Y, Up: Z | 默认，模型在 MC 中可能"倒下" |
| Forward: -Z, Up: Y | 推荐，匹配 Minecraft 坐标系 |
| 或在 JSON 中用 `"rotation"` 补偿 | `[90, 0, 0]` 通常是 Y-up→Z-up |

---

## 六、OBJ 文件基础

### 最小示例（一个三角形）

```obj
# 顶点
v 0 0 0
v 1 0 0
v 0 1 0

# UV 坐标
vt 0 0
vt 1 0
vt 0 1

# 法线（可选）
vn 0 0 1

# 面（顶点索引/UV索引/法线索引）
f 1/1/1 2/2/1 3/3/1
```

### 坐标映射

| OBJ 轴 | Minecraft 轴 | 备注 |
|--------|-------------|------|
| X | X (东) | 一致 |
| Y | Z (南) | **Y→Z 交换** |
| Z | Y (上) | **Z→Y 交换** |

> ⚠️ OBJ 默认是 Y-up，Minecraft 也是 Y-up，但 Blender 导出时可能是 Z-up。
> 如果模型倒下，在 JSON 中加 `"translation"` 或 `"rotation"` 补偿。

---

## 七、OBJ vs JSON 方块模型

| | JSON 方块模型 | OBJ 模型 |
|---|---|---|
| 几何定义 | `from/to` 像素方块 | 任意顶点面 |
| 曲面/斜面 | 仅旋转方块 | 原生支持 ✅ |
| 文件大小 | 小 | 大（纯文本顶点） |
| 编辑工具 | Blockbench | Blender、Maya |
| 面数量 | 少（方块面） | 可任意多 |
| 碰撞箱 | 正常 | 需额外用 `getShape()` 定义 |
| Create 示例 | 大多数方块 | valve_handle, cogwheel, whitelist |

---

## 八、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 模型不显示 | `loader` 路径错误或未指定 | 检查 `"loader": "neoforge:obj"` |
| 纹理错乱 | `flip_v` 未设置 | 加 `"flip_v": true` |
| 模型方向不对 | OBJ 坐标轴与 MC 不同 | 在 JSON `display` 中旋转 |
| 模型太小/太大 | OBJ 单位与 MC 不同 | 调整 `scale` |
| 碰撞箱不对 | OBJ 几何 ≠ 碰撞 | 单独定义 `getShape()` |

---

## 九、制作流程总结

1. **Blender 建模** → 导出 `.obj`
2. **放文件** → `assets/<modid>/models/block/<name>/<name>.obj`
3. **写基 JSON** → 指定 `loader` + `model` 路径 + 纹理
4. **写变体 JSON** → `parent` 指向基模型，覆盖纹理
5. **写 blockstates** → 正常引用变体 JSON
6. **定义碰撞箱** → `Block.getShape()` 手动写形状
