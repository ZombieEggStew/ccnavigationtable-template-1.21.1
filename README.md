# CCPE — Design & Architecture

[![zh-CN](https://img.shields.io/badge/lang-%E4%B8%AD%E6%96%87-blue)](README_CN.md)

> Wireless sensor access for CC:Tweaked · NeoForge 1.21.1

## Problem

CC:Tweaked's peripheral system requires computers to be adjacent to the target block. While many mods provide peripheral adapters for their blocks, blocks without adapters cannot be read remotely.

CCPE provides a **wireless sensor block** that attaches to any block, connects to computers via channel numbers, and caches the target block's NBT and common physics data for fast Lua access.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Server Tick (main thread)                                  │
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
│                         │ snapshot every tick                │
│                         ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  CC:T Computer Thread (Lua)                           │  │
│  │                                                       │  │
│  │  pe.get(ch, "Items[0].Count")  → read CompoundTag     │  │
│  │  pe.getNavTargetPos(ch)        → read position cache   │  │
│  │  pe.getPhysicsMass(ch)         → read physics cache    │  │
│  │                                                       │  │
│  │  All ~0.02ms/call                                      │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Performance

Cached data has at most 1 tick (50ms) of staleness, but reads are extremely fast (~0.02ms/call), suitable for high-frequency polling. Write operations (redstone output) still go through the main thread for thread safety.

### Design Decisions

Rather than using Mixin injection into other mods' block entities, data is cached in our own BE. This avoids compatibility issues and eliminates the need for per-mod adapter code. The generic NBT path (`get`/`getAll`) works with any block; for known types (navigation table, Sable physics structures), typed fast paths are provided.

### Chunk & Physics Body Loading

Three loading modes (switchable in GUI):

| Mode | Implementation | Description |
|---|---|---|
| 0 - Off | None | No loading intervention |
| 1 - Load Chunk | `ServerLevel.setChunkForced(cx, cz, true)` | Vanilla forced chunk loading |
| 2 - Load Physics Body | `ServerSubLevelContainer.addForceLoadTicket()` + `TicketType.PORTAL` | Sable physics body anti-unload + movement tracking |

Mode 2 checks the Sable structure's `logicalPose` every tick via `serverTick`, dynamically relocating PORTAL tickets to the body's current chunk. Bearing connection chains are refreshed every 5 seconds (100 ticks) via `SubLevelHelper.getConnectedChain()`, automatically tracking new or broken constraint connections.

Config (`Config.java`):
- `sensorChunkLoadEnabled` — global toggle
- `sensorMaxForceLoad` — max concurrent loaders
- `sensorPortalTicketRadius` — PORTAL ticket coverage radius

## Project Structure

```
src/main/java/com/zzy205/myfirstmod/
├── block/
│   ├── PeripheralExtenderBlock.java       # Attach logic, GUI ticker
│   ├── PeripheralExtenderBlockEntity.java # Cache fields, tick refresh
│   └── RedstoneTransceiverBlockEntity.java
├── compat/
│   ├── cc/
│   │   ├── PeripheralExtenderAPI.java     # Lua API
│   │   ├── PeripheralExtenderRegistry.java# Channel registration
│   │   └── RedstoneTransceiverPeripheral.java
│   └── sable/
│       └── SableCompat.java              # Sable physics API wrapper
└── CCPeripheraExtender.java              # Mod entry point
```

## Dependencies

| Mod | Version |
|---|---|
| NeoForge | 1.21.1 |
| CC:Tweaked | 1.118.0+ |
| Create | 6.0.10+ |
| Simulated (Aeronautics) | 1.3.0+ |
| Sable | 2.0.3+ |

## Inspiration

The Microcontroller mod — a computer mod distinct from CC:Tweaked. Its Sensor connected wirelessly via channels and read target block NBT directly, without per-mod peripheral adapters. This "universal sensor" design is the core idea behind CCPE.

Microcontroller later disappeared, so I wrote my own Sensor.

## License

MIT
