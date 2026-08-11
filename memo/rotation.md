# Minecraft NeoForge 1.21.1 旋转问题手册

## 1. 核心规则：旋转方向

### 顺时针 vs 逆时针

| 组件 | 旋转方向（俯视 Y 轴） | 示例：90° |
|------|----------------------|----------|
| Minecraft blockstates `y` | **逆时针 (CCW)** | `(x,z) → (16-z, x)` |
| `SuperByteBuffer.rotateCentered(θ, UP)` | **顺时针 (CW)** | `(x,z) → (z, 16-x)` |
| `VoxelShaper.forHorizontal(shape, NORTH)` | 与 blockstates 一致 | CCW |

**结论：`rotateCentered` 和 blockstates 方向相反，必须取负号补偿。**

---

## 2. `Direction.toYRot()` 值速查

```java
Direction.SOUTH.toYRot() = 0°
Direction.WEST.toYRot()  = 90°
Direction.NORTH.toYRot() = 180°
Direction.EAST.toYRot()  = 270°
```

---

## 3. 标准 HorizontalDirectionalBlock 的 BER 旋转公式

```java
// MonitorBlock: context.getHorizontalDirection().getOpposite() → FACING
// Blockstates: facing=north→y=0, east→y=90, south→y=180, west→y=270

float yRot = (float) -Math.toRadians(facing.getOpposite().toYRot());
// 取负号：补偿 rotateCentered 的 CW 方向
```

### 验证表

| FACING | blockstates y | `getOpposite().toYRot()` | 取负后 | 等效 CCW |
|--------|--------------|--------------------------|--------|---------|
| NORTH  | 0°           | SOUTH=0°                 | 0°     | 0° ✅   |
| EAST   | 90°          | WEST=90°                 | -90°→270° CW | 90° CCW ✅ |
| SOUTH  | 180°         | NORTH=180°               | -180°→180° | 180° ✅ |
| WEST   | 270°         | EAST=270°                | -270°→90° CW | 270° CCW ✅ |

---

## 4. FaceAttachedHorizontalDirectionalBlock 的特殊情况

Simulated 的 `AltitudeSensorRenderer` 使用了不同的公式：

```java
// ⚠️ 仅适用于 FaceAttachedHorizontalDirectionalBlock
final float yRot = !direction.getAxis().equals(Direction.Axis.Z) ?
    Math.toRadians(facing.getOpposite().toYRot()) :  // X轴方向
    Math.toRadians(facing.toYRot());                   // Z轴方向（NORTH/SOUTH）

buffer.rotateCentered((float)(yRot + Math.PI), Direction.UP);
```

**为什么不同？** `FaceAttachedHorizontalDirectionalBlock` 的 FACING 在 Z 轴方向有不同语义，需要 `toYRot()` 而非 `getOpposite().toYRot()` 来补偿。

**如果你用的是标准 `HorizontalDirectionalBlock`，使用第 3 节的公式即可。**

---

## 5. 变换顺序

```java
// ✅ 正确：先本地动画，再朝向旋转
buffer.translate(0, animY, 0);              // ① 本地 Y 平移（动画）
buffer.rotateCentered(yRot, Direction.UP);  // ② 朝向旋转

// Y 平移与 Y 旋转可交换（因为 Y 轴旋转不改变 Y 坐标）
// 但如果动画涉及 X 或 Z 平移，必须先平移再旋转
```

---

## 6. 旋转中心

所有旋转都围绕方块中心 `[8, 8, 8]`（在 0-16 模型坐标空间中）：

```java
// rotateCentered 内部等价于：
// 顶点 -= [8,8,8]
// 顶点 = 旋转矩阵 × 顶点
// 顶点 += [8,8,8]
```

Minecraft blockstates 的 Y 旋转也使用相同的中心和公式：
```
y=0:   (x, z) → (x, z)
y=90:  (x, z) → (16-z, x)
y=180: (x, z) → (16-x, 16-z)
y=270: (x, z) → (z, 16-x)
```

---

## 7. AABB 旋转（子部件点击检测用）

```java
public static AABB rotateAABB(AABB northBox, Direction facing) {
    return switch (facing) {
        case EAST  -> new AABB(1-northBox.maxZ, northBox.minY, northBox.minX,
                               1-northBox.minZ, northBox.maxY, northBox.maxX);
        case SOUTH -> new AABB(1-northBox.maxX, northBox.minY, 1-northBox.maxZ,
                               1-northBox.minX, northBox.maxY, 1-northBox.minZ);
        case WEST  -> new AABB(northBox.minZ, northBox.minY, 1-northBox.maxX,
                               northBox.maxZ, northBox.maxY, 1-northBox.minX);
        default    -> northBox; // NORTH
    };
}
```

---

## 8. VoxelShaper 使用

```java
// 定义北向基准的形状（所有坐标在模型空间中，0-16 像素）
private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
    Shapes.or(
        Block.box(0, 0, 3, 16, 2, 13),
        Block.box(1, 2, 4, 15, 14, 9)
    ),
    Direction.NORTH  // ← 默认朝向
);

// 使用时自动按 FACING 旋转
@Override
public VoxelShape getShape(BlockState state, ...) {
    return SHAPE.get(state.getValue(FACING));
}
```

---

## 9. 常见错误清单

| 症状 | 原因 | 解决 |
|------|------|------|
| 东西朝向错位，南北正常 | 没取负号补偿 CW/CCW | 加 `-` 号 |
| 所有方向都错位 180° | 多了或少了 `+Math.PI` | 去掉 `+Math.PI`（标准方块不需要） |
| 子部件线框位置错误 | `rotateAABB` 公式方向反了 | 检查 switch-case 对应关系 |
| Partial model 与 block model 不对齐 | 旋转角度不一致 | 确保 BER 和 blockstates 用相同旋转值 |

---

## 10. 参考代码

- Simulated `AltitudeSensorRenderer`：`sources/simulated/.../AltitudeSensorRenderer.java`
  - 使用 `FaceAttachedHorizontalDirectionalBlock`，有 `+Math.PI` + Z轴特殊处理
- 本项目 `MonitorRenderer`：`src/main/java/.../MonitorRenderer.java`
  - 使用标准 `HorizontalDirectionalBlock`，只需 `-getOpposite().toYRot()`
