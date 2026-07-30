# CCNavigationtable — 模组总览

## 项目信息

| 项 | 值 |
|----|-----|
| Mod ID | `ccnavigationtable` |
| 平台 | NeoForge 1.21.1 |
| JDK | 21 (`C:\Program Files\Java\jdk-21.0.10`) |
| 包路径 | `com.zzy205.myfirstmod` |
| 编译 | `$env:JAVA_HOME="..."; .\gradlew.bat compileJava` |

## 核心功能

为 Minecraft 飞控系统提供一个**传感器平台**：
- 附着在任意方块上，读取该方块的完整 NBT 数据
- 通过 CC:Tweaked 的 Lua API 按**频道(channel)**无线访问传感器数据
- 支持 Sable 物理子次元（航空学）的坐标修正和物理数据读取
- 支持强制加载附着物理结构（防止距离优化卸载），动态追踪移动 + 连接链
- **无线红石**：Lua 发送/读取 0-15 模拟信号，传感器方块作为红石源输出
- **外设代理**：通过频道无线访问附着方块的 CC:T 外设方法

---

## 文件结构总览

```
src/main/java/com/zzy205/myfirstmod/
├── CCNavigationtable.java              ← 主类, 网络包注册
├── Config.java                         ← 配置文件
├── block/
│   ├── MySensorBlock.java              ← 传感器方块: 附着逻辑, NBT读取, ticker
│   ├── MySensorBlockEntity.java        ← BE: 频道, 幽灵槽, chunk加载, Sable ticket
│   └── MyModBlockEntities.java         ← BE 类型注册
├── compat/
│   ├── cc/
│   │   ├── SensorAPI.java              ← Lua API (ccnav.sensors 模块)
│   │   ├── SensorRegistry.java         ← 频道→BE 映射表
│   │   └── CCNavSensorsSetup.java      ← CC:T API 工厂注册
│   ├── sable/
│   │   └── SableCompat.java            ← Sable 反射兼容层
│   └── jei/                            ← JEI 幽灵槽拖放
├── network/
│   ├── SensorNbtPayload.java           ← S2C: NBT 推送
│   ├── SensorFilterPayload.java        ← C2S: 频道变更
│   └── SensorItemPayload.java          ← C2S: 幽灵物品同步
└── screen/
    ├── MySensorScreen.java             ← GUI: NBT 树形视图
    ├── MySensorMenu.java               ← 容器: 幽灵槽+玩家背包
    └── GhostItemSlot.java              ← 幽灵槽实现
```

---

## Lua API 完整参考

```lua
local sensors = require("ccnav.sensors")

-- ── Layer 1: 基础信息 ──
sensors.getBlockPos(ch)           -- {x, y, z}   方块坐标（含 Sable 修正）
sensors.getBlockId(ch)            -- "create:speed_controller"
sensors.getNavTargetPos(ch)       -- {x, y, z}   导航桌当前目标

-- ── Layer 2: Sable 物理数据 ──
sensors.getPhysicsPos(ch)         -- {x, y, z}        世界空间位置
sensors.getPhysicsVelocity(ch)    -- {vx, vy, vz}     线速度 (需 velocity_sensor)
sensors.getPhysicsAngularVelocity(ch) -- {wx, wy, wz} 角速度 (需 velocity_sensor)
sensors.getPhysicsOrientation(ch) -- {x, y, z, w}     四元数朝向
sensors.getPhysicsMass(ch)        -- 1234.5           质量 kg
sensors.getPhysicsCenterOfMass(ch)-- {x, y, z}        质心局部坐标
sensors.getPhysicsGravityForce(ch)-- 13579.5          重力 N (= mass × 11)

-- ── Layer 3: NBT 查询 ──
sensors.get(ch, "Items[0].Count") -- 路径查询
sensors.get(ch, "FuelLevel")      -- 顶层 key
sensors.getAll(ch)                -- 全量 NBT → Lua Table

-- ── 无线红石 ──
sensors.setRedstoneOutput(ch, 15)  -- 设置频道 ch 的红石输出 0-15
sensors.getRedstoneOutput(ch)       -- 读取当前输出值
sensors.getRedstoneInput(ch)        -- 读取传感器位置的红石输入 (getBestNeighborSignal)

-- ── 外设代理 ──
local p = sensors.getPeripheral(ch)  -- 附着方块的 CC:T 外设对象
if p then p.setSpeed(128) end
```

### 使用示例：飞控

```lua
local s = require("ccnav.sensors")
local ch = 1

-- 惯性导航
local pos = s.getPhysicsPos(ch)
local vel = s.getPhysicsVelocity(ch)
local quat = s.getPhysicsOrientation(ch)

-- 姿态控制
local angVel = s.getPhysicsAngularVelocity(ch)
local mass = s.getPhysicsMass(ch)
local com = s.getPhysicsCenterOfMass(ch)

-- 读取引擎状态（通过 NBT 路径）
local fuel = s.get(ch, "FuelLevel")
local throttle = s.get(ch, "Throttle")

-- 直接操控外设
local sc = s.getPeripheral(ch)
if sc then sc.setSpeed(256) end
```

---

## 架构设计要点

### 1. 频道系统（`SensorRegistry`）

```
传感器放置 → onLoad() → SensorRegistry.register(channel, BE)
   ├─ 冲突检测: 频道已被占用 → 自动分配最小未占用频道
   ├─ 僵尸清理: 遍历 registry 清理 removed=true 的条目
   └─ 广播刷新: 通知所有 BE 更新 occupiedChannels 快照

传感器破坏 → setRemoved() → SensorRegistry.unregister(channel, BE)
   └─ 仅当 BE 匹配时才移除
```

- **数据结构**: `ConcurrentHashMap<Integer, MySensorBlockEntity>`
- **频道号范围**: 0~9999
- **occupiedChannels 同步**: `getUpdateTag()` 通过方块更新同步到客户端，GUI 滚轮跳过已占用频道

### 2. Sable 子次元兼容（`SableCompat`）

**全部通过反射调用**，Sable 未加载时安全降级为 no-op：

| 方法 | Sable API 等价 |
|------|---------------|
| `getContainingSubLevel(be)` | `Sable.HELPER.getContaining(BlockEntity)` |
| `projectOutOfSubLevel(level, pos)` | `Sable.HELPER.projectOutOfSubLevel()` |
| `getSubLevelWorldPos(subLevel)` | `subLevel.logicalPose().position()` |
| `getVelocity(level, pos)` | `Sable.HELPER.getVelocity()` |
| `getAngularVelocity(level, subLevel)` | `physicsSystem().getPhysicsHandle().getAngularVelocity()` |
| `getSubLevelOrientation(subLevel)` | `logicalPose().orientation()` |
| `getMass(subLevel)` | `getMassTracker().getMass()` |
| `getCenterOfMass(subLevel)` | `getMassTracker().getCenterOfMass()` |
| `getConnectedChain(subLevel)` | `SubLevelHelper.getConnectedChain()` |
| `tryAddForceLoadTicket(...)` | `ServerSubLevelContainer.addForceLoadTicket()` |

### 3. 双层加载系统（动态追踪 + 连接链）

```
传感器 onLoad()
  ├─ 在 Sable 子次元中？
  │    ├─ YES → tryRegisterSableTicket()
  │    │    ├─ Layer 1: Sable SubLevel force-load ticket (自定义类型: ccnavigationtable:sensor_force_load)
  │    │    │    └─ 防止 Sable 距离优化卸载物理结构
  │    │    ├─ Layer 2: 连接链 → getConnectedChain()
  │    │    │    └─ 轴承连接的所有子物理结构也添加 ticket
  │    │    └─ Layer 3: PORTAL ticket (addRegionTicket, radius=3, 默认 7×7 chunks)
  │    │         └─ 玩家等效加载等级，实体/方块 tick 全部正常工作
  │    │
  │    └─ 每 tick → serverTick()
  │         ├─ 重新获取连接链（处理轴承动态连接/断开）
  │         ├─ 检查物理结构是否移动到新 chunk → 移动 PORTAL ticket
  │         └─ 每 5 秒强制刷新防止超时
  │
  └─ NO → forceLoadSurroundingChunks()
       └─ vanilla setChunkForced (静态 3×3 chunks)
```

### 4. 无线红石系统

```
CC:T Computer → setRedstoneOutput(ch, 15)
  └─ SensorAPI (mainThread=true, 服务端直接执行)
       └─ MySensorBlockEntity.setRedstoneOutput(15)
            ├─ 钳位 0-15 → 存储 redstoneOutput
            └─ MySensorBlock.updateRedstoneOutput()
                 ├─ level.isLoaded(pos) 安全检查（防止卸载时死锁）
                 ├─ 同步 POWERED 方块状态
                 └─ level.updateNeighborsAt() 传播红石信号

传感器作为红石源：
  MySensorBlock.getSignal() → BE.getRedstoneOutput()
  MySensorBlock.isSignalSource() → POWERED
```

### 5. CC:T 外设代理（`getPeripheral`）

```java
// 1. 直接实现 IPeripheral（少数方块）
if (be instanceof IPeripheral p) return p;

// 2. NeoForge BlockCapability（CC:T 99% 的官方外设走这条路）
return level.getCapability(PeripheralCapability.get(), attachedPos, side);
```

`PeripheralCapability` 是 CC:T 在 NeoForge 上注册外设的标准机制，
`redstone_relay`、`computer`、`modem` 等全部通过它注册。

---

## 配置项（`Config.java`）

```toml
# 传感器 NBT 刷新间隔 (tick), 0=禁用
sensorNbtPollInterval = 20

# 区块加载
sensorChunkLoadEnabled = true      # 3×3 vanilla 强制加载开关
sensorMaxForceLoad = 32            # 同时加载的最大传感器数, 0=无限制
sensorPortalTicketRadius = 3       # Sable 结构 PORTAL ticket 半径 (chunks)
```

---

## 关键陷阱备忘

1. **`ItemStack.STREAM_CODEC` 不允许空物品** → 网络包用 `ItemStack.OPTIONAL_STREAM_CODEC`
2. **`AbstractContainerScreen.clicked()` 传 sentinel `slotId=-999`** → 必须加 `slotId >= 0` 检查
3. **NBT 树每 tick 重建** → 必须用 `expandedPaths` (Set) 恢复展开状态
4. **滚动偏移污染总高度** → `nbtTotalLines = lineY - (TEXT_START_Y - nbtScrollOffset)`
5. **Sable 子次元不能调 `setChunkForced`** → 会导致幽灵方块 + 退出卡死 → 改用 Sable ticket 系统
6. **`PeripheralLookup` 是 Fabric 专有类** → NeoForge 用 `PeripheralCapability` + `level.getCapability()`
7. **`setRemoved()` 中调用 `level.setBlock()` 会卡保存** → 只清内部状态，加 `level.isLoaded(pos)` 守卫
8. **`level.getCapability(PeripheralCapability.get(), pos, side)`** — CC:T NeoForge 外设查询的唯一正确 API

---

## 参考实现对照

| 模组 | 加载方式 | 适用场景 |
|------|---------|---------|
| Aero-Reformation | Sable ticket + PORTAL ticket + AnchorMarker | 飞行器锚点 |
| Create-Aeronautics-FTB-Chunks | FTB 权限 + Sable ticket + PORTAL ticket | 多人 SMP |
| SSRD | Mixin 覆盖 Sable 距离判定 | 单人远距离渲染 |
| **我们的实现** | Sable ticket + PORTAL ticket + 连接链 | 飞控传感器 |
