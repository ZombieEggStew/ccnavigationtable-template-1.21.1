# Servo Bearing

![servo_bearing](../img/servo_bearing.png)

> In the early days of aviation, aircraft were small and slow: the pilot
> moved the control surfaces through simple cables and pulleys, relying
> purely on muscle power to overcome the aerodynamic forces on them.

> As aircraft grew bigger and faster, the aerodynamic forces on the control
> surfaces (hinge moment) became enormous and beyond human strength. Around
> World War II, hydraulic boosters appeared — acting like "muscles" that
> amplify the pilot's force to drive the control surfaces.

> The Cessna 172, for example, is a low-speed, light trainer whose
> aerodynamic loads stay entirely within human strength, so it still keeps
> this purely mechanical control system to this day. The design is simple,
> highly reliable, and lets the pilot feel the airflow's reaction on the
> surfaces directly — which is very important for flight training.


## Servo Bearing

**No redstone**, **no power input** (controlled by the pilot's psychic powers). The rotation angle is driven entirely by CC:Tweaked Lua as a target-angle servo.

It replicates `create:mechanical_bearing`'s rotation logic (angle advancing per tick, `applyRotation()` → `movedContraption.setAngle(angle)`), just with the power source swapped from the stress network to Lua. Like the mechanical bearing, the assembled structure is a kinematic **contraption entity** — rigid, points exactly where told, no inertia; Sable recognizes it as a kinematic contraption inside a physics body (mass merges into the parent).


## Why a new bearing?

Create and Aeronautics already have two bearings, each with its own design philosophy — the **Servo Bearing** is the third, purpose-built for precise control.

### The powered bearing's rendering problem

`create:mechanical_bearing`'s *client* does not show the server angle directly. It keeps a `clientAngleDiff` that is halved every tick and **chases** the server value exponentially — so at the end of a move the last few degrees visually **crawl** into place. This is purely a **client-side visual artifact**: the server-side angle and the physics are exact, and the rendering lag does **not** affect physics at all.

The Servo Bearing removes that whole chase chain (details below), so the visual is smooth and lands in place — no crawling.

| | `Powered Bearing create:mechanical_bearing` | `Physics Bearing simulated:swivel_bearing` | `ccpe:servo_bearing` |
|---|---|---|---|
| Power input | Create stress network (RPM) | Cogwheel meshed from the side | **None** — pure Lua target angle |
| Driven structure | Kinematic contraption entity | Real physics body (Sable sub-level) | Kinematic contraption entity |
| Dynamics | Rigid, kinematic, no inertia | PD servo, inertia, aero feedback | Rigid, kinematic, no inertia |
| Angle source | `angle += speed` accumulation | Network speed | `setTargetAngle()` shortest path |
| Client rendering | Exponential catch-up → **crawling** | Physics-driven (accurate) | Server angle + frame interpolation |
| Redstone | POWERED / wrench / movementMode | Powered lock | None |


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

They complement each other: use the **Aero Bearing** when you need real
aerodynamic feedback; use the **Servo Bearing** when the control surface must
go exactly where the computer tells it.

## Known limitations
- `MAX_ANGULAR_SPEED` (18°/tick) is hard-coded; a config option is planned.
