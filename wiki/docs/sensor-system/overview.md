# Sensor System

> Aviation sensors for physics bodies


The **Sensor System** is a set of attachable avionics blocks for physics bodies (Sable sub-levels). Once installed on a body, CC:Tweaked computers **on the same body** (including constraint chains) can read the sensors through a single Lua module: `require("ccpe.sensor_system")`.

Everything is body-scoped: a computer only sees the sensors installed on its **own** physics body (including constraint chains), so several aircraft can be instrumented independently without interference.

## Sensor blocks

| Block | ID | What it measures | Gated by |
|---|---|---|---|
| [Static Port](static-port.md) | `ccpe:static_port` | Pressure & altitude at the port's own position | ≥ 1 static port |
| [Pitot Tube](pitot-tube.md) | `ccpe:pitot_tube` | Signed ground speed & airspeed along the tube's mouth axis | ≥ 1 pitot tube **and** ≥ 1 static port (pitot-static gate) |
| [Inertial Navigation System](ins.md) | `ccpe:ins` | Attitude (pitch / roll / yaw), position, orientation quaternion, angular velocity | ≥ 1 INS |
| [Flight Management Computer](fmc.md) | `ccpe:fmc` | Mass, gravity force, center of mass, Create stress network of the attached block, propeller speed solver | ≥ 1 FMC |
| [Aviation Integrated Computer](aic.md) | `ccpe:aic` | Counts as **both** an INS and an FMC | — |
| [Short-Range Signal Linker](short-range-linker.md) | `ccpe:short_range_linker` | Per-body peripheral channel + redstone I/O | On a physics body |

## How gating works

Each sensor category requires the physics body (including constraint chains) to have **at least 1** of the corresponding block installed. If the gate fails, the related methods return `nil` (and the sensor entries in `getSensors()` carry `nil` readings):

- Static port readings need ≥ 1 **static port**.
- Speed readings need a complete **pitot-static system** — ≥ 1 **pitot tube AND ≥ 1 static port**.
- Attitude readings need ≥ 1 **INS**.
- Physics-data readings need ≥ 1 **FMC**.
- An **AIC** counts as an INS **and** an FMC at the same time, unlocking both categories with a single block.

## The Lua module

```lua
local ss = require("ccpe.sensor_system")
```

Shared by all sensor blocks:

| Method | Returns | Description |
|---|---|---|
| `isOnBody()` | boolean | Whether the computer is on a physics body |
| `getBodyId()` | string / nil | UUID of the containing physics body |
| `getSensors()` | table | Same-tick snapshot of all sensors on the body: `{type, pos={x,y,z}, pos_rel={x,y,z}, ...}` — `pos` is relative to the physics body origin, `pos_rel` is relative to the current computer |

Plus per-block methods (see each page):

- **Static Port** — `getAltitude()`, `getPressure()`, `getAverageAltitude()`, `getAveragePressure()`, `getWeightedAltitude()`, `getWeightedPressure()`
- **Pitot Tube** — `getSpeed()`, `getAirSpeed()`, `getAverageSpeed()`, `getAverageAirSpeed()`
- **INS** — `getAngles()`, `getPosition()`, `getBodyPosition()`, `getOrientation()`, `getAngularVelocity()`
- **FMC** — `getPhysicsCenterOfMassRel()`, `getPhysicsMass()`, `getPhysicsChainMass()`, `getPhysicsGravityForce()`, `getPhysicsChainGravityForce()`, `getStressRemaining()`, `getStressCapacity()`, `initPropeller(N, S)`, `getPropellerRPM(F, P, V, θ?)`
- **Short-Range Signal Linker** — `getPeripheral(channel)`, `getRedstoneOutput(channel)`, `getRedstoneInput(channel)`, `setRedstoneOutput(channel, signal)`

## Reading semantics

- **Per-sensor positions** — readings are taken at each sensor block's own position, not at the body origin (e.g. each static port / pitot tube has its own independent reading).
- **Per-tick refresh** — readings refresh at most once per tick (at most 1 tick stale); Lua reads perform **zero main-thread scheduling**, so high-frequency polling is effectively free.
- **`getSensors()` snapshot** — all sensors are read on the same tick, so multi-sensor math (e.g. differential pressure) is consistent.
- **Most-recently-placed** — convenience methods like `getSpeed()` / `getAltitude()` return the data of the **most recently placed** block of that type (registration order = placement order, valid within the current session). After a server restart the target may change — read a **specific** sensor via `getSensors()` and identify it by `pos_rel`.

## Quick example

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("computer not on a physics body")
end

print("bodyId:", ss.getBodyId())
print("alt:   ", ss.getAltitude(), "  pressure:", ss.getPressure())
print("speed: ", ss.getSpeed(), "  airspeed:", ss.getAirSpeed())

local a = ss.getAngles()
if a then
    print(string.format("pitch=%.1f roll=%.1f yaw=%.1f", a.pitch, a.roll, a.yaw))
end

-- All sensors, one consistent snapshot
for i, s in ipairs(ss.getSensors()) do
    print(i, s.type, s.pos_rel.x, s.pos_rel.y, s.pos_rel.z)
end
```

## Page index

- [Static Port](static-port.md) — pressure & altitude
- [Pitot Tube](pitot-tube.md) — speed & airspeed (pitot-static gate)
- [Inertial Navigation System](ins.md) — attitude & motion
- [Flight Management Computer](fmc.md) — physics data, stress & propeller solver
- [Aviation Integrated Computer](aic.md) — INS + FMC in one block
- [Short-Range Signal Linker](short-range-linker.md) — per-body channels & redstone I/O
