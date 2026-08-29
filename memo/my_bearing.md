# My Bearing（自研风帆轴承）— 方案设计

> 状态：**阶段 1-2 已实现（方块 + 注册 + BE 骨架 + 装配/拆卸），待进游戏验证**。
> 实现前先读参考源码（见文末「参考来源」）。

## 需求（与 simulated:swivel_bearing 的差异）

| 维度 | swivel_bearing（参考） | 本项目轴承（my_bearing） |
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
- 新建 `block/MyBearingPlateBlock.java`：plate（link block）方块，同 swivel `SwivelBearingPlateBlock`；
  **不注册物品**（玩家无法直接放置，装配时自动生成），拾取/掉落给主轴承物品
- 注册：`block/MyModBlocks.java` + `block/MyModBlockEntities.java`（沿用现有 `registerBlocks` 模式；
  plate 用 `BLOCKS.register` 直注册，不走 `registerBlockItems`）
- 资源：`blockstates/my_bearing.json`（assembled × facing 变体，未装配用 `my_bearing_item` 模型、
  装配后用 `my_bearing_assembled` 模型）、`blockstates/my_bearing_plate.json`、`models/item/my_bearing.json`
  （parent → `block/my_bearing/my_bearing_item`）、loot table ×2、lang（en_us/zh_cn）、创造模式物品栏

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
  - 装配时在 sub-level plot 内自动放置 plate 方块（`my_bearing_plate`，模型 = `my_bearing_plate.json`，随从动物理体旋转）
  - 创建约束：`pipeline.addConstraint(containingSubLevel, plateSubLevel, new RotaryConstraintConfiguration(anchorPos, platePos, facingVec, plateFacingVec))`（参照 swivel `attachConstraints` 第 571-596 行）
- `disassemble()`：移除约束 + 拆回世界（`SimAssemblyHelper.disassembleSubLevel`）
- **待验证**：重载重连（`checkPersistence` → `reattachConstraint`）与 plate 装配流程需进游戏实测

### 阶段 4：驱动（输入转速 → 物理旋转）（待实现）

每 tick（服务端，`tick()`）：

```java
// 1. 从 Create 应力网络取输入转速
float speed = Math.abs(getSpeed());  // RPM
if (speed < 0.01f) return;           // 无动力不动（可选：无动力时锁定？）

// 2. 转速 → 目标角推进（RPM → 度/tick，同 KineticBlockEntity.convertToAngular）
targetAngleDegrees += convertToAngular(speed) * (正反转方向);

// 3. PD 伺服把从动物理体追到目标角（同 swivel updateServoCoefficients 第 363-401 行）
handle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS,
    Math.toRadians(targetAngleDegrees), kP, kD, false, 0.0);
```

- 关键：**目标角每 tick 推进 = 连续旋转**；PD（kP/kD）由物理引擎在子步级平滑执行 → 高转速也跟手（同 swivel 96 RPM 验证）
- `kP`/`kD` 参照 `SimConfigService` 的 `swivelBearingStiffness` / `swivelBearingDamping`，按从动物理体惯性张量缩放（swivel 第 377-396 行）
- **当前骨架状态**：`updateServoCoefficients()` 暂用常量 kP=6000/kD=1500 简单锁定当前角度；待阶段 4 替换为惯性缩放 + tick 推进

### 阶段 5：渲染

- 简单模型：轴承本体 + 轴（可参考现有 `TransmissionPeripheralRenderer` / `TransmissionPeripheralVisual` 的 OrientedInstance 手法）
- 从动物理体由 Sable 自己渲染（sub-level），不需要额外渲染代码

### 阶段 6：可选增强

- **CC 外设**（如果要 Lua 控制）：仿 `SwivelBearingPeripheral` 加 `getTargetAngle` / `setTargetAngle`（注意官方外设目前只有 getter，setter 需自加）
- **角度传感器**：通过现有 `SableCompat.getAngularVelocity` 读从动物理体角速度
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

## 已实现文件清单（阶段 1-2）

| 文件 | 说明 |
|---|---|
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingBlock.java` | 轴承方块（轴向输入、ASSEMBLED、空手右键装配、扳手先拆卸、Sable 装配移动回调） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingPlateBlock.java` | plate 方块（同 swivel link block，无物品、拾取给主轴承） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingBlockEntity.java` | 轴承 BE：装配/拆卸/重连/约束/NBT（驱动待阶段 4） |
| `src/main/java/com/zzy205/myfirstmod/block/MyBearingPlateBlockEntity.java` | plate BE：parent 关联、被破坏连锁破坏父轴承 |
| `src/main/java/com/zzy205/myfirstmod/block/MyModBlocks.java` | 注册 my_bearing + my_bearing_plate（plate 不注册物品） |
| `src/main/java/com/zzy205/myfirstmod/block/MyModBlockEntities.java` | 注册两个 BE 类型 |
| `src/main/java/com/zzy205/myfirstmod/item/MyModCreativeModeTabs.java` | 物品栏加 my_bearing |
| `src/main/resources/assets/ccpe/blockstates/my_bearing.json` | 未装配 `my_bearing_item` / 装配后 `my_bearing_assembled` × facing |
| `src/main/resources/assets/ccpe/blockstates/my_bearing_plate.json` | plate 模型 × facing |
| `src/main/resources/assets/ccpe/models/item/my_bearing.json` | 物品模型 → `block/my_bearing/my_bearing_item` |
| `src/main/resources/data/ccpe/loot_table/blocks/my_bearing.json` | 掉自己 |
| `src/main/resources/data/ccpe/loot_table/blocks/my_bearing_plate.json` | 掉主轴承（同 swivel dropOther） |
| `src/main/resources/assets/ccpe/lang/en_us.json` / `zh_cn.json` | `block.ccpe.my_bearing` |

## 待确认问题

- [x] plate 方案：照 swivel 注册独立 plate 方块（装配时自动生成进 sub-level，不注册物品）— **已确认**
- [ ] 风帆装配的**范围**：只装 facing 对面的单个结构（风帆骨架），还是 gatherConnectedBlocks 连成一片？（默认：SimAssemblyHelper.assembleFromSingleBlock 同 swivel，当前按此实现）
- [ ] 无动力时从动物理体**锁定还是自由**？（默认：锁定在当前角度，防风帆被气流吹动；当前 updateServoCoefficients 简单锁定，阶段 4 完善）
- [ ] 是否要 **CC 外设**（Lua 控制）？（默认：先不做，后续按需加）
