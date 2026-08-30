# 惯性导航系统（ccpe:ins，INS）实现记录

> 状态：**已实现并进游戏验证通过**（2026-08 完成）。视觉行为照抄 `simulated:gimbal_sensor` 的重力摆动画，
> 但部件层级做了自定义（比 simulated 更进一步：偏航标记 test 在最外层并正确指北）。
> 逻辑部分（`ccpe.sensor_system.getAngles()`，pitch/roll/yaw 度，门控 INS）**已实现，待进游戏验证**。
> 本文记录实现细节与关键坑，修改该方块前先读本文。

## 需求与结果

- 新方块 `ccpe:ins`「惯性导航系统」（INS）：装在物理体（Sable sub-level）上的姿态指示器，
  实时反映物理体滚转（绕 Z）、俯仰（绕 X），并带一个永远面向北方的偏航标记（绕 Y）。
- 视觉核心 = simulated:gimbal_sensor 的「重力摆模拟」：罗盘被重力扭矩"托"着永远水平，
  物理体翻滚时有惯性摆动感、撞限位反弹、外壳角速度耦合甩动。
- 验证结果：物理体滚转/俯仰正确响应；test 标记指北正确；比 simulated 原版多出"偏航带动轴"的层级结构。

## 模型与部件（`assets/ccpe/models/block/my_aero_sensor/`）

| 文件 | 部件 | 旋转 |
|---|---|---|
| `ins/block.json` | 外壳/底座 | 静态（blockstate 渲染） |
| `ins/gimbal.json` | 万向环 | 只绕 Z（滚转，`eulerAngles.x`），中间层 |
| `ins/compass.json` | 罗盘盘 | 只绕 X（俯仰，`eulerAngles.y`），最里层 |
| `ins/test.json` | 偏航标记（箭头形） | 只绕 Y（偏航指北，`eulerAngles.z`），最外层 |
| `ins/item.json` | 物品栏模型 | — |

- **建模约定**：所有可动部件模型以**方块中心 (8,8,8) 为原点**建模（Blockbench origin 8,8,8，
  元素坐标可为负/超出 0-16）；渲染时平移到 `(0.5, 0.5-PIVOT_DROP, 0.5)` 再绕该点旋转。
- **PIVOT_DROP**：转动部件整体下移量（当前 `3.5f/16f`，用户在游戏里调过 5px → 3.5px），
  模型与旋转中心同步下移。改位移只改 `InsVisual` / `InsRenderer` 顶部的 `PIVOT_DROP`。
- 纹理：`textures/block/my_aero_sensor/ins.png`。

## 渲染层级（核心，勿乱改）

部件层级（外 → 内）：**test(Y) → gimbal(Z) → compass(X)**，三个部件各自独立四元数：

```
test     = Y              (applyCompassQuaternion,   eulerAngles.z)
gimbal   = Y·Z            (applyCompass→applyPrimary, eulerAngles.x)
compass  = Y·Z·X          (applyCompass→applyPrimary→applySecondary, eulerAngles.y)
```

- 四元数右乘调用顺序 = `rotateY → rotateZ → rotateX`（对 yaw/gimbal/compass 依次叠加）。
- 与 simulated 原版（needle(Y) 最内、gimbal=Z、compass=Z·X）不同；本 mod 的 Y 在最外层。
- 双渲染路径必须保持一致：`InsVisual`（Flywheel，OrientedInstance ×3）与
  `InsRenderer`（BER 回退，SuperByteBuffer）。`PIVOT_DROP` 两处都要改。

## 动画模拟（客户端每 tick，`InsBlockEntity`）

- 位置：`BlockEntity` + 静态 `tick(level, pos, state, be)`（项目模式，替代 simulated 的 SmartBlockEntity），
  客户端 `tickClient` 跑模拟，服务端 `tickServer` 算 `XAngle/ZAngle`（gimbal_sensor 风格，兼容保留）
  并**注册进 `BodySensorRegistry`（ATTITUDE 传感器）**，作为 `ccpe.sensor_system.getAngles()` 的姿态门控
  （机体上有 INS 才返回 pitch/roll/yaw，度；生命周期对齐 StaticPortBlockEntity：onLoad 注册 /
  setRemoved 注销 / tickServer 每 20 tick 复核 UUID 重注册）。
- 参数（照抄 simulated）：`angleInertia = (110, 110, 34)`、`angleDamping = (0.2, 0.2, 0.2)`；
  限位固定 90°（原版 ScrollValueBehaviour 可调，本 mod 砍掉）。
- 组成：重力扭矩 `addGravityTorque`（永远水平）、指北扭矩 `addCompassTorque`（test 指北）、
  外壳角速度耦合 `getShellVelocity`（甩动）、限位碰撞 `collide`（x/y 轴 ±90° 反弹 ×-0.9，z 无限制）、
  `CompassTarget`（自然维度目标 (0,0,-1) 指北；下界/末地随机乱转）。
- 渲染插值：`previousAngles → eulerAngles` 的 `lerp(partialTick)`。

### ⚠️ 关键约束：动画逆变换链必须与渲染层级一致

模拟里的三个逆变换（世界向量 → 局部系）按本 mod 层级为：

- **重力**（`addGravityTorque`）：世界 → base → 逆 Y（`transformCompassInverse`）→ 逆 Z（`transformPrimaryInverse`）
  到 gimbal 系；`localDown = (0,-1,0).rotateX(eulerAngles.y)`（compass 相对 gimbal 只绕 X，X 轴=gimbal 局部 X）；
  叉积后 `torque.x += z 分量`（→eulerAngles.x）、`torque.y += x 分量`（→eulerAngles.y）。
- **指北**（`addCompassTorque`）：世界 → base → 逆 Y 到 test 系；`(0,0,-1) × target`，`torque.z += y 分量`。
- **外壳角速度**（`getShellVelocity`）：base 系取 Y 分量 → `shellVelocity.z`（test 层）；
  逆 Y 后取 Z 分量 → `shellVelocity.x`（gimbal 层）；逆 Z 后取 X 分量 → `shellVelocity.y`（compass 层）。

> 曾踩坑：只改渲染层级不改逆变换链 → 盘面"永远水平"失效、部件乱转。
> 改任一层的旋转顺序/轴映射时，三处逆变换必须同步改。

## 交互与随机扰动

- 空手右键（`useWithoutItem`）与扳手（`onWrenched`，IWrenchable）都触发 `randomNudge()`。
- **⚠️ randomNudge 坑**（两次修正）：
  1. 原版 `eulerAngles.z = random × 2π` → test 瞬间旋转大角度。改为不给角度、只给角速度。
  2. 把 `eulerAngles` 全部归零 → 已转过一个大角度指北的 test 会瞬间跳回原点再转回。**必须保留 `eulerAngles.z`**，
     只 `eulerAngles.x/y = 0` + 给 z 一个 ±0.15 rad/tick 随机角速度（test 轻轻摆一下被指北扭矩拉回）。
- `onLoad`（客户端）也会 `randomNudge()`（初始扰动）。

## 简化项（相对 gimbal_sensor）

- 无红石输出（isSignalSource/getSignal/canConnectRedstone 已删）。
- 无 blockstate 旋转：`HORIZONTAL_AXIS` 属性已删，`getBaseQuaternion()` 恒为单位四元数，
  blockstate 单一 `""` variant，模型保持默认朝向。若模型朝向反了，在 `getBaseQuaternion` 加固定 `rotateY(180°)`。
- 无滚轮限位调节（固定 90°）、无护目镜 tooltip、无红石指示灯渲染。
- 选择框/碰撞盒：`Block.box(6, 0, 6, 10, 6, 10)`（中心 4×6×4，不随朝向旋转）。

## 注册链

`MyModBlocks.ins` → `MyModBlockEntities.ins_entity` →
`MyModPartialModels.INS_GIMBAL/COMPASS/YAW`（路径 `my_aero_sensor/ins/*`）→
`MyModCreativeModeTabs` → `CCPeripheralExtenderClient`（Visualizer `InsVisual` + BER `InsRenderer`）。
资源：blockstate / models/item / loot_table / lang（「惯性导航系统」/「Inertial Navigation System」）。

## 参考来源

- `references/Simulated-Project-main/.../content/blocks/gimbal_sensor/`（GimbalSensorBlockEntity / Visual / Renderer）
- `references/Simulated-Project-main/.../assets/simulated/models/block/gimbal_sensor/*.json`（模型结构参照）

## 后续可选项

- [x] 接 `ccpe.sensor_system` Lua API（`getAngles()` 已实现，2026-08：pitch 正=抬头 / roll 正=右翼下压 /
      yaw 0=局部 −Z 指北、正=右转（顺时针从上往下看）−180..180，全部度；门控 = 机体含约束链上有 ≥1 INS；
      算法 = gimbal_sensor 重力投影（XAngle/ZAngle 同款）+ 世界北水平方位（稳态等于指北标记读数）；
      姿态在 `SensorSystemAPI.update()` 每 tick 由 `sub.logicalPose()` 直接计算，不走 BE 的 XAngle/ZAngle）。
- [x] `getPosition()` 已实现（2026-08）：最后放置的 INS 的**世界坐标** {x, y, z}（plot 坐标经
      `SableCompat.projectOutOfSubLevel` 投影到世界，与静压孔高度同源；随物理体移动/旋转实时变化；
      门控与 getAngles 相同 = 机体上有 ≥1 INS）。
- [x] `getOrientation()` / `getAngularVelocity()` 已实现（2026-08）：机体姿态四元数 {x, y, z, w} 与
      世界系角速度 {x, y, z} rad/s（分别复用 `SableCompat.getSubLevelOrientation` / `getAngularVelocity`，
      均为刚体属性直接读取）；门控与 getAngles/getPosition 相同 = 机体上有 ≥1 INS。
- Create 护目镜 tooltip 显示俯仰/滚转/航向读数。
- 非自然维度指北行为开关（当前随机乱转）。
