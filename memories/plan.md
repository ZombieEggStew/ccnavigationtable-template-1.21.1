# CC:Tweaked 传感器 Lua API 实现方案

## 概述

为 CCNavigationtable 传感器添加 CC:Tweaked 集成，使计算机可以通过 Lua 无线访问任意位置的传感器数据，用于飞控等自动化控制系统。

---

## ✅ 已完成

### 频道注册系统
- `compat/cc/PeripheralExtenderRegistry.java` — 频道→BE 一对一映射
  - `register(channel, be)` — 冲突时自动重分配，清理僵尸条目
  - `unregister(channel, be)` — 仅当 BE 匹配时移除，自动通知剩余传感器刷新
  - 防止 chunk 重载导致的同一 BE 多频道残留
  - 自动跳过 `removed=true` 的僵尸

### PeripheralExtenderAPI — 三层 Lua API
- `compat/cc/PeripheralExtenderAPI.java` — `ILuaAPI`，`getModuleName() = "ccnav.pe"`
- `compat/cc/CCNavPeripheralExtenderSetup.java` — `ComputerCraftAPI.registerAPIFactory()`
- `CCNavigationtable.commonSetup` — 检测 CC:T 加载后注册
- **tick 级 NBT 缓存** — 同 tick 内多次查询只序列化一次

Lua 使用方式：
```lua
local pe = require("ccnav.pe")
```

| Layer | Lua 方法 | 说明 |
|-------|----------|------|
| 1 | `pe.getBlockPos(ch)` | `{x, y, z}`，含 Sable 坐标修正 |
| 1 | `pe.getBlockId(ch)` | 方块注册 ID 字符串 |
| 1 | `pe.getNavTargetPos(ch)` | 目标世界坐标 `{x, y, z}`（导航桌专用，直接 API） |
| 1 | `pe.getNavRelativeAngle(ch)` | 指针偏角 0-360°（导航桌专用，直接 API） |
| 1 | `pe.getNavSelfPos(ch)` | 导航桌自身投影坐标 `{x, y, z}`（发射点参考） |
| 1 | `pe.getNavDistance(ch)` | 直线距离（米） |
| 1 | `pe.getNavHorizontalDistance(ch)` | 水平距离 XZ（米） |
| 1 | `pe.getNavDirection(ch)` | 世界系归一化方向 `{dx, dy, dz}` |
| 1 | `pe.getNavBearing(ch)` | 水平方位角 0-360°（世界系，atan2(dx,dz)） |
| 1 | `pe.getNavElevation(ch)` | 仰角 -90°~+90°（水平面上为正） |
| 2 | `sensors.get(ch, path)` | 路径查询，语法: `"id"` / `"a.b.c"` / `"Items[0].Count"` |
| 3 | `sensors.getAll(ch)` | 全量 NBT → Lua Table |

### GUI 增强
- 放置传感器时自动从小到大分配最小未被占用的频道号
- 滚轮切换频道时跳过已被其他传感器占用的频道
- 左键/右键点击 NBT 叶子节点 → 复制 `sensors.get(频道,"路径")` 到剪贴板
- 右键点击非叶子节点 → 复制路径
- 复制提示在窗口底部居中显示，3秒后消失
- 滚动条修正（4px 底部裁剪）

### 数据流网关
- `SensorFilterPayload` 处理器通过 `SensorRegistry.register()` 验证频道变更
- `useWithoutItem` 从 `SensorRegistry` 直接读取已占用频道列表（不依赖 BE 缓存）
- `MySensorBlock.getAttachedBlockNBT()` 改为 `public`

---

## ❌ 待完成

### Layer 1 补充方法
- `sensors.getChannel(ch)` — 返回频道号
- `sensors.hasSensor(ch)` — boolean

### MySensorBlockEntity 性能优化
- `cachedAttachedBE: BlockEntity` — 避免每次 chunk lookup
- `cachedNBT: CompoundTag` + `cachedNBTTime: long` — BE 端 NBT 缓存
- `refreshAttachedBE()` — neighborChanged 时刷新

### Chunk Loading & Sable 适配
- `keepChunkLoaded` 开关
- `setChunkForceLoaded(boolean)` — vanilla `setChunkForced`
- `SableForceLoadHelper.java` — 反射探测 Sable，注册/注销 `SubLevelLoadingTicket`

### 配置
- `Config.java`: `SENSOR_CACHE_TICKS`, `SENSOR_MAX_FORCE_LOAD`

### neighborChanged
- `MySensorBlock.neighborChanged` 检测附着方块变化 → `refreshAttachedBE()`

### 区块加载开关
### 方块命名 窗口标题 问题