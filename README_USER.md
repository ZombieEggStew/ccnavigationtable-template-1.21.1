# CCPE — CC Peripheral Extender

> Wireless sensor access for ComputerCraft: Tweaked on NeoForge

## What is this?

CCPE adds **peripheral extender blocks** to CC:Tweaked. Attach a sensor to any block, set a channel number, and wirelessly read that block's data from Lua — no cables, no distance limits.

```lua
local pe = require("ccpe.pe")
local data = pe.getAll(1)              -- read full NBT of block on channel 1
local cnt  = pe.get(1, "Items[0].Count")  -- read a specific field by path
```

## Features

CC:Tweaked's standard peripherals require the computer to be directly adjacent to the target block. While many mods add peripheral adapters for their blocks, not every block has one.

CCPE's sensor caches block data via NBT snapshots — **works with any block**, no need to wait for mod authors to add peripheral support.

The cache refreshes every server tick (50ms), and Lua reads take ~0.02ms per call, suitable for high-frequency monitoring.

## API

### 📡 Wireless Block Reading
- Attach sensor to **any** block
- Read NBT via `pe.get(channel, path)` or `pe.getAll(channel)`
- Path syntax: `"Items[0].Count"`, `"ForgeData.CustomName"`, etc.

### 🧭 Navigation Table Integration
- `pe.getNavTargetPos(ch)` → `{x, y, z}` — target world coordinates
- `pe.getNavSelfPos(ch)` → `{x, y, z}` — self world coordinates
- `pe.getNavDistance(ch)` → `number` — distance to target (meters)
- `pe.getNavRelativeAngle(ch)` → `number` — bearing angle (degrees, 0~360)

### 🚀 Physics Data (requires Sable/physical structure)
| Method | Return | Description |
|---|---|---|
| `getPhysicsPos(ch)` | `{x, y, z}` | World position (meters) |
| `getPhysicsOrientation(ch)` | `{x, y, z, w}` | Rotation quaternion |
| `getPhysicsMass(ch)` | `number` | Mass (kg) |
| `getPhysicsCenterOfMass(ch)` | `{x, y, z}` | Center of mass (world coordinates) |
| `getPhysicsGravityForce(ch)` | `number` | Gravity force (N) |

> Velocity methods require sensor attached to `simulated:velocity_sensor`
> 
| Method | Return | Description |
|---|---|---|
| `getPhysicsVelocity(ch)` | `{x, y, z}` | Ground velocity (m/s) |
| `getPhysicsAirVelocity(ch)` | `{x, y, z}` | Airspeed, wind subtracted (m/s) |
| `getPhysicsAngularVelocity(ch)` | `{x, y, z}` | Angular velocity (rad/s) |

### 📶 Wireless Redstone
- `pe.setRedstoneOutput(ch, 0-15)` — transmit redstone signal (mainThread=true)
- `pe.getRedstoneOutput(ch)` — read transmitted signal
- `pe.getRedstoneInput(ch)` — read received signal

### 🔌 Peripheral Proxy
- `pe.getPeripheral(ch)` — wirelessly access the attached block's CC:T peripheral methods

### 📻 Redstone Transceiver
- Channel-based Create Redstone Link integration
- `receiver.setRedstoneSignal(channel, 0-15)` — transmit to Create network (mainThread=true)
- `receiver.getRedstoneSignal(channel)` — read Create network signal

### 🏗️ Chunk & Physics Body Loading

The sensor can keep its target area loaded:

| Mode | Description | Use Case |
|---|---|---|
| Off | No loading | Short-range use |
| **Load Chunk** | Uses vanilla `setChunkForced` on the sensor's chunk | Prevent the block's chunk from unloading |
| **Load Physics Body** | Registers Sable force-load ticket + PORTAL ticket that follows movement | Prevent aircraft/physics bodies from being unloaded by Sable's distance optimization |

> Physics body mode automatically tracks the structure's movement, relocating PORTAL tickets to the body's current chunk. Bearing connection chains are refreshed every 5 seconds.

Config (`config/ccpe-common.toml`):
- `sensorChunkLoadEnabled` — enable chunk loading
- `sensorMaxForceLoad` — max concurrent loaded sensors
- `sensorPortalTicketRadius` — PORTAL ticket radius

## Quick Start

```lua
local pe = require("ccpe.pe")

-- Read a chest's inventory
local items = pe.getAll(1)
for k, v in pairs(items) do print(k, v) end

-- Navigation guidance (sensor on navigation table required)
local target = pe.getNavTargetPos(2)
local dist   = pe.getNavDistance(2)
local angle  = pe.getNavRelativeAngle(2)
print(string.format("Target: %.0fm away, bearing %.1f°", dist, angle))

-- Physics data
local mass = pe.getPhysicsMass(3)
local com  = pe.getPhysicsCenterOfMass(3)
print(string.format("Mass: %.1f kg, COM: %.1f, %.1f, %.1f", mass, com.x, com.y, com.z))

-- Wireless redstone
pe.setRedstoneOutput(4, 15)  -- activate sensor on channel 4
```

## Requirements

- **NeoForge** 1.21.1
- **CC:Tweaked** 1.118.0+
- **Create** 6.0.10+
- **Simulated** (Create: Aeronautics) 1.3.0+
- **Sable** 2.0.3+

## Performance

Read operations ~0.02ms/call, redstone write operations ~50ms/call.

## Inspiration

The Microcontroller mod — a computer mod distinct from CC:Tweaked. Its Sensor connected to computers wirelessly via channels and read target block NBT data directly, without requiring per-mod peripheral adapters. This "universal sensor" design philosophy is exactly what CCPE aims for.

Unfortunately Microcontroller later disappeared, so I wrote my own Sensor.

## License

MIT
