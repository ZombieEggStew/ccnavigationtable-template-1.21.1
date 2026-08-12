# CC:Tweaked 传感器 Lua API 实现方案 — 2026-07-29

## 项目
NeoForge 1.21.1 mod: `ccnavigationtable` (Minecraft 1.21.1, JDK 21)

## 目标
让 CC:Tweaked 计算机通过 Lua 无线访问传感器读取的附着方块 NBT 数据，实现飞控自动控制系统。

---

## 一、架构总览

```
┌─────────────┐  require("ccnav.sensors")   ┌──────────────────┐
│  CC:T 计算机  │ ◄────────────────────────── │  SensorAPI       │
│  (任意位置)   │                             │  (ILuaAPI 全局)   │
│             │  sensors.getPos(1)           │                  │
│  Lua代码:    │  sensors.get(1, "Speed")    │  SensorRegistry   │
│  local s =   │  sensors.getAll(1)          │  channel→BlockPos │
│   require(...│ ──────────────────────────► │        │         │
│  )          │                             │        ▼         │
│  local pos  │                             │  MySensorBE      │
│   = s.getPos│                             │  cachedAttachedBE│
│   (1)       │                             │  .saveWithoutMeta│
└─────────────┘                             └──────────────────┘
```

**关键决策**：
- 使用 `ILuaAPI`（全局 Lua API），**不是 `IPeripheral`**（外设需要物理连接/有线网络）
- 每频道唯一一个传感器（端口绑定模型）
- 无视维度和距离限制（飞控需要跨维度读取物理化结构数据）
- 每 tick（20Hz）可读取，通过缓存优化性能

---

## 二、分层 API 设计

### Layer 1 — 快捷方法（零序列化，直接读 BE 字段）
| 方法 | 返回 | 数据来源 |
|------|------|----------|
| `sensors.getPos(channel)` | `{x, y, z}` | `BE.getBlockPos()` |
| `sensors.getBlockId(channel)` | `"mod:block_id"` | `BE.getBlockState().getBlock()` |
| `sensors.getChannel(channel)` | 频道号 | Registry 内存储 |

### Layer 2 — 路径查询（NBT 路径，按需读取）
| 方法 | 说明 |
|------|------|
| `sensors.get(channel, "Speed")` | 顶层 key |
| `sensors.get(channel, "x")` | NBT 坐标（已 Sable 修正） |
| `sensors.get(channel, "Items[0].Count")` | 列表索引+嵌套 |

路径语法 `key.key.key` 或 `key[n].key`，解析后递归查找 `CompoundTag`/`ListTag`。

### Layer 3 — 全量兜底
| 方法 | 说明 |
|------|------|
| `sensors.getAll(channel)` | 返回完整 NBT 的 Lua Table |

---

## 三、性能设计（20Hz 飞控）

### 缓存策略
```
MySensorBlockEntity:
  cachedAttachedBE: BlockEntity       ← 放置/neighborChanged 时刷新
  cachedNBT: CompoundTag              ← 序列化缓存
  cachedNBTTime: long                 ← gameTime 时间戳
  CACHE_VALID_TICKS = 1               ← 每 tick 刷新一次（20Hz）
  
getCachedNBT():
  if (gameTime - cachedNBTTime < CACHE_VALID_TICKS)
      return cachedNBT                ← 缓存命中，0 开销
  cachedNBT = cachedAttachedBE.saveWithoutMetadata(registryAccess)
  tryAddRealWorldPos(cachedNBT)       ← Sable 坐标修正
  cachedNBTTime = gameTime
  return cachedNBT
```

- 同一 tick 内多次查询 `get(1, "x")` + `get(1, "Speed")` 只序列化一次
- `saveWithoutMetadata` 代替 `saveWithFullMetadata`（省掉不必要的元数据序列化）
- **Lua 查询路径不调用 `setChanged()` / `sendBlockUpdated()`**（只读操作不需要发包）
- Layer 1 方法完全不走序列化

---

## 四、新增/修改文件清单

### 新建文件
1. **`compat/cc/SensorRegistry.java`**
   - 静态 `Map<Integer, BlockPos>` — 频道→传感器坐标
   - `register(channel, pos)` / `unregister(channel, pos)` / `get(channel)`
   - 冲突检测：同频道重复注册时 warn

2. **`compat/cc/SensorAPI.java`**
   - 实现 `ILuaAPI`，module name = `"ccnav.sensors"`
   - `@LuaFunction(mainThread = true)` 标注所有方法
   - Layer 1/2/3 方法实现
   - `convertNBTToLua()` / `resolvePath()` 工具方法

3. **`compat/cc/CCNavSensorsSetup.java`**
   - `ComputerCraftAPI.registerAPIFactory()` 注册入口
   - `static register()` 在 mod 初始化时调用

### 修改文件
4. **`MySensorBlockEntity.java`** — 核心修改：
   - 新增 `cachedAttachedBE: BlockEntity`
   - 新增 `cachedNBT: CompoundTag` + `cachedNBTTime: long`
   - 新增 `keepChunkLoaded: boolean`（持久化 NBT）
   - `onLoad()`: `SensorRegistry.register(channel, this)` + `refreshAttachedBE()` + `tryEnableChunkLoad()` + `tryRegisterSableTracking()`
   - `setRemoved()`: `SensorRegistry.unregister(channel)` + `releaseChunkLoad()` + `releaseSableTracking()`
   - `getCachedNBT()`: 带时间戳的序列化缓存
   - `refreshAttachedBE()`: 重新获取附着方块 BE 引用

5. **`MySensorBlock.java`** — `neighborChanged` 扩展：
   - 检测 `fromPos == getAttachedPos(state, pos)` → 调 `refreshAttachedBE()`
   - 如果新的附着方块没有 BE → 清空 `cachedAttachedBE` 和 `cachedNBT`

6. **`CCNavigationtable.java`** — 主 mod 类：
   - 调用 `CCNavSensorsSetup.register()`

7. **`Config.java`** — 新增配置项：
   - `SENSOR_CACHE_TICKS`（默认 1，缓存有效期）
   - `SENSOR_MAX_FORCE_LOAD`（默认 32，全局最大 force-load 传感器数量）

---

## 五、Chunk Loading 设计

### Vanilla Force-Load
```
开启: ServerLevel.setChunkForced(chunkX, chunkZ, true)
      → 同时 force-load 附着方块所在 chunk
关闭: ServerLevel.setChunkForced(chunkX, chunkZ, false)
BE移除: 自动释放 force-load（防止泄露）
```

- 通过 GUI toggle 控制（`scrolledValue` / `selectIndex` 之外加开关）
- 持久化于 `keepChunkLoaded` NBT 字段
- Config `SENSOR_MAX_FORCE_LOAD` 限制全局数量
- `setChunkForced` 自动写入 `forced_chunks.dat`，重启后恢复

---

## 六、Sable / 航空学兼容

### Sable Sub-Level Tracking 系统
Sable 使用 sub-level tracking system 管理物理化结构的加载和渲染。
`SubLevelTrackingSystem` + `SubLevelTrackingPointSavedData` 控制 tracking range。

### 适配策略
1. **Tracking Range 设置为 1024**：
   ```
   传感器开启 keepChunkLoaded →
     → 反射获取传感器所在的 Sable ServerSubLevel
     → 通过 SubLevelTrackingSystem 将 tracking range 设为 1024
     → 确保物理化结构在远距离仍保持模拟活跃
   ```

2. **退路方案：Sable forceload 指令**：
   - 也可用 Sable 的 forceload 机制强制加载
   - 反射调用 Sable API（类似 `tryAddRealWorldPos` 的反射模式）

3. **实现方式**：
   在 `MySensorBlockEntity` 中加 `tryRegisterSableTracking()` / `releaseSableTracking()`，
   通过反射探测 Sable 类，检测不到就静默跳过（不影响无 Sable 环境）。

4. **与现有关联**：
   已有的 `tryAddRealWorldPos()` 处理了坐标修正，tracking 确保物理模拟持续运行，
   两者互补：坐标对 + 数据新鲜。

---

## 七、实现流程

### 初始化流程
```
1. CCNavigationtable 构造函数
   └─ CCNavSensorsSetup.register()
      └─ ComputerCraftAPI.registerAPIFactory(computer → new SensorAPI())

2. 传感器放置 (MySensorBlockEntity.onLoad)
   ├─ SensorRegistry.register(scrolledValue, getBlockPos())
   ├─ refreshAttachedBE() → cache BlockEntity ref
   └─ if keepChunkLoaded:
       ├─ setChunkForced(true) — vanilla
       └─ tryRegisterSableTracking() — 反射 Sable
```

### Lua 查询流程
```
sensors.getPos(1)
  → SensorRegistry.get(1) → BlockPos
  → level.getBlockEntity(pos) → MySensorBlockEntity
  → sensor.getBlockPos() → 返回 (Layer 1, 0 序列化)

sensors.get(1, "Speed")
  → SensorRegistry.get(1) → MySensorBlockEntity
  → sensor.getCachedNBT() → 缓存命中/序列化一次
  → resolvePath("Speed") → 路径递归查找
  → convertTagToLua() → 返回 (Layer 2, 序列化一次/tick)

sensors.getAll(1)
  → getCachedNBT() → convertNBTToLua() → 返回全量 (Layer 3)
```

### 关闭流程
```
传感器破坏/替换 (MySensorBlockEntity.setRemoved)
  ├─ SensorRegistry.unregister(scrolledValue)
  ├─ if keepChunkLoaded:
  │   ├─ setChunkForced(false)
  │   └─ releaseSableTracking()
  └─ cachedAttachedBE = null

neighborChanged (附着方块变化)
  └─ refreshAttachedBE() → 重新获取或清空
```

---

## 八、关键陷阱备忘

1. **`ILuaAPI` vs `IPeripheral`** — 需要无线访问必须用 `ILuaAPI`，外设必须物理连接
2. **`mainThread = true`** — 所有访问 MC 世界的方法必须标注，调用在服务器主线程执行
3. **`onLoad` 重复调用** — chunk 加载时也会调，用 `level.isClientSide` 过滤 + 注册表覆盖写入
4. **缓存时间戳用 `gameTime`** — 不是 `System.currentTimeMillis()`，tick 对齐
5. **NBT→Lua Table** — CC:T 自动转换 `Map<String, Object>`，但 `byte[]`/`int[]` 数组需转 List
6. **force-load 泄露** — `setRemoved()` 必须释放，否则 chunk 永久 force-loaded
7. **Sable 反射安全** — 和 `tryAddRealWorldPos` 一样 wrap 在 try-catch 中
8. **Channel 冲突** — 同频道重复注册时 warn，后者覆盖前者
