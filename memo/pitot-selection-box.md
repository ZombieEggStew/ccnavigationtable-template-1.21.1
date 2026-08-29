# 皮托管选择框调试记录（VoxelShaper 旋转坑总结）

> 状态：**已定稿，进游戏验证通过**。6 向选择框（地面/天花板/四面墙）与模型吻合；后扩展为
> **24 态（FACING × ROLL）**，扳手右键模型顶面绕点击面旋转，全部朝向已实测符合要求。

## 24 态扩展（FACING × ROLL，2025 新增）

> 状态：**已定稿，进游戏验证通过**（24 个朝向的旋转行为与选择框均符合要求）。
> 需求：Create 扳手右键**模型顶面**（模型选择框局部 x-z 平面所在的面 = 当前 FACING 方向的面）时，
> **绕点击面旋转**（绕模型局部 Y 轴），右键侧面/前面不旋转。
> 已验证：现有 6 个 end_rod 风格变体（只有 x/y 旋转，无 z）表达不了该旋转——"绕点击面转 90°"的
> 24 种组合（6 朝向 × 轴/法线 × ±90°）逐一计算全部落到不存在的朝向上（管口东西向躺倒等）。
> 结论：需扩展 blockstate。

### 设计（`PitotTubeBlock.java` + `blockstates/pitot_tube.json`）

- **两个属性**：`FACING`（6 向，放置时 = 点击面，= 模型顶面方向）+ `ROLL`（0..3 索引 = 绕顶面法线
  0°/90°/180°/270°）→ **24 个朝向**。
- **扳手**：`getRotatedBlockState`：`targetedFace == facing` 才响应，`ROLL = (roll + 1) % 4`
  （FACING 不变——绕点击面转，顶面永远朝向同一方向）；其它面不旋转。
- **blockstate**：16 个变体用基础模型 x/y 旋转（up/down 全 4 滚转 + 立姿 roll 0/2）；
  8 个（立姿 roll 1/3，管子倾斜到东西向）用**烘焙模型 `pitot_tube_z90.json`**
  （基础模型绕 Z 轴 90°、pivot (8,8,8)，由用户制作）再叠 x/y 旋转。
  原因：1.21.1 `BlockModelRotation` 只支持 x/y（16 组合），`BlockElementRotation` 只允许 ±45/22.5°，
  都不支持 z 90°。
- **选择框**：24 个盒 = `AABB(基准盒按 R(facing, roll) 绕方块中心旋转)`，直接硬编码在
  `PitotTubeBlock.buildShapes()`；roll=0 的 6 项与旧版 `SHAPES.get(facing)` 逐项一致
  （脚本验证 + 旧版进游戏验证）。

### 验证结果（进游戏）

1. ✅ 24 个朝向的选择框与模型吻合（含 8 个 z90 变体的烘焙模型纹理/UV 正确）。
2. ✅ 扳手右键顶面：管子绕点击面转，FACING 不变，4 次滚转循环。
3. ✅ 右键侧面/前面：不旋转、无音效。

### 经验教训（24 态扩展）

1. **"绕点击面旋转"在标准 6 向 blockstate 里必然不可表达**：点击面法线 = 模型顶面方向 = FACING，
   绕它转 90° 会把管子转到"东西向躺倒"等朝向，而 end_rod 风格 6 变体只有 x/y 旋转（16 组合），
   `BlockElementRotation` 又只允许 ±45/22.5°。**动手前先脚本穷举验证**：把 6 朝向 × 轴/法线 × ±90°
   全部算出并核对是否落在现有变体上，比凭感觉推导快、也不会漏。
2. **关键洞察：绕顶面旋转不改变顶面方向**（旋转轴与面法线平行）→ 状态空间可分解为
   FACING（顶面方向）× ROLL（绕顶面法线滚转），扳手只改 ROLL、FACING 不变——语义正好是"绕点击面转"。
3. **blockstate 表达 24 态**：16 个用基础模型 x/y 旋转；8 个（需要 z 90° 的）用**烘焙模型**——
   基础模型绕 Z 轴 90°、pivot (8,8,8)，由用户在 Blockbench 制作（UV 自动重映射），比手算 element
   坐标 + 面重标安全得多；烘焙模型再叠 blockstate x/y 可覆盖全部 z 态（先脚本验证属于同一陪集）。
4. **形状只跟几何走，不跟模型烘焙走**：24 个选择框 = `AABB(基准盒按 R(facing, roll) 旋转)`，与模型
   JSON 的烘焙/UV 无关；以 roll=0 的 6 项与旧版已验证选择框逐项一致作为正确性锚点。
5. **属性取值**：本版本 `IntegerProperty.create` 只有 `(name, min, max)`（无变长参数），非连续角度用
   0..3 索引，blockstate 里 roll 0..3 对应 y 0/90/180/270。

## 一句话

选择框 = **一个基准盒 + `VoxelShaper.forDirectional(基准盒, 基准朝向)`**，旋转全部交给 Catnip（绕方块中心、先 X 后 Y），
不手写任何旋转。但 end_rod 风格 blockstate（`facing=up` 未旋转、水平向 `x:90`）下，**Catnip 的 X 旋转方向与原版
blockstate 的 x 旋转方向相反**，因此水平四向需要"基准盒先绕方块中心 **Y 轴** 180°"再走 forDirectional；
UP/DOWN 两向（180° 旋转无方向差异）保持 forDirectional 直出。

## 旧 6 向实现（已被 24 态取代，保留作历史参考）

> 以下 `buildShapes()` 为 6 向时代的实现；当前代码见上文「24 态扩展」：`PitotTubeBlock` 用
> `FACING` + `ROLL` 两属性 + 24 个硬编码盒，不再用 `VoxelShaper`。

```java
/** 水平四向修正轴（穷举开关，已确认 Y 正确） */
private static final Direction.Axis FLIP_AXIS = Direction.Axis.Y;

/** 形状基准：管口朝北、贴地（facing=up 未旋转变体下的实测盒） */
private static final VoxelShape BASE = Block.box(5, 0, 2, 11, 7, 12);

private static final VoxelShaper SHAPES = buildShapes();

private static VoxelShaper buildShapes() {
    VoxelShaper base = VoxelShaper.forDirectional(BASE, Direction.UP);                        // UP/DOWN 直出
    VoxelShaper flipped = VoxelShaper.forDirectional(Shaper.rot180(BASE, FLIP_AXIS), Direction.UP); // 水平四向
    VoxelShaper result = new VoxelShaper();
    result.withShape(base.get(Direction.UP), Direction.UP);
    result.withShape(base.get(Direction.DOWN), Direction.DOWN);
    for (Direction dir : Direction.Plane.HORIZONTAL)
        result.withShape(flipped.get(dir), dir);
    return result;
}

/** 访问 VoxelShaper 的 protected rotatedCopy（与 iron_handle 的 HandleShaper 同手法） */
private static final class Shaper extends VoxelShaper {
    static VoxelShape rot180(VoxelShape shape, Direction.Axis axis) {
        return rotatedCopy(shape, switch (axis) {
            case X -> new Vec3(180, 0, 0);
            case Y -> new Vec3(0, 180, 0);
            case Z -> new Vec3(0, 0, 180);
            default -> Vec3.ZERO;
        });
    }
}
```

`getShape()` = `SHAPES.get(state.getValue(FACING))`。blockstate 不变（end_rod 风格：`facing=up` 无旋转、
`facing=north` `x:90`、`facing=east` `x:90 y:90`、`facing=down` `x:180`……）。

## 三个坑（按排查顺序）

### 坑 1：单位混用 → 选择框完全不可见

最初代码用 `forAllBoxes` 手写旋转：回调给的是 **0-1 归一化**坐标，却被直接喂给 `Block.box`（期望 **0-16 像素**，
内部 ÷16）→ 生成的选择框是方块角落一个 2%~5% 大小的碎块，肉眼看不到（现象："放置后没有选择框"）。
`Block.box(double...)` 源码：`return Shapes.box(x1/16.0, ...)`。

**教训：永远不要手写 VoxelShape 旋转**（单位、方向都容易错）；用 Catnip 的 `VoxelShaper.rotatedCopy`
（`protected static`，继承 `VoxelShaper` 即可访问，`HandleShaper` 就是这么做）。

### 坑 2：基准朝向必须 = blockstate 的"未旋转变体"

本 blockstate 的未旋转变体是 `facing=up`（模型在 Blockbench 里就是画成"管口朝北、贴地"的姿势，`facing=up`
变体无旋转 → 放地面即看到该姿势）。**基准盒必须按该姿势填写、基准朝向用 UP**（同 `StaticPortBlock` /
Create pump / Create piston head）。基准朝向选错（如按 NORTH 填）→ 选择框与模型整体差 90°（"7 高 × 10 长"
变成"10 高 × 7 长"，用户实测描述为"转了 90 度"）。

### 坑 3：Catnip 的 X 旋转方向与原版 blockstate 相反 → 水平四向差 180°

原版 blockstate 变体旋转（`net.minecraft.client.resources.model.BlockModelRotation`）：
`new Quaternionf().rotateYXZ(−y·rad, −x·rad, 0)`，等效矩阵 **Ry(−θy)·Rx(−θx)**（X 先转、Y 后转，绕方块中心）。
而 Catnip `VoxelShaper.DefaultRotationValues` 对水平向用 **Rx(+90)**（旋转 = `values(to) − values(from)`，先 X 后 Y）。
两者 X 方向相反：

- UP/DOWN（Rx(±180)，180° 无方向差异）→ 不受影响；
- 水平四向（Rx(±90)）→ 差 180°（墙上的选择框"反了"）。

**修正方式**：基准盒先绕某轴 180° 再走 forDirectional（等效于把每个水平朝向的变换补上 180°）。
**不能对结果形状再转 180°**——X/Y 旋转不可交换，那样 EAST/WEST 会错位。
穷举实测确定绕 **Y 轴**正确（X 轴会让水平盒跑到对面一侧，Z 轴同样不对；以 facing=north 为例：
X→南侧下半格、Y→北侧下半格、Z→南侧上半格，模型实际在北侧下半格 → Y）。

## 关键约定（写新选择框前的 checklist）

1. **确定 blockstate 风格**：未旋转变体是哪个 facing？
   - end_rod 风格（`facing=up` 无旋转）→ 基准朝向 `UP`；
   - plain-Y 风格（如 `facing=north` 无旋转）→ 基准朝向 `NORTH`。
2. **基准盒 = 模型在未旋转变体里的样子**（进游戏实测，别从模型 JSON 纯推导，容易读错元素含义）。
3. UP/DOWN 用 forDirectional 直出；水平四向按坑 3 是否需要翻转、绕哪根轴——**进游戏实测 + 穷举开关**确定。
4. 不要手写旋转（单位坑）；需要任意轴旋转用 `VoxelShaper.rotatedCopy`（继承 VoxelShaper）。
5. **需要"绕点击面旋转"（滚转）→ 参考 24 态扩展**：`FACING`（顶面方向）× `ROLL`（0..3 索引）两属性；
   先脚本验证所需朝向是否全落在 x/y 可达集内，缺的用烘焙 z 模型补。

## 参考来源

| 参考 | 位置 | 借鉴点 |
|---|---|---|
| iron_handle 选择框 | `references/Simulated-Project-main/.../content/blocks/handle/HandleShaper.java` | 基准盒 + `forDirectional(_, UP)` + `rotatedCopy`（继承 VoxelShaper 用 protected 方法）+ axis 变体 EAST/WEST 互换 |
| Catnip `VoxelShaper` / `VecHelper` | `references/Catnip-NeoForge-1.21.1-0.8.54-sources/.../utility/VoxelShaper.java` | `forDirectional` 旋转 = `values(to) − values(from)`，先 X 后 Y，绕 (8,8,8)；`rotatedCopy` protected |
| 原版 `BlockModelRotation` | `build/moddev/artifacts/neoforge-21.1.235-sources.jar` → `net/minecraft/client/resources/model/BlockModelRotation.java` | blockstate 变体旋转 = `rotateYXZ(−y, −x)` = Ry(−θy)·Rx(−θx)（X 先转） |
| 原版 `Block.box(double)` | 同 sources jar → `net/minecraft/world/level/block/Block.java` | 参数按像素、内部 ÷16（单位坑根源） |
| 本 mod 已验证同款 | `src/main/java/.../block/StaticPortBlock.java` | `forDirectional(盒, UP)` 直出（对称模型无水平方向问题，故无坑 3） |
| Create 同款 | `references/Create-mc1.21.1-dev/.../AllShapes.java`、`blockstates/mechanical_pump.json` | pump / piston head：end_rod 风格 + `forDirectional(_, UP)` |

## 经验教训

1. **几何/旋转正确性问题，以进游戏实测为最终裁判**。数值推导极易在某一步符号翻转上出错（本次绕哪根轴
   就是穷举 + 实测确定的，纯推导一度指向错误结论）。
2. **不要手写 VoxelShape 旋转**——单位（归一化 vs 像素）和方向（正负角）两处都是坑；一律用 Catnip `rotatedCopy`。
3. **基准朝向跟着 blockstate 风格走**（未旋转变体），基准盒按模型在该变体下的样子填；基准盒能进游戏实测就实测。
4. **症状定位口诀**：选择框完全不可见 → 单位坑；整体差 90° → 基准朝向错；水平反了但 UP/DOWN 对 → Catnip 与
   vanilla 的 X 旋转方向差（水平四向补 180°）。
5. **穷举开关 + 实测**：不确定时把候选做成常量（如 `FLIP_AXIS`），让用户逐个试，比继续推导快得多，也方便回退。
