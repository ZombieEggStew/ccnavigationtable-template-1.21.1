# Trailing Wheel Mount

> Passive single-wheel suspension with Lua-driven steering — no redstone, no Create power input

The **Trailing Wheel Mount** (`ccpe:trailing_wheel_mount`) is a passive wheel
suspension block. It holds one tire, supports the vehicle with real Sable
suspension physics, and rolls freely when the vehicle is pushed — it never
drives the vehicle itself (no Create kinetic/stress input).

Unlike the original offroad `wheel_mount`, steering is **not** read from
redstone. The mount is exposed as a ComputerCraft peripheral and steered
purely from Lua.

## Prerequisites

- A tire must be installed: any item with the `offroad:TIRE` data component
  (right-click the tire-facing side or the bottom to mount / swap tires).
- The block must be assembled into a Sable physics body (sub-level) for the
  suspension and steering forces to act. A standalone block is static.

## Peripheral

- Type: `trailing_wheel_mount`
- Obtain it with `peripheral.find("trailing_wheel_mount")` or
  `peripheral.wrap(side)`.

## Lua API

| Method | Returns | Description |
|---|---|---|
| `setSteering(value)` | - | Steering input in `[-1, 1]` (out-of-range values are clamped). `0` = straight, `±1` = full lock (~±30°). Positive / negative are the two steering directions (sign convention follows offroad's redstone-differential steering; verify the physical direction in game). Runs on the server main thread. |
| `getSteering()` | number | Current steering input in `[-1, 1]` |
| `getSteeringAngle()` | number | Current steering angle in degrees (max ~±30) |
| `hasTire()` | boolean | Whether a tire is installed |
| `getTireRadius()` | number | Installed tire radius in blocks (`0` when no tire is installed) |
| `getExtension()` | number | Live suspension travel in blocks (larger = the wheel hangs lower) |
| `getAngularVelocity()` | number | Wheel spin angular velocity in radians per tick |
| `isLiftedUp()` | boolean | Whether the wheel is airborne (lifted wheels have no traction) |
| `getTouchingFriction()` | number | Friction coefficient of the block under the wheel (`1.0` on normal blocks, lower on ice-like surfaces) |

## Behavior notes

- **Lua-only steering**: there is no redstone input at all. The Lua value is
  stored server-side as a steering signal (`-15..15`, `0` = straight) and
  converted to a yaw angle with the same formula as offroad (max ~±30°).
- **Not persisted**: the steering value lives in memory only — it resets to
  `0` (straight) when the chunk unloads or the server restarts.
- **Smooth steering**: the wheel yaw eases toward the target (chasing lerp),
  both on the physics side and in the client animation.
- **Steering changes the force direction**: the rolling-resistance and
  side-friction forces are applied along the steered axis, so steering
  actually guides the vehicle — push the vehicle and watch it turn.
- **Passive wheel**: the mount never pushes the vehicle; drive force must come
  from elsewhere (a driven axle, engine, etc.).
- The steering value is synced to clients so the mount arm and tire visually
  rotate around the steering pivot.

## Example

```lua
local wm = peripheral.find("trailing_wheel_mount")

wm.setSteering(1.0)   -- full lock one way
wm.setSteering(0.0)   -- straighten
wm.setSteering(-0.5)  -- half lock the other way

print(wm.getSteering())       -- e.g. -0.5
print(wm.getSteeringAngle())  -- current angle in degrees

-- telemetry
if wm.hasTire() then
    print("tire radius:", wm.getTireRadius())
end
print("extension:", wm.getExtension())
print("angular velocity:", wm.getAngularVelocity())
print("lifted:", wm.isLiftedUp())
print("friction:", wm.getTouchingFriction())
```
