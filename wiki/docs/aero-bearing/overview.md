# Aero Bearing

![aero_bearing](../img/my_bearing_item.png)
> 1.0.9 added

The **Aero Bearing** is a Create kinetic block paired with a Sable physics body. Power enters through the **half-shaft on the back face** (axial input, like `create:mechanical_bearing`), and the assembled structure becomes an **independent physics body** (sub-level) driven by a rotary constraint — it does **not** carry stress through.

## Modes

The bearing has two modes:

| Mode | Behaviour |
|---|---|
| **Stress Driven** (default) | Input RPM drives the target angle tick-by-tick (`convertToAngular`). The sub-level rotates continuously. Sequenced angle inputs (e.g. `create:sequenced_gearshift`, hand crank, valve handle with `TURN_ANGLE`) are honoured, so the sub-level rotates **exactly** the commanded angle. |
| **Lua Control** | Skipped the "RPM × time = angle" accumulation: the rotation angle is set directly via `setTargetAngle()`. The stress network is still consumed for stress only (impact 4.0, same as `simulated:swivel_bearing`). Entered with `setControlMode(true)` or automatically by the first `setTargetAngle()`. |

## Lua API

The peripheral type is `aero_bearing` (wrap by direction, e.g. `peripheral.wrap("right")`, or `peripheral.find("aero_bearing")`).

| Method | Description |
|---|---|
| `setTargetAngle(degrees)` | Absolute positioning of the sub-level (in degrees). Requires assembly; automatically enters Lua Control mode. `mainThread=true` |
| `getTargetAngle()` | Current target angle (degrees, server-authoritative) |
| `getTargetAngleRad()` | Current target angle (radians) |
| `setControlMode(enabled)` | Enter / leave Lua Control mode (keeps the current orientation, no jump) |
| `isControlMode()` | Whether Lua Control mode is active |
| `isAssembled()` | Whether a sub-level has been assembled |
| `assemble()` | Assemble the structure into a sub-level; returns whether it succeeded |
| `disassemble()` | Disassemble the sub-level back into world blocks; returns whether it succeeded |

```lua
local b = peripheral.wrap("right")

-- Assemble the structure in front of the bearing
print(b.assemble())            -- true if a structure was assembled

-- Lua Control mode: position the sub-level at an absolute angle
b.setTargetAngle(90)           -- rotate to 90° (auto-enters Lua Control mode)
b.setTargetAngle(-45)          -- rotate back

-- Query state
print(b.getTargetAngle())      -- -45.0
print(b.isControlMode())       -- true

-- Leave Lua Control mode, back to stress-driven rotation
b.setControlMode(false)
```

## Differences from `simulated:swivel_bearing`

| | swivel_bearing | Aero Bearing |
|---|---|---|
| Power input | Cogwheel meshed from the side | **Direct axial input** — shaft/network connects straight to the bearing axis |
| Stress network | Through-shaft (stress passes through) | **Not through** — the sub-level is driven by a physics constraint, stress stops at the bearing |
| Angle control | Via network speed (sequenced inputs) | Stress mode: same; **Lua Control mode: angle set directly via Lua, skipping the network angle accumulation** |
