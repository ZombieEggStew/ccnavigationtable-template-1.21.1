# CCPE — 设计 & 架构

[![en](https://img.shields.io/badge/lang-English-blue)](README.md)

> 为 CC:Tweaked 提供无线传感器访问的 NeoForge 模组 · 1.21.1

## 解决什么问题

CC:Tweaked 的外设机制要求计算机紧贴目标方块。
虽然很多模组为各自的方块适配了外设，但对于没有适配的方块，玩家无法远程读取其状态。

CCPE 提供了一个**无线传感器方块**，附着到任意方块后，通过频道号与计算机无线连接，缓存目标方块的 NBT 数据和常用物理信息，供 Lua 程序高速读取。

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│  服务端 Tick（主线程）                                       │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ PeripheralExtenderBlockEntity.refreshAllCaches()      │  │
│  │                                                       │  │
│  │  cachedAttachedBE      ← level.getBlockEntity()       │  │
│  │  cachedCompoundTag     ← be.saveWithFullMetadata()    │  │
│  │  cachedNavTargetPos    ← nav.getTargetPosition()      │  │
│  │  cachedDistance        ← nav.distanceToTarget()       │  │
│  │  cachedSubLevel        ← Sable.HELPER.getContaining() │  │
│  │  ...                                                  │  │
│  └───────────────────────────────────────────────────────┘  │
│                         │ 每 tick 快照                      │
│                         ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  CC:T 计算机线程（Lua）                                │  │
│  │                                                       │  │
│  │  pe.get(ch, "Items[0].Count")  → 读 CompoundTag 缓存  │  │
│  │  pe.getNavTargetPos(ch)        → 读坐标缓存            │  │
│  │  pe.getPhysicsMass(ch)         → 读物理缓存            │  │
│  │                                                       │  │
│  │  全部 ~0.02ms/次                                       │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 性能特点

缓存数据最多有 1 tick（50ms）的延迟，但读取极快（~0.02ms/次），适合高频轮询场景。
对于写入操作（红石输出），仍通过主线程执行以保证线程安全。

### 技术方案

不使用 Mixin 注入其他模组的方块实体，而是在自己的 BE 中缓存数据。这样做的好处是兼容性好，不需要为每个目标模组编写适配代码。通用的 NBT 读取（`get`/`getAll`）对任意方块都有效；对于已知类型（如导航桌、Sable 物理结构），额外提供类型化的快速读取通道。

### 区块与物理体加载

传感器支持三种加载模式（GUI 中切换）：

| 模式 | 实现 | 说明 |
|---|---|---|
| 0 - 关闭 | 无 | 不干预加载 |
| 1 - 加载区块 | `ServerLevel.setChunkForced(cx, cz, true)` | 原版强制区块加载 |
| 2 - 加载物理体 | `ServerSubLevelContainer.addForceLoadTicket()` + `TicketType.PORTAL` | Sable 物理体防卸载 + 移动追踪 |

模式 2 通过 `serverTick` 每 tick 检查 Sable 物理结构的 `logicalPose`，动态将 PORTAL ticket 移动到物理体当前所在区块。每 5 秒（100 tick）通过 `SubLevelHelper.getConnectedChain()` 刷新轴承连接链，自动追踪新建或断开的约束连接。

相关配置 (`Config.java`)：
- `sensorChunkLoadEnabled` — 全局开关
- `sensorMaxForceLoad` — 最大并发加载数
- `sensorPortalTicketRadius` — PORTAL ticket 覆盖半径

## 项目结构

```
src/main/java/com/zzy205/myfirstmod/
├── block/
│   ├── PeripheralExtenderBlock.java       # 附着逻辑、GUI ticker
│   ├── PeripheralExtenderBlockEntity.java # 缓存字段、tick 刷新
│   └── RedstoneTransceiverBlockEntity.java
├── compat/
│   ├── cc/
│   │   ├── PeripheralExtenderAPI.java     # Lua API
│   │   ├── PeripheralExtenderRegistry.java# 频道注册
│   │   └── RedstoneTransceiverPeripheral.java
│   └── sable/
│       └── SableCompat.java              # Sable 物理 API 封装
└── CCPeripheraExtender.java              # Mod 入口
```

## 依赖

| Mod | 版本 |
|---|---|
| NeoForge | 1.21.1 |
| CC:Tweaked | 1.118.0+ |
| Create | 6.0.10+ |
| Simulated (Aeronautics) | 1.3.0+ |
| Sable | 2.0.3+ |

## 灵感来源

Microcontroller 模组——一个区别于 CC:Tweaked 的电脑模组。它的 Sensor 通过频道与计算机无线连接，直接读取目标方块的 NBT 数据，无需为每个模组单独编写外设适配。这种"通用传感器"的设计理念正是 CCPE 的核心思路。

可惜 Microcontroller 后来消失了，于是我写了自己的 Sensor。

## 协议

MIT
