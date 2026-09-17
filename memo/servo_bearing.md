# Servo Bearing（Lua 舵机轴承）— 实现记录

> 状态：**已实现并进游戏验证通过**（装配 / Lua 控制 / 客户端平滑旋转均正常）。
> 用途：复刻 `create:mechanical_bearing` 的旋转逻辑，做成 CC:Tweaked Lua 控制的舵机，修复原版客户端「末端爬行」问题。
> 方块 id：`ccpe:servo_bearing`；外设类型：`servo_bearing`。
> 模型：**用户自建**（Blockbench），纹理 `assets/ccpe/textures/block/servo_bearing.png`。

## 需求（与 aero_bearing / swivel / mechanical_bearing 的差异）

| 维度 | create:mechanical_bearing（参考） | 本项目 servo_bearing |
|---|---|---|
| 动力来源 | Create 应力网络（RPM → angle 累加） | **无动力**：`hasShaftTowards=false` 不接应力网络，角度由 Lua 目标角驱动 |
| 红石 | POWERED / 扳手 / movementMode | **无红石**：无锁定、无 movementMode |
| 控制方式 | 应力网络 + Sequencer | **CC:Tweaked 外设**（纯目标角舵机语义） |
| 驱动载体 | Create Contraption 实体（`ControlledContraptionEntity`） | 同左（运动学刚性，指哪打哪） |
| 客户端渲染 | `clientAngleDiff` 指数追赶 → 末端爬行 | **修复**：服务端角度直接同步 + 帧间插值 → 无爬行 |
| plate 方块 | 无 | 无（contraption 实体不需要连接点，比 aero_bearing 少一套 plate） |

对比 `aero_bearing`（`memo/my_bearing.md`）：aero_bearing 走 **Sable 物理**（RotaryConstraint PD 伺服驱动从动物理体，动力学、有惯性、气动会反馈）；servo_bearing 走 **Create contraption 实体**（运动学、角度刚性、无惯性）。两者互补：aero_bearing 适合「物理舵面/旋翼」，servo_bearing 适合「精确角度控制」。

## 总体架构

```
CC:Tweaked 电脑（Lua）
   │  setTargetAngle(deg) / assemble() / disassemble() / isAssembled() / getAngle()
   ▼
ServoBearingBlockEntity（服务端权威 angle）
   │  照抄 mechanical_bearing：每 tick angle += step；目标角模式照抄 Sequencer TURN_ANGLE clamp
   │  （每 tick 至多 MAX_ANGULAR_SPEED=12°，剩余度数递减，精确到位）
   ▼  applyRotation() → movedContraption.setAngle(angle)
ControlledContraptionEntity（舵面结构）
   │  在 Sable 物理体内 = KinematicContraption（Sable 自动识别，质量并入母机，见 sable-main
   │  AbstractContraptionEntityMixin）
   ▼
客户端渲染：read() 直接用服务端 angle（lazyTickRate=1 每 tick 同步）
   → getInterpolatedAngle = angleLerp(prevAngle, angle, partialTicks) → 帧间插值，无爬行
```

## 关键设计

### 1. 旋转逻辑（照抄 mechanical_bearing，动力换 Lua）

- 每 tick：`prevAngle = angle`；
- 服务端：目标角推进（等价 mechanical_bearing 的 `sequencedAngleLimit` clamp）：
  ```java
  if (targetAngleLimit != 0) {
      float step = Mth.clamp(MAX_ANGULAR_SPEED, 0f, (float) Math.abs(targetAngleLimit))
              * (float) Math.signum(targetAngleLimit);   // 至多转剩余度数，方向朝目标
      targetAngleLimit -= step;
      angle = (angle + step) % 360;
  }
  ```
- `setTargetAngle(deg)`：`targetAngleLimit = AngleHelper.getShortestAngleDiff(angle, deg)`（带符号剩余度数，走最短路径）；
- `applyRotation()` → `movedContraption.setAngle(angle)` + `setRotationAxis(FACING 轴)`；
- `MAX_ANGULAR_SPEED = 12f`（度/游戏 tick = 240°/s），硬编码常量，**后续可 config 化**。

### 2. 客户端视觉修复（核心新工作，与 mechanical_bearing 的关键差异）

原版爬行根因（已定位）：
- `read()` 回退 angle 并记 `clientAngleDiff`；`tick()` 里 `clientAngleDiff /= 2` 指数衰减 + `getAngularSpeed` 加 `clientAngleDiff/3` 追赶 → 角度指数收敛到服务端值 → 末端最后几度视觉爬行，转速越高越明显。

本实现砍掉整条追赶链：
1. `read()` 客户端**直接用服务端同步的 angle**（不回退、不追赶）；
2. 客户端 `tick()` **不推进角度**，只 `applyRotation()`（让实体 angle 跟上同步值）；
3. `lazyTickRate=1` 每 tick 同步一次角度（服务端权威 → 客户端滞后 ≤ 1 tick）；
4. `getInterpolatedAngle(pt)` 改为**插值式** `AngleHelper.angleLerp(pt, prevAngle, angle)`（原版是外推式 `Mth.lerp(pt, angle, angle+speed)`）。

效果：视觉 = 服务端角度轨迹 + 帧间插值 → 匀速平滑、末端 50ms 内到位、无爬行；且与 Sable 物理侧（本来就吃服务端角度 + 物理子步插值）一致。
渲染器/Visual 传 `partialTicks`（不带原版的 `-1`，因为插值式不是外推式）。

### 3. 装配（照抄 mechanical_bearing.assemble）

```java
BearingContraption contraption = new BearingContraption(false, direction); // 非风车
contraption.assemble(level, worldPosition);
contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
movedContraption = ControlledContraptionEntity.create(level, this, contraption); // this = IControlContraption
movedContraption.setPos(anchor...); movedContraption.setRotationAxis(direction.getAxis());
level.addFreshEntity(movedContraption);
running = true; angle = 0;
```

- **在 Sable sublevel 内装配已验证可行**（`BearingContraption.assemble` 直接用 sublevel 作为 Level；Sable 的 `AbstractContraptionEntityMixin` 自动把 contraption 识别为 KinematicContraption）。
- 空手右键 = `assembleNextTick = true`（下一 tick 装配/拆卸）；Lua `assemble()`/`disassemble()` 为**同步直接调用**（mainThread），行为等价。

## 已实现文件清单

| 文件 | 说明 |
|---|---|
| `src/main/java/com/zzy205/myfirstmod/block/ServoBearingBlock.java` | 方向方块：`hasShaftTowards=false`（无轴/无应力网络）、放置朝向=点击面、空手右键装配、扳手先拆卸 |
| `src/main/java/com/zzy205/myfirstmod/block/ServoBearingBlockEntity.java` | 核心 BE：angle 逻辑 + 目标角 clamp + 客户端修复 + 外设（`Peripheral` 内部类） |
| `src/main/java/com/zzy205/myfirstmod/block/ServoBearingVisual.java` | Flywheel：顶部转盘（`MyModPartialModels.SERVO_BEARING_TOP`）绕 FACING 轴按插值角度转 |
| `src/main/java/com/zzy205/myfirstmod/block/ServoBearingRenderer.java` | BER 回退：同 Visual，`CachedBuffers.partial` + `kineticRotationTransform` + facing 定向 |
| `src/main/java/com/zzy205/myfirstmod/block/MyModPartialModels.java` | 新增 `SERVO_BEARING_TOP = block("servo_bearing/top")` |
| `src/main/java/com/zzy205/myfirstmod/CCPeripheralExtenderClient.java` | **注册 Flywheel Visual + BER**（漏了会导致 top 不显示，见踩坑） |
| `src/main/java/com/zzy205/myfirstmod/block/MyModBlocks.java` | 注册 `servo_bearing` |
| `src/main/java/com/zzy205/myfirstmod/block/MyModBlockEntities.java` | 注册 `servo_bearing_entity` |
| `src/main/java/com/zzy205/myfirstmod/item/MyModCreativeModeTabs.java` | 创造标签加物品 |
| `src/main/java/com/zzy205/myfirstmod/compat/cc/CCPeripheralCapabilities.java` | 外设 capability 注册 |

资源（用户自建模型，Blockbench）：
| 文件 | 说明 |
|---|---|
| `assets/ccpe/models/block/servo_bearing/block.json` | 底座（y 0-12），blockstate 用 |
| `assets/ccpe/models/block/servo_bearing/item.json` | 物品模型（底座+顶部，含 display） |
| `assets/ccpe/models/block/servo_bearing/top.json` | 顶部转盘（y 12-16），partial 用 |
| `assets/ccpe/textures/block/servo_bearing.png` | 纹理 |
| `assets/ccpe/blockstates/servo_bearing.json` | 6 向变体 → `block/servo_bearing/block` |
| `assets/ccpe/models/item/servo_bearing.json` | parent → `ccpe:block/servo_bearing/item` |
| `data/ccpe/loot_table/blocks/servo_bearing.json` | 掉自己 |
| `assets/ccpe/lang/en_us.json` / `zh_cn.json` | 方块名 + tooltip |

## Lua API（外设类型 `servo_bearing`）

```lua
local s = peripheral.wrap("right")
s.assemble()              -- 装配 FACING 方向结构，返回是否成功（已装配直接 true）
s.disassemble()           -- 拆回世界，返回是否成功（未装配直接 true）
s.isAssembled()           -- 是否已装配
s.setTargetAngle(deg)     -- 最短路径定位（需先装配，未装配返回 false）
s.getTargetAngle()        -- 目标角度（0..360）
s.getAngle()              -- 当前实际角度（0..360，服务端权威）
```

## 已知边界 / 注意事项

1. **MAX_ANGULAR_SPEED 硬编码 12°/tick**（240°/s）——目标角推进速度；后续按需加 Config 项（`Config.java` 已有 `ModConfigSpec` 模式可参考）。
2. **每 tick `sendData()`**（lazyTickRate=1）——一个 float 角度的同步包，20 次/秒，可接受；若担心可改为「角度变化 > ε 才发送」。
3. **角度回绕**：`angle % 360` + `normalizeDegrees` 归一化到 [0,360)；`setTargetAngle` 走最短路径（`getShortestAngleDiff`），多圈目标（如 450°）会走 -270° 而非 +450°。
4. **外设 `equals`**：通过 `Peripheral.outer()` 取外层 BE 位置比较（内部类不能直接 `that.worldPosition` 访问外部字段）。
5. **无应力消耗**：`calculateStressApplied` 沿用 KineticBlockEntity 默认（0）；方块虽是 `DirectionalKineticBlock`（IRotate），但 `hasShaftTowards=false` 不进入应力网络。
6. **装配失败**：`assemble()` 返回 false 时，Create 的 `AssemblyException` 已存 `lastException`（`getLastAssemblyException`），**尚未暴露到 Lua**（待办）。

## 踩坑记录（编译/运行）

| 坑 | 解决 |
|---|---|
| `IControlContraption.isAttachedTo` 签名是 `AbstractContraptionEntity`，不是 `ControlledContraptionEntity` | 参数类型用 `AbstractContraptionEntity`（`import com.simibubi.create.content.contraptions.AbstractContraptionEntity`） |
| `SafeBlockEntityRenderer` **无参构造**（不能 `super(context)`），但 `registerBlockEntityRenderer` 需要 `(Context)->Renderer` 工厂 | 保留 `public ServoBearingRenderer(BlockEntityRendererProvider.Context)` 构造器，body 为空（自动调无参 super） |
| 内部类不能 `that.worldPosition` 访问外部类字段 | 加 `private ServoBearingBlockEntity outer() { return ServoBearingBlockEntity.this; }`，用 `that.outer().worldPosition` |
| `Mth.clamp` 不接受 `(float, int, double)`（double 不自动窄化） | 显式 `0f` 和 `(float) Math.abs(...)` |
| **Flywheel Visual + BER 漏注册 → 放置只有底座没 top** | 两处都必须在 `CCPeripheralExtenderClient` 注册（`SimpleBlockEntityVisualizer.builder(...)` + `registerBlockEntityRenderer(...)`），否则顶部转盘不渲染 |

## 待办 / 可扩展

- [ ] `getLastAssemblyException()` 暴露到 Lua（失败原因排查）
- [ ] `MAX_ANGULAR_SPEED` config 化（`Config.java` 加 `ModConfigSpec.DoubleValue`）
- [ ] 连续转动模式 `setAngularSpeed(rpm)`（最初砍掉，如需要再加——对照 mechanical_bearing 的连续 `angle += convertToAngular(speed)`）
- [ ] `assemble()`/`disassemble()` 是否对齐玩家右键的「下一 tick 异步」（当前为同步直接调用，行为等价，暂无需改）
- [ ] 顶部转盘旋转方向/枢轴微调（若视觉角度与实际不符）

## 参考来源

| 参考 | 位置 | 借鉴点 |
|---|---|---|
| `MechanicalBearingBlockEntity` | `references/Create-mc1.21.1-dev/.../bearing/` | 旋转逻辑（angle/applyRotation/assemble/disassemble/IControlContraption） |
| `BearingVisual` / `BearingRenderer` | 同上 | 顶部转盘 OrientedInstance 渲染 / kineticRotationTransform + facing 定向 |
| `MyBearingBlock(Entity/Visual/Renderer)` | `src/main/java/com/zzy205/myfirstmod/block/` | 方块交互 / Visual+BER 注册位置 / 放置朝向 / 扳手语义 |
| `MyModPartialModels` | 同上 | 自定义 PartialModel 注册惯例 |
