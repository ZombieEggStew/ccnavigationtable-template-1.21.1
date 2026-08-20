# CCPE — CC Peripheral Extender

> Handy ComputerCraft: Tweaked peripherals for Create: Aeronautics on NeoForge

## What's Included

*   **Micro Peripheral Extender** — wireless NBT reading for any block; wireless redstone signal control; remote peripheral access
*   **Redstone Transceiver** — wireless read and transmit of Create Redstone Link signals
*   **Electronic Transmission** — a rotation speed controller designed specifically for CC:T control

## Features

### 📡 Wireless Block Reading

![1](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/pics/sensor.png?raw=true)

CC:Tweaked requires computers to be directly adjacent to target blocks for peripheral access. While many mods add peripheral support for their blocks, not every block has it.

CCPE adds a **Peripheral Extender**. Attach the sensor to any block, set a channel number, and wirelessly read that block's data from Lua — no cables, no distance limits.

*   Read NBT via `pe.get(channel, path)` or `pe.getAll(channel)`
*   Path syntax: `"Items[0].Count"`, `"ForgeData.CustomName"`, etc.

Data refreshes every server tick (50ms), Lua reads take ~0.02ms/call — ideal for high-frequency monitoring.

```
local pe = require("ccpe.pe")
local data = pe.getAll(1)              -- read full NBT of block on channel 1
local cnt  = pe.get(1, "Items[0].Count")  -- read a specific field by path
```

### 🔌 Peripheral Proxy

*   `pe.getPeripheral(ch)` — wirelessly access the attached block's CC:T peripheral methods

### 🧭 Navigation Table Integration (requires simulated:navigation\_table)

| Method                     |Return    |Description                    |
| -------------------------- |--------- |------------------------------ |
| <code>pe.getNavTargetPos(ch)</code> |<code>{x, y, z}</code> |Target world position          |
| <code>pe.getNavSelfPos(ch)</code> |<code>{x, y, z}</code> |Self world position            |
| <code>pe.getNavDistance(ch)</code> |<code>number</code> |Distance to target (meters)    |
| <code>pe.getNavRelativeAngle(ch)</code> |<code>number</code> |Bearing angle (degrees, 0~360) |

### 🚀 Physics Data (requires Sable/physics structure)

| Method                          |Return       |Description                                   |
| ------------------------------- |------------ |--------------------------------------------- |
| <code>getPhysicsPos(ch)</code>  |<code>{x, y, z}</code> |World position (m)                            |
| <code>getPhysicsOrientation(ch)</code> |<code>{x, y, z, w}</code> |Rotation quaternion                           |
| <code>getPhysicsCenterOfMass(ch)</code> |<code>{x, y, z}</code> |Center of mass (world coordinates)            |
| <code>getPhysicsMass(ch)</code> |<code>number</code> |Mass (kg)                                     |
| <code>getPhysicsChainMass(ch)</code> |<code>number</code> |Total mass of physics body chain (kg)         |
| <code>getPhysicsGravityForce(ch)</code> |<code>number</code> |Gravity force (N)                             |
| <code>getPhysicsChainGravityForce(ch)</code> |<code>number</code> |Total gravity force of physics body chain (N) |

**Velocity methods require sensor attached to `simulated:velocity_sensor`**

| Method                        |Return    |Description                     |
| ----------------------------- |--------- |------------------------------- |
| <code>getPhysicsVelocity(ch)</code> |<code>{x, y, z}</code> |Ground velocity (m/s)           |
| <code>getPhysicsAirVelocity(ch)</code> |<code>{x, y, z}</code> |Airspeed, wind subtracted (m/s) |
| <code>getPhysicsAngularVelocity(ch)</code> |<code>{x, y, z}</code> |Angular velocity (rad/s)        |

### 📶 Wireless Redstone

*   `pe.setRedstoneOutput(ch, 0-15)` — transmit redstone signal (main thread)
*   `pe.getRedstoneOutput(ch)` — read transmitted signal
*   `pe.getRedstoneInput(ch)` — read received signal

***

### 📻 Redstone Transceiver

![2](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/pics/transceiver.png?raw=true)

Allows computers to directly read and transmit Create Redstone Link signals — no need to place multiple redstone link blocks next to your computer.

Each transceiver uses Banners to configure multiple channels, each bound to a pair of redstone frequency items. Access via channel number from Lua:

```
local r = peripheral.find("ccpe:redstone_transceiver")

-- Read the Create redstone network signal on channel 3
local signal = r.getRedstoneSignal(3)

-- Transmit a full signal to the Create network on channel 7
r.setRedstoneSignal(7, 15)
```

| Method                           |Description                                                                         |
| -------------------------------- |----------------------------------------------------------------------------------- |
| <code>getRedstoneSignal(channel)</code> |Read the Create Redstone Link signal bound to the channel (0–15)                    |
| <code>setRedstoneSignal(channel, 0-15)</code> |Transmit a redstone signal to the Create network bound to the channel (main thread) |

***

### 🎛️ Electronic Transmission

![3](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/pics/transmission.png?raw=true)

> **How is it different from the RotationSpeedController?** Using Create's RotationSpeedController as a peripheral (calling `setTargetSpeed()`) triggers `RotationPropagator.handleRemoved()`, which cascades through the entire downstream sub-network clearing all sources. This causes unexpected behavior (e.g. stepper servos from Aeroworks running wild when speed changes). Simulated's AnalogTransmission lacks a CC:T interface and is difficult to fine-tune.

The Electronic Transmission uses `detachKinetics()` + `attachKinetics()` to gently refresh the network without disrupting downstream devices.

Electronic Transmission (`ccpe:transmission_peripheral`) **does not accept redstone signals** — Lua control only. Place it in a kinetic network to adjust downstream speed in real time.

```
local t = peripheral.find("ccpe:transmission_peripheral")

-- Ratio mode: downstream = upstream × ratio
t.setRatio(0.5)   -- reduce to 50%
t.setRatio(3.0)   -- boost to 3× (capped at 256 RPM)

-- Target speed mode: directly set downstream speed (0–256, 2 decimal places)
t.setTargetSpeed(128.56)
print(t.getTargetSpeed())  -- 128.56

-- Query current state
print(t.getRatio())
```

| Method                |Description                                                              |
| --------------------- |------------------------------------------------------------------------ |
| <code>setRatio(ratio)</code> |Set speed ratio (≥0), enters ratio mode (main thread)                    |
| <code>getRatio()</code> |Get current ratio                                                        |
| <code>setTargetSpeed(speed)</code> |Directly set downstream speed 0–256.00, enters target mode (main thread) |
| <code>getTargetSpeed()</code> |Get target speed                                                         |

> Calling `setRatio` switches to ratio mode; calling `setTargetSpeed` switches to target mode. Actual output is capped at 256 RPM in both modes.

***

### 🏗️ Chunk & Physics Body Loading

The sensor can keep its target area loaded:

| Mode              |Description                                                             |Use Case                                                                             |
| ----------------- |----------------------------------------------------------------------- |------------------------------------------------------------------------------------ |
| Off               |No loading                                                              |Short-range use                                                                      |
| <strong>Load Chunk</strong> |Uses vanilla <code>setChunkForced</code> on the sensor's chunk          |Prevent the block's chunk from unloading                                             |
| <strong>Load Physics Body</strong> |Registers Sable force-load ticket + PORTAL ticket that follows movement |Prevent aircraft/physics bodies from being unloaded by Sable's distance optimization |

> Physics body mode automatically tracks the structure's movement, relocating PORTAL tickets to the body's current chunk. Bearing connection chains are refreshed every 5 seconds.

Config (`config/ccpe-common.toml`):

*   `sensorChunkLoadEnabled` — enable chunk loading
*   `sensorMaxForceLoad` — max concurrent loaded sensors
*   `sensorPortalTicketRadius` — PORTAL ticket radius

***

## Quick Start

```
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
pe.setRedstoneOutput(4, 15)  -- power the sensor on channel 4

-- Create redstone link control
local redstone = peripheral.find("ccpe:redstone_transceiver")
redstone.setRedstoneSignal(5, 15)  -- transmit to Create network on channel 5
redstone.getRedstoneSignal(3)      -- read Create network signal on channel 3

-- Electronic transmission control
local trans = peripheral.find("ccpe:transmission_peripheral")
trans.setTargetSpeed(200)  -- set downstream speed to 200 RPM
trans.setRatio(0.75)       -- switch to ratio mode: 75% output
```

## Requirements

*   **NeoForge** 1.21.1
*   **CC:Tweaked** 1.118.0+
*   **Create** 6.0.10+
*   **Simulated** (Create: Aeronautics) 1.3.0+
*   **Sable** 2.0.3+

## Performance

Read operations ~0.02ms/call, redstone write operations ~50ms/call.