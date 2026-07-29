
# CCNavigationTable

一个为 CC:Tweaked 计算机提供**无线传感器数据读取**的 NeoForge 模组，专为飞控自动控制系统设计。

## 模组目标

在 Minecraft 中，许多科技模组（如 Create、Simulated 等）将关键运行数据存储在方块实体的 NBT 中。CCNavigationTable 的目标是让 **CC:Tweaked 计算机能够通过 Lua 无线读取**这些数据，无需物理线缆连接，从而实现对自动化系统的远程监控与飞控编程。

核心特性：
- 🔌 **无线传感器**：传感器可附着在任意方块上，读取其 NBT 数据
- 🌐 **跨维度无距离限制**：CC 计算机可在任意位置、任意维度访问传感器数据
- ⚡ **20Hz 高频读取**：每 tick 可查询一次，配合缓存优化性能
- 🧭 **导航桌兼容**：支持读取 Simulated 导航桌（`navigation_table`）的目标坐标
- ✈️ **Sable / 航空学兼容**：支持子次元坐标修正与 Sub-Level 追踪范围扩展
- 🔢 **频道绑定模型**：每个传感器占用唯一频道号，通过频道访问数据
- 📦 **强制区块加载**：可选的 vanilla chunk force-load，确保传感器所在区块保持活跃

## 基本用法

### 1. 放置传感器

在创造模式物品栏中找到 **Sensor**（传感器），将其放置在需要监控的方块旁（传感器会自动附着到相邻方块）。

打开传感器 GUI 可以：
- 查看/修改频道号
- 设置 NBT 过滤路径
- 切换强制加载开关

### 2. Lua API 调用

在 CC:Tweaked 计算机中输入以下代码：

```lua
-- 引入传感器 API
local sensors = require("ccnav.sensors")

-- 获取频道 1 传感器的附着方块坐标
local pos = sensors.getPos(1)
print("传感器位置: x=" .. pos.x .. ", y=" .. pos.y .. ", z=" .. pos.z)

-- 获取方块 ID
local id = sensors.getBlockId(1)
print("附着方块: " .. id)

-- 按路径读取 NBT 字段（如读取 Create 速度控制器的转速）
local speed = sensors.get(1, "Speed")
print("转速: " .. speed)

-- 路径支持嵌套和列表索引
local count = sensors.get(1, "Items[0].Count")

-- 读取导航桌的当前目标坐标
local target = sensors.getNavTargetPos(2)
print("导航目标: x=" .. target.x .. ", y=" .. target.y .. ", z=" .. target.z)

-- 获取全部 NBT 数据
local allData = sensors.getAll(1)
for k, v in pairs(allData) do
    print(k .. " = " .. tostring(v))
end
```

### 3. API 参考

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `sensors.getPos(channel)` | 频道号 | `{x, y, z}` | 附着方块的世界坐标（含 Sable 坐标修正） |
| `sensors.getBlockId(channel)` | 频道号 | `"mod:block_id"` | 附着方块的注册 ID |
| `sensors.get(channel, path)` | 频道号, NBT 路径 | 字段值 | 按路径读取 NBT 字段 |
| `sensors.getAll(channel)` | 频道号 | Lua Table | 返回完整 NBT 数据 |
| `sensors.getNavTargetPos(channel)` | 频道号 | `{x, y, z}` | 导航桌当前目标坐标 |

NBT 路径语法：
- `"Speed"` — 顶层 key
- `"ForgeData.Speed"` — 嵌套 CompoundTag
- `"Items[0]"` — ListTag 索引
- `"Items[0].Count"` — 列表中元素的字段

## 依赖

- **Minecraft** 1.21.1
- **NeoForge** 
- **CC:Tweaked** (必需)
- **Create** (可选，用于 GUI 风格兼容)
- **Sable** (可选，用于航空学子次元坐标修正)
- **JEI** (可选)

## 开发相关

### 构建

```bash
./gradlew build
```

### 运行客户端

```bash
./gradlew runClient
```

### 刷新依赖

```bash
./gradlew --refresh-dependencies
```

---

Mapping Names:
============
默认使用 Mojang 官方映射名称。详见：https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
