# My Sensor System（速度传感器 + 气压传感器 + `ccpe.sensor_system`）— 方案设计

> 状态：**方案已定稿，未实现**。本文是实施蓝图，动手前先读参考源码（见文末「参考来源」）。

## 需求

在游戏中加入两个传感器方块，放在物理体（Sable sub-level）上；物理体上的 CC:Tweaked 电脑通过
Lua 模块 `ccpe.sensor_system` **直接**获取所在物理体的物理信息（速度、高度、气压等），并能
**检测所在物理体上是否装有速度传感器 / 气压传感器**。

| 维度 | 需求 | 已确认决策 |
|---|---|---|
| 传感器形式 | 本 mod 自研两个新方块（速度传感器 + 气压传感器），不依赖 Simulated 运行时 | ✅ |
| 物理体范围 | "所在物理体" = 约束链（`getConnectedChain`），轴承连接的子物理体算同一机体（整架飞机） | ✅ |
| 访问方式 | 独立于频道与外设系统：`local ss = require("ccpe.sensor_system")`，无需频道号、无需 `peripheral.wrap` | ✅ |
| 电脑定位 | 电脑通过 `IComputerSystem.getLevel() + getPosition()`（CC:Tweaked 公开 API）获取自身位置 → `SableCompat.getContainingSubLevel` 得到所在物理体引用 | ✅ |
| 数据基准 | **物理体原点**（`subLevel.logicalPose().position()`）：全机体同一高度/位置读数 | ✅ |
| 数据新鲜度 | 1 tick（20tps）可接受 | ✅ |
| 读取频率 | **高频读取**：Lua 读缓存零主线程调度（`mainThread=false`），由服务端每 tick 刷新缓存 | ✅ |
| 门控 | **存在性门控**：读速度类信息必须有速度传感器，读高度/气压类必须有气压传感器；传感器清单/存在性查询本身不门控 | ✅ |
| 传感器注册 | 服务端注册表 `BodySensorRegistry`（物理体 UUID → 传感器集合） | ✅ |

## 总体架构

```
物理体（Sable sub-level，查询含约束链 = 整架飞机）
  ├── 速度传感器方块 (ccpe:speed_sensor)     ──┐
  ├── 气压传感器方块 (ccpe:pressure_sensor)  ──┼→ BodySensorRegistry（物理体UUID → 传感器集合）
  └── CC:Tweaked 电脑（computer block，随物理体装配）
          └─ local ss = require("ccpe.sensor_system")
               │  IComputerSystem.getLevel()+getPosition()  （公开 API，工厂回调传入）
               │       ↓
               │  SableCompat.getContainingSubLevel(level, pos) → 电脑所在物理体 SubLevel 引用
               │       ↓
               │  物理体原点数据：SableCompat.getSubLevelWorldPos(sub)（位置/高度）
               │                + 刚体读取（线速度/角速度/质量/质心/姿态）
               │       ↓
               │  BodySensorRegistry.sensorsOnBody(sub) → 所在物理体（含链）传感器清单 → 门控判断
               └── Lua 方法全部 mainThread=false 读缓存（ILuaAPI.update() 每电脑 tick 主线程刷新）
```

**核心机制全部现成**：
- 电脑位置：`IComputerSystem`（`dan200.computercraft.api.lua`，common-api 公开接口）有
  `ServerLevel getLevel()` / `BlockPos getPosition()`（对照 CC-Tweaked 源码
  `ILuaAPIFactory.create(IComputerSystem)`）。
- 物理数据：`SableCompat` 已有全部读取 API（速度/空气速度/角速度/质量/质心/链质量/朝向/世界位置）。
- 约束链：`SableCompat.getConnectedChain(sub)`。
- 高频缓存模式：`ILuaAPI.update()`（"Called every time the computer is ticked"，服务端主线程）
  与 Peripheral Extender 的"服务端 tick 刷新 BE 缓存"同构，只是缓存宿主是 API 实例。

## 物理数据模型（物理体原点）

| 数据 | 来源 | 说明 |
|---|---|---|
| 位置/高度 | `SableCompat.getSubLevelWorldPos(sub)` = `subLevel.logicalPose().position()` | 物理体原点世界坐标；高度 = `.y` |
| 线速度 | 优先 `physicsSystem.getPhysicsHandle(serverSubLevel).getLinearVelocity()`（刚体原语，实现时确认方法名）；fallback `SableCompat.getVelocity` | 世界系 m/s |
| 角速度 | `SableCompat.getAngularVelocity(level, sub)`（已有） | 世界系 rad/s |
| 姿态 | `subLevel.logicalPose().orientation()` 或参考 mod 的 `LevelPoseProviderExtension.sable$getPose(sub).orientation()`（插值姿态） | 四元数 x/y/z/w |
| 质量/质心/链质量 | `SableCompat.getMass / getCenterOfMass / getChainMass`（已有） | kg |
| 气压 | `pressure = exp(-0.004 × (y − seaLevel))` | 参考 CreateAvionics：海平面 1.0，约每 250 格降 1/e；海平面下 clamp 最大 1.5；逻辑高度顶为 0；`seaLevel = level.getSeaLevel()` |

门控（存在性）：
- 速度类（`getVelocity/getSpeed/getVerticalSpeed/getAngularVelocity`）→ 所在物理体必须有**速度传感器**
- 高度/气压类（`getAltitude/getPressure`）→ 必须有**气压传感器**
- 其余（`isOnBody/getBodyId/getPosition/getOrientation/getMass/getCenterOfMass/getSensors/hasSpeedSensor/hasPressureSensor`）不门控

## Lua API 设计（`ccpe.sensor_system`）

```lua
local ss = require("ccpe.sensor_system")

ss.isOnBody()            -- boolean：电脑是否在物理体上（不在 → 其余方法返回 0/空/nil，不报错）
ss.getBodyId()           -- 所在物理体 UUID 字符串（同机体电脑返回同一值）
ss.getPosition()         -- {x, y, z} 物理体原点世界坐标
ss.getVelocity()         -- {x, y, z} 物理体线速度 m/s（门控：速度传感器）
ss.getSpeed()            -- 标量速度 m/s（门控）
ss.getVerticalSpeed()    -- 垂直速度 m/s，正=上升（门控）
ss.getAngularVelocity()  -- {x, y, z} rad/s（门控）
ss.getOrientation()      -- {x, y, z, w} 姿态四元数
ss.getAltitude()         -- 物理体原点世界 Y（门控：气压传感器）
ss.getPressure()         -- 气压，海平面=1.0（门控）
ss.getMass()             -- 质量 kg
ss.getCenterOfMass()     -- {x, y, z} 质心世界坐标
ss.getSensors()          -- 所在物理体（含约束链）传感器清单：{{type="speed_sensor", pos={x,y,z}}, {type="pressure_sensor", ...}, ...}
ss.hasSpeedSensor()      -- boolean
ss.hasPressureSensor()   -- boolean
```

实现要点：
- `SensorSystemAPI implements ILuaAPI`，`getModuleName() = "ccpe.sensor_system"`，工厂回调参数
  `IComputerSystem` 保存为字段（attach 时获取，电脑位置变化以 `getLevel()/getPosition()` 实时为准）。
- 缓存字段全部 `volatile`（主线程 `update()` 写，电脑线程 Lua 读）。
- `update()`（每电脑 tick，服务端主线程）刷新：所在物理体引用 → 原点位置/速度/气压/传感器清单。
- Lua 方法 `@LuaFunction`（默认 mainThread=false）直接读缓存，零主线程调度。
- 不在物理体上：`isOnBody()=false`，缓存为空默认值。

## 传感器方块（自研，不引用 Simulated 类）

- `SpeedSensorBlock` / `SpeedSensorBlockEntity`（`ccpe:speed_sensor`）
- `PressureSensorBlock` / `PressureSensorBlockEntity`（`ccpe:pressure_sensor`）
- 注册：`MyModBlocks` / `MyModBlockEntities` / `MyModItems` / `MyModCreativeModeTabs` / lang / 模型 / loot / 配方（可选）
- BE 职责（生命周期对齐 Peripheral Extender 模式）：
  - `onLoad`：`SableCompat.getContainingSubLevel(be)` → 注册进 `BodySensorRegistry`
  - `tick`：校验所在物理体 UUID（装配/拆卸/重载后 UUID 变化 → 重注册）
  - `setRemoved`：注销
- 可选：Create 护目镜 tooltip（显示当前读数）
- 传感器本身**不需要**实现 `IPeripheral`（独立于外设系统）；数据源是物理体刚体，传感器只作
  "存在性标记 + 门控依据 + 视觉元素"

## BodySensorRegistry（服务端注册表）

- 新类 `compat/cc/BodySensorRegistry.java`
- 结构：`Map<UUID subLevelId, Set<Entry>>`，`Entry = {SensorType type, BlockPos pos}`
  （`SensorType: SPEED | PRESSURE`）
- `register(be)` / `unregister(be)` / `sensorsOnBody(SubLevel sub)`（含约束链，汇总所有链上 UUID 的传感器）
- 与 `GlobalChannelRegistry`/`PeripheralExtenderRegistry` 同模式；只登记本系统两种传感器，
  不混入 Peripheral Extender

## 参考来源

| 参考 | 位置 | 借鉴点 |
|---|---|---|
| `PhysicsDataAccessorBlockEntity` | `references/Simulated-CC-Compat-master/.../content/blocks/` | **每 tick 恒定刷新缓存字段 + 外设读缓存**（高频零调度）；`Sable.HELPER.getVelocity/getContaining/projectOutOfSubLevel` 用法 |
| `PhysicsDataAccessorPeripheral` | 同上 `content/peripherals/` | 外设方法直接返回 BE 缓存字段 |
| `LevelPoseProviderExtension` | `references/sable-main`（Sable mixin 接口） | 插值姿态读取（`sable$getPose(sub).orientation()`） |
| `AltitudeSensorPeripheral` | `references/CreateAvionics-main/.../compat/simulated/peripherals/` | 气压公式 `exp(-0.004 × (y − seaLevel))`、`getHeight` 语义 |
| `ILuaAPI.update()` / `IComputerSystem` | `references/CC-Tweaked-mc-1.21.x/projects/common-api/.../api/lua/` | 每电脑 tick 主线程钩子；`getLevel()/getPosition()` 公开接口 |
| `SableCompat` | `src/main/java/com/zzy205/myfirstmod/compat/sable/` | 全部物理读取 API（复用，不改） |
| `PeripheralExtenderBlockEntity` | `src/main/java/com/zzy205/myfirstmod/block/` | 生命周期（onLoad/tick/setRemoved）、按需缓存模式（本次改为每 tick 恒定刷新） |
| `InsBlockEntity` | `src/main/java/com/zzy205/myfirstmod/block/` | 惯性导航系统（INS，姿态指示器）：服务端 `XAngle/ZAngle` getter 已预留；未来 `getOrientation` 可直接读 `subLevel.logicalPose().orientation()`，实现记录见 `memo/my_aero_sensor.md` |

## 将改动的文件（实施时）

**新增**：
- `block/SpeedSensorBlock.java`、`block/SpeedSensorBlockEntity.java`
- `block/PressureSensorBlock.java`、`block/PressureSensorBlockEntity.java`
- `compat/cc/SensorSystemAPI.java`（`ccpe.sensor_system` Lua 模块）
- `compat/cc/BodySensorRegistry.java`
- 资源：blockstates、models（2 组）、lang（en_us/zh_cn）、loot table、配方（可选）

**修改**：
- `compat/cc/CCPeripheralExtenderSetup.java`（注册新 API 工厂）
- `block/MyModBlocks.java`、`block/MyModBlockEntities.java`、`item/MyModItems.java`、`item/MyModCreativeModeTabs.java`

**复用不改**：`SableCompat`、`GlobalChannelRegistry`、Peripheral Extender 全部现有机制。

## 风险与验证点（需进游戏实测）

1. **电脑 BE 装配进 Sable sub-level 后** `IComputerSystem.getLevel()/getPosition()` 返回
   sub-level + plot 坐标（大数 ~2×10⁷）——需实测；`getContainingSubLevel` 有 `instanceof SubLevel`
   兜底（`ServerSubLevel` 类结构含 `SubLevel` 引用，实现时确认类型层次）。
2. **物理体移动时电脑位置是否同步**：`ServerComputer.setPosition` 由 CC:Tweaked 内部调用
   （contraption 场景已支持，Sable 场景需验证）；若 CC:Tweaked 未追踪 Sable sub-level 移动，
   需 mod 侧补偿（后备方案：`update()` 里以传感器/物理体为锚，不依赖电脑位置新鲜度）。
3. **刚体线速度原语**：`RigidBodyHandle.getLinearVelocity` 是否存在/签名（javap 确认），
   fallback `SableCompat.getVelocity`。
4. **update() 每 tick 刷新的性能**：每电脑每 tick 一次 `getContainingSubLevel` + 数次 Sable 读取；
   物理体数量多时留意。若过重，降级为"按需刷新 + N tick 兜底"（对齐 PE cacheDirty 模式）。
5. **Sable 卸载/重建**：`update()` 里 `sub.isRemoved()` 检查 + 重查（物理体被距离优化卸载时，
   传感器 tick 的 force-load 机制可复用 PE 的 ticket 方案，本期可选）。
6. 气压公式参数（0.004 衰减、seaLevel 基准）可按预期调整。

## 待确认问题

- [x] 传感器形式：自研两个新方块 — 已确认
- [x] 物理体范围：含约束链 — 已确认
- [x] 访问方式：`ccpe.sensor_system` 独立模块 — 已确认
- [x] 数据基准：物理体原点 — 已确认
- [x] 新鲜度：1 tick — 已确认
- [x] 高频读取：update() 缓存 + mainThread=false — 已确认
- [x] 门控：存在性门控（速度→速度传感器，高度/气压→气压传感器）— 已确认
- [x] 传感器注册：BodySensorRegistry — 已确认
- [ ] 刚体线速度原语方法名（实现时 javap 确认）
- [ ] 电脑在 sub-level 的 getPosition 行为（进游戏验证）
- [ ] 物理体移动时电脑位置同步（进游戏验证）
