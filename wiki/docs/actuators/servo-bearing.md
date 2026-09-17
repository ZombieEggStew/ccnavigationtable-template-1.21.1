# Servo Bearing

![servo_bearing](../img/servo_bearing.png)
> 1.1.4 added

In a real small aircraft there is no power steering: the pilot moves the
ailerons, elevator and rudder by **pulling steel control cables by hand**.
Each control surface is connected to the cockpit stick/pedals through cables,
and every input you feel is a direct pull on those wires. The **Servo Bearing**
replaces those cables with a machine: a CC:Tweaked computer positions the
attached control surface at an exact angle, electrically.

## Why a new bearing?

Two other bearings already exist in this mod, and each has a different
philosophy — the **Servo Bearing** is the third, purpose-built for precise
Lua-driven control.

| | `create:mechanical_bearing` | `simulated:swivel_bearing` | `ccpe:servo_bearing` |
|---|---|---|---|
| Power input | Create stress network (RPM) | Cogwheel meshed from the side | **None** — pure Lua target angle |
| Driven structure | Kinematic contraption entity | Real physics body (Sable sub-level) | Kinematic contraption entity |
| Dynamics | Rigid, kinematic, no inertia | PD servo, inertia, aero feedback | Rigid, kinematic, no inertia |
| Angle source | `angle += speed` accumulation | Network speed / Lua via control mode | `setTargetAngle()` shortest path |
| Client rendering | Exponential catch-up → **crawling** | Physics-driven (accurate) | Server angle + frame interpolation |
| Redstone | POWERED / wrench / movementMode | Powered lock | None |

### The `mechanical_bearing` rendering problem

`create:mechanical_bearing`'s *client* does not show the server angle directly.
It keeps a `clientAngleDiff` that is halved every tick and **chases** the
server value exponentially — so at the end of a move the last few degrees
visually **crawl** into place. This is a purely **client-side visual artifact**:
the server-side angle and the physics are exact, and the rendering lag does
**not** affect physics at all.

The Servo Bearing removes that whole chase chain (details below), so the
visual is smooth and lands in place — no crawling.

## Servo Bearing

`ccpe:servo_bearing` — a Create-style bearing block with **no power input**
(`hasShaftTowards=false`, so it never joins a stress network) and **no
redstone**. The rotation angle is driven entirely by CC:Tweaked Lua through a
target-angle servo.

It replicates `create:mechanical_bearing`'s rotation logic (angle advancing per
tick, `applyRotation()` → `movedContraption.setAngle(angle)`), but powered by
Lua instead of the stress network. Like the mechanical bearing, the assembled
structure is a kinematic **contraption entity** — rigid, points exactly where
told, no inertia, and Sable recognizes it as a kinematic contraption inside a
physics body (mass merges into the parent).

## Lua API

Peripheral type: `servo_bearing` (wrap by direction, e.g.
`peripheral.wrap("right")`, or `peripheral.find("servo_bearing")`).

| Method | Description |
|---|---|
| `assemble()` | Assemble the structure in the FACING direction; returns whether it succeeded |
| `disassemble()` | Disassemble the structure back into world blocks; returns whether it succeeded |
| `isAssembled()` | Whether a structure is currently assembled |
| `setTargetAngle(degrees)` | Position to `degrees` along the **shortest path** (0–360). Requires assembly; returns `false` if not assembled |
| `getTargetAngle()` | Current target angle (0–360) |
| `getAngle()` | Current **actual** angle (0–360, server-authoritative) |

```lua
local s = peripheral.wrap("right")

print(s.assemble())        -- assemble the structure in front of the bearing
s.setTargetAngle(90)       -- rotate to 90° (shortest path)
s.setTargetAngle(-45)      -- rotate back
print(s.getAngle())        -- 45.0 (waiting to reach -45°)
```

## Behavior notes

- **Speed limit**: the angle advances at most **18°/tick (360°/s)** and is
  clamped to the remaining distance, so it always lands exactly on the target.
- **Shortest path**: `setTargetAngle` always rotates the shorter way (e.g.
  from 350° to 10° → +20°). Multi-turn targets (e.g. 450°) resolve to −270°.
- **Server authority + smooth client**: the client reads the server angle
  every tick (`lazyTickRate=1`, lag ≤ 1 tick) and renders with **frame
  interpolation** (`angleLerp(prevAngle, angle, partialTicks)`) instead of the
  original extrapolation — uniform speed, lands within ~50 ms, **no crawling**.
- **Assembly**: right-click with empty hand toggles assembly; Lua
  `assemble()`/`disassemble()` are equivalent (synchronous). Assembly works
  inside a Sable sub-level.
- **No plate block**: unlike `aero_bearing`, no connection plate is needed —
  the contraption entity carries the structure directly.

## Compared to `aero_bearing`

| | `aero_bearing` | Servo Bearing |
|---|---|---|
| Drive | Sable physics (RotaryConstraint PD servo) | Create contraption entity (kinematic) |
| Dynamics | Inertia, aero feedback | Rigid, angle-exact, no inertia |
| Power | Axial stress input (or Lua control mode) | None |
| Best for | Physical control surfaces / rotors | Precise angle control |

They complement each other: use the **Aero Bearing** when aerodynamics should
react physically (sails, rotors), and the **Servo Bearing** when you need the
control surface to go exactly where the computer tells it.

## Known limitations

- `MAX_ANGULAR_SPEED` (18°/tick) is hard-coded; a config option is planned.
- `setTargetAngle` on an unassembled bearing returns `false`.
- Assembly failure reasons are stored internally but not yet exposed via Lua.
- A continuous-rotation mode (`setAngularSpeed(rpm)`) is not implemented yet.
