# Inertial Navigation System (INS)

![ins](../img/ins_item.png)

The **Inertial Navigation System** (`ccpe:ins`) is an attitude indicator block for physics bodies (Sable sub-levels). The center block's red end always points north.

## Attitude readings gate

All INS-gated methods require the physics body (including constraint chains) to have **at least 1 INS** (`ccpe:ins`) installed. Without one, they all return `nil`:

| Method | Returns | Description |
|---|---|---|
| `getAngles()` | table / nil | Body attitude `{pitch, roll, yaw}` in **degrees** (see convention below) |
| `getPosition()` | table / nil | World position `{x, y, z}` of the **most recently placed** INS block |
| `getBodyPosition()` | table / nil | World position `{x, y, z}` of the **physics body origin** (its pivot / center-of-mass axis) |
| `getOrientation()` | table / nil | Body orientation quaternion `{x, y, z, w}` (world frame) |
| `getAngularVelocity()` | table / nil | World-frame angular velocity `{x, y, z}` (rad/s) |

The INS also appears in `getSensors()` as `{type="ins", pos={x,y,z}, pos_rel={x,y,z}}` (no per-sensor readings — use the dedicated methods above).

## Angle convention

- **pitch** — rotation around the body-local X axis; **positive = nose up**.
- **roll** — rotation around the body-local Z axis; **positive = right wing down** (banking right).
- **yaw** — **0 = the body's local −Z points north**; **positive = turning right** (clockwise seen from above); range −180..180. In steady state it equals the reading shown by the INS north marker.

!!! note "Gimbal-lock caveat"
    pitch/roll are derived from the gravity vector (same algorithm as `simulated:gimbal_sensor`). Near vertical attitudes (±90° pitch) the decomposition degrades — the same limitation as a real attitude indicator.

## Position semantics

- `getPosition()` — where the **INS block itself** is in the world (its plot coordinates projected through the Sable physics-body transform). It moves as the body moves/rotates.
- `getBodyPosition()` — where the **whole body's origin** is (the pivot used by the physics system). The two are usually close but not identical, because the INS block is usually mounted off the origin.

## Example

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("computer not on a physics body")
end

-- Attitude (degrees)
local a = ss.getAngles()
if a then
    print(string.format("pitch=%.1f roll=%.1f yaw=%.1f", a.pitch, a.roll, a.yaw))
end

-- INS block position vs body origin (world coordinates)
print("ins pos:   ", textutils.serialize(ss.getPosition()))
print("body origin:", textutils.serialize(ss.getBodyPosition()))

-- Orientation quaternion {x,y,z,w}
print("quaternion:", textutils.serialize(ss.getOrientation()))

-- Angular velocity (rad/s, world frame)
print("ang vel:   ", textutils.serialize(ss.getAngularVelocity()))
```

The shared methods (`isOnBody()`, `getBodyId()`, `getSensors()`, ...) behave as documented on the [Static Port](static-port.md) page. Physics data gated by the **Flight Management Computer (FMC)** is documented on the [Flight Management Computer](fmc.md) page.
