# CC:Tweaked 传感器 Lua API 实现方案

## 概述

为 CCNavigationtable 传感器添加 CC:Tweaked 集成，使计算机可以通过 Lua 无线访问任意位置的传感器数据，用于飞控等自动化控制系统。

---

## 一、架构总览

```
┌──────────────────┐     require("ccnav.sensors")     ┌─────────────────────┐
│  CC:T 计算机       │ ◄────────────────────────────── │  ILuaAPI             │
│  (任意位置/维度)   │                                  │  (全局注入, 服务端)    │
│                  │     sensors.getPos(1)             │                     │
│  local sensors   │ ── sensors.get(1, "Speed") ──►  │  SensorAPI          │
│   = require(...) │     sensors.getAll(1)             │   ├─ Layer1: 快捷读  │
│                  │                                  │   ├─ Layer2: 路径查  │
│                  │     ◄── 返回 Lua Table ──         │   └─ Layer3: 全量NBT│
└──────────────────┘                                  │                     │
                                                      │  SensorRegistry     │
                                                      │  Map<频道, BE>      │
                                                      └─────────┬───────────┘
                                                                │
                                                 ┌──────────────▼───────────┐
                                                 │  MySensorBlockEntity      │
                                                 │  ├─ cachedAttachedBE      │
                                                 │  ├─ cachedNBT + 时间戳    │
                                                 │  ├─ keepChunkLoaded       │
                                                 │  └─ Sable force-load      │
                                                 └──────────────────────────┘
```

---

## 二、核心组件

### 2.1 SensorRegistry — 频道注册表

**文件**: `compat/cc/SensorRegistry.java`

```text
职责: 维护 频道 → MySensorBlockEntity 的一对一映射
结构: static Map<Integer, MySensorBlockEntity>
方法:
  register(channel, be)    → 注册（冲突时 warn + 覆盖）
  unregister(channel, be)  → 注销（仅当 be 匹配时才移除）
  get(channel)             → 返回 BE 或 null
  listChannels()           → 返回所有已注册频道号
```

**注册时机**: `MySensorBlockEntity.onLoad()`（仅服务端）
**注销时机**: `MySensorBlockEntity.setRemoved()`（仅服务端）

每频道一个传感器（端口模型），`scrolledValue` 字段作为频道编号。

---

### 2.2 SensorAPI — 三层 Lua API

**文件**: `compat/cc/SensorAPI.java`

实现 `ILuaAPI`，通过 `getModuleName()` 返回 `"ccnav.sensors"`。

Lua 调用方式: `local sensors = require("ccnav.sensors")`

#### Layer 1: 快捷方法（0 序列化开销）

直接从缓存的 BE 引用读取，完全不走 NBT 序列化：

| Lua 方法 | 数据来源 | 说明 |
|----------|----------|------|
| `sensors.getPos(channel)` | `BE.getBlockPos()` + Sable坐标修正 | `{x, y, z}` |
| `sensors.getBlockId(channel)` | `BE.getBlockState().getBlock()` | `"create:speed_controller"` |
| `sensors.getChannel(channel)` | Registry 直接查 | 返回频道号 |
| `sensors.hasSensor(channel)` | Registry.containsKey | boolean |

#### Layer 2: 路径查询（tick 级缓存）

```lua
sensors.get(channel, "Speed")         -- {16.0}
sensors.get(channel, "x")            -- {100.5}
sensors.get(channel, "Items[0].id")   -- {"minecraft:diamond"}
```

路径语法:
- `"key"` → 顶层 NBT key
- `"a.b.c"` → 嵌套 CompoundTag
- `"list[0]"` → ListTag 索引
- `"list[0].key"` → 列表中元素的字段

同一 tick 内多次 `get()` 查询同一传感器不同路径时，只做一次 NBT 序列化。

#### Layer 3: 全量 NBT

```lua
sensors.getAll(channel)     -- 完整 NBT → Lua Table
```

调用 `saveWithoutMetadata()` 序列化后递归转 Map。包含 `tryAddRealWorldPos` 坐标修正。

所有方法标注 `@LuaFunction(mainThread = true)`，运行在服务器主线程。

---

### 2.3 CCNavSensorsSetup — 注册入口

**文件**: `compat/cc/CCNavSensorsSetup.java`

```text
职责: 调用 ComputerCraftAPI.registerAPIFactory() 注册 ILuaAPIFactory
      → 为每个计算机创建 SensorAPI 实例
```

在 `CCNavigationtable` 构造函数中调用 `CCNavSensorsSetup.register()`。

---

## 三、MySensorBlockEntity 改动

### 3.1 新增字段

```text
cachedAttachedBE: BlockEntity    ← 放置/neighborChanged 时缓存，避免每次 chunk lookup
cachedNBT: CompoundTag           ← 上次序列化结果
cachedNBTTime: long              ← 序列化时的 level.getGameTime()
cacheValidTicks: int = 1         ← 缓存有效期（可配置）
keepChunkLoaded: boolean         ← 是否启用区块/物理体加载
```

### 3.2 新增方法

```text
refreshAttachedBE()           → 重新获取附着方块 BE + 清空 NBT 缓存
getCachedNBT()                → 检查缓存有效期，命中返回缓存，否则重新序列化
tryRegisterPhysicsActive()    → Sable/Aero 物理体 forceload 注册（反射）
tryUnregisterPhysicsActive()  → Sable/Aero 物理体 forceload 注销（反射）
setChunkForceLoaded(boolean)  → vanilla setChunkForced + Sable forceload ticket
```

### 3.3 onLoad / setRemoved

```text
onLoad():
  if (!isClientSide):
    SensorRegistry.register(scrolledValue, this)
    refreshAttachedBE()
    if (keepChunkLoaded):
      setChunkForceLoaded(true)

setRemoved():
  if (!isClientSide):
    SensorRegistry.unregister(scrolledValue, this)
    setChunkForceLoaded(false)   // 防止泄露
    super.setRemoved()
```

---

## 四、Chunk Loading & Sable 适配

### 4.1 Vanilla Chunk Loading

```text
setChunkForceLoaded(true):
  ServerLevel level = (ServerLevel) this.level
  level.setChunkForced(chunkX, chunkZ, true)
  同时 force-load 附着方块所在的 chunk

setChunkForceLoaded(false):
  level.setChunkForced(chunkX, chunkZ, false)
```

### 4.2 Sable 子次元物理体适配

**原理**: Sable 通过 `SubLevel` 系统管理物理化结构。正常情况下，sub-level 的 tracking 范围取决于
`SableConfig.SUB_LEVEL_TRACKING_RANGE`（默认 320）。如果玩家距离 > tracking range，sub-level
会被冻结/卸载。

**适配策略**（两个层次）:

#### 层次1: Sable Force-Load Ticket（推荐）

使用 Sable 提供的 `SubLevelLoadingTicket` API 强制保持 sub-level 活跃：

```text
流程:
1. 反射探测 Sable.HELPER.getContaining(attachedBE) → SubLevel?
2. 如果非 null → 获取 ServerSubLevelContainer
3. 注册自定义 TicketType: SubLevelLoadingTicketType.create(
     "ccnavigationtable:sensor_force_load", BlockPos.CODEC)
4. 调用 container.addForceLoadTicket(subLevel, ourType, sensorBlockPos)
5. 传感器移除时: container.removeForceLoadTicket(subLevel, ourType, sensorBlockPos)
```

这等效于 `/sable forceload add <sub_level>` 命令，但以编程方式实现，
且 ticket 的生命周期与传感器 BE 绑定。

#### 层次2: Tracking Range

Sable 的 tracking range 是全局配置（`sub_level_tracking_range`，默认 320）。
建议在配置文档中说明：使用传感器飞控的服务器应将该值设为 1024。

可以通过 Sable 的 `/sable config` 命令或直接修改 `sable-server.toml`。

#### 反射安全

所有 Sable 交互通过 `try-catch (NoClassDefFoundError | ClassNotFoundException)` 包裹，
Sable 未安装时静默降级（仅使用 vanilla chunk loading）。

---

## 五、MySensorBlock.neighborChanged 适配

```text
neighborChanged(state, level, pos, block, fromPos, isMoving):
  ├─ super / canSurvive 检查（已有）
  ├─ if fromPos == getAttachedPos(state, pos):
  │     BE be = level.getBlockEntity(pos)
  │     if be instanceof MySensorBlockEntity sensorBE:
  │       sensorBE.refreshAttachedBE()   → 重新获取附着 BE + 清空 NBT 缓存
  └─ 返回
```

**场景覆盖**:
- 附着方块被破坏/替换 → cachedAttachedBE 变为 null → 下次 `getCachedNBT()` 返回空
- 附着方块被 Create 扳手拆卸 → 同破坏逻辑
- 附着方块仅状态变化 → BE 引用不变，NBT 缓存自然过期（`cacheValidTicks`）

---

## 六、NBT → Lua 转换

```text
CompoundTag → Map<String, Object>
ListTag     → List<Object>
Byte/Short  → int (Lua 只有 number)
Int/Long    → number
Float/Double→ number
StringTag   → String
空/未知     → tag.getAsString()
```

注入 `_block` 字段标识来源方块注册 ID。

---

## 七、文件清单

| 操作 | 文件路径 | 职责 |
|------|----------|------|
| **新建** | `compat/cc/SensorRegistry.java` | 频道→传感器注册表 |
| **新建** | `compat/cc/SensorAPI.java` | ILuaAPI 实现，三层数据读取 |
| **新建** | `compat/cc/CCNavSensorsSetup.java` | registerAPIFactory 注册入口 |
| **新建** | `compat/cc/SableForceLoadHelper.java` | Sable forceload ticket 反射辅助 |
| **修改** | `CCNavigationtable.java` | 调用 CCNavSensorsSetup.register() |
| **修改** | `block/MySensorBlockEntity.java` | 缓存BE、缓存NBT、chunk load、注册/注销 |
| **修改** | `block/MySensorBlock.java` | neighborChanged BE 刷新 |
| **修改** | `Config.java` | 加 sensorCacheTicks / sensorMaxForceLoad 配置 |

---

## 八、配置项

在 `Config.java` 中新增:

```text
SENSOR_CACHE_TICKS       = 1     (范围 0~20, 0=每次实时读取)
SENSOR_MAX_FORCE_LOAD    = 16    (范围 0~256, 0=不允许, 全局 force-load 上限)
```

---

## 九、性能分析

### 20Hz 飞控场景 (每 tick 1次查询)

| 操作 | 路径 | 预估耗时 |
|------|------|----------|
| `getPos(channel)` | BE 直接读 | ~1μs |
| `getBlockId(channel)` | BE 直接读 | ~1μs |
| `get(channel, "Speed")` | NBT 路径解析 + 缓存序列化 | ~0.2ms |
| `get(channel, "x")` (同tick再次) | 纯路径解析，缓存命中 | ~5μs |
| `getAll(channel)` | 全量序列化+转换 | ~0.5-2ms |

10 个传感器 × 20Hz = 200 次查询/tick (大部分是 Layer1 快捷读取，序列化开销极小)。

---

## 十、实现流程

1. 创建 `SensorRegistry`，实现 register/unregister/get
2. 修改 `MySensorBlockEntity`：添加缓存字段 + onLoad/setRemoved 注册逻辑
3. 创建 `SensorAPI`，实现三个 Layer 的 Lua 方法
4. 创建 `CCNavSensorsSetup`，注册 ILuaAPIFactory
5. 在 `CCNavigationtable` 中调用 setup
6. 修改 `MySensorBlock.neighborChanged` 刷新缓存 BE
7. 创建 `SableForceLoadHelper`，实现反射 Sable forceload
8. 在 `MySensorBlockEntity` 中集成 chunk load + Sable forceload
9. 在 `Config.java` 中添加配置项
10. 编译测试
