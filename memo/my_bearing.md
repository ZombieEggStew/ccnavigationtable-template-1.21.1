# My Bearing（航空轴承）— 方案设计

> 状态：**阶段 1-6 已实现**（方块/注册/BE/装配/拆卸/驱动/渲染/CC 外设控制模式），已进游戏验证通过（旋转精准、半轴渲染、Lua 控制）。
> 实现前先读参考源码（见文末「参考来源」）。

## 需求（与 simulated:swivel_bearing 的差异）

| 维度 | swivel_bearing（参考） | 本项目轴承（aero_bearing） |
|---|---|---|
| 应力网络 | **贯通传动杆**：一侧物理体 → 轴承 → 另一侧物理体，应力沿杆传递 | **不贯通**：轴承从一侧物理体（或世界方块）取动力，从动物理体只被带动旋转 |
| 动力输入 | 中间**齿轮**，须从侧面用 Create 齿轮啮合传入 | **直接轴向输入**：像 Create `mechanical_bearing` 那样，传动轴/应力网络直接接在轴承轴上 |
| 连接对象 | 两侧都是物理体（sub-level） | 一端接应力源（轴），另一端接**从动物理体**（sub-level） |
| 用途 | 通用旋转关节 | 驱动飞机风帆/舵面 |

## 总体架构

```
        Create 应力网络（轴/齿轮箱）
                 │
                 ▼  getSpeed() → 目标角速度
      ┌──────────────────────┐
      │   MyBearingBlock      │  ← 继承 DirectionalKineticBlock（轴向输入，同 Create bearing）
      │   MyBearingBlockEntity│  ← 继承 KineticBlockEntity + BlockEntitySubLevelActor
      └──────────┬───────────┘
                 │ RotaryConstraint（Rapier 物理约束，单旋转自由度）
                 ▼
        从动物理体（sub-level，只转动，不传应力）
```

**核心思想**：动力输入走 Create 应力网络（轴向），驱动走 Sable 物理约束（RotaryConstraint + PD 伺服），中间用"输入转速 → 目标角度"桥接。**从动物理体完全在物理引擎里旋转**，与 Create 应力网络无关——所以不贯通应力，也没有 flicker 风险（不 attach/detach 动力学网络）。

## 为什么这样能解决需求

1. **不贯通应力**：从动物理体是 sub-level（物理体），由 Rapier 约束驱动，不是 Create 动能方块 → 应力网络到轴承本体为止。
2. **轴向输入**：方块继承 `DirectionalKineticBlock`，`hasShaftTowards` 返回轴向，传动轴直接接上（同 `BearingBlock`，Create 源码 `BearingBlock.java` 第 14-26 行）。
3. **与 swivel_bearing 的齿轮输入不同**：swivel 用 `ExtraKinetics` + cogwheel 副 BE 从侧面啮合；本项目直接用主 BE 的 `getSpeed()` 作动力源，无副 BE。

## 实现流程（按顺序）

### 阶段 0：依赖确认（已完成）

- `build.gradle` 已有 `compileOnly files("libs/simulated-neoforge-1.21.1-1.3.0.jar")` → 可引用 `dev.simulated_team.simulated.util.SimAssemblyHelper`（swivel 的装配工具）
- 已有 `compat/sable/SableCompat.java`（`getContainingSubLevel` / `getServerContainer` / 坐标系变换工具）

### 阶段 1：方块与注册（已实现）

- 新建 `block/MyBearingBlock.java`：继承 `com.simibubi.create.content.kinetics.base.DirectionalKineticBlock`（同 `BearingBlock` 模式）
  - `getRotationAxis` → `FACING` 的轴
  - `hasShaftTowards` → 轴向（未装配时双面，装配后仅背面，同 swivel 第 93-96 行）
  - 空手右键 = 装配/拆卸（`assembleNextTick = true`，同 swivel 第 66-73 行）
  - `ASSEMBLED` blockstate 属性
  - ~~`ROTATION` blockstate 属性~~：**已删除**——模型改版后绕旋转轴对称（与 swivel 一致），
    「绕 FACING 轴自转 90°」无视觉差异，不再需要（曾用于表达不对称 head 的顶部朝向）
  - **放置朝向 = 被点击的面**（覆写 `getStateForPlacement` → `FACING = context.getClickedFace()`）：
    点地板 → 竖直（默认模型）、点天花板 → 上下颠倒、点墙 → 躺倒；
    不用 Create 默认「视线反方向」（俯仰判定不可控 + 相邻轴自动对齐干扰）
  - **扳手旋转 = Create 标准**（不覆写 `getRotatedBlockState`，用 `IWrenchable` 默认，同 swivel）：
    点击面轴与 FACING 轴**垂直** → `FACING = FACING.getClockWise(点击面轴)`（绕点击面轴转 90°）；
    点击面轴与 FACING 轴**平行** → 绕旋转轴自转 90°，对称模型无视觉差异 → 无操作
- 新建 `block/MyBearingPlateBlock.java`：plate（link block）方块，同 swivel `SwivelBearingPlateBlock`；
  **不注册物品**（玩家无法直接放置，装配时自动生成），拾取/掉落给主轴承物品；
  模型 = 方形板（绕旋转轴对称，同 swivel，无 ROTATION）
- 注册：`block/MyModBlocks.java` + `block/MyModBlockEntities.java`（沿用现有 `registerBlocks` 模式；
  plate 用 `BLOCKS.register` 直注册，不走 `registerBlockItems`）
- 资源：`blockstates/aero_bearing.json`（assembled × facing = 12 变体，无 rotation；未装配用 `aero_bearing_item` 模型、
  装配后用 `aero_bearing_assembled` 模型）、`blockstates/aero_bearing_plate.json`（6 变体）、`models/item/aero_bearing.json`
  （parent → `block/aero_bearing/aero_bearing_item`）、loot table ×2、lang（en_us/zh_cn）、创造模式物品栏

### 阶段 2：方块实体骨架（已实现）

- 新建 `block/MyBearingBlockEntity.java`：继承 `KineticBlockEntity`，实现 `BlockEntitySubLevelActor`（Sable 物理 tick 入口）
- 字段：
  - `@Nullable UUID subLevelID`（从动物理体）
  - `@Nullable BlockPos platePos`（plate 方块位置）
  - `@Nullable RotaryConstraintHandle handle`（约束句柄）
  - `double targetAngleDegrees`（目标角，每 tick 由转速推进）
  - `boolean assembleNextTick` / `boolean assembling` / `AssemblyException lastException`
- NBT 持久化：`subLevelID`、`platePos`（键 `SwivelPlate`）、`targetAngleDegrees`（参照 swivel 第 598-665 行）
- 装配/拆卸：`assemble()` / `disassemble()` / `reattachConstraint()` / `checkPersistence()`（重载重连）
- 新建 `block/MyBearingPlateBlockEntity.java`：parent 关联 + 被破坏时连锁破坏父轴承（同 swivel plate BE）

### 阶段 3：装配（风帆 → sub-level）（已实现）

- 空手右键 → `assemble()`：
  - 用 **SimAssemblyHelper.assembleFromSingleBlock(level, pos, facing 对面方块, false, false)**（swivel 第 415 行）把风帆结构组装成 sub-level；或 Sable 自带 `SubLevelAssemblyHelper.assembleBlocks` + `gatherConnectedBlocks`（`SubLevelAssemblyHelper.java` 第 69/191 行）——二选一，优先 Simulated 的（与 swivel 行为一致）
  - 记录 `subLevelID`
  - 装配时在 sub-level plot 内自动放置 plate 方块（`aero_bearing_plate`，模型 = `aero_bearing_plate.json`，随从动物理体旋转）
  - 创建约束：`pipeline.addConstraint(containingSubLevel, plateSubLevel, new RotaryConstraintConfiguration(anchorPos, platePos, facingVec, plateFacingVec))`（参照 swivel `attachConstraints` 第 571-596 行）
- `disassemble()`：移除约束 + 拆回世界（`SimAssemblyHelper.disassembleSubLevel`）
- **待验证**：重载重连（`checkPersistence` → `reattachConstraint`）与 plate 装配流程需进游戏实测

### 阶段 4：驱动（输入转速 → 物理旋转）（已实现，同 swivel 逻辑）

每 tick（服务端，`tick()`）——**完全移植 swivel 218-259 行的驱动**：

1. 从 Create 应力网络取输入转速 `getSpeed()`（RPM），钳制到 `maxSwivelBearingSpeed`（默认 96，同 swivel `limitCogSpeed`）
2. 转速 → 目标角推进：`targetAngleDegrees += convertToAngular(speed)`；**FACING 指向负轴时转速取反**（同 swivel 236-239 行）
3. **序列化角度输入**（sequenced gearshift / 曲柄等，TURN_ANGLE）：`onSpeedChanged` 把网络传播来的
   `sequenceContext` 换算成 `sequencedAngleLimit`（剩余可转角度，`getEffectiveValue`，同 swivel cogwheel 895-899 行），
   tick 里按剩余角度钳制每 tick 推进量、转完即停 → **精确到位**（同 swivel 224-226 行）；`SequencedAngleLimit` 入 NBT
4. 非序列化且物理暂停（`SubLevelPhysicsSystem.getPaused()`）时不推进目标角（同 swivel 228-232 行）
5. 有转速时 `pipeline.wakeUp` 两侧 sub-level（同 swivel 244-258 行）

PD 伺服（`updateServoCoefficients`，同 swivel 357-401 行）：

- `kP`/`kD` 用 `SimConfigService` 的 `swivelBearingStiffness`（1600）/ `swivelBearingDamping`（40），**按两侧 sub-level 惯性张量沿旋转轴的投影缩放**（`totalInertia`，下限 10）
- 目标角用 `AngleHelper.angleLerp(partialPhysicsTick, last, target)` 在物理 tick 间插值（平滑）
- aero_bearing 无 POWERED 锁定开关（swivel 的红石锁定），恒走锁定分支（防风帆被吹动）

**防晃动关键**：装配成功后调用 `setTargetAngleFromCurrentOrientation(plateState, subLevel)`（同 swivel 337-355 行），
把目标角初始化为当前物理朝向——否则 PD 伺服把从动物理体强扭到 0° 会导致顶部持续晃动。

### 阶段 5：渲染（已实现）

- 轴承本体用 blockstate 模型（`aero_bearing_item` / `aero_bearing_assembled`）
- **背面半个传动杆**（`SHAFT_HALF`，轴向输入口）：
  - Flywheel：`MyBearingVisual`（OrientedInstance，每帧按当前 FACING 动态定向 + 绕 FACING 轴以 `转速 × 时间` 旋转，同 TransmissionPeripheralVisual 手法）
  - BER 回退：`MyBearingRenderer`（`CachedBuffers.partialFacing(SHAFT_HALF, state, facing.getOpposite())` + `kineticRotationTransform`）
  - 从动物理体由 Sable 自己渲染（sub-level），不需要额外渲染代码

### 阶段 6：CC 外设（Lua 控制模式）（已实现）

- 外设类型 `ccpe:aero_bearing`，注册见 `compat/cc/CCPeripheralCapabilities.java`
- **控制模式**（`setControlMode(true)`，`setTargetAngle` 自动进入）：tick 不再按应力网络转速推进目标角
  （应力网络仅保留应力消耗），旋转角度由 Lua 直接控制——**跳过「转速 × 时间 = 角度」的累计过程**；
  进入/退出控制模式时用 `setTargetAngleFromCurrentOrientation` 保持当前朝向（不跳变）
- Lua API：`setTargetAngle(deg)`（绝对定位，需先装配，未装配返回 false）、`getTargetAngle()`（度）、
  `getTargetAngleRad()`（弧度，同 swivel 官方外设）、`isControlMode()`、`setControlMode(bool)`、
  `isAssembled()`（是否已装配）、`assemble()`（装配 FACING 方向结构，返回是否成功）、
  `disassemble()`（拆回世界，返回是否成功）
- 角度服务端权威（`targetAngleDegrees` + `ControlMode` 入 NBT）；PD 伺服照常由 plate BE 每物理 tick 驱动；
  `setTargetAngle` 后 `wakeUp` 两侧 sub-level
- **待验证**：`setTargetAngle` 大角度（>360°）时物理走最短路径还是累计多圈
- **Create 护目镜 tooltip**（`addToGoggleTooltip`，同 transmission_peripheral 结构）：显示模式
  （Lua 控制 / 应力驱动）、当前角度（`getCurrentAngleDegrees()`，从从动物理体实时朝向计算，同
  swivel 337-355 公式）、目标角度、应力统计；lang 键 `tooltip.ccpe.aero_bearing.*`
- **角度传感器**（可选）：通过现有 `SableCompat.getAngularVelocity` 读从动物理体角速度
- **无动力锁定**：`speed==0` 时用 `setMotor(axis, target, kP_high, kD, false, 0)` 锁在当前角（可选）

## 已知风险与注意点

1. **装配复杂**：sub-level 装配是重操作，注意 `assembling` 标志防重入（swivel 第 676-678 行）、装配失败回滚（`AssemblyException`）
2. **卸载/重载**：sub-level 重载后约束句柄失效，需要 `validateConstraintHandle` + 按 `subLevelID` 重连（swivel 第 533-539 行）
3. **flicker 安全**：本设计从动物理体不走 Create 网络，理论上无 flicker；但轴承本体作为 Create 动能方块仍受应力/转速上限约束
4. **转速上限**：建议钳制（同 swivel `MAX_SERVO_RPM=96`），过高转速 PD 追不上或抖动
5. **约束锚点**：`RotaryConstraintConfiguration` 的 pos1/pos2 必须是各自 plot 内坐标（世界空间），normal 需归一化（源码 validate 第 19-28 行）

## 参考来源

| 参考 | 位置 | 借鉴点 |
|---|---|---|
| `SwivelBearingBlockEntity` | `references/Simulated-Project-main/simulated/common/.../swivel_bearing/` | 装配、约束创建（`attachConstraints` 571-596）、PD 伺服（363-401）、NBT、重连 |
| `SwivelBearingBlock` | 同上 | `DirectionalKineticBlock` 轴向输入、`hasShaftTowards`、空手右键装配 |
| `MechanicalBearingBlockEntity` / `BearingBlock` | `references/Create-mc1.21.1-dev/.../bearing/` | 轴向动力输入模式、`getInterpolatedAngle` |
| `RotaryConstraintConfiguration` | `references/sable-main/common/.../constraint/` | 旋转约束配置（pos1/pos2/normal1/normal2） |
| `SubLevelAssemblyHelper` | `references/sable-main/common/.../api/` | 备选装配路径（`assembleBlocks`/`gatherConnectedBlocks`） |
| `SableCompat` | `src/main/java/com/zzy205/myfirstmod/compat/sable/` | 项目现有 Sable 工具（坐标系/容器/角速度读取） |

## 已实现文件清单（阶段 1-4）

| 文件 | 说明 |
|---|---|
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingBlock.java` | 轴承方块（轴向输入、ASSEMBLED、空手右键装配、扳手先拆卸、放置朝向=点击面、扳手旋转 Create 标准（同 swivel，无 ROTATION）、装配后基座选择框 y 0-11.9、Sable 装配移动回调） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingPlateBlock.java` | plate 方块（同 swivel link block，无物品、拾取给主轴承；模型对称无 ROTATION；选中框 y 12.1-16 / 碰撞框 y 12-16 xz 3-13） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingShapes.java` | 基座/plate 选择框与碰撞框（数值对齐 SimBlockShapes：BEARING_ASSEMBLED / PLATE / PLATE_COLLISION） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingBlockEntity.java` | 轴承 BE：装配/拆卸/重连/约束/NBT + 驱动（swivel 式：转速→目标角推进、惯性缩放 PD 伺服、装配时当前朝向初始化防晃动；plate 继承 FACING）+ 应力消耗（impact 4.0，同 swivel 注册值）+ CC 外设（Lua 控制模式：setTargetAngle 直接控制角度，跳过应力网络角度累计）+ 护目镜 tooltip（模式/当前角度/目标角度） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingVisual.java` | Flywheel 渲染：背面半个传动杆（SHAFT_HALF，OrientedInstance） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingRenderer.java` | BER 回退渲染：背面半个传动杆（SHAFT_HALF，kineticRotationTransform） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingPlateBlockEntity.java` | plate BE：parent 关联、被破坏连锁破坏父轴承 |
| `src/main/java/com/zzy205/myfirstmod/block/MyModBlocks.java` | 注册 aero_bearing + aero_bearing_plate（plate 不注册物品） |
| `src/main/java/com/zzy205/myfirstmod/block/MyModBlockEntities.java` | 注册两个 BE 类型 |
| `src/main/java/com/zzy205/myfirstmod/item/MyModCreativeModeTabs.java` | 物品栏加 aero_bearing |
| `src/main/resources/assets/ccpe/blockstates/aero_bearing.json` | 未装配 `aero_bearing_item` / 装配后 `aero_bearing_assembled` × facing（12 变体，无 rotation） |
| `src/main/resources/assets/ccpe/blockstates/aero_bearing_plate.json` | plate 模型 × facing（6 变体） |
| `src/main/resources/assets/ccpe/models/item/aero_bearing.json` | 物品模型 → `block/aero_bearing/aero_bearing_item` |
| `src/main/resources/data/ccpe/loot_table/blocks/aero_bearing.json` | 掉自己 |
| `src/main/resources/data/ccpe/loot_table/blocks/aero_bearing_plate.json` | 掉主轴承（同 swivel dropOther） |
| `src/main/resources/assets/ccpe/lang/en_us.json` / `zh_cn.json` | `block.ccpe.aero_bearing` |

## 待确认问题

- [x] plate 方案：照 swivel 注册独立 plate 方块（装配时自动生成进 sub-level，不注册物品）— **已确认**
- [ ] 风帆装配的**范围**：只装 facing 对面的单个结构（风帆骨架），还是 gatherConnectedBlocks 连成一片？（默认：SimAssemblyHelper.assembleFromSingleBlock 同 swivel，当前按此实现）
- [ ] 无动力时从动物理体**锁定还是自由**？（默认：锁定在当前角度，防风帆被气流吹动；已实现——装配时目标角=当前朝向 + 惯性缩放 PD 伺服恒锁定）
- [ ] 是否要 **CC 外设**（Lua 控制）？（默认：先不做，后续按需加）
