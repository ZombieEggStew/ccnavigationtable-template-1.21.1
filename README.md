# CCPE (CC Peripheral Extender) — 模组总览

## 项目信息

| 项 | 值 |
|----|-----|
| Mod ID | `ccpe` |
| Mod 名 | CC Peripheral Extender |
| 平台 | NeoForge 1.21.1 |
| JDK | 21 |
| 包路径 | `com.zzy205.myfirstmod` |
| 编译 | `.\gradlew.bat compileJava` |

## 核心功能

- **微型外设扩展器 (PeripheralExtender)**：附着在任意方块上，通过 CC:Tweaked 按频道无线读取 NBT / 物理数据 / 导航信息，无线红石输出 + 外设代理
- **红石收发器 (RedstoneTransceiver)**：Create Redstone Link 兼容的 CC:T 外设，通过 banner 频道映射 Create 频率网络

---

## 文件结构

```
src/main/java/com/zzy205/myfirstmod/
├── CCNavigationtable.java                ← 主类, 网络包 + 外设注册
├── CCNavigationtableClient.java          ← 客户端屏幕注册
├── Config.java                          ← 4 个配置项
├── block/
│   ├── PeripheralExtenderBlock.java     ← 附着/NBT/右键GUI/ticker
│   ├── PeripheralExtenderBlockEntity.java← 频道/幽灵槽/加载模式/Sable ticket
│   ├── RedstoneTransceiverBlock.java    ← 红石收发器方块
│   ├── RedstoneTransceiverBlockEntity.java← banner数据
│   ├── MyModBlocks.java / MyModBlockEntities.java
├── compat/
│   ├── cc/
│   │   ├── PeripheralExtenderAPI.java   ← require("ccnav.pe")
│   │   ├── PeripheralExtenderRegistry.java← 频道→BE 映射
│   │   ├── CCNavPeripheralExtenderSetup.java
│   │   ├── RedstoneTransceiverPeripheral.java
│   │   └── RedstoneTransceiverRegistry.java
│   ├── create/CreateRedstoneCompat.java ← Create Redstone Link 兼容
│   ├── sable/SableCompat.java          ← 直接 API, 非反射
│   └── jei/AddonJEIPlugin.java
├── network/
│   ├── SensorNbtPayload.java           ← S2C: NBT 推送
│   ├── SensorFilterPayload.java        ← C2S: 频道+加载模式
│   └── ReceiverSyncPayload.java        ← C2S: 收发器 banner
└── screen/
    ├── PeripheralExtenderMenu.java/Screen.java ← NBT树形+频道+加载模式
    ├── RedstoneTransceiverMenu.java/Screen.java← banner队列+幽灵槽
    ├── LoadModeHelper.java             ← 加载模式共用
    └── MyModMenus.java
```

---

## Lua API

### 外设扩展器 (`require("ccnav.pe")`)

```lua
local pe = require("ccnav.pe")

-- 基础
pe.getBlockPos(ch)              -- {x, y, z}
pe.getBlockId(ch)               -- "create:speed_controller"

-- 导航桌制导（直接 API, 不经过 NBT）
pe.getNavTargetPos(ch)          -- {x, y, z}   目标坐标
pe.getNavSelfPos(ch)            -- {x, y, z}   自身坐标
pe.getNavRelativeAngle(ch)      -- 0-360°      指针偏角
pe.getNavDistance(ch)           -- 米          直线距离
pe.getNavHorizontalDistance(ch) -- 米          水平距离
pe.getNavDirection(ch)          -- {dx,dy,dz}  方向向量
pe.getNavBearing(ch)            -- 0-360°      水平方位角
pe.getNavElevation(ch)          -- -90~+90°    仰角

-- Sable 物理
pe.getPhysicsPos(ch)            -- {x, y, z}
pe.getPhysicsVelocity(ch)       -- {vx, vy, vz} (需 velocity_sensor)
pe.getPhysicsAngularVelocity(ch)-- {wx, wy, wz}
pe.getPhysicsOrientation(ch)    -- {x, y, z, w} 四元数
pe.getPhysicsMass(ch)           -- kg
pe.getPhysicsCenterOfMass(ch)   -- {x, y, z}
pe.getPhysicsGravityForce(ch)   -- N

-- NBT 查询
pe.get(ch, "path") / pe.getAll(ch)

-- 无线红石
pe.setRedstoneOutput(ch, 15) / pe.getRedstoneOutput(ch) / pe.getRedstoneInput(ch)

-- 外设代理
pe.getPeripheral(ch)
```

### 红石收发器 (`peripheral.find("ccpe:receiver")`)

```lua
local r = peripheral.wrap("right")
r.getBannerCount() / r.getChannels() / r.getBannerChannel(i)
r.getBannerItem(banner, slot)    -- {id, count, nbt}
r.getRedstoneSignal(ch)          -- 0-15
r.setRedstoneSignal(ch, 15)
```

### 洲际导弹制导示例

```lua
local pe = require("ccnav.pe")
local CH = 1
local dist = pe.getNavDistance(CH)
local bearing = pe.getNavBearing(CH)
local elev = pe.getNavElevation(CH)

if dist < 10 then launchExplosive()
elseif dist < 200 then steer(bearing, -60)
else steer(bearing, math.max(elev, 45)) end
```

---

## 架构要点

### 加载模式

| 模式 | GUI 值 | 行为 |
|------|--------|------|
| 关闭 | 0 | 无 |
| 加载区块 | 1 | `setChunkForced` (单 chunk) |
| 加载物理体 | 2 | Sable ticket + PORTAL ticket + 连接链追踪 |

### SableCompat — 全部直接 API

不再使用反射。`getContainingSubLevel` / `projectOutOfSubLevel` / `getVelocity` / `getAngularVelocity` / `getMass` / `getCenterOfMass` / `getConnectedChain` 等全部直接 import 调用。

### 两套独立频道注册表

`PeripheralExtenderRegistry` (外设扩展器) 和 `RedstoneTransceiverRegistry` (收发器) 完全独立。

---

## 配置项

```toml
sensorNbtPollInterval = 20       # tick, 0=禁用
sensorChunkLoadEnabled = true    # 区块强加载开关
sensorMaxForceLoad = 32          # 同时最大加载数, 0=无限
sensorPortalTicketRadius = 3     # Sable ticket 半径 (chunks)
```

---

## 关键陷阱

1. **`ItemStack.STREAM_CODEC` 不允许空物品** → 用 `ItemStack.OPTIONAL_STREAM_CODEC`
2. **Sable 子次元不能 `setChunkForced`** → 改用 Sable ticket 系统
3. **`setRemoved()` 中 `level.setBlock()` 会卡保存** → `level.isLoaded(pos)` 守卫
4. **Aeronautics bundled JAR 是容器** → 需单独提取 `simulated-neoforge.jar`
5. **不需要 Mixin** — `ccpe.mixins.json` 已删除
