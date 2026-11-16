# Advanced Thrust Nozzle

![advanced_thrust_nozzle](../img//advanced_thrust_nozzle.png)

> A nozzle that can point **anywhere inside a 15° cone**

The **Advanced Thrust Nozzle** (`ccpe:advanced_thrust_nozzle`) is the upgraded version of the directional thrust nozzle. It mounts exactly the same way — on the airflow face of a Create `encased_fan`, still powered by the fan's RPM — except that the **nozzle body can tilt around a pivot at its base**. Thrust follows the **tilted** axis, so a single nozzle can push a craft in any direction inside the cone.

Tilting is driven by CC:Tweaked Lua in two flavours: **polar** (azimuth + tilt angle) and **joystick** (`x`/`y` = `-1..1`). Neither one teleports: the nozzle sweeps to the new direction at a constant speed **in the deflection plane**, and the render, thrust, and exhaust/intake/contrail particles all animate together.

## Compared to the directional nozzle

| | Directional nozzle `ccpe:thrust_nozzle` | Advanced thrust nozzle `ccpe:advanced_thrust_nozzle` |
|---|---|---|
| Thrust direction | FACING only | Anywhere inside a **15° cone** around FACING |
| Output per RPM | 1 pN/RPM | **2 pN/RPM** (same fan, twice the output) |
| Lua control | none (fan RPM only) | `setDeflection` / `setDirection` / `setThrustScale` |
| Channel | — | **Physics-body scoped**, addressable from Lua |
| Right-click GUI | — | Channel wheel |
| Exhaust / contrail / dye / wrench | ✅ | ✅ all inherited — and they follow the tilted axis |

## Placement

- It must occupy the **airflow face** of an `encased_fan` (same `canSurvive` check as `create:nozzle` and the directional nozzle). FACING is decided solely by the fan behind it.
- The fan's own thrust is **always 0** once a nozzle is installed there, so the nozzle is the only thrust source — nothing stacks.
- **Right-click with an empty hand** opens the channel GUI; **right-click with a dye** recolors the contrail particles only (goggles show the current color in the tooltip).
- **Sneak-right-click with a wrench** removes it instantly (drops go straight into your inventory). A normal wrench right-click does **not** rotate it — the only legal FACING is the fan's airflow face.

## Channel & addressing

The channel number is the nozzle's **address inside the physics body**, sharing one channel space with the [short-range signal linker](../sensor-system/short-range-linker.md) and the control desk (1:1 per channel within a body).

- The lowest free number on the chain is **assigned automatically** on placement; when two devices on the same chain claim one channel, the later one **defers** to the next free number.
- Devices register **only on physics bodies** (Sable sub-level, including the constraint chain). A nozzle sitting on the ground is not registered: `getChannel()` returns `-1` and the GUI shows "Must be placed on a physics body".
- The assignment is re-checked every 20 ticks, so bearings linking/unlinking (which merges or splits channel spaces) can push the number along.
- Leaving the physics body resets the channel to `-1`; re-mounting assigns a new one.
- Unlike the linker, the nozzle GUI has **no "load physics body" switch** — the nozzle holds no force-load ticket.

Address it from Lua through the same `ccpe.sensor_system` module:

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)        -- the nozzle on channel 0
print(n.getChannel(), n.getMaxTilt()) -- 0  15
```

## Lua API

Peripheral type: `advanced_thrust_nozzle` (obtained via `ccpe.sensor_system.getPeripheral(channel)`).

| Method | Returns | Description |
|---|---|---|
| `setDeflection(azimuthDeg, tiltDeg)` | boolean | **Polar form**: azimuth around the FACING axis (0–360, negatives wrap), tilt clamped to `0..15`. Only the target is written; the nozzle sweeps there in a straight line. Returns `false` for non-finite input |
| `setDirection(x, y)` | boolean | **Joystick form**: `x`/`y` ∈ `-1..1`, where the **direction** is the azimuth and the **length** maps (circularly) to the tilt angle (length 0 = straight, 1 = full 15°). Inputs are clamped to `-1..1` each and then to the unit circle, so `(1,1)` equals "45° direction at full tilt". Target-only, same straight-line motion |
| `getDeflection()` | table | `{azimuth, tilt, targetAzimuth, targetTilt, x, y, targetX, targetY}`: the first four are the **actual** values right now (different from the targets while moving), the targets come last. Azimuths are normalized to 0–360 (meaningless — reported as 0 — when the point sits at dead center); `x`/`y` are the actual deflection point in degrees (`tilt · (cos, sin) azimuth`), directly comparable with `setDirection` |
| `getMaxTilt()` | number | Tilt clamp (currently `15`), handy for normalizing scripts |
| `isMoving()` | boolean | Whether it is still animating (deflection or thrust scale not settled) |
| `setThrustScale(scale)` | boolean | Output scale `0..1` (clamped), multiplied on top of "RPM × air pressure". **Also drives the exhaust and intake particles** (see "Output and particles"); animated along the same motion curve |
| `getThrustScale()` | number | Current output scale (`0..1`, the live value while animating) |
| `setInverted(enabled)` | boolean | **Output reversal (thrust ⇄ suction)**: `true` flips the **sign** of the force along the axis at the same RPM and output scale (forward thrust becomes suction; negative-RPM suction reverses too). **Instant, no animation**; only the sign flips — magnitude (RPM / pressure / output scale) and pointing direction are untouched — and the particle chain swaps with it (**exhaust ⇄ intake**). Stored in NBT / blueprints |
| `isInverted()` | boolean | Whether output is currently reversed (`true` = force along the axis is flipped, i.e. thrust acts as suction) |
| `getChannel()` | number | Channel of this nozzle inside the physics body (`-1` = not on a body / unassigned) |

### Example

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)

-- polar form: azimuth 90° at full 15° tilt (sweeps there in ~0.19 s)
n.setDeflection(90, 15)
n.setThrustScale(0.5)                  -- half output (thrust and exhaust/intake halve together, also animated)
print(n.getDeflection().tilt, n.getThrustScale())

-- wait until it settles
while n.isMoving() do sleep(0.05) end
print(n.getDeflection().azimuth, n.getDeflection().targetAzimuth) -- equal

-- joystick form: direction = azimuth, length = tilt (goes straight)
n.setDirection(1, 0)                   -- full tilt toward model +X (= setDeflection(0, 15))
n.setDirection(0, -1)                  -- full tilt toward model −Z (= setDeflection(270, 15))
n.setDirection(0.5, 0)                 -- toward +X at half tilt (7.5°)
n.setDirection(0, 0)                   -- back to straight

-- output reversal (thrust ⇄ suction): instant, exhaust switches to intake
n.setInverted(true)                    -- force flips along the axis
print(n.isInverted())                  -- true
n.setInverted(false)                   -- back to normal direction
```

Azimuth 0° is the model's **+X** direction and 90° is **+Z** (`(cos, sin)` maps to `(x, y)`); the whole frame rotates with the block's FACING.


## Thrust model

```
thrust = fan RPM × air pressure × thrust scale × 2
```

- **Full output on a vector nozzle = 2 pN/RPM**; pressure comes from `DimensionPhysicsData` (1.0 at sea level, dropping with altitude).
- Sign convention matches the directional nozzle: **positive RPM pushes along FACING** (the craft feels the reaction), negative RPM reverses it (reverse thrust / intake).
- Thrust is **never occlusion-checked** — a blocked nozzle still pushes (same as the directional nozzle).
- The thrust scale **also drives the exhaust and intake particles** (see below), but **not the high-altitude contrail**.
- `setInverted(true)` only flips the **sign** (thrust ⇄ suction): magnitude and every other parameter stay identical, and the particle chain swaps from exhaust to intake in the same step.

| Fan RPM | Pressure | `setThrustScale` | Thrust |
|---|---|---|---|
| 256 RPM | 1.0 | 1.0 | **512 pN** |
| 256 RPM | 1.0 | 0.5 | 256 pN |
| 128 RPM | 1.0 | 1.0 | 256 pN |
| 256 RPM | 0.5 | 1.0 | 256 pN |


## Tuning constants

| What you want to change | Constant | Current value |
|---|---|---|
| Deflection speed (animation and real thrust together) | `AdvancedThrustNozzleBlockEntity.DEFLECTION_DEG_PER_SECOND` | 160°/s (= 8°/tick) |
| Vector nozzle output | `AdvancedThrustNozzleBlockEntity.THRUST_OUTPUT_MULTIPLIER` | 2.0 (2 pN/RPM) |
| Thrust-scale ramp | `AdvancedThrustNozzleBlockEntity.THRUST_SCALE_PER_TICK` | 0.25/tick |
| Maximum tilt angle | `AdvancedThrustNozzleBlockEntity.MAX_TILT_DEG` | 15° |
| Tilt pivot | `AdvancedThrustNozzleRenderer.PIVOT` | (8, 5, 8) px |
| Selection / collision box | `AdvancedThrustNozzleBlock.BASE_SHAPE` | `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)` |

